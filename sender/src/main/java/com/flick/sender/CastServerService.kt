package com.flick.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the [MediaHttpServer] for the life of a cast
 * session. Running the server here (with an ongoing notification and the
 * mediaPlayback foreground type) keeps serving alive while the TV plays and
 * while the phone's screen is off.
 */
class CastServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var httpServer: MediaHttpServer

    // Held only while serving. Guarded so acquire/release stay balanced across the
    // start (IO) and stop (main) threads even if they interleave.
    private val lockGuard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Bumped by every teardown path (ACTION_STOP, error, onDestroy). A start
    // coroutine captures this at launch and, under lockGuard, refuses to start the
    // engine or acquire the locks if a teardown has since run — otherwise a Stop
    // that lands during the start window (getSiteLocalIpv4 + engine spin-up) would
    // leave a resurrected engine holding port 8080 and freshly-acquired locks that
    // no stop path can ever release.
    private val generation = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        httpServer = MediaHttpServer(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything(setIdle = true)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uri = intent.data
                val name = intent.getStringExtra(EXTRA_NAME)
                val size = intent.getLongExtra(EXTRA_SIZE, -1L)

                // Fresh 128-bit token per ACTION_START: re-picking a video rotates it,
                // so a previously-shared URL stops working the moment the source changes.
                val token = newSessionToken()

                // A foreground service MUST post its notification promptly, so do
                // it synchronously before any I/O.
                startInForeground(buildNotification(text = getString(R.string.notif_text_serving)))

                if (uri == null) {
                    ServerStateHolder.setError(getString(R.string.error_server_start))
                    stopEverything(setIdle = false)
                    return START_NOT_STICKY
                }

                val startGen = generation.get()
                serviceScope.launch {
                    val ip = NetworkUtils.getSiteLocalIpv4()
                    if (ip == null) {
                        ServerStateHolder.setError(getString(R.string.error_no_lan))
                        stopEverything(setIdle = false)
                        return@launch
                    }
                    try {
                        // Start the engine, acquire the locks and publish RUNNING as one
                        // critical section gated on the session still being live. If a
                        // teardown ran during the IP lookup / engine spin-up it bumped
                        // the generation, so bail before touching anything — the teardown
                        // (which always runs releaseLocks + httpServer.stop after bumping)
                        // stays the last word, leaving no orphaned engine or leaked locks.
                        var started = false
                        synchronized(lockGuard) {
                            if (generation.get() == startGen) {
                                // Bind to the LAN IP only (never 0.0.0.0): removes the
                                // loopback co-resident-app vector and lets the handler
                                // pin the Host header to this address.
                                httpServer.start(uri, token, ip)
                                TransferTelemetry.reset()
                                acquireLocks()
                                ServerStateHolder.setRunning(name, size, ip, token)
                                started = true
                            }
                        }
                        if (!started) return@launch
                        updateNotification(text = "http://$ip:$SERVER_PORT/v/$token")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start media server", e)
                        ServerStateHolder.setError(getString(R.string.error_server_start))
                        stopEverything(setIdle = false)
                    }
                }
                return START_NOT_STICKY
            }

            else -> {
                // Unknown/relaunch intent: nothing to serve, so shut down cleanly.
                stopEverything(setIdle = true)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        // Invalidate any in-flight start coroutine before tearing down (coroutine
        // cancellation alone can't interrupt its blocking start/acquire sequence).
        generation.incrementAndGet()
        // Belt-and-braces: make sure the locks, socket and coroutines all die.
        releaseLocks()
        httpServer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopEverything(setIdle: Boolean) {
        // Invalidate any in-flight start coroutine before we release/stop, so it
        // can't resurrect the engine or acquire locks after this teardown ran.
        generation.incrementAndGet()
        releaseLocks()
        httpServer.stop()
        // Don't wipe an error surfaced by someone else (e.g. the Activity stopping
        // a stale server after losing its LAN IP, then setting ERROR) back to
        // IDLE — the async ACTION_STOP that tears down that server would otherwise
        // clobber the error the user needs to see.
        if (setIdle && ServerStateHolder.state.value.status != ServerStatus.ERROR) {
            ServerStateHolder.setIdle()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Wake / Wi-Fi locks -------------------------------------------------

    /**
     * Take the Wi-Fi and CPU locks that keep the server serving at full rate with
     * the screen off. Idempotent: re-arming while already held (the re-target
     * case) is a no-op, so acquire/release stay balanced.
     */
    private fun acquireLocks() {
        synchronized(lockGuard) {
            if (wifiLock == null) wifiLock = newWifiLock()
            if (wakeLock == null) wakeLock = newWakeLock()
            wifiLock?.let { if (!it.isHeld) it.acquire() }
            // Generous safety timeout so a lifecycle bug can never pin the CPU
            // forever; a healthy session releases long before it elapses.
            wakeLock?.let { if (!it.isHeld) it.acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
    }

    /** Release both locks. Safe to call when they were never acquired. */
    private fun releaseLocks() {
        synchronized(lockGuard) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    // WIFI_MODE_FULL_LOW_LATENCY is only honoured while the app is foreground with
    // the screen ON; this server must serve at full rate with the screen OFF, so
    // FULL_HIGH_PERF is the correct mode despite its API 34 deprecation.
    @Suppress("DEPRECATION")
    private fun newWifiLock(): WifiManager.WifiLock? {
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    private fun newWakeLock(): PowerManager.WakeLock? {
        val powerManager = getSystemService(PowerManager::class.java) ?: return null
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    // --- Session token ------------------------------------------------------

    /**
     * Mint a fresh 128-bit session token: 16 SecureRandom bytes, URL-safe base64
     * with no padding/newlines, giving a compact (~22-char) path segment free of
     * '+', '/', '=' so it drops straight into the cast URL.
     */
    private fun newSessionToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }

    // --- Notification -------------------------------------------------------

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, REQ_OPEN, openIntent, pendingFlags(),
        )

        val stopIntent = Intent(this, CastServerService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, REQ_STOP, stopIntent, pendingFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_action_stop),
                stopPending,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CastServerService"

        private const val CHANNEL_ID = "cast_server"
        private const val NOTIF_ID = 42
        private const val REQ_OPEN = 1
        private const val REQ_STOP = 2

        private const val WIFI_LOCK_TAG = "flick:cast-wifi"
        private const val WAKE_LOCK_TAG = "flick:cast-wake"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L // 6 hours

        // 16 bytes = 128 bits of SecureRandom entropy per session token.
        private const val TOKEN_BYTES = 16

        const val ACTION_START = "com.flick.sender.action.START"
        const val ACTION_STOP = "com.flick.sender.action.STOP"
        private const val EXTRA_NAME = "com.flick.sender.extra.NAME"
        private const val EXTRA_SIZE = "com.flick.sender.extra.SIZE"

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return flags
        }

        /** Start (or re-target) the foreground media server for [uri]. */
        fun start(context: Context, uri: Uri, name: String?, size: Long) {
            val intent = Intent(context, CastServerService::class.java).apply {
                action = ACTION_START
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
            }
            startForegroundServiceCompat(context, intent)
        }

        /** Stop the media server and dismiss the foreground notification. */
        fun stop(context: Context) {
            val intent = Intent(context, CastServerService::class.java).setAction(ACTION_STOP)
            // Delivered as a normal start command; the service tears itself down.
            context.startService(intent)
        }

        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            // minSdk is 26 (O), so startForegroundService is always available.
            context.startForegroundService(intent)
        }
    }
}

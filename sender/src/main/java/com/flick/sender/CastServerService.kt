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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flick.sender.net.ControlProtocolV2
import java.security.SecureRandom
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
    private val resourceOwnership = GenerationResourceOwnership()

    private val startGate = LatestStartGate()
    // Serializes lifecycle-wide effects (foreground notification and stopSelf) with
    // the generation transition. The slow socket work deliberately stays outside it
    // so a newer ACTION_START can supersede a blocked older start.
    private val teardownGuard = Any()

    override fun onCreate() {
        super.onCreate()
        httpServer = MediaHttpServer(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val castId = intent.getStringExtra(EXTRA_CAST_ID)
                if (castId != null) stopCurrentCast(castId, startId)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uri = intent.data
                val name = intent.getStringExtra(EXTRA_NAME)
                val size = intent.getLongExtra(EXTRA_SIZE, -1L)
                val castId = intent.getStringExtra(EXTRA_CAST_ID)
                val bindHost = intent.getStringExtra(EXTRA_BIND_HOST)

                // Fresh 128-bit token per ACTION_START: re-picking a video rotates it,
                // so a previously-shared URL stops working the moment the source changes.
                val token = newSessionToken()

                // A foreground service MUST post its notification promptly, so do
                // it synchronously before any I/O.
                if (castId == null || !ControlCastId.valid(castId)) return START_NOT_STICKY
                val session = synchronized(teardownGuard) {
                    startGate.begin(castId).also { startInForeground(buildNotification(castId)) }
                }

                if (uri == null || bindHost == null || !NetworkUtils.isOwnedLanIpv4(bindHost)) {
                    failCurrentStart(session, startId, getString(R.string.error_server_start))
                    return START_NOT_STICKY
                }

                serviceScope.launch {
                    if (!startGate.isLatest(session)) return@launch
                    if (!NetworkUtils.isOwnedLanIpv4(bindHost)) {
                        failCurrentStart(session, startId, getString(R.string.error_no_lan))
                        return@launch
                    }
                    if (!startGate.isLatest(session)) return@launch
                    try {
                        var started = false
                        synchronized(lockGuard) {
                            if (startGate.isLatest(session)) {
                                // Bind to the LAN IP only (never 0.0.0.0): removes the
                                // loopback co-resident-app vector and lets the handler
                                // pin the Host header to this address.
                                resourceOwnership.claimServer(session)
                                httpServer.start(uri, token, bindHost)
                                if (!startGate.isLatest(session)) {
                                    closeResourcesOwnedByLocked(session)
                                    return@synchronized
                                }
                                TransferTelemetry.reset()
                                resourceOwnership.claimLocks(session)
                                acquireLocks()
                                if (!startGate.runIfLatest(session) {
                                        ServerStateHolder.setRunning(castId, name, size, bindHost, token)
                                    }
                                ) {
                                    closeResourcesOwnedByLocked(session)
                                } else {
                                    started = true
                                }
                            }
                        }
                        if (!started) return@launch
                    } catch (e: Exception) {
                        failCurrentStart(session, startId, getString(R.string.error_server_start))
                    }
                }
                return START_NOT_STICKY
            }

            else -> {
                // An unknown relaunch must not tear down an active newer generation.
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        synchronized(teardownGuard) {
            startGate.clear()
            closeAllResources()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopCurrentCast(castId: String, startId: Int) {
        synchronized(teardownGuard) {
            val session = startGate.stop(castId) ?: return
            closeResourcesOwnedBy(session)
            ServerStateHolder.setIdle()
            ServerStateHolder.publishTerminal(session, SourceServerTerminalKind.STOPPED)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun failCurrentStart(session: CastGeneration, startId: Int, message: String) {
        synchronized(teardownGuard) {
            // A stale failure can only clean up work it created. It must never set
            // ERROR, remove the notification, or stop a newer cast.
            if (!startGate.invalidateIfLatest(session)) {
                closeResourcesOwnedBy(session)
                return
            }
            closeResourcesOwnedBy(session)
            ServerStateHolder.setError(session.castId, message)
            ServerStateHolder.publishTerminal(
                session,
                SourceServerTerminalKind.FAILED,
                SOURCE_SERVER_START_FAILED,
            )
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun closeResourcesOwnedBy(session: CastGeneration) {
        synchronized(lockGuard) { closeResourcesOwnedByLocked(session) }
    }

    private fun closeResourcesOwnedByLocked(session: CastGeneration) {
        val release = resourceOwnership.release(session)
        if (release.locks) {
            releaseLocks()
        }
        if (release.server) {
            httpServer.stop()
        }
    }

    private fun closeAllResources() {
        synchronized(lockGuard) {
            resourceOwnership.releaseAll()
            releaseLocks()
            httpServer.stop()
        }
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

    private fun buildNotification(castId: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, REQ_OPEN, openIntent, pendingFlags(),
        )

        val stopIntent = Intent(this, CastServerService::class.java).setAction(ACTION_STOP)
            .putExtra(EXTRA_CAST_ID, castId).setData(Uri.parse("flick-stop://$castId"))
        val stopPending = PendingIntent.getService(
            this, castId.hashCode(), stopIntent, pendingFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text_serving))
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
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
        private const val CHANNEL_ID = "cast_server"
        private const val NOTIF_ID = 42
        private const val REQ_OPEN = 1

        private const val WIFI_LOCK_TAG = "flick:cast-wifi"
        private const val WAKE_LOCK_TAG = "flick:cast-wake"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L // 6 hours
        private const val SOURCE_SERVER_START_FAILED = "source_server_start_failed"

        // 16 bytes = 128 bits of SecureRandom entropy per session token.
        private const val TOKEN_BYTES = 16

        const val ACTION_START = "com.flick.sender.action.START"
        const val ACTION_STOP = "com.flick.sender.action.STOP"
        private const val EXTRA_NAME = "com.flick.sender.extra.NAME"
        private const val EXTRA_SIZE = "com.flick.sender.extra.SIZE"
        private const val EXTRA_CAST_ID = "com.flick.sender.extra.CAST_ID"
        private const val EXTRA_BIND_HOST = "com.flick.sender.extra.BIND_HOST"

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return flags
        }

        /** Start (or re-target) the foreground media server for [uri]. */
        fun start(context: Context, castId: String, uri: Uri, name: String?, size: Long, bindHost: String) {
            val intent = Intent(context, CastServerService::class.java).apply {
                action = ACTION_START
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
                putExtra(EXTRA_CAST_ID, castId)
                putExtra(EXTRA_BIND_HOST, bindHost)
            }
            startForegroundServiceCompat(context, intent)
        }

        /** Stop the media server and dismiss the foreground notification. */
        fun stop(context: Context, castId: String) {
            val intent = Intent(context, CastServerService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CAST_ID, castId)
            // Delivered as a normal start command; the service tears itself down.
            context.startService(intent)
        }

        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            // minSdk is 26 (O), so startForegroundService is always available.
            context.startForegroundService(intent)
        }
    }
}

private object ControlCastId { fun valid(value: String) = ControlProtocolV2.id(value) }

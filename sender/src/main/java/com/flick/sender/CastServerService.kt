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
import com.flick.sender.util.FlickLog
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    // The throughput sampler. It belongs to the service and not to a screen because the
    // only other caller of TransferTelemetry's fold is a LaunchedEffect inside the signal
    // sheet: with that sheet closed — which is nearly always — the byte counters keep
    // counting but the derived RATE never advances, so anything reading it would measure
    // nothing. This lives exactly as long as the served session does.
    private var samplerJob: Job? = null

    // The LAN IP the live socket is bound to, so a later subtitle retarget composes
    // its URL against the same origin the video URL already uses.
    @Volatile
    private var servedHost: String? = null

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

            ACTION_SET_SUBTITLE -> {
                val castId = intent.getStringExtra(EXTRA_CAST_ID)
                if (castId != null && ControlCastId.valid(castId)) {
                    applySubtitle(castId, intent.getParcelableExtraCompat(EXTRA_SUBTITLE_URI))
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val uri = intent.data
                val name = intent.getStringExtra(EXTRA_NAME)
                val size = intent.getLongExtra(EXTRA_SIZE, -1L)
                val castId = intent.getStringExtra(EXTRA_CAST_ID)
                val bindHost = intent.getStringExtra(EXTRA_BIND_HOST)
                val subtitleUri = intent.getParcelableExtraCompat(EXTRA_SUBTITLE_URI)

                // Fresh 128-bit token per ACTION_START: re-picking a video rotates it,
                // so a previously-shared URL stops working the moment the source changes.
                val token = newSessionToken()
                // The subtitle gets its own token from the same generator, so revoking
                // or swapping it can never widen what the video token already grants.
                val subtitle = subtitleUri?.let { MediaHttpServer.SubtitleSource(it, newSessionToken()) }

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
                                httpServer.start(uri, token, subtitle, bindHost)
                                if (!startGate.isLatest(session)) {
                                    closeResourcesOwnedByLocked(session)
                                    return@synchronized
                                }
                                TransferTelemetry.reset()
                                startSampler()
                                resourceOwnership.claimLocks(session)
                                acquireLocks()
                                servedHost = bindHost
                                if (!startGate.runIfLatest(session) {
                                        // Published BEFORE RUNNING: the coordinator waits on
                                        // RUNNING, so the subtitle capability must already be
                                        // visible by the time that wait returns.
                                        SubtitleServingState.publish(castId, subtitleUrl(bindHost, subtitle))
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

    /**
     * Attach, swap or revoke the sideloaded subtitle of the live cast. Only the
     * generation that currently owns the socket may repoint it, so a late intent from
     * a superseded cast can never re-arm a capability that teardown revoked.
     */
    private fun applySubtitle(castId: String, uri: Uri?) {
        synchronized(teardownGuard) {
            val session = startGate.current()
            if (session == null || session.castId != castId) return
            val host = servedHost ?: return
            val subtitle = uri?.let { MediaHttpServer.SubtitleSource(it, newSessionToken()) }
            val applied = synchronized(lockGuard) { httpServer.setSubtitle(subtitle) }
            if (!applied) return
            SubtitleServingState.publish(castId, subtitleUrl(host, subtitle))
            // Length only, and never the file: the token itself is the capability.
            FlickLog.i(
                "bind",
                if (subtitle == null) "subtitle revoked" else "subtitle armed tokenLen=${subtitle.token.length}",
            )
        }
    }

    private fun subtitleUrl(host: String, subtitle: MediaHttpServer.SubtitleSource?): String? =
        subtitle?.let { "http://$host:$SERVER_PORT/s/${it.token}" }

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
            stopSampler()
            httpServer.stop()
            // Tearing down the socket is what revokes the subtitle token; drop the
            // published URL in the same step so nothing can advertise a dead capability.
            servedHost = null
            SubtitleServingState.clear()
        }
    }

    private fun closeAllResources() {
        synchronized(lockGuard) {
            resourceOwnership.releaseAll()
            releaseLocks()
            stopSampler()
            httpServer.stop()
            servedHost = null
            SubtitleServingState.clear()
        }
    }

    // --- Throughput sampler -------------------------------------------------

    /**
     * Tick the transfer fold once a second for as long as bytes can move. One second is
     * the resolution the capacity verdict is written against; anything slower averages a
     * buffer fill together with the throttle that follows it.
     */
    private fun startSampler() {
        samplerJob?.cancel()
        samplerJob = serviceScope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                TransferTelemetry.sampleNow()
            }
        }
    }

    private fun stopSampler() {
        samplerJob?.cancel()
        samplerJob = null
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
        private const val SAMPLE_INTERVAL_MS = 1_000L

        // 16 bytes = 128 bits of SecureRandom entropy per session token.
        private const val TOKEN_BYTES = 16

        const val ACTION_START = "com.flick.sender.action.START"
        const val ACTION_STOP = "com.flick.sender.action.STOP"
        const val ACTION_SET_SUBTITLE = "com.flick.sender.action.SET_SUBTITLE"
        private const val EXTRA_NAME = "com.flick.sender.extra.NAME"
        private const val EXTRA_SIZE = "com.flick.sender.extra.SIZE"
        private const val EXTRA_CAST_ID = "com.flick.sender.extra.CAST_ID"
        private const val EXTRA_BIND_HOST = "com.flick.sender.extra.BIND_HOST"
        private const val EXTRA_SUBTITLE_URI = "com.flick.sender.extra.SUBTITLE_URI"

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            }
            return flags
        }

        /** Start (or re-target) the foreground media server for [uri]. */
        fun start(
            context: Context,
            castId: String,
            uri: Uri,
            name: String?,
            size: Long,
            bindHost: String,
            subtitleUri: Uri? = null,
        ) {
            val intent = Intent(context, CastServerService::class.java).apply {
                action = ACTION_START
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SIZE, size)
                putExtra(EXTRA_CAST_ID, castId)
                putExtra(EXTRA_BIND_HOST, bindHost)
                putExtra(EXTRA_SUBTITLE_URI, subtitleUri)
            }
            startForegroundServiceCompat(context, intent)
        }

        /**
         * Attach, swap or (with a null [subtitleUri]) revoke the sideloaded subtitle of
         * the running cast. Delivered as a plain start command like [stop]; the caller
         * must already know the service is serving [castId].
         */
        fun setSubtitle(context: Context, castId: String, subtitleUri: Uri?) {
            val intent = Intent(context, CastServerService::class.java)
                .setAction(ACTION_SET_SUBTITLE)
                .putExtra(EXTRA_CAST_ID, castId)
                .putExtra(EXTRA_SUBTITLE_URI, subtitleUri)
            context.startService(intent)
        }

        @Suppress("DEPRECATION")
        private fun Intent.getParcelableExtraCompat(key: String): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, Uri::class.java)
            } else {
                getParcelableExtra(key) as? Uri
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

/**
 * Process-wide publication of the sideloaded-subtitle capability the media server is
 * currently serving, bridging the service to the cast coordinator exactly as
 * [ServerStateHolder] bridges the video half. It is separate from that state because
 * a subtitle can be attached, swapped or revoked without disturbing the video session
 * it rides on, and each of those mints or retires its own token.
 */
internal object SubtitleServingState {

    /** [url] is null when the cast is serving no subtitle (never attached, or revoked). */
    data class Served(val castId: String, val revision: Long, val url: String?)

    private val _state = MutableStateFlow<Served?>(null)
    val state: StateFlow<Served?> = _state.asStateFlow()

    private var revision = 0L

    /** Monotonic counter so a waiter can tell a fresh publication from the one it saw. */
    @Synchronized
    fun revision(): Long = revision

    @Synchronized
    fun publish(castId: String, url: String?) {
        _state.value = Served(castId, ++revision, url)
    }

    @Synchronized
    fun clear() {
        ++revision
        _state.value = null
    }
}

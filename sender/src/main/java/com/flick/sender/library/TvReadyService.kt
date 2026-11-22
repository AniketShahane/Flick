package com.flick.sender.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.flick.sender.FlickApplication
import com.flick.sender.MainActivity
import com.flick.sender.R
import com.flick.sender.net.PairingStore
import com.flick.sender.util.FlickLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Contract §8: the ready window. A `connectedDevice` foreground service whose only job is to
 * keep this process — and with it the library socket — out of the cached state Android 14+
 * freezes ten seconds in, so the TV can still browse and pick right after a film.
 *
 * In ready mode (§8, §13) it also runs the doorbell: a listener on the Wi-Fi address only,
 * bound while a Wi-Fi network is up. Idle, it holds no wake lock, no Wi-Fi lock, no
 * multicast lock and no timer; a partial wake lock is taken only around a valid knock.
 */
class TvReadyService : Service() {

    private var ready = false
    private var doorbell: TvDoorbell? = null
    private var networkCallback: DoorbellNetworks? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            null -> {
                // A sticky restart after the process was killed: only ready mode is sticky.
                val device = coordinator()?.readyServiceRestarted()
                if (device == null) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                promote(device, readyMode = true, startId)
            }
            ACTION_START -> {
                val device = intent.getStringExtra(EXTRA_TV)?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.notif_device_generic)
                promote(device, intent.getBooleanExtra(EXTRA_READY, false), startId)
            }
            ACTION_USER_STOP -> {
                events.tryEmit(End.USER)
                endService(startId)
            }
            else -> endService(startId)
        }
        return if (ready) START_STICKY else START_NOT_STICKY
    }

    private fun promote(device: String, readyMode: Boolean, startId: Int) {
        createChannels()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification(device, readyMode),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: RuntimeException) {
            // A start the platform let through and then refused at promotion: the window
            // simply does not open.
            FlickLog.w("library", "ready window refused err=${e.javaClass.simpleName}")
            events.tryEmit(End.REFUSED)
            endService(startId)
            return
        }
        ready = readyMode
        if (readyMode) startDoorbell() else stopDoorbell()
    }

    private fun endService(startId: Int) {
        ready = false
        stopDoorbell()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onDestroy() {
        stopDoorbell()
        releaseKnockWake()
        super.onDestroy()
    }

    // --- Doorbell (contract §13) ---------------------------------------------

    private fun startDoorbell() {
        networkCallback?.let {
            it.reevaluate()
            return
        }
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val store = PairingStore(this)
        val bell = TvDoorbell(
            keyFor = { tvId, keyId -> store.get(tvId)?.takeIf { it.keyId == keyId && !it.needsRepair }?.key },
            onKnock = { tvId, keyId, peerHost, reply ->
                acquireKnockWake(this@TvReadyService)
                main.post { coordinator()?.onDoorbellKnock(tvId, keyId, peerHost, reply) ?: releaseKnockWake() }
            },
            nowMs = { SystemClock.elapsedRealtime() },
            replyFor = { tvId, _ -> coordinator()?.knockReply(tvId) ?: KnockReply.CONNECT },
        )
        doorbell = bell
        val callback = DoorbellNetworks(bell, store)
        // Without INTERNET: a home LAN with its uplink down is still where the TV is.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback, callback.handler)
            networkCallback = callback
        } catch (e: RuntimeException) {
            FlickLog.w("doorbell", "network watch refused err=${e.javaClass.simpleName}")
            callback.release()
            bell.shutdown()
            doorbell = null
        }
    }

    /**
     * Every Wi-Fi network up now, so losing the bound one (or its address) moves the listener
     * to another rather than leaving nothing listening: Android 12+ brings the next network up
     * before the old one goes, and a phone can hold two.
     *
     * The subnet rule is asked again whenever the pairings change, so a TV paired, moved or
     * forgotten on this network is followed without waiting for the next Wi-Fi change. All of
     * it runs on one thread of its own, off the main thread the socket binds would block.
     */
    private inner class DoorbellNetworks(
        private val bell: TvDoorbell,
        private val store: PairingStore,
    ) : ConnectivityManager.NetworkCallback() {
        private val thread = HandlerThread("flick-doorbell-net").apply { start() }
        val handler = Handler(thread.looper)
        private val links = LinkedHashMap<Network, LinkProperties>()
        private var bound: DoorbellBinding.Choice<Network>? = null
        private val rebindTask = Runnable { rebind() }

        // Held here: SharedPreferences keeps its change listeners only weakly.
        private val pairingsWatch = store.watch { reevaluate() }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            links[network] = linkProperties
            rebind()
        }

        override fun onLost(network: Network) {
            links.remove(network)
            rebind()
        }

        /** From any thread; a burst of pairing writes asks once. */
        fun reevaluate() {
            handler.removeCallbacks(rebindTask)
            handler.post(rebindTask)
        }

        fun release() {
            store.unwatch(pairingsWatch)
            thread.quitSafely()
        }

        private fun rebind() {
            val paired = store.verifiedHosts()
            val candidates = links.mapValuesTo(LinkedHashMap<Network, String?>()) { (_, properties) ->
                val addresses = properties.linkAddresses
                    .filter { it.address is Inet4Address }
                    .mapNotNull { link -> link.address.hostAddress?.let { DoorbellBinding.Address(it, link.prefixLength) } }
                DoorbellBinding.listenAddress(addresses, paired)
            }
            val next = DoorbellBinding.choose(bound, candidates)
            if (next == bound) return
            if (next == null) {
                bound = null
                bell.close()
                report(bell, null)
                return
            }
            val port = runCatching { bell.bind(InetAddress.getByName(next.host)) }.getOrNull()
            // A failed bind is tried again on the next network or pairing change rather than remembered.
            bound = next.takeIf { port != null }
            report(bell, port)
        }
    }

    private fun stopDoorbell() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        callback.release()
        doorbell?.shutdown()
        doorbell = null
        coordinator()?.onDoorbellPort(null)
    }

    /** From the callback thread; a report from a listener this service has since replaced is dropped. */
    private fun report(bell: TvDoorbell, port: Int?) {
        main.post { if (doorbell === bell) coordinator()?.onDoorbellPort(port) }
    }

    private fun coordinator() = (application as? FlickApplication)?.coordinator

    private fun notification(device: String, readyMode: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, TvReadyService::class.java).setAction(ACTION_USER_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, if (readyMode) QUIET_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.tv_ready_title, device))
            .setContentText(getString(if (readyMode) R.string.tv_ready_wake_text else R.string.tv_ready_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                androidx.media3.session.R.drawable.media3_icon_stop,
                getString(R.string.notif_action_stop),
                stop,
            )
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tv_ready_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tv_ready_channel_desc)
                setShowBadge(false)
            },
        )
        // Its own id, so the person can minimise or hide just this one. LOW is the floor: the
        // system raises a foreground service's channel below LOW to LOW unless the person set it.
        manager.createNotificationChannel(
            NotificationChannel(
                QUIET_CHANNEL_ID,
                getString(R.string.tv_ready_quiet_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.tv_ready_quiet_channel_desc)
                setShowBadge(false)
            },
        )
        ConnectPrompt.createChannel(this)
    }

    /** How the service ended from inside. */
    internal enum class End { USER, REFUSED }

    companion object {
        private const val CHANNEL_ID = "tv_ready"
        private const val QUIET_CHANNEL_ID = "tv_ready_quiet"
        private const val NOTIF_ID = 43
        private const val REQ_OPEN = 11
        private const val REQ_STOP = 12
        private const val ACTION_START = "com.flick.sender.action.TV_READY_START"
        private const val ACTION_STOP = "com.flick.sender.action.TV_READY_STOP"
        private const val ACTION_USER_STOP = "com.flick.sender.action.TV_READY_USER_STOP"
        private const val EXTRA_TV = "com.flick.sender.extra.TV_NAME"
        private const val EXTRA_READY = "com.flick.sender.extra.TV_READY_MODE"
        private const val WAKE_TAG = "Flick:knock"
        internal const val KNOCK_WAKE_MS = 20_000L

        private val events = MutableSharedFlow<End>(extraBufferCapacity = 4)

        /** The window ended from inside the service: the person pressed Stop, or promotion was refused. */
        internal val ended: SharedFlow<End> = events.asSharedFlow()

        private val wakeGuard = Any()
        private var knockWake: PowerManager.WakeLock? = null

        /**
         * Held for at most [KNOCK_WAKE_MS] while a knock, or the prompt's Connect, brings the
         * library back; [releaseKnockWake] lets it go sooner.
         */
        internal fun acquireKnockWake(context: Context) {
            synchronized(wakeGuard) {
                // The application's: the lock outlives any Service instance.
                val lock = knockWake ?: context.applicationContext.getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                    ?.apply { setReferenceCounted(false) }
                    ?: return
                knockWake = lock
                lock.acquire(KNOCK_WAKE_MS)
            }
        }

        /** The knock has been answered: the TV has a catalog, or there was nothing to dial. */
        internal fun releaseKnockWake() {
            synchronized(wakeGuard) {
                knockWake?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
            }
        }

        /** False when the platform refused the start; the window then does not open. */
        internal fun start(context: Context, tvName: String?, readyMode: Boolean = false): Boolean = try {
            context.startForegroundService(
                Intent(context, TvReadyService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TV, tvName)
                    .putExtra(EXTRA_READY, readyMode),
            )
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+, an IllegalStateException.
            FlickLog.w("library", "ready window start refused err=${e.javaClass.simpleName}")
            false
        }

        /**
         * Delivered as a start command rather than `stopService`, so it lands after the start
         * command that promoted the service and never ends one before `startForeground`.
         */
        internal fun stop(context: Context) {
            try {
                context.startService(Intent(context, TvReadyService::class.java).setAction(ACTION_STOP))
            } catch (e: IllegalStateException) {
                // Refused only while this app holds no foreground service, so nothing is running.
                FlickLog.d("library", "ready window stop refused err=${e.javaClass.simpleName}")
            }
        }

        private fun pendingFlags(): Int {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_IMMUTABLE
            return flags
        }
    }
}

package com.flick.receiver.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.WifiTelemetry

/**
 * Every Wi-Fi association edge this TV goes through, written to the log so that a
 * read months from now can be lined up against a wall-clock incident time.
 *
 * The fault it exists for is the router-side, pair-scoped layer-2 forwarding block
 * measured in `research/04`: for minutes at a time the phone and this TV cannot
 * reach each other while each reaches the gateway and every other host at full
 * speed. It is transient and self-clearing — one instance at 13 min 43 s, another
 * at about 20 — and every escape an unprivileged app has is closed, so the only
 * question left open is what ENDS it. A new Wi-Fi [Network] on either device within
 * ±30 s of the path returning answers it: the block is cleared by re-association,
 * and no app-side action could ever have shortened it.
 *
 * So this observes and does nothing else. It never touches the radio, is consulted
 * by no decision, and puts nothing on the control wire.
 *
 * Foreground-only, deliberately. It lives exactly as long as the composition that
 * starts it, because the receiver has no Service of any kind and adding one for
 * telemetry would be a standby, battery and store-review change out of all
 * proportion to a log line. A TV in standby records nothing, and a block that
 * begins and ends there leaves no trace here.
 *
 * SSID and BSSID are never logged: they identify a household, and reading either
 * would demand a location permission this app does not hold and will not ask for.
 * [WifiAssociationEpochs] hands out a counter instead, which says WHICH association
 * a line is about without saying which network it is. That counter is local to one
 * monitor, so a second `epoch=1` in a single log is a fresh Activity rather than a
 * fresh association — and [wifiAssociationEdge] marks it on the line rather than
 * leaving it for a reader to remember.
 */
class WifiAssociationMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private val epochs = WifiAssociationEpochs<Network>()

    // Deliberately not the DEFAULT network callback the LAN monitor uses: a TV
    // holding an Ethernet default never has Wi-Fi as its default, and a default
    // that moves between transports is a route change rather than an association.
    // registerNetworkCallback carries ACCESS_NETWORK_STATE, which is already
    // declared; requestNetwork would additionally need CHANGE_NETWORK_STATE, and
    // asking the framework to KEEP a network up is not what this does.
    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    // Callbacks registered without a Handler are delivered serialized on
    // ConnectivityManager's own thread, which is why the epoch bookkeeping below
    // holds no lock.
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            epochs.onAvailable(network)?.let { record(wifiAssociationEdge(it), it) }
        }

        override fun onLost(network: Network) {
            epochs.onLost(network)?.let { record(WIFI_EDGE_LOST, it) }
        }
    }

    fun start() {
        runCatching { manager.registerNetworkCallback(request, callback) }
    }

    /**
     * Unregisters, and keeps the epoch bookkeeping on purpose: registering again
     * re-announces the association that was never lost, with the same [Network],
     * and a screensaver round trip is not a re-association.
     */
    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    private fun record(edge: String, epoch: Int) {
        // Info, unlike the LAN monitor's verbose neighbour: this is one line per
        // real association edge rather than one per callback, and a line that never
        // reaches logcat on a release build cannot answer the question this exists
        // for.
        //
        // The radio is sampled inline, on the callback thread, because the reading
        // is only worth anything AT the edge — a hop to another thread would report
        // a link the TV had moved on from. It is one short binder call, and the
        // reading is of the radio as it stands rather than of the [Network] named
        // here: the framework exposes no per-network frequency without transport
        // info, so across the width of a roam the two can disagree.
        FlickLog.i(
            "lan",
            wifiAssociationLine(
                edge = edge,
                epoch = epoch,
                atMs = SystemClock.elapsedRealtime(),
                link = WifiTelemetry.read(appContext),
            ),
        )
    }
}

internal const val WIFI_EDGE_FIRST = "first"
internal const val WIFI_EDGE_AVAILABLE = "available"
internal const val WIFI_EDGE_LOST = "lost"

/** The epoch a monitor hands out before it has seen the radio move. */
internal const val WIFI_FIRST_EPOCH = 1

/**
 * What to call an arriving association, given the epoch it opened.
 *
 * A callback replays `onAvailable` for every already-up network the moment it registers,
 * so a monitor's FIRST arrival is at least as often the association already in place as a
 * new one, and from inside the process the two cannot be told apart. Counting it as an
 * edge is the false positive that would make a ±30 s correlation agree with whatever it
 * was asked. [WIFI_EDGE_FIRST] therefore claims only what is known — the first thing this
 * monitor saw — which leaves [WIFI_EDGE_AVAILABLE] meaning exactly one thing: a NEW
 * association seen by a monitor that was already watching. Only those are evidence.
 *
 * This is the narrower companion to [WifiAssociationEpochs], which settles re-announcement
 * WITHIN one monitor's life; a composition-scoped monitor restarts with a fresh counter,
 * and this is what says so on the line itself rather than in a reader's memory.
 */
internal fun wifiAssociationEdge(epoch: Int): String =
    if (epoch == WIFI_FIRST_EPOCH) WIFI_EDGE_FIRST else WIFI_EDGE_AVAILABLE

/** What a frequency, link speed or RSSI reads as when the radio no longer has one. */
internal const val WIFI_LINK_UNREAD = "none"

/**
 * Whether an `onAvailable` is a genuinely NEW association or the same one announced
 * again, and what each association is called in the log.
 *
 * This is the subtle half of the telemetry. A network callback replays
 * `onAvailable` for every already-up network that matches its request the moment it
 * is registered, so a receiver that registers per composition re-hears the
 * association it never lost after every screensaver, Home press and system dialog.
 * Counting those would be the exact false positive that makes a ±30 s correlation
 * agree with everything asked of it.
 *
 * Identity is the [android.net.Network] itself, whose equality is the network id
 * the framework assigned. That id outlives OUR registration gaps, which is the
 * whole reason a re-announcement can be told apart from a new association at all.
 *
 * A key is live from its `onAvailable` to its `onLost`, and an association is new
 * exactly when its key is not already live. The same one rule settles the two
 * harder shapes without special-casing either: a make-before-break roam, where the
 * new network is up before the old one goes away (both live, epochs interleaved —
 * an `available` printed before the `lost` of the epoch beneath it is the radio
 * moving without ever dropping the link), and a network id reissued long after its
 * own loss (not live, so new again).
 *
 * [capacity] bounds the live map because a network lost while this is unregistered
 * is never withdrawn — `stop` keeps the bookkeeping on purpose — so dead keys
 * accrue at one per association over a process that may run for days. This hardware
 * holds one Wi-Fi network at a time, two for the width of a roam, so anything
 * evicted at this depth is long dead.
 */
internal class WifiAssociationEpochs<K : Any>(private val capacity: Int = LIVE_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val live = LinkedHashMap<K, Int>()
    private var assigned = 0

    /** The new association's epoch, or null when [key] is already live. */
    fun onAvailable(key: K): Int? {
        if (live.containsKey(key)) return null
        assigned++
        live[key] = assigned
        if (live.size > capacity) live.remove(live.keys.first())
        return assigned
    }

    /** The epoch that association was given, or null when it was not live. */
    fun onLost(key: K): Int? = live.remove(key)

    companion object {
        /** Live associations retained before the eldest is dropped. */
        const val LIVE_CAPACITY = 32
    }
}

/**
 * One association edge as a line, in [FlickLog]'s `key=value` shape.
 *
 * Both edges carry exactly the same keys so that `adb logcat | grep wifi-assoc` is a
 * table rather than a sequence of paragraphs, and the key set is the redaction contract
 * made checkable: nothing identifying can be added to it by accident.
 *
 * The anchor and [atMs] deliberately match the sender's line word for word. The question
 * both exist for is whether an association on EITHER device coincides with a block
 * clearing, which is answered by putting two devices' rings side by side and subtracting
 * — and a phone that said `wifi-assoc … atMs=` while the TV said `wifi … monoMs=` would
 * have to be reconciled by hand every time, which is how a diagnostic stops being used.
 *
 * [atMs] is `SystemClock.elapsedRealtime`. The logcat stamp beside it is the wall
 * clock an incident time is matched against; this is the one two edges can be
 * subtracted from each other across, over a clock the user or NTP may have moved.
 *
 * The radio is usually already gone by the time `onLost` arrives, so a null [link]
 * is the ordinary case at that edge and prints as [WIFI_LINK_UNREAD] rather than as
 * a fabricated zero — an RSSI of 0 dBm is a reading, not a silence.
 */
internal fun wifiAssociationLine(
    edge: String,
    epoch: Int,
    atMs: Long,
    link: WifiTelemetry.Link?,
): String =
    "wifi-assoc edge=$edge epoch=$epoch atMs=$atMs " +
        "freqMhz=${link?.frequencyMhz ?: WIFI_LINK_UNREAD} " +
        "linkMbps=${link?.linkSpeedMbps ?: WIFI_LINK_UNREAD} " +
        "rssiDbm=${link?.rssiDbm ?: WIFI_LINK_UNREAD}"

package com.flick.receiver.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.IncidentJournal
import java.net.InetAddress
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Clears a Wi-Fi TDLS direct link this TV has been left holding to a phone.
 *
 * The phone's driver sets a direct link up on its own under a cast's sustained
 * unicast and tears it down on its own 10–32 s after the stream idles. When that
 * teardown frame fails to send (`TDLS: Failed to send message (action_code=3)` on
 * the phone), this TV keeps a link the phone no longer has: unicast is lost in both
 * directions while multicast still crosses, for many minutes. Tearing down from this
 * side was measured to cure that within seconds.
 *
 * Constraints, both measured on the reference phone:
 * - It must never tear down a link both sides still hold. The phone drops this TV's
 *   Teardown frame (`TDLS: Invalid MIC in Teardown`), so this TV would forget the
 *   link while the phone kept sending into it until its own idle teardown — the
 *   same blackout, reversed. Hence no teardown at the moment a cast ends, and none
 *   while the phone still answers: only an unanswered phone, after the phone's own
 *   teardown should have happened, can mean this TV holds the orphan.
 * - Only the address overload is usable: this TV cannot learn the phone's MAC, so
 *   the platform resolves it from the TV's ARP table, which keeps the phone's entry
 *   while the link is stuck.
 */
object DirectLinkReset {
    private const val REACH_TIMEOUT_MS = 1_500

    private val scheduler = ScheduledThreadPoolExecutor(1) { Thread(it, "flick-tdls-reset").apply { isDaemon = true } }.apply {
        setKeepAliveTime(10, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    /**
     * Process-wide, not per server: a check outlives the ControlServer that scheduled
     * it, and only a counter every cast and every later watch advances can tell it a
     * newer cast — or a newer quiet spell with its own timing — superseded it.
     */
    private val epoch = AtomicLong()

    fun castStarted() {
        epoch.incrementAndGet()
    }

    /** [stillQuiet] covers a cast that was already playing when the watch began. */
    fun watch(context: Context, peerIp: String, trigger: String, stillQuiet: () -> Boolean) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        val address = runCatching { InetAddress.getByName(peerIp) }.getOrNull() ?: return
        val mine = epoch.incrementAndGet()
        val tornDown = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val current = { epoch.get() == mine && !done.get() && stillQuiet() }
        for (delayMs in IdleLinkChecks.DELAYS_MS) {
            scheduler.schedule(
                { check(wifi, address, trigger, delayMs / 1000, current, tornDown, done) },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun check(
        wifi: WifiManager,
        address: InetAddress,
        trigger: String,
        afterSec: Long,
        current: () -> Boolean,
        tornDown: AtomicBoolean,
        done: AtomicBoolean,
    ) {
        if (!current()) return
        if (answers(address, REACH_TIMEOUT_MS)) {
            if (IdleLinkChecks.seriesDone(tornDown.get(), phoneAnswered = true)) {
                done.set(true)
                FlickLog.i("lan", "tdls watch done at=$trigger+${afterSec}s peer=answered")
            }
            return
        }
        // The probe can take ~3 s; a cast adopted inside it must not lose its link.
        if (!current()) return
        tearDown(wifi, address, TeardownSite.IDLE, "at=$trigger+${afterSec}s peer=unanswered") { accepted ->
            if (accepted != false) tornDown.set(true)
        }
    }

    /**
     * Whether the phone answers `isReachable`. Android's `isReachable` catches its own
     * send and connect failures (no route, network unreachable) and returns false, so
     * a probe this TV cannot send reads as unanswered; only an exception it lets
     * escape counts as answered here.
     */
    internal fun answers(address: InetAddress, timeoutMs: Int): Boolean =
        runCatching { address.isReachable(timeoutMs) }.getOrDefault(true)

    /**
     * Asks the platform to tear the direct link to [address] down. [onResult] gets
     * whether it was accepted, or null below API 34, where the platform does not say.
     * Never call it on the main thread, nor for a phone that answered.
     */
    internal fun tearDown(
        wifi: WifiManager,
        address: InetAddress,
        site: TeardownSite,
        detail: String,
        onResult: (Boolean?) -> Unit = {},
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                wifi.setTdlsEnabled(address, false, Runnable::run) { ok ->
                    FlickLog.i("lan", "tdls teardown $detail accepted=$ok")
                    IncidentJournal.record("tdls_teardown", "site" to site, "accepted" to ok)
                    onResult(ok)
                }
            } else {
                wifi.setTdlsEnabled(address, false)
                FlickLog.i("lan", "tdls teardown $detail")
                IncidentJournal.record("tdls_teardown", "site" to site, "accepted" to "unknown")
                onResult(null)
            }
        }.onFailure {
            FlickLog.w("lan", "tdls teardown failed ${detail.substringBefore(' ')}", it)
            IncidentJournal.record("tdls_teardown_failed", "site" to site, "error" to it.javaClass.simpleName)
            onResult(false)
        }
    }
}

/** Where a teardown was decided; journaled by name. */
internal enum class TeardownSite { IDLE, MIDFILM, PREFLIGHT }

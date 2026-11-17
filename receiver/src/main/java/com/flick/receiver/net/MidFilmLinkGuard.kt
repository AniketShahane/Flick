package com.flick.receiver.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.flick.receiver.session.DirectLinkGuard
import com.flick.receiver.util.FlickLog
import com.flick.receiver.util.IncidentJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL

/**
 * Repairs a direct link only this TV still holds, while a cast is playing and as a
 * cast starts — see [MidFilmLinkPolicy] for when, and [DirectLinkReset] for why a
 * teardown is never sent to a phone that answers.
 *
 * The phone is the media URL's host, which `MediaUrlValidator` has already pinned
 * to the control connection's peer. Probes and teardowns run on a thread of their
 * own, one per playing cast; nothing here runs while no cast is playing, and the
 * between-films checks of [DirectLinkReset.watch] are left alone.
 */
class MidFilmLinkGuard(
    context: Context,
    /** `elapsedRealtime` in, starvation out; read from the guard's own thread. */
    private val starvedMs: (Long) -> Long,
    private val lastMediaByteAtMs: () -> Long,
) : DirectLinkGuard {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var running: Watch? = null

    override fun watchCast(castId: String, mediaUrl: String) {
        val host = peerOf(mediaUrl) ?: return
        synchronized(lock) {
            val current = running
            if (current != null && current.castId == castId && !current.cancelled) return
            current?.cancelled = true
            val watch = Watch(castId, host)
            running = watch
            Thread(watch, "flick-link-guard").apply { isDaemon = true }.start()
        }
    }

    override fun releaseCast() {
        synchronized(lock) {
            running?.cancelled = true
            running = null
        }
    }

    override suspend fun repairUnreachablePeer(mediaUrl: String): Boolean = withContext(Dispatchers.IO) {
        val host = peerOf(mediaUrl) ?: return@withContext false
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return@withContext false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext false
        if (DirectLinkReset.answers(address, PREFLIGHT_PROBE_TIMEOUT_MS)) {
            FlickLog.i("lan", "preflight repair skipped peer=answered")
            IncidentJournal.record("tdls_teardown_skipped", "site" to TeardownSite.PREFLIGHT, "peer" to "answered")
            return@withContext false
        }
        DirectLinkReset.tearDown(wifi, address, TeardownSite.PREFLIGHT, "at=preflight peer=unanswered")
        delay(PREFLIGHT_SETTLE_MS)
        true
    }

    private inner class Watch(val castId: String, private val host: String) : Runnable {
        @Volatile var cancelled = false

        override fun run() {
            val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return
            val policy = MidFilmLinkPolicy(SystemClock.elapsedRealtime())
            while (!cancelled) {
                val nowMs = SystemClock.elapsedRealtime()
                val step = policy.step(nowMs, starvedMs(nowMs))
                report(policy.drainEvents())
                when (step) {
                    is MidFilmLinkPolicy.Step.Sleep -> try {
                        Thread.sleep(step.ms)
                    } catch (_: InterruptedException) {
                        return
                    }
                    is MidFilmLinkPolicy.Step.Probe -> {
                        val startedAtMs = SystemClock.elapsedRealtime()
                        val answered = DirectLinkReset.answers(address, step.timeoutMs)
                        if (cancelled) return
                        val endedAtMs = SystemClock.elapsedRealtime()
                        policy.onProbed(
                            kind = step.kind,
                            answered = answered,
                            startedAtMs = startedAtMs,
                            starvedAfterMs = starvedMs(endedAtMs),
                            bytesDuringProbe = lastMediaByteAtMs() >= startedAtMs,
                        )
                        if (step.kind == MidFilmLinkPolicy.ProbeKind.FOLLOW_UP) {
                            FlickLog.i("lan", "tdls followup at=midfilm answered=$answered tookMs=${endedAtMs - startedAtMs}")
                            IncidentJournal.record("tdls_followup", "answered" to answered)
                        }
                    }
                    is MidFilmLinkPolicy.Step.TearDown -> {
                        // Re-read last: a cast that ended inside the probe must not lose a link.
                        if (cancelled) return
                        DirectLinkReset.tearDown(
                            wifi,
                            address,
                            TeardownSite.MIDFILM,
                            "at=midfilm starvedMs=${step.starvedMs} misses=${step.misses}",
                        )
                        policy.onTornDown(SystemClock.elapsedRealtime())
                    }
                }
            }
        }

        private fun report(events: List<MidFilmLinkPolicy.Event>) {
            for (event in events) when (event) {
                is MidFilmLinkPolicy.Event.ArmedChanged -> {
                    FlickLog.i("lan", "midfilm guard armed=${event.armed}")
                    IncidentJournal.record("link_guard", "armed" to event.armed)
                }
                is MidFilmLinkPolicy.Event.EpisodeBegan -> {
                    FlickLog.w("lan", "starvation begin starvedMs=${event.starvedMs} armed=${event.armed}")
                    IncidentJournal.record("starvation_begin", "starved_ms" to event.starvedMs, "armed" to event.armed)
                }
                is MidFilmLinkPolicy.Event.EpisodeEnded -> {
                    FlickLog.w(
                        "lan",
                        "starvation end lastedMs=${event.lastedMs} probes=${event.probes} " +
                            "unanswered=${event.unanswered} teardowns=${event.teardowns}",
                    )
                    IncidentJournal.record(
                        "starvation_end",
                        "lasted_ms" to event.lastedMs,
                        "probes" to event.probes,
                        "unanswered" to event.unanswered,
                        "teardowns" to event.teardowns,
                    )
                }
            }
        }
    }

    /** The literal private IPv4 host of a validated media URL, or null. Never resolves a name. */
    private fun peerOf(mediaUrl: String): String? =
        runCatching { URL(mediaUrl).host }.getOrNull()?.takeIf(MediaUrlValidator::isPrivateIpv4)

    private companion object {
        const val PREFLIGHT_PROBE_TIMEOUT_MS = 1_000
        const val PREFLIGHT_SETTLE_MS = 500L
    }
}

package com.flick.sender.net

import com.flick.sender.WifiBand
import com.flick.sender.WifiLinkInfo
import com.flick.sender.model.PlaybackPhase

/**
 * What this phone has proven about the link carrying the current cast.
 *
 * Nothing here is terminal and nothing here refuses a cast. The only outcome this
 * changes is a cast that was already going to die of `startup_timeout`, which now gets
 * a correct face instead of a misleading one; playback is never stopped or downgraded.
 */
sealed interface LinkVerdict {
    /** No opinion — and the honest answer for most of a cast's life. */
    data object Unknown : LinkVerdict

    /**
     * The path sustained [peakBps] at least once. One-sided by construction: this phone
     * is the server, and a server never writes faster than the client pulls, so an
     * observed rate is a rate the path DEMONSTRABLY carried.
     */
    data class Proven(val peakBps: Long) : LinkVerdict

    /** Above real time but inside the headroom. Logged; deliberately drives no UI. */
    data class Marginal(val measuredBps: Long, val requiredBps: Long) : LinkVerdict

    /** Under real time across a sustained window, so the buffer can only shrink. */
    data class Starved(val measuredBps: Long, val requiredBps: Long) : LinkVerdict
}

/**
 * One reading off the serving socket: [bytes] written in [elapsedMs] with [inFlight]
 * transfers open across it.
 *
 * These are bytes this phone WROTE, which is why the reading can under-report a fast
 * link (the TV stops pulling once its buffer is full) but can never over-report a slow
 * one. [inFlight] is what separates the two: a link carrying nothing because nobody is
 * asking is not a slow link.
 */
data class LinkSample(val bytes: Long, val elapsedMs: Long, val inFlight: Int) {
    val bitsPerSec: Long get() = if (elapsedMs > 0L) bytes * 8_000L / elapsedMs else 0L
}

/**
 * The retained samples a verdict is reached on, with the bitrate the file needs and what
 * the TV was doing while they were taken.
 *
 * [requiredBps] is null whenever the file's metadata cannot support the arithmetic —
 * and a null is a permanent "say nothing", never a guess.
 *
 * [demandsBytes] is the receiver's own hunger, from [LinkCapacityPolicy.demandsBytes]. It
 * is the second half of the one-sidedness: bytes written prove capacity only while
 * somebody is pulling them, and a shortfall means nothing unless somebody was.
 */
data class LinkWindow(
    val samples: List<LinkSample>,
    val requiredBps: Long?,
    val demandsBytes: Boolean,
)

/** Advisory raised before a byte moves, from link rate alone. Never a gate. */
data class PreCastLinkAdvisory(val requiredBps: Long, val usableBps: Long, val band: WifiBand?)

/**
 * Every number and every rule behind under-capacity detection, with no state, no clock
 * and no Android in it. [LinkCapacityMonitor] owns the state and delegates every
 * decision here.
 *
 * A false positive is worse than the stutter it would explain: this feature exists to
 * put a correct face on a cast that was already failing, so each rule below is written
 * to say nothing rather than to say something wrong.
 */
object LinkCapacityPolicy {

    /**
     * The margin a measured rate must clear before the link counts as proven.
     *
     * The tempting figure is the VBR peak-to-average ratio — UHD Blu-ray runs 30–60 Mbps
     * average against 128 Mbps peaks, i.e. 2.1x–4.3x — and using it would refuse casts
     * that work. Content peaks are the BUFFER's job and the receiver's buffer is already
     * sized for them: `BufferBudget.protectionSecondsAt` yields 39.5 s of cushion at
     * 40 Mbps, 25.5 s at 60 and 14.3 s at 100, so a 2x excursion lasting 10 s costs 10 s
     * against a 25 s reserve. What is left for this number to cover is measurement error
     * and link variability, which is the same 80% margin Plex uses.
     */
    const val HEADROOM = 1.25f

    /** Below this span a window is a moment, not a trend, and can never read Starved. */
    const val MIN_WINDOW_MS = 6_000L

    /** TCP slow start plus the TV's opening range probes; these seconds measure neither link nor film. */
    const val WARMUP_MS = 2_000L

    /** Shorter than this a refill is a hiccup the buffer absorbed, not an episode. */
    const val MIN_EPISODE_MS = 1_000L

    /** A refill this soon after a seek is the seek's, and a seek always costs one. */
    const val SEEK_GRACE_MS = 5_000L

    /**
     * Media held ahead of the playhead below which the receiver is loading flat out, so a
     * shortfall measured against it is the link's and not the buffer's.
     *
     * `DefaultLoadControl` loads unconditionally below its `minBufferMs` — 14.3 s on the
     * verified TV, as low as 1 s on a small heap — and above that until the byte target is
     * reached, which `BufferBudget.protectionSecondsAt` puts at 39.5 s of media at 40 Mbps
     * and still 10.6 s at UHD Blu-ray's 128 Mbps ceiling. Five seconds is under every one
     * of those, so a buffer at its ceiling can never be read as hunger, and just above the
     * ~4.8 s the receiver resumes playback from, so a buffer this low is one the TV is
     * certainly pulling against.
     */
    const val HUNGRY_RESERVE_MS = 5_000L

    const val EPISODE_WINDOW_MS = 120_000L
    const val EPISODES_TO_ESCALATE = 3

    /**
     * Fraction of the Wi-Fi link rate a real transfer can expect to see. Link rate is a
     * PHY negotiation, not a throughput measurement: roughly half of PHY is achievable at
     * best, and a 1300 Mbps 802.11ac link measured 341 Mbps under iperf (26%). This is
     * deliberately the generous end of that range so the pre-cast advisory errs toward
     * staying quiet.
     */
    const val USABLE_LINK_FRACTION = 0.45f

    /**
     * Bounds on a believable container bitrate. Outside them the METADATA is wrong, not
     * the film: MediaStore under-reporting duration on a long remux would manufacture a
     * 500 Mbps "requirement" and warn about a perfect link. UHD Blu-ray's own ceiling is
     * 128 Mbps, so the upper bound is a sanity check rather than a content limit.
     */
    const val MIN_PLAUSIBLE_BPS = 100_000L
    const val MAX_PLAUSIBLE_BPS = 400_000_000L

    /**
     * The rate the file must cross the wire at to play in real time — container bitrate,
     * because container bytes are what cross the wire.
     *
     * Null whenever the inputs or the result cannot be trusted. There is no fallback and
     * no estimate: a wrong requirement is how this feature would libel a healthy link.
     */
    fun requiredBitrateBps(sizeBytes: Long, durationMs: Long): Long? {
        if (sizeBytes <= 0L || durationMs <= 0L) return null
        val bps = sizeBytes * 8_000L / durationMs
        return bps.takeIf { it in MIN_PLAUSIBLE_BPS..MAX_PLAUSIBLE_BPS }
    }

    /** Media-seconds delivered per wall-second. Zero rather than a divide when nothing is required. */
    fun realTimeFactor(measuredBps: Long, requiredBps: Long): Float =
        if (requiredBps <= 0L) 0f else measuredBps.toFloat() / requiredBps.toFloat()

    /** What [linkSpeedMbps] of negotiated PHY realistically carries. */
    fun usableThroughputBps(linkSpeedMbps: Int): Long {
        if (linkSpeedMbps <= 0) return 0L
        return (linkSpeedMbps * 1_000_000.0 * USABLE_LINK_FRACTION).toLong()
    }

    /**
     * Whether a sample taken [sinceFirstByteMs] after the first served byte may enter the
     * window.
     */
    fun retainSample(sinceFirstByteMs: Long): Boolean = sinceFirstByteMs >= WARMUP_MS

    /**
     * Whether the TV is still pulling bytes as fast as they arrive.
     *
     * [LinkSample.inFlight] cannot answer this and is not a second opinion on it: Media3
     * holds ONE open range request across the file, so a full buffer leaves the transfer
     * open and the server simply blocked in `write`. What the socket then measures is the
     * current scene's consumption — under the container average on every quiet scene, and
     * ~0 bits/s for as long as the user pauses — which is the whole of the Kodi #22332
     * false positive (a cache-level test accusing gigabit LANs with nothing stuttering).
     *
     * [reserveMs] is media held AHEAD of the playhead: the receiver reports an absolute
     * buffered position, so this is `bufferedMs - posMs`. BUFFERING needs no reserve test
     * — playback has stopped for want of bytes, which includes the opening fill this
     * feature exists to put a face on.
     */
    fun demandsBytes(phase: PlaybackPhase, reserveMs: Long): Boolean = when (phase) {
        PlaybackPhase.BUFFERING -> true
        // Paused counts: the player keeps filling while paused, and a paused reserve does
        // not drain, so a low one is bytes asked for and not delivered.
        PlaybackPhase.PLAYING, PlaybackPhase.PAUSED -> reserveMs <= HUNGRY_RESERVE_MS
        PlaybackPhase.IDLE, PlaybackPhase.ENDED, PlaybackPhase.ERROR -> false
    }

    /**
     * The pre-cast advisory, from link rate alone — the only thing knowable before a byte
     * moves, and the reason there is no pre-flight refusal anywhere in this feature.
     *
     * Compared without [HEADROOM] on purpose: [USABLE_LINK_FRACTION] has already discounted
     * the link rate by more than half, and stacking a second margin on an input this weak
     * would raise the card on links that carry the film fine.
     */
    fun preCastAdvisory(requiredBps: Long?, link: WifiLinkInfo?): PreCastLinkAdvisory? {
        val required = requiredBps?.takeIf { it > 0L } ?: return null
        val info = link ?: return null
        val usable = usableThroughputBps(info.linkSpeedMbps)
        if (usable <= 0L || usable >= required) return null
        return PreCastLinkAdvisory(required, usable, info.band)
    }

    /**
     * The verdict for one retained window.
     *
     * Order is the whole design. A single sample clearing [HEADROOM] answers the window
     * before any starvation test runs, because that sample is proof the path carried the
     * film and no amount of later throttling can unprove it — on a healthy link the buffer
     * fills in seconds and throughput then drops to the CONTENT rate, which is exactly what
     * a starvation test would misread. [alreadyProven] carries that proof across windows.
     *
     * `rtf < 1.0` is arithmetic, not a heuristic: under real time the buffer can only
     * shrink. The measured rate is the window's aggregate (total bytes over total span), so
     * one fast second inside a starved window cannot mask the drain — and cannot be read as
     * starvation either, because the [HEADROOM] test above already claimed it.
     *
     * The arithmetic holds only while the TV is asking, which is why the same shortfall
     * says nothing at all under [LinkWindow.demandsBytes]: a server writes at the rate its
     * client reads, so a full buffer and a slow link produce the identical low number.
     */
    fun verdict(window: LinkWindow, alreadyProven: Boolean): LinkVerdict {
        val required = window.requiredBps?.takeIf { it > 0L } ?: return LinkVerdict.Unknown
        val samples = window.samples
        val peakBps = samples.maxOfOrNull { it.bitsPerSec } ?: 0L
        if (samples.any { realTimeFactor(it.bitsPerSec, required) >= HEADROOM }) {
            return LinkVerdict.Proven(peakBps)
        }
        if (alreadyProven) return LinkVerdict.Proven(peakBps)

        val spanMs = samples.sumOf { it.elapsedMs }
        if (spanMs < MIN_WINDOW_MS) return LinkVerdict.Unknown
        // A TV that stopped asking is not a slow link, and one gap in the window is enough
        // to make the aggregate a measurement of the gap.
        if (samples.any { it.inFlight <= 0 }) return LinkVerdict.Unknown

        val measuredBps = samples.sumOf { it.bytes } * 8_000L / spanMs
        if (realTimeFactor(measuredBps, required) >= 1f) return LinkVerdict.Marginal(measuredBps, required)
        // Under real time, and only the receiver's own reserve can say whether that is the
        // link falling short or its buffer being full. Unknown rather than Marginal: this
        // window measured a throttle, and Marginal asserts a rate at or above the film's.
        return if (window.demandsBytes) LinkVerdict.Starved(measuredBps, required) else LinkVerdict.Unknown
    }

    /**
     * Whether a refill that began at [startedAtMs] and ran [durationMs] counts against the
     * link.
     *
     * A seek costs a refill on every link there is, so one inside [SEEK_GRACE_MS] of a
     * committed seek is the user's, not the network's. [lastSeekCommitMs] of 0 means no
     * seek has been made this cast.
     *
     * A stamp INSIDE the refill excuses it for the same reason, and that is the common
     * case rather than an edge: the phone holds a seek outstanding for the whole of the
     * refill it caused — a BUFFERING frame reports the position the TV is seeking FROM
     * ([SeekPolicy.pending]), so every frame of that fill re-dates the grace window it
     * exists to spend. Reading a stamp later than the refill's start as "the seek came
     * after, so charge the link" would charge the user's own three skips to a healthy LAN.
     */
    fun countsAsEpisode(startedAtMs: Long, lastSeekCommitMs: Long, durationMs: Long): Boolean {
        if (durationMs < MIN_EPISODE_MS) return false
        if (lastSeekCommitMs <= 0L) return true
        return startedAtMs - lastSeekCommitMs >= SEEK_GRACE_MS
    }

    /**
     * Whether the stalling card is owed, from the episode count ALONE.
     *
     * Deliberately never consults the verdict: average container bitrate is the weakest
     * input this feature has, and a VBR-peak starvation on a nominally-fast-enough file is
     * still three stalls the user watched. The verdict only picks the copy.
     */
    fun shouldEscalate(episodeStartsMs: List<Long>, nowMs: Long): Boolean {
        val cutoff = nowMs - EPISODE_WINDOW_MS
        return episodeStartsMs.count { it >= cutoff && it <= nowMs } >= EPISODES_TO_ESCALATE
    }
}

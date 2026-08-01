package com.flick.sender.net

import com.flick.sender.model.PlaybackPhase
import com.flick.sender.model.PlaybackUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The stalling card's whole state.
 *
 * [raised] is decided by [episodes] alone — never by the verdict — so a starvation the
 * average-bitrate arithmetic cannot see still reaches the user. The verdict rides along
 * only to choose the copy: [quotesNumbers] is what separates the body that names two
 * bitrates from the one that names none.
 */
data class LinkStall(
    val raised: Boolean = false,
    val episodes: Int = 0,
    val verdict: LinkVerdict = LinkVerdict.Unknown,
) {
    // Starved only, though Marginal carries the same two figures. Marginal means the
    // measured rate is AT OR ABOVE what the film needs, so quoting it under this card
    // would print "needs 40.0 Mbps, carrying 44.0 Mbps" beneath a title asserting the
    // opposite. The card is still owed — three stalls happened — but the numbers are the
    // one thing that would then be arguing with it.
    val measuredBps: Long?
        get() = (verdict as? LinkVerdict.Starved)?.measuredBps

    val requiredBps: Long?
        get() = (verdict as? LinkVerdict.Starved)?.requiredBps

    val quotesNumbers: Boolean get() = measuredBps != null && requiredBps != null
}

/**
 * Collects what the serving socket and the TV's own state frames say about the link, and
 * publishes the two things a screen can act on: the [verdict] and the [stall].
 *
 * Holds no policy of its own — every threshold and every decision lives in
 * [LinkCapacityPolicy]. What it owns is the state that policy needs and cannot keep: the
 * peak sample of the cast, the rolling window, the rebuffer ring, whether the TV's last
 * frame had it asking for bytes, and the fact that a link once proven stays proven.
 *
 * [clockMs] is injected rather than read from `SystemClock` so the whole of this is
 * testable on a plain JVM. Every field below is plain because the coordinator feeds this
 * from its application scope, which is main-confined.
 */
class LinkCapacityMonitor(private val clockMs: () -> Long) {

    private val _verdict = MutableStateFlow<LinkVerdict>(LinkVerdict.Unknown)
    val verdict: StateFlow<LinkVerdict> = _verdict.asStateFlow()

    private val _stall = MutableStateFlow(LinkStall())
    val stall: StateFlow<LinkStall> = _stall.asStateFlow()

    private var castId: String? = null
    private var collecting = false
    private var requiredBps: Long? = null

    private var firstByteAtMs = 0L
    private var peakBps = 0L
    private var proven = false
    private val recent = ArrayDeque<LinkSample>()
    // False until the TV says otherwise: a window taken before any frame arrived has no
    // evidence anybody was pulling, and that is a "say nothing", not a starvation.
    private var demandsBytes = false

    private val episodes = ArrayDeque<Long>()
    private var lastSeekCommitMs = 0L
    private var lastPhase = PlaybackPhase.IDLE
    private var buffering = false
    private var bufferingSinceMs = 0L
    private var bufferingCountable = false
    private var episodeCounted = false
    private var dismissed = false

    /**
     * Arm for a fresh cast. [requiredBps] is null whenever the file's metadata could not
     * support the arithmetic, and a null cast measures nothing and says nothing.
     */
    fun beginCast(castId: String, requiredBps: Long?) {
        clearState()
        this.castId = castId
        this.requiredBps = requiredBps
        collecting = true
        publishIdle()
    }

    /** Disarm. Nothing about one cast's link may survive into the next. */
    fun reset() {
        clearState()
        publishIdle()
    }

    /**
     * A terminal for the cast being measured freezes the verdict where it died, so the
     * error face reads the link as it was rather than as the teardown left it. Keyed on
     * the cast id: a `Failed` belonging to a superseded cast can never silence this one.
     */
    fun onCastStart(state: CastStartState) {
        if (state is CastStartState.Failed && state.castId == castId) collecting = false
    }

    /**
     * A load re-issued against a live cast — a subtitle swap. The TV flushes its decoder
     * and refills from a new byte offset, which is a seek in everything but name, so the
     * refill it costs is charged to the same grace window.
     */
    fun onReload() {
        if (collecting) lastSeekCommitMs = clockMs()
    }

    /**
     * One reading off the serving socket. The first byte-carrying sample stamps the start
     * of warm-up and is itself discarded with it.
     */
    fun onSample(sample: LinkSample) {
        if (!collecting || sample.elapsedMs <= 0L) return
        val now = clockMs()
        if (firstByteAtMs == 0L) {
            if (sample.bytes <= 0L) return
            firstByteAtMs = now
        }
        if (!LinkCapacityPolicy.retainSample(now - firstByteAtMs)) return
        recent.addLast(sample)
        while (recent.size > WINDOW_SAMPLES) recent.removeFirst()
        peakBps = maxOf(peakBps, sample.bitsPerSec)
        evaluate(now)
    }

    /**
     * One TV state frame. Rebuffer episodes are counted the moment they cross
     * [LinkCapacityPolicy.MIN_EPISODE_MS] rather than when they end, so the third stall
     * raises the card while the user is still watching it.
     */
    fun onPlayback(state: PlaybackUiState) {
        if (!collecting) return
        val now = clockMs()
        // The reserve, not the raw field: the receiver reports an absolute buffered
        // position, which climbs with the film whatever the link is doing. A newly hungry
        // TV starts a fresh window; samples gathered while its buffer was full measured
        // throttling, not the capacity now being asked of the link.
        val nextDemandsBytes =
            LinkCapacityPolicy.demandsBytes(state.phase, state.bufferedMs - state.confirmedMs)
        if (nextDemandsBytes && !demandsBytes) recent.clear()
        demandsBytes = nextDemandsBytes
        // These three are raised only by a seek in flight or one still landing — the idle
        // branch of PlaybackSession clears `syncing` unconditionally — so stamping on them
        // dates the grace window from the seek and not from a slow link.
        if (state.scrubbing || state.skipping || state.syncing) lastSeekCommitMs = now
        if (state.phase == PlaybackPhase.BUFFERING) {
            if (!buffering) {
                buffering = true
                bufferingSinceMs = now
                // The PHASE this interrupted, never the frame's own `playing` flag: the
                // receiver reports that flag as ExoPlayer's isPlaying, which is false for
                // the whole of any buffer, so the frame that opens a refill cannot say
                // whether anyone was watching. A refill out of PLAYING is a rebuffer; out
                // of IDLE it is the cast starting, which has its own face and its own
                // timeout and is not this feature's business.
                bufferingCountable = lastPhase == PlaybackPhase.PLAYING
                episodeCounted = false
            }
            if (bufferingCountable && !episodeCounted &&
                LinkCapacityPolicy.countsAsEpisode(bufferingSinceMs, lastSeekCommitMs, now - bufferingSinceMs)
            ) {
                episodeCounted = true
                episodes.addLast(bufferingSinceMs)
                while (episodes.size > EPISODE_RING) episodes.removeFirst()
            }
        } else {
            buffering = false
        }
        lastPhase = state.phase
        publishStall(now)
    }

    /**
     * The user answered the card. Sticky for the rest of the cast: the episodes that
     * raised it are still true, and a card that re-raises on the next stall is a card
     * that ignored the answer.
     */
    fun dismissStall() {
        if (!_stall.value.raised) return
        dismissed = true
        _stall.value = _stall.value.copy(raised = false)
    }

    private fun evaluate(now: Long) {
        val decided = LinkCapacityPolicy.verdict(
            LinkWindow(recent.toList(), requiredBps, demandsBytes),
            proven,
        )
        if (decided is LinkVerdict.Proven) proven = true
        // The policy sees one window; the cast's peak is held here, so a proof carried
        // forward keeps quoting the rate that earned it rather than the current window's.
        _verdict.value = if (decided is LinkVerdict.Proven) {
            LinkVerdict.Proven(maxOf(decided.peakBps, peakBps))
        } else {
            decided
        }
        publishStall(now)
    }

    private fun publishStall(now: Long) {
        val cutoff = now - LinkCapacityPolicy.EPISODE_WINDOW_MS
        while (episodes.isNotEmpty() && episodes.first() < cutoff) episodes.removeFirst()
        _stall.value = LinkStall(
            raised = !dismissed && LinkCapacityPolicy.shouldEscalate(episodes.toList(), now),
            episodes = episodes.size,
            verdict = _verdict.value,
        )
    }

    private fun publishIdle() {
        _verdict.value = LinkVerdict.Unknown
        _stall.value = LinkStall()
    }

    private fun clearState() {
        castId = null
        collecting = false
        requiredBps = null
        firstByteAtMs = 0L
        peakBps = 0L
        proven = false
        recent.clear()
        demandsBytes = false
        episodes.clear()
        lastSeekCommitMs = 0L
        lastPhase = PlaybackPhase.IDLE
        buffering = false
        bufferingSinceMs = 0L
        bufferingCountable = false
        episodeCounted = false
        dismissed = false
    }

    private companion object {
        /** Ten 1 s samples: long enough to outlast a VBR excursion, short enough to still be now. */
        const val WINDOW_SAMPLES = 10

        /** Only the last two minutes can escalate, so the ring is a memory bound and nothing more. */
        const val EPISODE_RING = 16
    }
}

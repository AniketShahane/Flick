package com.flick.receiver.summon

enum class AttemptState { Pending, Opened, Missed, Abandoned }

data class SummonAttempt(
    val id: Long,
    val issuedAtMs: Long,
    val interactiveAtIssue: Boolean,
    val state: AttemptState = AttemptState.Pending,
)

data class MissVerdict(
    val attempt: SummonAttempt,
    val kind: SummonMiss,
    val strikes: Int,
    val blockedNow: Boolean,
    val waitedMs: Long,
)

data class LateCorrection(val attemptId: Long, val unblocked: Boolean)

/**
 * The summon bookkeeping, apart from the Android calls that act on it. Main thread
 * only; the caller supplies the clock and applies every verdict.
 *
 * Each attempt resolves exactly once — Opened, Missed or Abandoned — and every
 * operation names the attempt it is about, so a stale caller can never resolve a
 * newer one.
 */
class SummonLedger(initialStrikes: Int, initialBlocked: Boolean) {
    var strikes: Int = initialStrikes
        private set
    var blocked: Boolean = initialBlocked
        private set

    /** Set by a miss or by leaving during a cast; cleared only by the next ON_START. */
    var missedLatch: Boolean = false
        private set
    var inFlight: SummonAttempt? = null
        private set

    private var holdCounter = 0L
    private var newestHold = 0L
    private var attemptCounter = 0L

    /** The strike the last miss scored, kept so a trampoline that resumes late can take it back. */
    private var lastStrike: StrikeRecord? = null
    private var lastOpenedAtMs: Long? = null

    private data class StrikeRecord(val attemptId: Long, val setBlocked: Boolean)

    fun takeHold(): Long {
        newestHold = ++holdCounter
        return newestHold
    }

    /** Only the newest hold releases: a superseded probe must not drop its successor's wake lock. */
    fun releaseHold(token: Long): Boolean {
        if (token == 0L || token != newestHold) return false
        newestHold = 0L
        return true
    }

    fun issue(nowMs: Long, interactive: Boolean): SummonAttempt {
        val attempt = SummonAttempt(++attemptCounter, nowMs, interactive)
        inFlight = attempt
        return attempt
    }

    /** The wait in ms, or null when [attemptId] is not the pending attempt. */
    fun opened(attemptId: Long, nowMs: Long): Long? {
        val attempt = pending(attemptId) ?: return null
        inFlight = attempt.copy(state = AttemptState.Opened)
        strikes = SummonStrikePolicy.afterOutcome(strikes, SummonOutcome.Opened)
        lastStrike = null
        lastOpenedAtMs = nowMs
        return nowMs - attempt.issuedAtMs
    }

    fun abandon(attemptId: Long): Boolean {
        val attempt = pending(attemptId) ?: return false
        inFlight = attempt.copy(state = AttemptState.Abandoned)
        return true
    }

    fun missed(attemptId: Long, nowMs: Long, wakeResumed: Boolean): MissVerdict? {
        val attempt = pending(attemptId) ?: return null
        val resolved = attempt.copy(state = AttemptState.Missed)
        inFlight = resolved
        missedLatch = true
        val kind = SummonPolicy.classifyMiss(wakeResumed, attempt.interactiveAtIssue)
        strikes = SummonStrikePolicy.afterOutcome(strikes, SummonPolicy.outcomeOf(kind))
        val blockedNow = kind == SummonMiss.Blocked && !blocked && SummonStrikePolicy.blockedNow(strikes)
        if (blockedNow) blocked = true
        lastStrike = if (kind == SummonMiss.Blocked) StrikeRecord(attemptId, blockedNow) else null
        return MissVerdict(resolved, kind, strikes, blockedNow, nowMs - attempt.issuedAtMs)
    }

    /**
     * A trampoline that resumed after its attempt was scored Blocked: the launch was
     * allowed after all, so the strike goes, and the block with it if that strike set it.
     */
    fun wakeResumed(attemptId: Long): LateCorrection? {
        val strike = lastStrike?.takeIf { it.attemptId == attemptId } ?: return null
        strikes = SummonStrikePolicy.afterOutcome(strikes, SummonOutcome.Late)
        if (strike.setBlocked) blocked = false
        lastStrike = null
        return LateCorrection(attemptId, strike.setBlocked)
    }

    fun leftDuringCast(nowMs: Long, beforeReady: Boolean): LatchReason {
        missedLatch = true
        val openedAt = lastOpenedAtMs
        return if (beforeReady && openedAt != null && nowMs - openedAt <= SummonPolicy.BOUNCE_WINDOW_MS) {
            LatchReason.Bounced
        } else {
            LatchReason.LeftDuringCast
        }
    }

    fun foreground() {
        missedLatch = false
    }

    fun unblock() {
        blocked = false
        strikes = 0
        lastStrike = null
    }

    private fun pending(attemptId: Long): SummonAttempt? =
        inFlight?.takeIf { it.id == attemptId && it.state == AttemptState.Pending }
}

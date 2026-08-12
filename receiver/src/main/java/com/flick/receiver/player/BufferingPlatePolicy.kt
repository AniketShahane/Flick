package com.flick.receiver.player

/** Which of the two rebuffer plates the film is behind. */
enum class BufferingPlate { TOPPING_UP, STALLED }

/**
 * The plate escalates past the point where the buffer can no longer be described as
 * holding.
 *
 * "Topping up — quality held" is true for the first seconds of a rebuffer and false
 * once the forward buffer is spent — and it was held for MINUTES: ~20 load retries over
 * ~100 s, then four silent re-prepares at 2/4/8/15 s each buying another ~100 s. Media3
 * cannot shorten that (`stuckBufferingDetectionTimeoutMs` defaults to 600 000 ms and
 * stuck-playing detection is off unless a static experimental flag is set, which this
 * app never sets), so the honest fix is the narration rather than the recovery.
 *
 * [protectionSeconds] is the ride-out THIS device actually bought — see
 * [BufferBudget.protectionSecondsAt] — so the threshold is measured rather than a
 * guessed constant. [recoveryAttempts] escalates immediately: the silence of the first
 * recovery is the anti-buffering thesis working, and by the second the buffer it was
 * protecting has provably been spent.
 */
fun bufferingPlate(stallMs: Long, protectionSeconds: Int, recoveryAttempts: Int): BufferingPlate =
    if (recoveryAttempts > 0 || stallMs >= protectionSeconds * 1_000L) BufferingPlate.STALLED
    else BufferingPlate.TOPPING_UP

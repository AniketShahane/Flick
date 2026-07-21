package com.flick.receiver.session

/** Pure startup-only retry budget; steady-state recovery belongs to PlayerController. */
object StartupRetryPolicy {
    const val MAX_RETRIES = 2
    private val backoffs = longArrayOf(250L, 500L)

    fun delayForRetry(completedRetries: Int, isTransientIo: Boolean, nowMs: Long, deadlineMs: Long): Long? {
        if (!isTransientIo || completedRetries >= MAX_RETRIES || nowMs >= deadlineMs) return null
        return backoffs[completedRetries].takeIf { nowMs + it < deadlineMs }
    }
}

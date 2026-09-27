package com.flick.receiver.session

/**
 * The seam through which a cast adopted while this TV's Activity is stopped may ask
 * to be brought to the front.
 *
 * A background activity launch can be dropped by the platform without an error, so
 * neither [request] returning nor [awaitStarted]'s answer is the verdict: only the
 * Activity's own Lifecycle reaching STARTED counts, re-read by the caller after the
 * wait.
 */
interface ForegroundRequest {
    /** Main thread, probe-coroutine start, only while below STARTED. 0 = nothing taken. No binder call when off. */
    fun holdAwake(): Long

    /** Called exactly once per probe coroutine, in `finally`, with holdAwake's result (0 included). */
    fun release(token: Long)

    /** Main thread, after a probe Ok, at most once per cast generation. False = refuse now. */
    fun request(): Boolean

    /** True iff STARTED was reached (then settles ≤ one frame). Cancellation abandons the attempt and propagates. */
    suspend fun awaitStarted(timeoutMs: Long): Boolean

    /** The wait ended and lifecycleStarted() is false. No-op when the attempt already resolved. */
    fun missed()

    /** A TV that never opens itself: every stopped cast fails exactly as it did before this seam existed. */
    object None : ForegroundRequest {
        override fun holdAwake(): Long = 0L
        override fun release(token: Long) = Unit
        override fun request(): Boolean = false
        override suspend fun awaitStarted(timeoutMs: Long): Boolean = false
        override fun missed() = Unit
    }
}

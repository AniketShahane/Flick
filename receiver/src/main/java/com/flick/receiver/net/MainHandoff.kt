package com.flick.receiver.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How long a Ktor worker waits for the main thread to adopt a `loadMedia`.
 *
 * Missing it refuses the frame and closes the whole control socket, so it is sized
 * for a TV that is genuinely busy rather than for a healthy one, which adopts in a
 * few milliseconds: a TV minutes out of boot, swapping and compacting other apps,
 * has been seen holding its main thread for more than a second, and one second
 * turned that into a dropped cast. It must still end well inside the phone's own
 * wait for `loadAccepted` (about 10 s), or the phone gives up on an answer this
 * TV is still going to send. A phone that only waits 2 s fails such a stall
 * whatever this is.
 */
internal const val MAIN_ADOPTION_TIMEOUT_MS = 4_000L

/** An adoption this slow is logged even though it succeeded — see [mainAdoptionSlow]. */
internal const val SLOW_MAIN_ADOPTION_MS = 500L

/**
 * Whether an adoption took long enough to be worth a line of its own. The next
 * stall that falls inside the budget would otherwise leave no trace at all, and
 * the one that did not is only diagnosable against the ones that nearly did.
 */
internal fun mainAdoptionSlow(adoptMs: Long): Boolean = adoptMs >= SLOW_MAIN_ADOPTION_MS

/**
 * One piece of main-thread work a Ktor worker waits on WITHOUT holding a lock.
 *
 * The main thread takes the control server's lock on several of its own paths, so
 * a wait made under that lock can only ever end at the deadline. Releasing it means
 * the lease can change while the work is queued, which is why [run] re-reads
 * ownership on the main thread itself: a lease revoked before that read refuses the
 * work, and one revoked after it is torn down by the control-loss callback, which
 * the revoke posts to the same looper and which therefore runs after this.
 *
 * With [abandonOnTimeout], a timed-out wait also guarantees the work never runs
 * later: the worker and the main thread race for one state transition, and a
 * worker that loses it waits for work already in progress rather than reporting a
 * timeout for a cast the main thread is adopting at that very moment.
 */
internal class MainHandoff<T : Any>(private val abandonOnTimeout: Boolean) {
    sealed interface Outcome<out T : Any> {
        /** [value] is null when the work returned null or threw. */
        data class Done<T : Any>(val value: T?) : Outcome<T>
        /** Ownership had already changed when the main thread reached the work. */
        data object Refused : Outcome<Nothing>
        data object TimedOut : Outcome<Nothing>
    }

    private val state = AtomicInteger(PENDING)
    private val done = CountDownLatch(1)
    @Volatile private var result: Outcome<T> = Outcome.TimedOut

    /** Main-thread side. Runs [block] only while [owned] still holds. */
    fun run(owned: () -> Boolean, block: () -> T?) {
        if (!state.compareAndSet(PENDING, RUNNING)) return
        try {
            result = if (!owned()) Outcome.Refused else Outcome.Done(runCatching(block).getOrNull())
        } finally {
            done.countDown()
        }
    }

    /** Worker side. */
    fun await(timeoutMs: Long): Outcome<T> {
        if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) return result
        if (!abandonOnTimeout || state.compareAndSet(PENDING, ABANDONED)) return Outcome.TimedOut
        done.await()
        return result
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val ABANDONED = 2
    }
}

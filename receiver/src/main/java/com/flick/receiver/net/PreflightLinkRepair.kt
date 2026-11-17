package com.flick.receiver.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * The cast-start half of the direct-link repair: tear down only for a phone that does
 * not answer, then give the link a moment to come back before the caller re-checks.
 *
 * [answers] blocks and cannot be cancelled (`isReachable` can take two seconds), so
 * a cast stopped or replaced while it ran is seen only by the cancellation check
 * after it — and that check is what keeps a teardown off the next cast's link.
 */
internal object PreflightLinkRepair {
    const val SETTLE_MS = 500L

    suspend fun run(answers: suspend () -> Boolean, tearDown: () -> Unit): Boolean {
        if (answers()) return false
        currentCoroutineContext().ensureActive()
        tearDown()
        delay(SETTLE_MS)
        return true
    }
}

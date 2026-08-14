package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleReloadWatchdogTest {
    @Test fun aPlayingReloadNeedsBothANewReadyTransitionAndANewPresentedFrame() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.onSubtitleLoaded(token, MEDIA_A))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertFalse(watchdog.onPresented(token, MEDIA_B))
        assertTrue(watchdog.onPresented(token, MEDIA_A))
        assertFalse(watchdog.consumeDeadline(token, MEDIA_A))
    }

    @Test fun aPausedReloadReadyWithoutANewFrameStillRollsBackAtTheDeadline() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.onSubtitleLoaded(token, MEDIA_A))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertTrue(watchdog.consumeDeadline(token, MEDIA_A))
    }

    @Test fun aPausedReloadReadyWithItsNewFrameIsHealthy() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.onSubtitleLoaded(token, MEDIA_A))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertFalse(watchdog.onPresented(token, MEDIA_B))
        assertTrue(watchdog.onPresented(token, MEDIA_A))
        assertFalse(watchdog.consumeDeadline(token, MEDIA_A))
    }

    @Test fun aStaleReadyAndFrameCannotCompleteBeforeTheReloadActuallyStarts() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = false)

        assertFalse(watchdog.onSubtitleLoaded(token, MEDIA_A))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertFalse(watchdog.onPresented(token, MEDIA_A))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = false))
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertTrue(watchdog.onPresented(token, MEDIA_A))
    }

    @Test fun aTerminalTextFailureCannotHideBehindReadyVideoAndIsClearedByRollback() {
        val watchdog = SubtitleReloadWatchdog()
        val failures = ExternalSubtitleFailureState()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        failures.recordLoadFailure()
        assertFalse(watchdog.onPlaybackState(token, MEDIA_A, ready = true))
        assertFalse(watchdog.onPresented(token, MEDIA_A))
        assertTrue(watchdog.consumeDeadline(token, MEDIA_A))
        assertTrue(failures.canRollback(hasSubtitle = true))

        failures.recordRollback()
        assertFalse(failures.shouldRollbackAfterPlayerError(hasSubtitle = false))
    }

    @Test fun aSupersedingReloadMakesTheEarlierDeadlineStale() {
        val watchdog = SubtitleReloadWatchdog()
        val first = 1L
        val second = 2L
        watchdog.arm(first, MEDIA_A, alreadyReloading = true)
        watchdog.arm(second, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.consumeDeadline(first, MEDIA_A))
        assertTrue(watchdog.consumeDeadline(second, MEDIA_A))
        assertFalse(watchdog.consumeDeadline(second, MEDIA_A))
    }

    @Test fun aDifferentMediaGenerationOrCancellationCannotRollBack() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.consumeDeadline(token, MEDIA_B))
        watchdog.cancel()
        assertFalse(watchdog.consumeDeadline(token, MEDIA_A))
    }

    @Test fun staleProofsFromAnIdenticalPriorAttemptCannotCompleteTheNewAttempt() {
        val watchdog = SubtitleReloadWatchdog()
        watchdog.arm(1L, MEDIA_A, alreadyReloading = true)
        watchdog.arm(2L, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.onPlaybackState(1L, MEDIA_A, ready = true))
        assertFalse(watchdog.onPlaybackState(2L, MEDIA_A, ready = true))
        assertFalse(watchdog.onPresented(1L, MEDIA_A))
        assertFalse(watchdog.onSubtitleLoaded(1L, MEDIA_A))
        assertFalse(watchdog.onPresented(2L, MEDIA_A))
        assertTrue(watchdog.onSubtitleLoaded(2L, MEDIA_A))
        assertFalse(watchdog.consumeDeadline(2L, MEDIA_A))
    }

    /**
     * The measured incident: a 4K reload resumed and drew, its subtitle was still on the
     * way at the deadline, and rolling the healthy player back cost a 2 231 ms freeze
     * where the subtitle itself was only 751 ms late.
     */
    @Test fun aReloadPlayingWithoutItsTextYetIsTheShapeThatEarnsMoreTime() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)
        watchdog.onPlaybackState(token, MEDIA_A, ready = true)
        watchdog.onPresented(token, MEDIA_A)

        assertTrue(watchdog.filmHealthyWithoutSubtitle(token, MEDIA_A))
        assertTrue(subtitleReloadExtends(true, elapsedSinceArmMs = 12_000L, capMs = 30_000L))
    }

    /** A reload that has not resumed is the stall the deadline exists to end. */
    @Test fun aReloadThatNeverResumedEarnsNothingAndRollsBack() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)

        assertFalse(watchdog.filmHealthyWithoutSubtitle(token, MEDIA_A))
        assertFalse(subtitleReloadExtends(false, elapsedSinceArmMs = 12_000L, capMs = 30_000L))
    }

    /** Healthy is not pending: a completed attempt has nothing left to extend. */
    @Test fun aHealthyAttemptIsNotWaitingForAnything() {
        val watchdog = SubtitleReloadWatchdog()
        val token = 1L
        watchdog.arm(token, MEDIA_A, alreadyReloading = true)
        watchdog.onPlaybackState(token, MEDIA_A, ready = true)
        watchdog.onPresented(token, MEDIA_A)
        assertTrue(watchdog.onSubtitleLoaded(token, MEDIA_A))

        assertFalse(watchdog.filmHealthyWithoutSubtitle(token, MEDIA_A))
    }

    /** A subtitle that never arrives is still dropped — at the cap, not at the first pass. */
    @Test fun theExtensionIsBoundedSoASubtitleThatNeverArrivesStillRollsBack() {
        assertTrue(subtitleReloadExtends(true, elapsedSinceArmMs = 29_999L, capMs = 30_000L))
        assertFalse(subtitleReloadExtends(true, elapsedSinceArmMs = 30_000L, capMs = 30_000L))
        assertFalse(subtitleReloadExtends(true, elapsedSinceArmMs = 45_000L, capMs = 30_000L))
    }

    /** Another attempt's token must not keep this one alive. */
    @Test fun anExtensionIsScopedToTheAttemptThatEarnedIt() {
        val watchdog = SubtitleReloadWatchdog()
        watchdog.arm(1L, MEDIA_A, alreadyReloading = true)
        watchdog.onPlaybackState(1L, MEDIA_A, ready = true)
        watchdog.onPresented(1L, MEDIA_A)

        assertFalse(watchdog.filmHealthyWithoutSubtitle(2L, MEDIA_A))
        assertFalse(watchdog.filmHealthyWithoutSubtitle(1L, MEDIA_B))
    }

    private companion object {
        const val MEDIA_A = "cast-a:4"
        const val MEDIA_B = "cast-b:5"
    }
}

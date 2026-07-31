package com.flick.sender.ui.screens

import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.ManualPairAttemptEvent
import com.flick.sender.net.PairErrorKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPairAttemptPolicyTest {

    @Test
    fun unrelatedGlobalBusyOnOpeningTheSheetStillShowsPair() {
        val state = ManualPairAttemptState().onControllerState(ManualPairAttemptEvent(startedGeneration = 4L))

        assertFalse(manualPairShowsProgress(state))
    }

    @Test
    fun aValidSubmitImmediatelyOwnsProgressAndBlocksAnotherSubmit() {
        val state = ManualPairAttemptState().begin(7L)

        assertTrue(manualPairShowsProgress(state))
        assertFalse(canSubmitManualPair("192.168.42.17", "47654", "4821", connecting = state.submitted))
    }

    @Test
    fun replacementDisconnectedPulseDoesNotReleaseTheManualAttempt() {
        val state = ManualPairAttemptState()
            .begin(7L)
            .onControllerState(ManualPairAttemptEvent(startedGeneration = 7L))

        assertTrue(manualPairShowsProgress(state))
    }

    @Test
    fun onlyAnOwnedConfirmationChangesTheProgressCopy() {
        val idle = ManualPairAttemptState()
        val submitted = idle.begin(7L)

        assertFalse(manualPairAwaitsTvConfirmation(idle, ConnectionStatus.CONFIRM_ON_TV))
        assertTrue(manualPairAwaitsTvConfirmation(submitted, ConnectionStatus.CONFIRM_ON_TV))
    }

    @Test
    fun matchingTerminalGenerationRestoresPairAndPermitsRetry() {
        val failed = ManualPairAttemptState()
            .begin(7L)
            .onControllerState(ManualPairAttemptEvent(startedGeneration = 7L, terminalGeneration = 7L))

        assertFalse(manualPairShowsProgress(failed))
        assertTrue(canSubmitManualPair("192.168.42.17", "47654", "4821", connecting = failed.submitted))
        assertTrue(manualPairShowsProgress(failed.begin(8L)))
    }

    @Test
    fun repeatedSubmitWhileTheLocalAttemptIsOwnedDispatchesOnlyOnce() {
        var dispatches = 0
        var state = ManualPairAttemptState()
        repeat(2) {
            if (canSubmitManualPair("192.168.42.17", "47654", "4821", connecting = state.submitted)) {
                state = state.begin(7L)
                dispatches += 1
            }
        }

        assertTrue(dispatches == 1)
    }

    @Test
    fun sameVisibleErrorStillReleasesTheNewGenerationWhenIntermediatesAreConflated() {
        val preexistingError = PairErrorKind.UNREACHABLE
        val state = ManualPairAttemptState().begin(12L)
        // A collector can observe this same enum before and after the attempt. The monotonic
        // terminal generation, not the enum transition, is what proves this result is new.
        val observedError = PairErrorKind.UNREACHABLE
        val afterOutcome = state.onControllerState(
            ManualPairAttemptEvent(startedGeneration = 12L, terminalGeneration = 12L),
        )

        assertTrue(preexistingError == observedError)
        assertFalse(manualPairShowsProgress(afterOutcome))
    }

    @Test
    fun staleTerminalGenerationCannotReleaseANewerSubmission() {
        val state = ManualPairAttemptState()
            .begin(13L)
            .onControllerState(ManualPairAttemptEvent(startedGeneration = 13L, terminalGeneration = 12L))

        assertTrue(manualPairShowsProgress(state))
    }

    @Test
    fun unrelatedConnectedStateCannotReleaseTheManualSubmission() {
        // CONNECTED is deliberately absent from the reducer's inputs: another control
        // path may reach it, but only a matching terminal generation belongs to this form.
        val state = ManualPairAttemptState()
            .begin(13L)
            .onControllerState(ManualPairAttemptEvent(startedGeneration = 14L))

        assertFalse(manualPairAwaitsTvConfirmation(state, ConnectionStatus.CONNECTED))
        assertTrue(manualPairShowsProgress(state))
    }
}

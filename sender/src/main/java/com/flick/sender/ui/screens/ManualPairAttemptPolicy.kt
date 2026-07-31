package com.flick.sender.ui.screens

import com.flick.sender.model.ConnectionStatus
import com.flick.sender.net.ManualPairAttemptEvent

/**
 * The manual form owns the generation it started from its own Pair button.
 *
 * Pair errors are presentation state and may legitimately repeat. The controller therefore
 * publishes [ManualPairAttemptEvent] separately: it carries no endpoint or secret and its
 * terminal generation changes even when a second attempt ends with the same visible error.
 */
internal data class ManualPairAttemptState(
    private val generation: Long? = null,
) {
    val submitted: Boolean get() = generation != null

    fun begin(generation: Long): ManualPairAttemptState = ManualPairAttemptState(generation)

    /** Ignores unrelated controller activity and only consumes this form's completion. */
    fun onControllerState(event: ManualPairAttemptEvent): ManualPairAttemptState {
        val submittedGeneration = generation ?: return this
        return if (event.terminalGeneration == submittedGeneration) {
            ManualPairAttemptState()
        } else {
            this
        }
    }
}

/** A submitted manual attempt is the only reason this form replaces Pair with progress. */
internal fun manualPairShowsProgress(state: ManualPairAttemptState): Boolean = state.submitted

/** The global connection is copy only after this sheet has started its own attempt. */
internal fun manualPairAwaitsTvConfirmation(
    state: ManualPairAttemptState,
    connection: ConnectionStatus,
): Boolean = state.submitted && connection == ConnectionStatus.CONFIRM_ON_TV

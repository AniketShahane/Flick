package com.flick.sender.support

data class SupportPromptState(
    val successfulCastCount: Int = 0,
    val promptConsumed: Boolean = false,
)

/** Pure state transitions for the one-time support prompt. */
object SupportPromptPolicy {
    const val SUCCESS_THRESHOLD = 3

    fun restore(successfulCastCount: Int, promptConsumed: Boolean): SupportPromptState =
        SupportPromptState(
            successfulCastCount = successfulCastCount.coerceIn(0, SUCCESS_THRESHOLD),
            promptConsumed = promptConsumed,
        )

    fun recordSuccess(state: SupportPromptState): SupportPromptState {
        val current = restore(state.successfulCastCount, state.promptConsumed)
        return current.copy(
            successfulCastCount = (current.successfulCastCount + 1).coerceAtMost(SUCCESS_THRESHOLD),
        )
    }

    /** Returns the durably consumable state, or null when no prompt may be shown. */
    fun consumeIfEligible(state: SupportPromptState): SupportPromptState? {
        val current = restore(state.successfulCastCount, state.promptConsumed)
        return current
            .takeIf { it.successfulCastCount >= SUCCESS_THRESHOLD && !it.promptConsumed }
            ?.copy(promptConsumed = true)
    }
}

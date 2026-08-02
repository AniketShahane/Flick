package com.flick.sender.support

import android.content.Context

/**
 * Persists only prompt eligibility: a saturated success count and a consumed marker.
 * Checkout URLs and payment data never enter this preferences file.
 */
class SupportPromptStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Records a successful cast synchronously; false means no durable progress was made. */
    fun recordSuccess(): Boolean = persistTransition(SupportPromptPolicy::recordSuccess)

    /**
     * Atomically claims the one-time prompt. Callers may show it only when this returns true,
     * so a rejected synchronous write fails closed instead of repeating the prompt later.
     */
    fun consumeIfEligible(): Boolean = persistTransition(SupportPromptPolicy::consumeIfEligible)

    private fun persistTransition(
        transition: (SupportPromptState) -> SupportPromptState?,
    ): Boolean = try {
        val next = readState()?.let(transition) ?: return false
        commit(next)
    } catch (_: RuntimeException) {
        // Optional support UI must never interrupt casting when local storage is unhealthy.
        false
    }

    private fun readState(): SupportPromptState? = try {
        SupportPromptPolicy.restore(
            successfulCastCount = prefs.getInt(SUCCESSFUL_CAST_COUNT, 0),
            promptConsumed = prefs.getBoolean(PROMPT_CONSUMED, false),
        )
    } catch (_: ClassCastException) {
        null
    }

    private fun commit(state: SupportPromptState): Boolean = prefs.edit()
        .putInt(SUCCESSFUL_CAST_COUNT, state.successfulCastCount)
        .putBoolean(PROMPT_CONSUMED, state.promptConsumed)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "flick_support"
        const val SUCCESSFUL_CAST_COUNT = "successful_cast_count"
        const val PROMPT_CONSUMED = "prompt_consumed"
    }
}

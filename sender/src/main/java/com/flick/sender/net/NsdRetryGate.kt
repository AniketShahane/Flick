package com.flick.sender.net

/** Makes discovery callbacks idempotent: one retry is pending until a fresh start owns it. */
internal class NsdRetryGate {
    private var active = false
    private var retryPending = false

    fun begin(): Boolean = if (active) false else { active = true; retryPending = false; true }
    fun startFailed(): Boolean { active = false; return requestRetry() }
    fun stopped(): Boolean { active = false; return requestRetry() }
    fun stopFailed(): Boolean { active = false; return requestRetry() }
    fun stopRequested() { active = false; retryPending = false }
    fun retryFired(): Boolean = if (retryPending) { retryPending = false; true } else false
    private fun requestRetry(): Boolean = if (retryPending) false else { retryPending = true; true }
}

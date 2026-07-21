package com.flick.sender.net

/** Owns the one pairing/resume generation; cancellation wins before any completion can persist. */
internal class PairingAttemptGate {
    private var generation = 0L
    fun begin(): Long = ++generation
    fun invalidate() { ++generation }
    fun isCurrent(token: Long): Boolean = generation == token
}

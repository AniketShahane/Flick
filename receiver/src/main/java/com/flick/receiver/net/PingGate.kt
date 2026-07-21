package com.flick.receiver.net

/** Bounds authenticated ping/pong work without affecting normal state updates. */
class PingGate(private val now: () -> Long) {
    private var windowStarted = now()
    private var used = 0
    fun tryAcquire(): Boolean {
        val current = now()
        if (current - windowStarted >= WINDOW_MS) { windowStarted = current; used = 0 }
        return (used++ < MAX_PINGS)
    }
    companion object { const val WINDOW_MS = 10_000L; const val MAX_PINGS = 5 }
}

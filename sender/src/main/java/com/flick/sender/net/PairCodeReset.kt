package com.flick.sender.net

/** Monotonic UI signal that clears only the code, preserving the typed endpoint. */
internal class PairCodeReset {
    private var revision = 0L
    fun clear(): Long = ++revision
}

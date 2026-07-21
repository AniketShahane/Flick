package com.flick.receiver.net

/** A resume challenge admits exactly one proof frame, successful or not. */
internal class ResumeProofGate {
    private var live = false
    fun issue() { live = true }
    fun consume(): Boolean = live.also { live = false }
}

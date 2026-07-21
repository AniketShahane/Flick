package com.flick.receiver.session

/** Captures terminal protocol phase before lifecycle cleanup clears the stage. */
object TerminalPhase {
    fun beforeReady(stage: MediaStage): Boolean = stage !is MediaStage.Active
}

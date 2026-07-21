package com.flick.receiver.player

import androidx.media3.common.Player

internal enum class MediaButtonKind { STOP, NEXT, PREVIOUS, OTHER }

internal fun consumesUnsupportedMediaButton(button: MediaButtonKind): Boolean =
    button == MediaButtonKind.STOP ||
        button == MediaButtonKind.NEXT ||
        button == MediaButtonKind.PREVIOUS

/** Only physical-remote transport commands that preserve cast ownership. */
internal fun rejectsExternalPlayerCommand(command: Int): Boolean = when (command) {
    Player.COMMAND_PLAY_PAUSE,
    Player.COMMAND_SEEK_BACK,
    Player.COMMAND_SEEK_FORWARD -> false
    else -> true
}

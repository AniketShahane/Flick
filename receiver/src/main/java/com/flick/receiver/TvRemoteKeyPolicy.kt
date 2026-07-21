package com.flick.receiver

internal const val TV_REMOTE_SEEK_STEP_MS = 10_000L

internal enum class TvRemoteButton {
    Select,
    Left,
    Right,
    Up,
    Down,
    MediaPlay,
    MediaPause,
    MediaPlayPause,
    MediaRewind,
    MediaFastForward,
    Other,
}

internal enum class TvRemoteEventType { Down, Up, Other }

internal sealed interface TvRemoteCommand {
    data object RevealChrome : TvRemoteCommand
    data object TogglePlayPause : TvRemoteCommand
    data object Play : TvRemoteCommand
    data object Pause : TvRemoteCommand
    data class SeekBy(val deltaMs: Long) : TvRemoteCommand
}

internal data class TvRemoteDecision(
    val consume: Boolean,
    val command: TvRemoteCommand? = null,
    val capture: Boolean = false,
    val releaseCapture: Boolean = false,
)

/**
 * Playback remote policy at the Activity boundary.
 *
 * With chrome hidden, D-pad commands are direct: center toggles, left/right
 * seek, and up/down reveal playback information. Once chrome is visible its
 * focus graph owns DPAD navigation; dedicated media keys remain global. A
 * handled hidden-chrome press stays captured through key-up so repeats cannot
 * leak into the newly visible focus graph.
 */
internal fun tvRemoteDecision(
    button: TvRemoteButton,
    eventType: TvRemoteEventType,
    repeatCount: Int,
    playbackActive: Boolean,
    chromeVisible: Boolean,
    capturedButton: TvRemoteButton?,
): TvRemoteDecision {
    if (capturedButton == button) {
        return when (eventType) {
            TvRemoteEventType.Up -> TvRemoteDecision(consume = true, releaseCapture = true)
            TvRemoteEventType.Down -> if (!playbackActive) {
                TvRemoteDecision(consume = true)
            } else {
                when (button) {
                    TvRemoteButton.Left -> TvRemoteDecision(
                        consume = true,
                        command = TvRemoteCommand.SeekBy(-TV_REMOTE_SEEK_STEP_MS),
                    )
                    TvRemoteButton.Right -> TvRemoteDecision(
                        consume = true,
                        command = TvRemoteCommand.SeekBy(TV_REMOTE_SEEK_STEP_MS),
                    )
                    else -> TvRemoteDecision(consume = true)
                }
            }
            TvRemoteEventType.Other -> TvRemoteDecision(consume = true)
        }
    }

    if (!playbackActive) return TvRemoteDecision(consume = false)

    val isMediaButton = button == TvRemoteButton.MediaPlay ||
        button == TvRemoteButton.MediaPause ||
        button == TvRemoteButton.MediaPlayPause ||
        button == TvRemoteButton.MediaRewind ||
        button == TvRemoteButton.MediaFastForward

    if (eventType == TvRemoteEventType.Up && isMediaButton) {
        return TvRemoteDecision(consume = true)
    }
    if (eventType != TvRemoteEventType.Down) return TvRemoteDecision(consume = false)

    when (button) {
        TvRemoteButton.MediaPlay -> return TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.Play else null,
        )
        TvRemoteButton.MediaPause -> return TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.Pause else null,
        )
        TvRemoteButton.MediaPlayPause -> return TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.TogglePlayPause else null,
        )
        TvRemoteButton.MediaRewind -> return TvRemoteDecision(
            consume = true,
            command = TvRemoteCommand.SeekBy(-TV_REMOTE_SEEK_STEP_MS),
        )
        TvRemoteButton.MediaFastForward -> return TvRemoteDecision(
            consume = true,
            command = TvRemoteCommand.SeekBy(TV_REMOTE_SEEK_STEP_MS),
        )
        else -> Unit
    }

    if (chromeVisible) return TvRemoteDecision(consume = false)

    return when (button) {
        TvRemoteButton.Select -> TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.TogglePlayPause else null,
            capture = true,
        )
        TvRemoteButton.Left -> TvRemoteDecision(
            consume = true,
            command = TvRemoteCommand.SeekBy(-TV_REMOTE_SEEK_STEP_MS),
            capture = true,
        )
        TvRemoteButton.Right -> TvRemoteDecision(
            consume = true,
            command = TvRemoteCommand.SeekBy(TV_REMOTE_SEEK_STEP_MS),
            capture = true,
        )
        TvRemoteButton.Up, TvRemoteButton.Down -> TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.RevealChrome else null,
            capture = true,
        )
        else -> TvRemoteDecision(consume = false)
    }
}

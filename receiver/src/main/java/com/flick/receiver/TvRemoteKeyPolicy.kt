package com.flick.receiver

internal const val TV_REMOTE_SEEK_STEP_MS = 10_000L
internal const val TV_REMOTE_SEEK_MAX_STEP_MS = 30_000L
private const val TV_REMOTE_REPEAT_PULSE_INTERVAL = 4

internal enum class TvRemoteButton {
    Select,
    Left,
    Right,
    Up,
    Down,
    Other,
}

internal enum class TvRemoteEventType { Down, Up, Other }

internal sealed interface TvRemoteCommand {
    data object RevealChrome : TvRemoteCommand
    data object TogglePlayPause : TvRemoteCommand
    data class SeekBy(val deltaMs: Long, val speedLevel: Int) : TvRemoteCommand
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
 * Left/right are playback gestures regardless of Compose focus: a tap is an
 * exact ten-second seek, while a hold emits gated 1x/2x/3x pulses capped at
 * thirty seconds per pulse. Center/up/down keep the existing hidden-chrome
 * behavior; visible chrome owns those focus events. Dedicated media keys are
 * deliberately absent and continue to MediaSession.
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
                    TvRemoteButton.Left, TvRemoteButton.Right -> TvRemoteDecision(
                        consume = true,
                        command = tvRemoteSeekCommand(button, repeatCount),
                    )
                    else -> TvRemoteDecision(consume = true)
                }
            }
            TvRemoteEventType.Other -> TvRemoteDecision(consume = true)
        }
    }

    val horizontal = button == TvRemoteButton.Left || button == TvRemoteButton.Right
    if (capturedButton != null && horizontal && eventType == TvRemoteEventType.Down) {
        // One gesture owns horizontal input until its matching key-up. An
        // opposite key pressed during the hold must not steal that capture.
        return TvRemoteDecision(consume = true)
    }

    if (!playbackActive) return TvRemoteDecision(consume = false)

    // Consume unmatched horizontal key-up events as well. They may belong to a
    // crossing key-down intentionally ignored above and must not reach Compose.
    if (horizontal && eventType == TvRemoteEventType.Up) {
        return TvRemoteDecision(consume = true)
    }

    if (eventType != TvRemoteEventType.Down) return TvRemoteDecision(consume = false)

    // Physical left/right are always playback gestures. Consume at the Activity
    // boundary before Compose can move focus or handle the same key a second time.
    if (horizontal) {
        return TvRemoteDecision(
            consume = true,
            command = tvRemoteSeekCommand(button, repeatCount),
            capture = true,
        )
    }

    if (chromeVisible) return TvRemoteDecision(consume = false)

    return when (button) {
        TvRemoteButton.Select -> TvRemoteDecision(
            consume = true,
            command = if (repeatCount == 0) TvRemoteCommand.TogglePlayPause else null,
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

/** Null means consume this repeat but wait for the next bounded seek pulse. */
private fun tvRemoteSeekCommand(button: TvRemoteButton, repeatCount: Int): TvRemoteCommand.SeekBy? {
    val pulse = tvRemoteSeekPulse(repeatCount) ?: return null
    val direction = if (button == TvRemoteButton.Left) -1L else 1L
    return TvRemoteCommand.SeekBy(direction * pulse.deltaMs, pulse.speedLevel)
}

internal data class TvRemoteSeekPulse(val deltaMs: Long, val speedLevel: Int)

internal fun tvRemoteSeekPulse(repeatCount: Int): TvRemoteSeekPulse? {
    if (repeatCount <= 0) return TvRemoteSeekPulse(TV_REMOTE_SEEK_STEP_MS, speedLevel = 1)
    if ((repeatCount - 1) % TV_REMOTE_REPEAT_PULSE_INTERVAL != 0) return null
    return when {
        repeatCount < 9 -> TvRemoteSeekPulse(TV_REMOTE_SEEK_STEP_MS, speedLevel = 1)
        repeatCount < 21 -> TvRemoteSeekPulse(20_000L, speedLevel = 2)
        else -> TvRemoteSeekPulse(TV_REMOTE_SEEK_MAX_STEP_MS, speedLevel = 3)
    }
}

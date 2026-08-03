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
 * **Horizontal keys seek only where there is nothing to navigate.** That is either
 * of two states: the chrome is down, so the film is the whole screen; or the scrub
 * bar itself holds focus, which is the one control on the chrome whose axis IS the
 * timeline. Everywhere else left/right are ordinary Compose focus navigation, and
 * the transport row — which is laid out horizontally — is traversed the way it is
 * drawn. A tap is an exact ten-second seek; a held key emits every fourth repeat
 * and progresses through capped 10/20/30-second pulses.
 *
 * Center/up/down keep the hidden-chrome behavior; visible chrome owns those focus
 * events. Dedicated media keys are deliberately absent and continue to
 * MediaSession.
 *
 * During playback, [capturedButton] is the gesture in flight. It outranks
 * [scrubFocused] and [chromeVisible]: once a key-down has been claimed as a seek,
 * the whole hold belongs to it through its own key-up, so a chrome auto-hide or a
 * focus move cannot split one physical press into two meanings. A non-playback
 * surface instead clears any stale capture and receives the event; side-panel
 * focus navigation must never be held hostage by an earlier playback gesture.
 */
internal fun tvRemoteDecision(
    button: TvRemoteButton,
    eventType: TvRemoteEventType,
    repeatCount: Int,
    playbackActive: Boolean,
    chromeVisible: Boolean,
    scrubFocused: Boolean,
    capturedButton: TvRemoteButton?,
): TvRemoteDecision {
    if (!playbackActive) {
        // Side panels and every non-playback surface own the whole D-pad. A
        // key-up lost during an earlier playback gesture must not leave a stale
        // capture swallowing the panel's first navigation press.
        return TvRemoteDecision(
            consume = false,
            releaseCapture = capturedButton != null,
        )
    }

    if (capturedButton == button) {
        return when (eventType) {
            TvRemoteEventType.Up -> TvRemoteDecision(consume = true, releaseCapture = true)
            TvRemoteEventType.Down -> when (button) {
                TvRemoteButton.Left, TvRemoteButton.Right -> TvRemoteDecision(
                    consume = true,
                    command = tvRemoteSeekCommand(button, repeatCount),
                )
                else -> TvRemoteDecision(consume = true)
            }
            TvRemoteEventType.Other -> TvRemoteDecision(consume = true)
        }
    }

    val horizontal = button == TvRemoteButton.Left || button == TvRemoteButton.Right
    if (capturedButton != null && button != TvRemoteButton.Other) {
        // One gesture owns the whole D-pad until its own key-up — not just the
        // axis it started on. A thumb rocking the ring mid-hold delivers a second
        // key-down, and letting that key take the capture leaves the held key's
        // key-up matching nothing: the gesture never ends, so whatever it put on
        // screen never comes down. Both halves of the crossing press are
        // swallowed so Compose never sees a key-up without its key-down either.
        // `Other` is excluded because dedicated media keys are never ours to hold.
        return TvRemoteDecision(consume = true)
    }

    val seekGesture = tvRemoteHorizontalSeeks(chromeVisible, scrubFocused)

    // Nothing holds the capture here, so a horizontal key-up follows whoever
    // would have owned its key-down. While left/right are navigation the whole
    // event pair belongs to the focus system.
    if (horizontal && eventType == TvRemoteEventType.Up) {
        return TvRemoteDecision(consume = seekGesture)
    }

    if (eventType != TvRemoteEventType.Down) return TvRemoteDecision(consume = false)

    if (horizontal && seekGesture) {
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

/**
 * Whether physical left/right are a seek rather than a focus move.
 *
 * Hidden chrome has nothing to navigate, and the focused scrub bar is the control
 * whose own axis is the timeline. Everything else on the chrome is reached by
 * moving through it.
 */
internal fun tvRemoteHorizontalSeeks(chromeVisible: Boolean, scrubFocused: Boolean): Boolean =
    !chromeVisible || scrubFocused

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

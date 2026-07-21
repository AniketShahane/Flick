package com.flick.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRemoteKeyPolicyTest {
    @Test fun hiddenSelectTogglesOnceAndCapturesThroughKeyUp() {
        val down = decide(TvRemoteButton.Select)
        assertTrue(down.consume)
        assertTrue(down.capture)
        assertEquals(TvRemoteCommand.TogglePlayPause, down.command)

        val repeated = decide(
            button = TvRemoteButton.Select,
            repeatCount = 1,
            capturedButton = TvRemoteButton.Select,
        )
        assertTrue(repeated.consume)
        assertNull(repeated.command)

        val up = decide(
            button = TvRemoteButton.Select,
            eventType = TvRemoteEventType.Up,
            capturedButton = TvRemoteButton.Select,
        )
        assertTrue(up.consume)
        assertTrue(up.releaseCapture)
    }

    @Test fun hiddenLeftAndRightSeekOnInitialAndRepeatedDownEvents() {
        val left = decide(TvRemoteButton.Left)
        assertEquals(TvRemoteCommand.SeekBy(-10_000L), left.command)
        assertTrue(left.capture)

        val heldRight = decide(
            button = TvRemoteButton.Right,
            repeatCount = 4,
            chromeVisible = true,
            capturedButton = TvRemoteButton.Right,
        )
        assertTrue(heldRight.consume)
        assertEquals(TvRemoteCommand.SeekBy(10_000L), heldRight.command)
    }

    @Test fun visibleChromeLeavesDpadToComposeFocus() {
        listOf(
            TvRemoteButton.Select,
            TvRemoteButton.Left,
            TvRemoteButton.Right,
            TvRemoteButton.Up,
            TvRemoteButton.Down,
        ).forEach { button ->
            val decision = decide(button = button, chromeVisible = true)
            assertFalse(decision.consume)
            assertNull(decision.command)
        }
    }

    @Test fun hiddenUpAndDownRevealWithoutRepeating() {
        val down = decide(TvRemoteButton.Down)
        assertTrue(down.consume)
        assertEquals(TvRemoteCommand.RevealChrome, down.command)

        val repeated = decide(
            button = TvRemoteButton.Down,
            repeatCount = 1,
            capturedButton = TvRemoteButton.Down,
        )
        assertTrue(repeated.consume)
        assertNull(repeated.command)
    }

    @Test fun dedicatedMediaKeysRemainGlobalAndRewindFastForwardRepeat() {
        assertEquals(
            TvRemoteCommand.Play,
            decide(TvRemoteButton.MediaPlay, chromeVisible = true).command,
        )
        assertEquals(
            TvRemoteCommand.Pause,
            decide(TvRemoteButton.MediaPause, chromeVisible = true).command,
        )
        assertEquals(
            TvRemoteCommand.TogglePlayPause,
            decide(TvRemoteButton.MediaPlayPause, chromeVisible = true).command,
        )
        assertEquals(
            TvRemoteCommand.SeekBy(-10_000L),
            decide(TvRemoteButton.MediaRewind, repeatCount = 3, chromeVisible = true).command,
        )
        assertEquals(
            TvRemoteCommand.SeekBy(10_000L),
            decide(TvRemoteButton.MediaFastForward, repeatCount = 3, chromeVisible = true).command,
        )
    }

    @Test fun inactivePlaybackDoesNotStealRemoteKeys() {
        val decision = decide(TvRemoteButton.MediaPlay, playbackActive = false)
        assertFalse(decision.consume)
        assertNull(decision.command)
    }

    private fun decide(
        button: TvRemoteButton,
        eventType: TvRemoteEventType = TvRemoteEventType.Down,
        repeatCount: Int = 0,
        playbackActive: Boolean = true,
        chromeVisible: Boolean = false,
        capturedButton: TvRemoteButton? = null,
    ): TvRemoteDecision = tvRemoteDecision(
        button = button,
        eventType = eventType,
        repeatCount = repeatCount,
        playbackActive = playbackActive,
        chromeVisible = chromeVisible,
        capturedButton = capturedButton,
    )
}

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

    @Test fun leftAndRightSeekRegardlessOfChromeOrFocus() {
        val left = decide(TvRemoteButton.Left)
        assertEquals(TvRemoteCommand.SeekBy(-10_000L, speedLevel = 1), left.command)
        assertTrue(left.capture)

        val heldRight = decide(
            button = TvRemoteButton.Right,
            repeatCount = 9,
            chromeVisible = true,
            capturedButton = TvRemoteButton.Right,
        )
        assertTrue(heldRight.consume)
        assertEquals(TvRemoteCommand.SeekBy(20_000L, speedLevel = 2), heldRight.command)
    }

    @Test fun visibleChromeLeavesCenterAndVerticalDpadToComposeFocus() {
        listOf(
            TvRemoteButton.Select,
            TvRemoteButton.Up,
            TvRemoteButton.Down,
        ).forEach { button ->
            val decision = decide(button = button, chromeVisible = true)
            assertFalse(decision.consume)
            assertNull(decision.command)
        }
    }

    @Test fun heldSeekUsesGatedProgressiveAndBoundedPulses() {
        assertEquals(TvRemoteSeekPulse(10_000L, 1), tvRemoteSeekPulse(0))
        assertEquals(TvRemoteSeekPulse(10_000L, 1), tvRemoteSeekPulse(1))
        assertNull(tvRemoteSeekPulse(2))
        assertEquals(TvRemoteSeekPulse(10_000L, 1), tvRemoteSeekPulse(5))
        assertEquals(TvRemoteSeekPulse(20_000L, 2), tvRemoteSeekPulse(9))
        assertEquals(TvRemoteSeekPulse(30_000L, 3), tvRemoteSeekPulse(21))
        assertEquals(TvRemoteSeekPulse(TV_REMOTE_SEEK_MAX_STEP_MS, 3), tvRemoteSeekPulse(101))
    }

    @Test fun crossingHorizontalKeyCannotStealTheCapturedGesture() {
        val crossingDown = decide(
            button = TvRemoteButton.Right,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(crossingDown.consume)
        assertFalse(crossingDown.capture)
        assertFalse(crossingDown.releaseCapture)
        assertNull(crossingDown.command)

        val ownerUp = decide(
            button = TvRemoteButton.Left,
            eventType = TvRemoteEventType.Up,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(ownerUp.consume)
        assertTrue(ownerUp.releaseCapture)

        val crossingUp = decide(
            button = TvRemoteButton.Right,
            eventType = TvRemoteEventType.Up,
        )
        assertTrue(crossingUp.consume)
        assertFalse(crossingUp.releaseCapture)
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

    @Test fun nonDpadKeysAreNeverStolenFromThePlatformMediaSession() {
        val active = decide(TvRemoteButton.Other)
        assertFalse(active.consume)
        assertNull(active.command)

        val decision = decide(TvRemoteButton.Other, playbackActive = false)
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

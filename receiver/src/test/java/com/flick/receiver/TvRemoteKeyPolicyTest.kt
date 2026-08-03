package com.flick.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D-pad model, stated as tests.
 *
 * Horizontal keys seek in exactly two states — chrome down, or the scrub bar
 * focused — and are ordinary focus navigation everywhere else. The product owner
 * rejected the previous model on the device: with left/right captured at the
 * Activity boundary, a transport row laid out horizontally could only be walked
 * with up and down.
 */
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

    @Test fun hiddenChromeLeavesLeftAndRightAsSeeks() {
        val left = decide(TvRemoteButton.Left)
        assertTrue(left.consume)
        assertTrue(left.capture)
        assertEquals(TvRemoteCommand.SeekBy(-10_000L, speedLevel = 1), left.command)

        val right = decide(TvRemoteButton.Right)
        assertEquals(TvRemoteCommand.SeekBy(10_000L, speedLevel = 1), right.command)
    }

    @Test fun visibleChromeHandsLeftAndRightToComposeFocus() {
        listOf(TvRemoteButton.Left, TvRemoteButton.Right).forEach { button ->
            val decision = decide(button = button, chromeVisible = true, scrubFocused = false)
            assertFalse(button.name, decision.consume)
            assertFalse(button.name, decision.capture)
            assertNull(button.name, decision.command)
        }
    }

    @Test fun visibleChromeSeeksOnlyWhileTheScrubBarHoldsFocus() {
        val onBar = decide(
            button = TvRemoteButton.Right,
            chromeVisible = true,
            scrubFocused = true,
        )
        assertTrue(onBar.consume)
        assertTrue(onBar.capture)
        assertEquals(TvRemoteCommand.SeekBy(10_000L, speedLevel = 1), onBar.command)

        val heldOnBar = decide(
            button = TvRemoteButton.Right,
            repeatCount = 9,
            chromeVisible = true,
            scrubFocused = true,
            capturedButton = TvRemoteButton.Right,
        )
        assertTrue(heldOnBar.consume)
        assertEquals(TvRemoteCommand.SeekBy(20_000L, speedLevel = 2), heldOnBar.command)
    }

    @Test fun horizontalKeyUpFollowsWhicheverOwnedItsKeyDown() {
        // A gesture this policy owns keeps both halves of the event pair: the up
        // may belong to a crossing key-down that was intentionally swallowed.
        assertTrue(
            decide(
                button = TvRemoteButton.Left,
                eventType = TvRemoteEventType.Up,
                chromeVisible = true,
                scrubFocused = true,
            ).consume,
        )
        // Navigation is the focus system's, and so is the key-up that closes it.
        assertFalse(
            decide(
                button = TvRemoteButton.Left,
                eventType = TvRemoteEventType.Up,
                chromeVisible = true,
                scrubFocused = false,
            ).consume,
        )
    }

    @Test fun aHoldKeepsSeekingWhenFocusOrChromeChangesUnderIt() {
        // The chrome auto-hides, a focus move lands, the panel state changes —
        // none of it may split one physical press into two meanings. The captured
        // arm is consulted before chrome visibility or scrub focus.
        val stillOnTheBar = decide(
            button = TvRemoteButton.Left,
            repeatCount = 5,
            chromeVisible = true,
            scrubFocused = false,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(stillOnTheBar.consume)
        assertEquals(TvRemoteCommand.SeekBy(-10_000L, speedLevel = 1), stillOnTheBar.command)

        val chromeWentDownMidHold = decide(
            button = TvRemoteButton.Left,
            repeatCount = 21,
            chromeVisible = false,
            scrubFocused = false,
            capturedButton = TvRemoteButton.Left,
        )
        assertEquals(
            TvRemoteCommand.SeekBy(-TV_REMOTE_SEEK_MAX_STEP_MS, speedLevel = 3),
            chromeWentDownMidHold.command,
        )

        val release = decide(
            button = TvRemoteButton.Left,
            eventType = TvRemoteEventType.Up,
            chromeVisible = true,
            scrubFocused = false,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(release.consume)
        assertTrue(release.releaseCapture)
    }

    @Test fun aChromeRevealArrivingMidHoldStillCannotStartASecondGesture() {
        // Same hold, opposite key: the crossing key-down is swallowed whether or
        // not horizontal is currently a seek.
        val crossing = decide(
            button = TvRemoteButton.Right,
            chromeVisible = true,
            scrubFocused = false,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(crossing.consume)
        assertFalse(crossing.capture)
        assertNull(crossing.command)

        val crossingUp = decide(
            button = TvRemoteButton.Right,
            eventType = TvRemoteEventType.Up,
            chromeVisible = true,
            scrubFocused = false,
            capturedButton = TvRemoteButton.Left,
        )
        assertTrue(crossingUp.consume)
        assertFalse(crossingUp.releaseCapture)
    }

    @Test fun noOtherDpadKeyCanTakeAHeldGesture() {
        // A thumb rocking the ring mid-hold. If a vertical or centre press took
        // the capture, the held key's key-up would match nothing, the release
        // would be credited to the wrong button, and the seek burst would sit on
        // the film until the next gesture.
        listOf(TvRemoteButton.Up, TvRemoteButton.Down, TvRemoteButton.Select).forEach { button ->
            val crossingDown = decide(button = button, capturedButton = TvRemoteButton.Right)
            assertTrue(button.name, crossingDown.consume)
            assertFalse(button.name, crossingDown.capture)
            assertFalse(button.name, crossingDown.releaseCapture)
            assertNull(button.name, crossingDown.command)

            // Its key-up goes the same way: Compose must not see an up whose down
            // it never saw.
            val crossingUp = decide(
                button = button,
                eventType = TvRemoteEventType.Up,
                capturedButton = TvRemoteButton.Right,
            )
            assertTrue(button.name, crossingUp.consume)
            assertFalse(button.name, crossingUp.releaseCapture)
        }

        // The holder still owns its own events, and only its key-up releases.
        val ownerRepeat = decide(
            button = TvRemoteButton.Right,
            repeatCount = 5,
            capturedButton = TvRemoteButton.Right,
        )
        assertEquals(TvRemoteCommand.SeekBy(10_000L, speedLevel = 1), ownerRepeat.command)

        val ownerUp = decide(
            button = TvRemoteButton.Right,
            eventType = TvRemoteEventType.Up,
            capturedButton = TvRemoteButton.Right,
        )
        assertTrue(ownerUp.releaseCapture)
    }

    @Test fun aHeldSelectIsEquallyExclusive() {
        // The rule is about the gesture in flight, not about which axis started
        // it: the reveal keys wait for the hold to end like everything else.
        val crossing = decide(
            button = TvRemoteButton.Up,
            capturedButton = TvRemoteButton.Select,
        )
        assertTrue(crossing.consume)
        assertFalse(crossing.capture)
        assertNull(crossing.command)
    }

    @Test fun aStuckCaptureIsClearedByItsOwnButton() {
        // Only the holder's key-up releases, so a capture that outlived its
        // release must stay reachable: pressing that same direction again both
        // acts and ends it on the following key-up.
        val again = decide(button = TvRemoteButton.Left, capturedButton = TvRemoteButton.Left)
        assertEquals(TvRemoteCommand.SeekBy(-10_000L, speedLevel = 1), again.command)
        assertTrue(
            decide(
                button = TvRemoteButton.Left,
                eventType = TvRemoteEventType.Up,
                capturedButton = TvRemoteButton.Left,
            ).releaseCapture,
        )
    }

    @Test fun horizontalOwnershipIsExactlyChromeDownOrScrubFocused() {
        assertTrue(tvRemoteHorizontalSeeks(chromeVisible = false, scrubFocused = false))
        assertTrue(tvRemoteHorizontalSeeks(chromeVisible = false, scrubFocused = true))
        assertTrue(tvRemoteHorizontalSeeks(chromeVisible = true, scrubFocused = true))
        assertFalse(tvRemoteHorizontalSeeks(chromeVisible = true, scrubFocused = false))
    }

    @Test fun anOpenPanelHandsTheWholeDpadToCompose() {
        assertFalse(receiverPlaybackGesturesEnabled(playbackActive = true, panelOpen = true))
        assertTrue(receiverPlaybackGesturesEnabled(playbackActive = true, panelOpen = false))
        assertFalse(receiverPlaybackGesturesEnabled(playbackActive = false, panelOpen = false))

        // With the panel open the policy is handed playbackActive = false, and
        // consumes nothing at all — including on the scrub bar's own state.
        listOf(TvRemoteButton.Left, TvRemoteButton.Right, TvRemoteButton.Select).forEach { button ->
            val decision = decide(button = button, playbackActive = false, scrubFocused = true)
            assertFalse(button.name, decision.consume)
            assertNull(button.name, decision.command)
        }
    }

    @Test fun anOpenPanelClearsAStalePlaybackCaptureWithoutEatingNavigation() {
        // A missed playback key-up used to leave the Activity policy holding a
        // capture. Even though the panel disabled playback gestures, the capture
        // branch ran first and swallowed Down before Compose could move focus
        // from the last subtitle track into the size selector.
        listOf(TvRemoteEventType.Down, TvRemoteEventType.Up).forEach { eventType ->
            val decision = decide(
                button = TvRemoteButton.Down,
                eventType = eventType,
                playbackActive = false,
                capturedButton = TvRemoteButton.Down,
            )
            assertFalse(eventType.name, decision.consume)
            assertTrue(eventType.name, decision.releaseCapture)
            assertNull(eventType.name, decision.command)
        }
    }

    @Test fun visibleChromeLeavesCenterAndVerticalDpadToComposeFocus() {
        listOf(
            TvRemoteButton.Select,
            TvRemoteButton.Up,
            TvRemoteButton.Down,
        ).forEach { button ->
            val decision = decide(button = button, chromeVisible = true)
            assertFalse(button.name, decision.consume)
            assertNull(button.name, decision.command)
        }
    }

    @Test fun theScrubBarNeverClaimsVerticalKeys() {
        // Up and Down are how the bar is reached and left, so they must reach
        // Compose even while the bar owns horizontal input.
        listOf(TvRemoteButton.Up, TvRemoteButton.Down, TvRemoteButton.Select).forEach { button ->
            val decision = decide(button = button, chromeVisible = true, scrubFocused = true)
            assertFalse(button.name, decision.consume)
            assertNull(button.name, decision.command)
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

        // Including while the scrub bar owns horizontal input.
        val onBar = decide(TvRemoteButton.Other, chromeVisible = true, scrubFocused = true)
        assertFalse(onBar.consume)
        assertNull(onBar.command)

        // And including mid-hold: the exclusive-gesture rule covers the DPAD, not
        // the buttons the platform routes straight to MediaSession.
        listOf(TvRemoteEventType.Down, TvRemoteEventType.Up).forEach { eventType ->
            val midHold = decide(
                button = TvRemoteButton.Other,
                eventType = eventType,
                capturedButton = TvRemoteButton.Right,
            )
            assertFalse(eventType.name, midHold.consume)
            assertNull(eventType.name, midHold.command)
        }
    }

    private fun decide(
        button: TvRemoteButton,
        eventType: TvRemoteEventType = TvRemoteEventType.Down,
        repeatCount: Int = 0,
        playbackActive: Boolean = true,
        chromeVisible: Boolean = false,
        scrubFocused: Boolean = false,
        capturedButton: TvRemoteButton? = null,
    ): TvRemoteDecision = tvRemoteDecision(
        button = button,
        eventType = eventType,
        repeatCount = repeatCount,
        playbackActive = playbackActive,
        chromeVisible = chromeVisible,
        scrubFocused = scrubFocused,
        capturedButton = capturedButton,
    )
}

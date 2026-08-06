package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the shell's overlay layer composes, once what the app WANTS and what is still on
 * the window are allowed to disagree.
 *
 * They disagree for the length of one exit, which is a few frames, and the whole reason
 * they are allowed to is the bug this closes: with the flag held until the animation
 * landed, the control that raised the sheet published nothing when pressed again inside
 * that window — `StateFlow` conflates an equal value — and the press was simply spent. It
 * is frames long and animation-driven, so nothing about it is reproducible by hand, which
 * is why the rule lives in a function that can be asked directly.
 */
class OverlayHostingTest {

    @Test fun theOverlayTheAppAsksForIsTheOneHosted() {
        assertEquals(
            Overlay.DIAGNOSTICS,
            hostedOverlay(Overlay.DIAGNOSTICS, outgoing = null, supportAvailable = true),
        )
    }

    @Test fun anOverlayStaysHostedForTheFramesItSpendsLeaving() {
        // The flag is already clear here — that is what frees the button — so nothing the
        // app wants names this sheet, and only the outgoing arm keeps it on the window.
        assertEquals(
            Overlay.DIAGNOSTICS,
            hostedOverlay(active = null, outgoing = Overlay.DIAGNOSTICS, supportAvailable = true),
        )
    }

    @Test fun askingForASheetBackDuringItsExitHostsTheSameOne() {
        // Not a second sheet and not a reopen: the same composition, which is what lets it
        // return to its seat instead of being torn down and played in again.
        assertEquals(
            Overlay.DIAGNOSTICS,
            hostedOverlay(Overlay.DIAGNOSTICS, Overlay.DIAGNOSTICS, supportAvailable = true),
        )
    }

    @Test fun anOverlaySummonedOverALeavingOneTakesTheLayer() {
        assertEquals(
            Overlay.QUALITY,
            hostedOverlay(Overlay.QUALITY, outgoing = Overlay.DIAGNOSTICS, supportAvailable = true),
        )
    }

    @Test fun nothingIsHostedOnceTheExitHasLanded() {
        assertNull(hostedOverlay(active = null, outgoing = null, supportAvailable = true))
    }

    @Test fun aSupportSheetIsNeverHostedWithoutACatalogToRender() {
        // With no catalog that overlay raises no sheet at all, so nothing would ever
        // report it gone and the empty layer would hold the route out of TalkBack.
        assertNull(hostedOverlay(Overlay.SUPPORT, outgoing = null, supportAvailable = false))
        assertNull(hostedOverlay(active = null, outgoing = Overlay.SUPPORT, supportAvailable = false))
        assertEquals(
            Overlay.SUPPORT,
            hostedOverlay(active = null, outgoing = Overlay.SUPPORT, supportAvailable = true),
        )
    }

    @Test fun losingTheCatalogTakesOnlyTheSupportSheet() {
        assertEquals(
            Overlay.DIAGNOSTICS,
            hostedOverlay(active = null, outgoing = Overlay.DIAGNOSTICS, supportAvailable = false),
        )
    }
}

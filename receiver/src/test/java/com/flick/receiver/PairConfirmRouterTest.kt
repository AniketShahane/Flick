package com.flick.receiver

import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.net.PairingSurface
import com.flick.receiver.session.MediaStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the router puts the on-TV "Allow this phone?" prompt.
 *
 * The prompt's whole value is that a person in the room has to answer it, so the one
 * thing it may never be is invisible: a card nobody is drawing can only ever expire,
 * which resolves to a refusal and would turn the confirmation into a way to make
 * pairing fail rather than a way to authorize it.
 */
class PairConfirmRouterTest {
    private val confirming = PairingSurface.Confirming("Pixel", 7L, 30_000L)

    private val stages = listOf(
        MediaStage.None,
        MediaStage.Checking("cast", 1L),
        MediaStage.Preparing("cast", 1L),
        MediaStage.Active("cast", 1L),
        MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L),
    )

    /**
     * At EVERY paired count, not just zero. "Pair another phone" from the idle screen
     * opens a code with phones already stored, so without this the prompt would be
     * asked of a TV sitting on Idle and could only run its clock down.
     */
    @Test fun aPendingConfirmationRoutesToThePairScreenAtEveryPairedCount() {
        (0..3).forEach { pairedCount ->
            assertEquals(
                StandbySurface.Pair,
                standbySurfaceFor(showSettings = false, surface = confirming, pairedCount = pairedCount),
            )
        }
    }

    /**
     * Settings still outranks it, exactly as it outranks a code and a seal. That is
     * safe because a confirmation cannot begin while Settings is up — entering
     * Settings closes the surface, so no code is live to prove — and the trip in
     * withdraws any prompt that was standing (`PairingManager.closeSurface`).
     */
    @Test fun settingsStillOutranksAConfirmation() {
        assertEquals(
            StandbySurface.Settings,
            standbySurfaceFor(showSettings = true, surface = confirming, pairedCount = 1),
        )
        assertFalse(
            pairingSurfaceRendered(MediaStage.None, showSettings = true, surface = confirming, pairedCount = 1),
        )
    }

    /**
     * The prompt is rendered, so the lifecycle ask reaches `requestOpen` — which
     * refuses while a decision is pending and leaves the card up. Same deliberate
     * split as the seal: this decision says "the pair screen is drawn", and the
     * manager decides what the pair screen is drawing.
     */
    @Test fun theAskReachesTheManagerAndTheManagerIsWhatRefusesIt() {
        assertTrue(
            pairingSurfaceRendered(MediaStage.None, showSettings = false, surface = confirming, pairedCount = 0),
        )
        assertTrue(
            pairingSurfaceRendered(MediaStage.None, showSettings = false, surface = confirming, pairedCount = 2),
        )
    }

    /** A cast on screen covers the standby surfaces entirely, prompt included. */
    @Test fun aCastOnScreenDrawsNoPrompt() {
        stages.filterNot { it is MediaStage.None }.forEach { stage ->
            assertFalse(
                "$stage covers the standby surfaces entirely",
                pairingSurfaceRendered(stage, showSettings = false, surface = confirming, pairedCount = 0),
            )
        }
    }
}

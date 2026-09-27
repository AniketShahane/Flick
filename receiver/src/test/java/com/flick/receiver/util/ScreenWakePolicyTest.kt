package com.flick.receiver.util

import com.flick.receiver.PlayerSurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the caller is expected to read a surface mode into the policy, on a TV where a
 * cast can wake it (the only case in which the surface mode decides anything).
 */
private fun keepAwakeFor(mode: PlayerSurfaceMode): Boolean = keepScreenOnWhilePresenting(
    presentingVideo = mode == PlayerSurfaceMode.VisiblePlayback,
    castHandshakeInFlight = mode == PlayerSurfaceMode.CoveredConnecting,
    pairingRendered = false,
    idleMayRest = true,
)

class ScreenWakePolicyTest {

    /** Every combination of the four inputs. */
    private val everyInput: List<List<Boolean>> = (0 until 16).map { bits -> (0 until 4).map { ((bits shr it) and 1) == 1 } }

    private fun keep(inputs: List<Boolean>) = keepScreenOnWhilePresenting(
        presentingVideo = inputs[0],
        castHandshakeInFlight = inputs[1],
        pairingRendered = inputs[2],
        idleMayRest = inputs[3],
    )

    @Test fun aTvThatCannotBeWokenByACastKeepsThePanelOnWhateverItShows() {
        // A screensaver over an unarmed Flick stops the Activity, and a stopped
        // unarmed Flick refuses the next cast: today's always-on behaviour stays.
        everyInput.filter { !it[3] }.forEach { assertTrue("inputs=$it", keep(it)) }
    }

    @Test fun anArmedTvHoldsThePanelOnlyForTheFilmTheHandshakeOrPairing() {
        everyInput.filter { it[3] }.forEach { inputs ->
            val expected = inputs[0] || inputs[1] || inputs[2]
            assertEquals("inputs=$inputs", expected, keep(inputs))
        }
    }

    @Test fun anArmedTvHoldsThePanelOverAFilmOnItsOwn() {
        // Ended or paused, the film is still a live cast: a screensaver over it
        // stops the Activity and ends the cast.
        assertTrue(
            keepScreenOnWhilePresenting(
                presentingVideo = true,
                castHandshakeInFlight = false,
                pairingRendered = false,
                idleMayRest = true,
            ),
        )
    }

    @Test fun anArmedIdleTvMayRest() {
        assertFalse(
            keepScreenOnWhilePresenting(
                presentingVideo = false,
                castHandshakeInFlight = false,
                pairingRendered = false,
                idleMayRest = true,
            ),
        )
    }

    @Test fun theTwoLiveSurfaceModesHoldItAwakeAndTheRestingOneReleases() {
        assertTrue(keepAwakeFor(PlayerSurfaceMode.VisiblePlayback))
        // Bounded by the 18 s startup deadline, and a screensaver landing between
        // "connecting" and the first frame would read as the cast having failed.
        assertTrue(keepAwakeFor(PlayerSurfaceMode.CoveredConnecting))
        // Idle, settings and the failure card all sit here — hours at a time
        // between casts, and none of them may deny an OLED panel its dimming.
        assertFalse(keepAwakeFor(PlayerSurfaceMode.Hidden))
    }

    @Test fun everySurfaceModeIsAccountedFor() {
        // A mode added later must be classified deliberately rather than inheriting
        // whichever answer the enum ordering happens to give it.
        assertTrue(PlayerSurfaceMode.entries.size == 3)
    }
}

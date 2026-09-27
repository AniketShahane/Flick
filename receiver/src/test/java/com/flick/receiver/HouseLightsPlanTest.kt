package com.flick.receiver

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.session.MediaStage
import com.flick.receiver.session.receiverErrorFace
import com.flick.receiver.session.ReceiverFaultDetail
import com.flick.receiver.ui.components.APERTURE_ALPHAS
import com.flick.receiver.ui.components.APERTURE_CORE
import com.flick.receiver.ui.components.APERTURE_POSITIONS
import com.flick.receiver.ui.components.CARD_ENTER_APERTURE
import com.flick.receiver.ui.components.HouseGate
import com.flick.receiver.ui.components.HouseMove
import com.flick.receiver.ui.components.HouseMoveKind
import com.flick.receiver.ui.components.HouseStage
import com.flick.receiver.ui.components.RateSample
import com.flick.receiver.ui.components.VEIL_DENSITY
import com.flick.receiver.ui.components.apertureRampAlpha
import com.flick.receiver.ui.components.apertureScale
import com.flick.receiver.ui.components.freshRevealRate
import com.flick.receiver.ui.components.houseMove
import com.flick.receiver.ui.components.houseRest
import com.flick.receiver.ui.components.houseStageFor
import com.flick.receiver.ui.theme.FlickMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class HouseLightsPlanTest {

    @Test fun everyMediaStageMapsToOneHouseStage() {
        assertEquals(HouseStage.Room, houseStageFor(MediaStage.None))
        assertEquals(HouseStage.Handshake, houseStageFor(MediaStage.Checking("cast", 1L)))
        assertEquals(HouseStage.Handshake, houseStageFor(MediaStage.Preparing("cast", 1L)))
        assertEquals(HouseStage.Film, houseStageFor(MediaStage.Active("cast", 1L)))
        assertEquals(HouseStage.Fault, houseStageFor(MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L)))
    }

    @Test fun firstCompositionLaunchesTheRoomAndSnapsEverythingElse() {
        assertMove(HouseMoveKind.Launch, 0f, 1f, HouseGate.None, move(null, HouseStage.Room))
        for (to in listOf(HouseStage.Fault, HouseStage.Handshake, HouseStage.Film)) {
            val (p, d) = houseRest(to)
            assertMove(HouseMoveKind.Snap, p, d, HouseGate.None, move(null, to))
        }
    }

    @Test fun lightsDownClosesFromWhereverTheRoomIs() {
        // A finished launch draws nothing and normalises to (1, 1).
        assertMove(HouseMoveKind.LightsDown, 1f, 1f, HouseGate.None, move(HouseStage.Room, HouseStage.Handshake, 1f, 1f))
        // Mid-launch: the aperture carries on closing from where it is, fully dense.
        assertMove(HouseMoveKind.LightsDown, 0.5f, 1f, HouseGate.None, move(HouseStage.Room, HouseStage.Handshake, 0.5f, 1f))
        // A fault at rest draws nothing, so it closes from fully open too.
        assertMove(HouseMoveKind.LightsDown, 1f, 1f, HouseGate.None, move(HouseStage.Fault, HouseStage.Handshake, 0f, 0f))
        // A fault still dissolving keeps its density and thickens from there.
        assertMove(HouseMoveKind.LightsDown, 0f, 0.5f, HouseGate.None, move(HouseStage.Fault, HouseStage.Handshake, 0f, 0.5f))
        // A fault pool still dissolving keeps both, so nothing outside the pool steps to black.
        assertMove(HouseMoveKind.LightsDown, 0.5f, 0.3f, HouseGate.None, move(HouseStage.Fault, HouseStage.Handshake, 0.5f, 0.3f))
    }

    @Test fun veilInStartsAtTheOutgoingDimAndNeverBrightens() {
        assertMove(
            HouseMoveKind.VeilIn, 0f, 0.34f, HouseGate.None,
            move(HouseStage.Film, HouseStage.Handshake, 0f, 0f, filmDim = 0.34f),
        )
        assertMove(
            HouseMoveKind.VeilIn, 0f, 0f, HouseGate.None,
            move(HouseStage.Film, HouseStage.Handshake, 0f, 0f, filmDim = 0f),
        )
        // Mid-lift: whichever of the lift's veil and the film's dim is darker.
        assertMove(
            HouseMoveKind.VeilIn, 0f, 0.4f, HouseGate.None,
            move(HouseStage.Film, HouseStage.Handshake, 0f, 0.4f, filmDim = 0.3f),
        )
        assertMove(
            HouseMoveKind.VeilIn, 0f, 0.5f, HouseGate.None,
            move(HouseStage.Film, HouseStage.Handshake, 0f, 0.4f, filmDim = 0.5f),
        )
    }

    @Test fun pictureUpLiftsFromTheVeilAndHoldsForTheResync() {
        assertMove(
            HouseMoveKind.PictureUp, 0f, VEIL_DENSITY, HouseGate.Reveal,
            move(HouseStage.Handshake, HouseStage.Film, 0f, VEIL_DENSITY),
        )
        // The race: the first frame lands while Act I is still closing.
        assertMove(
            HouseMoveKind.PictureUp, 0.4f, 1f, HouseGate.Reveal,
            move(HouseStage.Handshake, HouseStage.Film, 0.4f, 1f),
        )
    }

    @Test fun aLitStageStraightToFilmSnaps() {
        for (from in listOf(HouseStage.Room, HouseStage.Fault)) {
            assertMove(HouseMoveKind.Snap, 0f, 0f, HouseGate.None, move(from, HouseStage.Film, 1f, 1f))
        }
    }

    @Test fun lightsUpReturnsTheRoomFromTheCentre() {
        assertMove(
            HouseMoveKind.LightsUpRoom, 0f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Room, 0f, VEIL_DENSITY),
        )
        // Cancelled mid Act I: the aperture reopens from where it had reached.
        assertMove(
            HouseMoveKind.LightsUpRoom, 0.4f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Room, 0.4f, 1f),
        )
        // Cancelled on Act I's first frame: still fully lit, so nothing is drawn.
        assertMove(
            HouseMoveKind.LightsUpRoom, 0.9995f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Room, 0.9995f, 1f),
        )
        assertMove(
            HouseMoveKind.LightsUpRoom, 0f, 1f, HouseGate.Rest,
            move(HouseStage.Film, HouseStage.Room, 0f, 0f),
        )
    }

    @Test fun faultsDissolveUniformly() {
        assertMove(
            HouseMoveKind.LightsUpFault, 0f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Fault, 0f, VEIL_DENSITY),
        )
        assertMove(
            HouseMoveKind.LightsUpFault, 0f, 1f, HouseGate.Rest,
            move(HouseStage.Film, HouseStage.Fault, 0f, 0f),
        )
        // Faulted while Act I was still closing: the pool holds where it is and dissolves.
        assertMove(
            HouseMoveKind.LightsUpFault, 0.5f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Fault, 0.5f, 1f),
        )
        assertMove(
            HouseMoveKind.LightsUpFault, 0.9995f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Fault, 0.9995f, 1f),
        )
        // A veil only just starting over a film draws nothing lit: the documented cut.
        assertMove(
            HouseMoveKind.LightsUpFault, 0f, 1f, HouseGate.Rest,
            move(HouseStage.Handshake, HouseStage.Fault, 0.5f, 0.002f),
        )
    }

    @Test fun roomAndFaultExchangeWithoutAMove() {
        assertMove(HouseMoveKind.Rest, 1f, 1f, HouseGate.None, move(HouseStage.Room, HouseStage.Fault, 1f, 1f))
        assertMove(HouseMoveKind.Rest, 1f, 1f, HouseGate.None, move(HouseStage.Fault, HouseStage.Room, 0f, 0f))
        // A launch interrupted by a fault keeps its aperture so it can finish opening.
        assertMove(HouseMoveKind.Rest, 0.5f, 1f, HouseGate.None, move(HouseStage.Room, HouseStage.Fault, 0.5f, 1f))
    }

    @Test fun reducedMotionSnapsEveryMoveToItsRest() {
        val froms = listOf(null, HouseStage.Room, HouseStage.Fault, HouseStage.Handshake, HouseStage.Film)
        for (from in froms) {
            for (to in HouseStage.values()) {
                if (from == to) continue
                val (p, d) = houseRest(to)
                assertMove(
                    HouseMoveKind.Snap, p, d, HouseGate.None,
                    houseMove(from, to, 0.4f, 0.6f, filmDim = 0.34f, reducedMotion = true),
                )
            }
        }
    }

    @Test fun aSeamPlannedWhileStoppedStartsFromNothingOnScreen() {
        val froms = listOf(null, HouseStage.Room, HouseStage.Fault, HouseStage.Handshake, HouseStage.Film)
        for (from in froms) {
            // Closed and fully dense: the black of the covered surface, not a lit face.
            assertMove(
                HouseMoveKind.LightsDown, 0f, 1f, HouseGate.None,
                houseMove(from, HouseStage.Handshake, 1f, 1f, filmDim = 0f, reducedMotion = false, offstage = true),
            )
            for (to in listOf(HouseStage.Room, HouseStage.Fault, HouseStage.Film)) {
                val (p, d) = houseRest(to)
                assertMove(
                    HouseMoveKind.Snap, p, d, HouseGate.None,
                    houseMove(from, to, 0.4f, 0.6f, filmDim = 0.34f, reducedMotion = false, offstage = true),
                )
            }
        }
        // Reduced motion still wins.
        val (p, d) = houseRest(HouseStage.Handshake)
        assertMove(
            HouseMoveKind.Snap, p, d, HouseGate.None,
            houseMove(HouseStage.Room, HouseStage.Handshake, 1f, 1f, filmDim = 0f, reducedMotion = true, offstage = true),
        )
    }

    @Test fun theApertureOpensPastTheCornersAndClosesToNothing() {
        assertEquals(0f, apertureScale(0f), 0f)
        assertTrue(APERTURE_CORE * apertureScale(1f) >= sqrt(2f))
    }

    @Test fun theApertureRampHasNoKneeAtTheCoreOrTheRim() {
        assertEquals(0f, apertureRampAlpha(0f), 0f)
        assertEquals(1f, apertureRampAlpha(1f), 0f)
        assertEquals(0.5f, apertureRampAlpha(0.5f), 0.0001f)
        assertEquals(APERTURE_POSITIONS.size, APERTURE_ALPHAS.size)
        assertEquals(0f, APERTURE_POSITIONS.first(), 0f)
        assertEquals(1f, APERTURE_POSITIONS.last(), 0.0001f)
        // The clear core is unchanged, so the corners are still reached at full open.
        assertEquals(APERTURE_CORE, APERTURE_POSITIONS[1], 0f)
        assertEquals(0f, APERTURE_ALPHAS[1], 0f)
        // Flat leaving the core and entering the rim, where a linear ramp would kink.
        val steps = APERTURE_ALPHAS.size - 2
        assertTrue(APERTURE_ALPHAS[2] < 1f / steps)
        assertTrue(1f - APERTURE_ALPHAS[APERTURE_ALPHAS.size - 2] < 1f / steps)
        for (i in 1 until APERTURE_POSITIONS.size) {
            assertTrue(APERTURE_POSITIONS[i] > APERTURE_POSITIONS[i - 1])
            assertTrue(APERTURE_ALPHAS[i] >= APERTURE_ALPHAS[i - 1])
        }
    }

    @Test fun theCardEntersIntoTheLastOfTheLight() {
        val closing = TargetBasedAnimation(FlickMotion.lightsDown<Float>(), Float.VectorConverter, 1f, 0f)
        val enterMs = (0..FlickMotion.LIGHTS_DOWN_MS).first {
            closing.getValueFromNanos(it * NANOS_PER_MS) <= CARD_ENTER_APERTURE
        }
        assertTrue("card enters at $enterMs ms", enterMs in 400..480)
    }

    @Test fun onlyStandbyAndErrorHaveAShellFace() {
        assertEquals(ShellFace.Standby, shellFaceFor(MediaStage.None))
        assertEquals(
            ShellFace.Fault(receiverErrorFace(CastFailureCode.DECODER_INIT, ReceiverFaultDetail.None), beforeReady = false),
            shellFaceFor(MediaStage.Error("cast", CastFailureCode.DECODER_INIT, 1L, beforeReady = false)),
        )
        assertNull(shellFaceFor(MediaStage.Checking("cast", 1L)))
        assertNull(shellFaceFor(MediaStage.Preparing("cast", 1L)))
        assertNull(shellFaceFor(MediaStage.Active("cast", 1L)))
    }

    @Test fun aRevealDecidesOnlyOnARateReadUnderItsOwnCast() {
        assertEquals(23.976f, freshRevealRate("b", RateSample("b", 23.976f)), 0f)
        assertEquals("the previous film's cadence", 0f, freshRevealRate("b", RateSample("a", 23.976f)), 0f)
        assertEquals("read before any cast", 0f, freshRevealRate("b", RateSample(null, 60f)), 0f)
        assertEquals("no sample yet", 0f, freshRevealRate("b", null), 0f)
        assertEquals("no film active", 0f, freshRevealRate(null, RateSample(null, 24f)), 0f)
        assertEquals("format not yet known", 0f, freshRevealRate("b", RateSample("b", 0f)), 0f)
        assertEquals("not a cadence", 0f, freshRevealRate("b", RateSample("b", Float.NaN)), 0f)
    }

    private fun move(
        from: HouseStage?,
        to: HouseStage,
        prevAperture: Float = 1f,
        prevDensity: Float = 1f,
        filmDim: Float = 0f,
    ): HouseMove = houseMove(from, to, prevAperture, prevDensity, filmDim, reducedMotion = false)

    private fun assertMove(kind: HouseMoveKind, aperture: Float, density: Float, gate: HouseGate, actual: HouseMove) {
        assertEquals("kind of $actual", kind, actual.kind)
        assertEquals("aperture of $actual", aperture, actual.seedAperture, 0.0001f)
        assertEquals("density of $actual", density, actual.seedDensity, 0.0001f)
        assertEquals("gate of $actual", gate, actual.gate)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

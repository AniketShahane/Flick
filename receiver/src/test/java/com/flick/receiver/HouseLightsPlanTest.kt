package com.flick.receiver

import androidx.compose.ui.graphics.toArgb
import com.flick.receiver.net.CastFailureCode
import com.flick.receiver.session.MediaStage
import com.flick.receiver.session.receiverErrorFace
import com.flick.receiver.session.ReceiverFaultDetail
import com.flick.receiver.ui.components.APERTURE_ALPHAS
import com.flick.receiver.ui.components.APERTURE_CORE
import com.flick.receiver.ui.components.APERTURE_POSITIONS
import com.flick.receiver.ui.components.HouseGate
import com.flick.receiver.ui.components.HouseMove
import com.flick.receiver.ui.components.HouseMoveKind
import com.flick.receiver.ui.components.HouseStage
import com.flick.receiver.ui.components.RESYNC_GRACE_MS
import com.flick.receiver.ui.components.REVEAL_SETTLE_FALLBACK_MS
import com.flick.receiver.ui.components.RateSample
import com.flick.receiver.ui.components.STAGE_HOLD_CAP_MS
import com.flick.receiver.ui.components.TUNNEL_VANISH_Y
import com.flick.receiver.ui.components.TUNNEL_WINDOW_CORNER
import com.flick.receiver.ui.components.TUNNEL_WINDOW_FRACTION
import com.flick.receiver.ui.components.VEIL_DENSITY
import com.flick.receiver.ui.components.apertureRampAlpha
import com.flick.receiver.ui.components.apertureScale
import com.flick.receiver.ui.components.freshRevealRate
import com.flick.receiver.ui.components.houseMove
import com.flick.receiver.ui.components.houseRest
import com.flick.receiver.ui.components.houseStageFor
import com.flick.receiver.ui.components.FILM_FLOWING_WAIT_MS
import com.flick.receiver.ui.components.TunnelCutout
import com.flick.receiver.ui.components.TUNNEL_RING_COUNT
import com.flick.receiver.ui.components.TUNNEL_RING_LIFE_MS
import com.flick.receiver.ui.components.TUNNEL_STREAKS
import com.flick.receiver.ui.components.tunnelApproach
import com.flick.receiver.ui.components.tunnelEnvelope
import com.flick.receiver.ui.components.tunnelFadeIn
import com.flick.receiver.ui.components.tunnelLoopAt
import com.flick.receiver.ui.components.tunnelRingScale
import com.flick.receiver.ui.components.tunnelCutout
import com.flick.receiver.ui.components.tunnelFade
import com.flick.receiver.ui.components.tunnelHoldLeftMs
import com.flick.receiver.ui.components.tunnelShownSinceMs
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

class HouseLightsPlanTest {

    @Test fun everyMediaStageMapsToOneHouseStage() {
        assertEquals(HouseStage.Room, houseStageFor(MediaStage.None))
        assertEquals(HouseStage.Handshake, houseStageFor(MediaStage.Checking("cast", 1L)))
        assertEquals(HouseStage.Handshake, houseStageFor(MediaStage.Preparing("cast", 1L)))
        assertEquals(HouseStage.Film, houseStageFor(MediaStage.Active("cast", 1L)))
        assertEquals(HouseStage.Fault, houseStageFor(MediaStage.Error("cast", CastFailureCode.UNKNOWN, 1L)))
    }

    @Test fun firstCompositionLaunchesTheRoomRunsTheTunnelAndSnapsTheRest() {
        assertMove(HouseMoveKind.Launch, 0f, 1f, HouseGate.None, move(null, HouseStage.Room))
        assertMove(HouseMoveKind.LightsDown, 0f, 1f, HouseGate.None, move(null, HouseStage.Handshake))
        for (to in listOf(HouseStage.Fault, HouseStage.Film)) {
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

    @Test fun aStopBetweenTwoFilmsStillRunsTheTunnel() {
        // The phone re-casts as stop then loadMedia: Film -> Room -> Handshake.
        assertMove(HouseMoveKind.LightsUpRoom, 0f, 1f, HouseGate.Rest, move(HouseStage.Film, HouseStage.Room))
        // The Room's lights are still held or barely open: the tunnel rises over the dark.
        assertMove(HouseMoveKind.LightsDown, 0f, 1f, HouseGate.None, move(HouseStage.Room, HouseStage.Handshake, 0f, 1f))
        assertEquals(HouseMoveKind.LightsDown, move(HouseStage.Room, HouseStage.Handshake, 0.05f, 1f).kind)
    }

    @Test fun aHandshakeLeftAfterTheTunnelRoseClosesFromFullDark() {
        // The tunnel leaves the curtain empty and closed (0, 0), not a lit pool.
        assertMove(HouseMoveKind.LightsUpRoom, 0f, 1f, HouseGate.Rest, move(HouseStage.Handshake, HouseStage.Room, 0f, 0f))
        assertMove(HouseMoveKind.LightsUpFault, 0f, 1f, HouseGate.Rest, move(HouseStage.Handshake, HouseStage.Fault, 0f, 0f))
        // Nothing drawn over the film's cut into the window.
        assertMove(HouseMoveKind.PictureUp, 1f, 1f, HouseGate.Reveal, move(HouseStage.Handshake, HouseStage.Film, 0f, 0f))
    }

    @Test fun theTunnelsLeastTimeCoversItsFadeAndTheCapCanStillEndIt() {
        assertTrue(FlickMotion.TUNNEL_MIN_SHOWN_MS >= FlickMotion.TUNNEL_FADE_IN_MS + FlickMotion.TUNNEL_CAPTION_DELAY_MS + FlickMotion.TUNNEL_CAPTION_IN_MS)
        assertTrue("the stage hold's cap must still be able to end it", FlickMotion.TUNNEL_MIN_SHOWN_MS < STAGE_HOLD_CAP_MS)
    }

    @Test fun theSettleFallbackOutlastsTheWholeShow() {
        val show = STAGE_HOLD_CAP_MS + FILM_FLOWING_WAIT_MS + FlickMotion.TUNNEL_FILM_IN_MS +
            FlickMotion.TUNNEL_DWELL_MS + FlickMotion.TUNNEL_EXPAND_MS + FlickMotion.TUNNEL_SETTLE_MS
        assertTrue("fallback $REVEAL_SETTLE_FALLBACK_MS ms inside a $show ms show", REVEAL_SETTLE_FALLBACK_MS > show)
    }

    @Test fun theExpansionsPartsEndInsideIt() {
        assertTrue(FlickMotion.TUNNEL_FLASH_MS < FlickMotion.TUNNEL_EXPAND_MS)
        assertTrue(FlickMotion.TUNNEL_RIM_OUT_MS < FlickMotion.TUNNEL_EXPAND_MS)
        assertTrue(FlickMotion.TUNNEL_CAPTION_OUT_MS < FlickMotion.TUNNEL_DWELL_MS)
        assertEquals(1f, tunnelFade(0f), 0f)
        assertEquals(1f, tunnelFade(FlickMotion.TUNNEL_FADE_FROM), 0f)
        assertEquals(0.5f, tunnelFade((1f + FlickMotion.TUNNEL_FADE_FROM) / 2f), 0.0001f)
        assertEquals(0f, tunnelFade(1f), 0f)
    }

    @Test fun theWindowApproachesWithoutStallingOrJumping() {
        assertEquals(FlickMotion.TUNNEL_APPROACH_FROM, tunnelApproach(0L), 0f)
        var last = tunnelApproach(0L)
        for (t in 16L..20_000L step 16L) {
            val now = tunnelApproach(t)
            assertTrue("the approach went back at $t ms", now >= last)
            assertTrue("the approach jumped at $t ms", now - last < 0.01f)
            last = now
        }
        assertTrue("never past the window's own size", last <= 1f)
        assertTrue("still growing after the usual handshake", tunnelApproach(3_000L) < 0.95f)
    }

    @Test fun theExpansionLeavesAtTheApproachsSpeedAndOnlyGrows() {
        val w = 1920f
        val h = 1080f
        val startAt = 2_500L
        fun width(t: Long): Float {
            val u = ((t - startAt).toFloat() / FlickMotion.TUNNEL_EXPAND_MS).coerceIn(0f, 1f)
            val p = if (t <= startAt) 0f else FlickMotion.TunnelExpand.transform(u)
            val c = tunnelCutout(p, w, h, tunnelApproach(t))
            return c.right - c.left
        }
        val before = width(startAt) - width(startAt - 16)
        val after = width(startAt + 16) - width(startAt)
        assertTrue("the window's speed jumped from $before to $after px a frame", abs(after - before) < 1f)
        var last = width(0L)
        for (t in 16L..startAt + FlickMotion.TUNNEL_EXPAND_MS step 16L) {
            val now = width(t)
            assertTrue("the window shrank at $t ms", now >= last)
            last = now
        }
        assertEquals(w, width(startAt + FlickMotion.TUNNEL_EXPAND_MS), 0.01f)
        // Quickest at the end: the last frame covers more than the first.
        val first = width(startAt + 16) - width(startAt)
        val lastStep = width(startAt + FlickMotion.TUNNEL_EXPAND_MS) - width(startAt + FlickMotion.TUNNEL_EXPAND_MS - 16)
        assertTrue("the expansion does not accelerate: $first then $lastStep", lastStep > 10 * first)
    }

    @Test fun theExpansionNeverJumpsAtItsEndAt60Hz() = assertEndFramesBelow(frameMs = 1_000f / 60f, maxDp = 25f)

    @Test fun theExpansionNeverJumpsAtItsEndAt24Hz() = assertEndFramesBelow(frameMs = 1_000f / 24f, maxDp = 25f * (1_000f / 24f) / 16f)

    /** The porthole's widest per-frame edge move over the expansion's last 150 ms, on the 960 × 540 dp stage. */
    private fun assertEndFramesBelow(frameMs: Float, maxDp: Float) {
        val w = 960f
        val h = 540f
        val startAt = 2_500L
        fun edges(t: Float): TunnelCutout {
            val u = ((t - startAt) / FlickMotion.TUNNEL_EXPAND_MS).coerceIn(0f, 1f)
            return tunnelCutout(FlickMotion.TunnelExpand.transform(u), w, h, tunnelApproach(t.toLong()))
        }
        val end = startAt + FlickMotion.TUNNEL_EXPAND_MS.toFloat()
        var t = end - 150f
        while (t < end) {
            val next = minOf(t + frameMs, end)
            val a = edges(t)
            val b = edges(next)
            val moved = maxOf(a.left - b.left, a.top - b.top, b.right - a.right, b.bottom - a.bottom)
            assertTrue("an edge moved $moved dp in one ${frameMs.roundToInt()} ms frame at ${t - startAt} ms", moved <= maxDp)
            t = next
        }
    }

    @Test fun theTunnelsFadeHasRealTimeToRun() {
        // Keyed to time, the fade gets the expansion's last 30 %, several frames even at 24 Hz.
        val fadeMs = (1f - FlickMotion.TUNNEL_FADE_FROM) * FlickMotion.TUNNEL_EXPAND_MS
        assertTrue("the fade has $fadeMs ms", fadeMs >= 150f)
        assertTrue("the flash outlasts the porthole's slow start", FlickMotion.TUNNEL_FLASH_MS >= FlickMotion.TUNNEL_EXPAND_MS / 2)
    }

    @Test fun thePortholeIsTheWindowAndOpensOntoTheWholeScreen() {
        val w = 1920f
        val h = 1080f
        val window = tunnelCutout(0f, w, h)
        assertEquals(w / 2f - TUNNEL_WINDOW_FRACTION * w / 2f, window.left, 0.01f)
        assertEquals(TUNNEL_VANISH_Y * h - TUNNEL_WINDOW_FRACTION * h / 2f, window.top, 0.01f)
        assertEquals(TUNNEL_WINDOW_FRACTION * w, window.right - window.left, 0.01f)
        assertEquals(TUNNEL_WINDOW_FRACTION * h, window.bottom - window.top, 0.01f)
        assertEquals(TUNNEL_WINDOW_CORNER * TUNNEL_WINDOW_FRACTION * w, window.radius, 0.01f)
        val full = tunnelCutout(1f, w, h)
        assertEquals(0f, full.left, 0f)
        assertEquals(0f, full.top, 0f)
        assertEquals(w, full.right, 0f)
        assertEquals(h, full.bottom, 0f)
        assertEquals("square once it fills the screen", 0f, full.radius, 0f)
        var last = window
        for (step in 1..100) {
            val now = tunnelCutout(step / 100f, w, h)
            assertTrue("the porthole narrowed at $step %", now.left <= last.left && now.top <= last.top &&
                now.right >= last.right && now.bottom >= last.bottom)
            last = now
        }
    }

    @Test fun theTunnelFadesInDeceleratingOverItsFadeIn() {
        assertEquals(0f, tunnelFadeIn(0L), 0f)
        assertEquals(1f, tunnelFadeIn(FlickMotion.TUNNEL_FADE_IN_MS), 0f)
        assertEquals(1f, tunnelFadeIn(10_000L), 0f)
        val half = tunnelFadeIn(FlickMotion.TUNNEL_FADE_IN_MS / 2)
        assertTrue("not decelerating: $half at half time", half > 0.5f)
    }

    @Test fun theLoopIsFullFromItsFirstFrameAndWrapsUnseen() {
        // Evenly phased rings: at the first frame each is at its own point in its life.
        val starts = (0 until TUNNEL_RING_COUNT).map { tunnelLoopAt(0L, TUNNEL_RING_LIFE_MS, it.toFloat() / TUNNEL_RING_COUNT) }
        assertEquals(TUNNEL_RING_COUNT, starts.distinct().size)
        assertTrue("fewer than five rings lit on the first frame", starts.count { tunnelEnvelope(it) > 0f } >= 5)
        // A ring or streak wraps where its alpha is 0, so the jump from its end to its start is never seen.
        assertEquals(0f, tunnelEnvelope(0f), 0f)
        assertEquals(0f, tunnelEnvelope(1f), 0f)
        assertEquals(1f, tunnelEnvelope(0.12f), 0.0001f)
        assertEquals(TUNNEL_RING_LIFE_MS * 0.5f / TUNNEL_RING_LIFE_MS, tunnelLoopAt((TUNNEL_RING_LIFE_MS * 1.5f).toLong(), TUNNEL_RING_LIFE_MS, 0f), 0.001f)
        // At the end of its life a ring has left every edge of the 960 × 540 stage.
        val ringEnd = tunnelRingScale(1f)
        val windowW = TUNNEL_WINDOW_FRACTION * 960f
        val windowH = TUNNEL_WINDOW_FRACTION * 540f
        assertTrue(windowW / 2f * ringEnd > 480f)
        assertTrue(windowH / 2f * ringEnd > 540f * (1f - TUNNEL_VANISH_Y))
        // Every streak starts outside the window, along its own direction.
        for (streak in TUNNEL_STREAKS) {
            val x = abs(streak.ux) * streak.reach0
            val y = abs(streak.uy) * streak.reach0
            assertTrue("a streak at ${streak.angleDeg}° starts inside the window", x > windowW / 2f || y > windowH / 2f)
            assertEquals(1f, hypot(streak.ux, streak.uy), 0.0001f)
        }
        assertEquals(10, TUNNEL_STREAKS.size)
    }

    @Test fun theTunnelsPaletteIsTheSpecs() {
        // The reference look's values, written out so a token that drifts fails here.
        val spec = mapOf(
            FlickColor.TunnelWashCore to 0xFF1F3AA8.toInt(),
            FlickColor.TunnelWashMid to 0xFF0B1640.toInt(),
            FlickColor.TunnelWashEdge to 0xFF04070F.toInt(),
            FlickColor.TunnelRing to 0xFF6FA0FF.toInt(),
            FlickColor.TunnelAmber to 0xFFFFB61E.toInt(),
            FlickColor.TunnelStreakBlue to 0xFF9FC0FF.toInt(),
            FlickColor.TunnelStreakWhite to 0xFFFFFFFF.toInt(),
            FlickColor.TunnelWindow to 0xFFEAF0FF.toInt(),
            FlickColor.TunnelPortholeLight to 0xE69FC0FF.toInt(),
        )
        for ((token, argb) in spec) assertEquals(String.format("#%08X", argb), argb, token.toArgb())
        // Gradients that end clear, and a pane that hides the film completely.
        assertEquals(0f, FlickColor.TunnelGlowEdge.alpha, 0f)
        assertEquals(0f, FlickColor.TunnelPortholeLightEdge.alpha, 0f)
        assertEquals(1f, FlickColor.TunnelPortholeBase.alpha, 0f)
        // The glow and the light are the ring's and the streak's own hues, faded.
        assertEquals(FlickColor.TunnelRing.copy(alpha = 1f).toArgb(), FlickColor.TunnelGlowCore.copy(alpha = 1f).toArgb())
        assertEquals(FlickColor.TunnelStreakBlue.toArgb(), FlickColor.TunnelPortholeLight.copy(alpha = 1f).toArgb())
    }

    @Test fun aTunnelOwesItsLeastTimeFromItsFirstFrame() {
        val min = FlickMotion.TUNNEL_MIN_SHOWN_MS
        assertEquals("not yet drawn", 0L, tunnelHoldLeftMs(startedAtMs = null, nowMs = 5_000L))
        assertEquals(min, tunnelHoldLeftMs(startedAtMs = 5_000L, nowMs = 5_000L))
        assertEquals(min - 400L, tunnelHoldLeftMs(startedAtMs = 5_000L, nowMs = 5_400L))
        assertEquals(0L, tunnelHoldLeftMs(startedAtMs = 5_000L, nowMs = 5_000L + min))
        assertEquals(0L, tunnelHoldLeftMs(startedAtMs = 5_000L, nowMs = 9_000L))
    }

    @Test fun aModeSwitchRestartsTheTunnelsTimeFromItsSettle() {
        assertNull("not yet drawn", tunnelShownSinceMs(startedAtMs = null, lastChangeAtMs = 5_000L))
        assertEquals("no switch seen", 5_000L, tunnelShownSinceMs(startedAtMs = 5_000L, lastChangeAtMs = null))
        assertEquals("a switch settled before the tunnel started", 5_000L, tunnelShownSinceMs(5_000L, 4_000L))
        assertEquals("a switch still resyncing as it started", 4_800L + RESYNC_GRACE_MS, tunnelShownSinceMs(5_000L, 4_800L))
        assertEquals("a switch mid-show", 5_600L + RESYNC_GRACE_MS, tunnelShownSinceMs(5_000L, 5_600L))
        val since = tunnelShownSinceMs(5_000L, 5_600L)
        assertEquals(FlickMotion.TUNNEL_MIN_SHOWN_MS, tunnelHoldLeftMs(since, 5_600L + RESYNC_GRACE_MS))
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
}

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
import com.flick.receiver.ui.components.TUNNEL_RIM_RADIUS
import com.flick.receiver.ui.components.TUNNEL_RIM_WIDTH
import com.flick.receiver.ui.components.TUNNEL_VANISH_Y
import com.flick.receiver.ui.components.TUNNEL_WINDOW_CORNER
import com.flick.receiver.ui.components.TUNNEL_WINDOW_FRACTION
import com.flick.receiver.ui.components.TUNNEL_WINDOW_LIFT
import com.flick.receiver.ui.components.VEIL_DENSITY
import com.flick.receiver.ui.components.apertureRampAlpha
import com.flick.receiver.ui.components.apertureScale
import com.flick.receiver.ui.components.freshRevealRate
import com.flick.receiver.ui.components.houseMove
import com.flick.receiver.ui.components.houseRest
import com.flick.receiver.ui.components.houseStageFor
import com.flick.receiver.ui.components.tunnelFade
import com.flick.receiver.ui.components.tunnelHoldLeftMs
import com.flick.receiver.ui.components.tunnelShownSinceMs
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
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
        val show = STAGE_HOLD_CAP_MS + FlickMotion.TUNNEL_FILM_IN_MS + FlickMotion.TUNNEL_DWELL_MS + FlickMotion.TUNNEL_EXPAND_MS
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

    @Test fun theBezelCoversTheSurfacesSquareCorners() {
        // The surface's corner sits √2 · (R − w) from the bezel's outer arc's centre.
        val w = TUNNEL_RIM_WIDTH.value
        val r = TUNNEL_RIM_RADIUS.value
        assertTrue(sqrt(2f) * (r - w) <= r)
        // And the bezel is never sharper than the window it frames, at the 960 dp stage.
        assertTrue(r >= TUNNEL_WINDOW_CORNER * TUNNEL_WINDOW_FRACTION * STAGE_DP_WIDTH)
    }

    @Test fun theWindowIsCentredOnTheVanishingPoint() {
        assertEquals(TUNNEL_VANISH_Y - 0.5f, TUNNEL_WINDOW_LIFT, 0f)
    }

    @Test fun theTunnelDrawableIsTheGeometryHouseLightsPoses() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(TUNNEL_XML)
        val vector = doc.getElementsByTagName("vector").item(0) as Element
        val vw = vector.android("viewportWidth").toFloat()
        val vh = vector.android("viewportHeight").toFloat()
        assertEquals("the stage is 16:9", 16f / 9f, vw / vh, 0.0001f)
        val window = doc.byName("path", "window")
        val ww = TUNNEL_WINDOW_FRACTION * vw
        val wh = TUNNEL_WINDOW_FRACTION * vh
        val r = TUNNEL_WINDOW_CORNER * ww
        val x0 = vw / 2f - ww / 2f
        val y0 = TUNNEL_VANISH_Y * vh - wh / 2f
        val numbers = Regex("-?[0-9.]+").findAll(window.android("pathData")).map { it.value.toFloat() }.toList()
        // M x0+r,y0 h(w-2r) a r,r 0 0 1 r,r v(h-2r) ...
        assertEquals(x0 + r, numbers[0], 0.01f)
        assertEquals(y0, numbers[1], 0.01f)
        assertEquals(ww - 2 * r, numbers[2], 0.01f)
        assertEquals(r, numbers[3], 0.01f)
        assertEquals(wh - 2 * r, numbers[10], 0.01f)
        // Its fade over the room is the span the curtain and onLightsDown wait out.
        val fade = doc.getElementsByTagName("target").let { targets ->
            (0 until targets.length).map { targets.item(it) as Element }.first { it.android("name") == "tunnel" }
        }.getElementsByTagName("objectAnimator").item(0) as Element
        assertEquals(FlickMotion.TUNNEL_FADE_IN_MS, fade.android("duration").toLong())
        assertEquals("alpha", fade.android("propertyName"))
        assertEquals("0", vector.android("alpha"))
    }

    @Test fun theTunnelsPaletteMatchesItsTokens() {
        val colours = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(TUNNEL_COLOURS)
            .getElementsByTagName("color").let { list ->
                (0 until list.length).associate { i ->
                    val e = list.item(i) as Element
                    e.getAttribute("name") to e.textContent.trim().removePrefix("#").toLong(16).toInt()
                }
            }
        val tokens = mapOf(
            "tunnel_wash_core" to FlickColor.TunnelWashCore,
            "tunnel_wash_mid" to FlickColor.TunnelWashMid,
            "tunnel_wash_edge" to FlickColor.TunnelWashEdge,
            "tunnel_glow_core" to FlickColor.TunnelGlowCore,
            "tunnel_glow_mid" to FlickColor.TunnelGlowMid,
            "tunnel_glow_edge" to FlickColor.TunnelGlowEdge,
            "tunnel_ring" to FlickColor.TunnelRing,
            "tunnel_amber" to FlickColor.TunnelAmber,
            "tunnel_streak_white" to FlickColor.TunnelStreakWhite,
            "tunnel_streak_blue" to FlickColor.TunnelStreakBlue,
            "tunnel_streak_amber" to FlickColor.TunnelAmber,
            "tunnel_window" to FlickColor.TunnelWindow,
        )
        assertEquals(tokens.keys, colours.keys)
        for ((name, token) in tokens) {
            assertEquals("$name drifted from its token", token.toArgb(), colours.getValue(name))
        }
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

    private fun Element.android(attr: String): String = getAttributeNS(ANDROID_NS, attr)

    private fun org.w3c.dom.Document.byName(tag: String, name: String): Element {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }.first { it.android("name") == name }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val STAGE_DP_WIDTH = 960f
        val TUNNEL_XML = File("src/main/res/drawable/tunnel_loop.xml")
        val TUNNEL_COLOURS = File("src/main/res/values/colors_tunnel.xml")
    }
}

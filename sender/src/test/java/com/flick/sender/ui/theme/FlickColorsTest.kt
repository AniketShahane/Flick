package com.flick.sender.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The structural rules a Flick palette has to obey, in both themes, stated as arithmetic
 * rather than as taste.
 *
 * Dark mode broke every one of these by being an alias for the cinematic set, and none of
 * it showed up as a failure anywhere: a raised surface DARKER than the surface it is
 * raised over is a perfectly valid pair of colours, and on a canvas where drop shadows
 * render nothing it is also a card with no edge at all. That is the kind of defect only a
 * measurement catches — it looks like a flat app, not like a bug.
 */
class FlickColorsTest {

    private val palettes = listOf("light" to LightFlickColors, "dark" to DarkFlickColors)

    /**
     * Raised means lighter, in both themes. Light mode gets this for free — white cards on
     * an off-white canvas — and dark mode is where the direction can silently invert.
     */
    @Test fun aRaisedSurfaceIsLighterThanTheSurfaceItIsRaisedOver() {
        for ((name, c) in palettes) {
            assertTrue(
                "$name: surfaceRaised ${c.surfaceRaised.hex()} is not lighter than surface " +
                    "${c.surface.hex()} — a card drawn on it reads as a hole, not a card",
                c.surfaceRaised.luminance() > c.surface.luminance(),
            )
            assertTrue(
                "$name: surfaceRaised is not lighter than canvas ${c.canvas.hex()}",
                c.surfaceRaised.luminance() > c.canvas.luminance(),
            )
        }
    }

    /**
     * …and by enough to see. The floor is the light set's own canvas-to-white step, which
     * is the smallest separation this app has ever shipped and still reads as an edge.
     *
     * Dark has to clear it by more, not less: the light theme also carries a tinted drop
     * shadow under its cards, and a dark shadow on a near-black canvas draws nothing, so
     * on dark the tonal step is the ONLY thing separating a card from the page.
     */
    @Test fun theElevationStepIsBigEnoughToRead() {
        val floor = contrast(LightFlickColors.canvas, LightFlickColors.surfaceRaised)
        assertTrue("the light step moved; retune the floor deliberately", floor > 1.05f)
        val darkStep = contrast(DarkFlickColors.canvas, DarkFlickColors.surfaceRaised)
        assertTrue(
            "dark canvas-to-raised is $darkStep, under the light theme's own $floor — and dark " +
                "has no visible shadow to make up the difference",
            darkStep >= floor,
        )
    }

    /** A tonal container has to read as a control rather than as one more surface. */
    @Test fun theTonalContainerStandsOffTheSurfaceItSitsOn() {
        for ((name, c) in palettes) {
            val step = contrast(c.surfaceRaised, c.primaryContainer)
            assertTrue(
                "$name: primaryContainer ${c.primaryContainer.hex()} is $step from surfaceRaised — " +
                    "the folder chip and every tonal button disappear into the card behind them",
                step >= 1.2f,
            )
        }
    }

    /**
     * Every surface a palette paints under [FlickTheme], including `fillCard` composited
     * over both of the surfaces it is laid on — which are the ones no palette listing
     * shows, because those colours do not exist until they are drawn.
     *
     * `fillControl`, `fillTrack` and the rest of the fills are absent because nothing
     * under this theme paints them: they belong to the transport and the Now-Playing
     * screen, which are forced cinematic. Asserting an ink against a surface the app never
     * puts it on is a claim that costs a real design decision to satisfy and buys nothing
     * — it is what first pushed the dark action blue a step paler than the brand.
     */
    private fun FlickColors.surfaces() = listOf(
        "canvas" to canvas,
        "surface" to surface,
        "surfaceRaised" to surfaceRaised,
        "surfaceRaisedAlt" to surfaceRaisedAlt,
        "fillCard over surfaceRaised" to fillCard.over(surfaceRaised),
        "fillCard over canvas" to fillCard.over(canvas),
    )

    private fun FlickColors.inks() = listOf(
        "onSurface" to onSurface,
        "onSurfaceDim" to onSurfaceDim,
        "onSurfaceFaint" to onSurfaceFaint,
        "primary" to primary,
        "trouble" to trouble,
    )

    /**
     * The dark set clears 4.5:1 for every ink role on every surface, composited fills
     * included. This is the palette this change is responsible for, so it is held to the
     * real floor with nothing carved out.
     */
    @Test fun everyDarkInkRoleClearsFourAndAHalfOnEverySurface() {
        for ((sName, s) in DarkFlickColors.surfaces()) {
            for ((iName, i) in DarkFlickColors.inks()) {
                val ratio = contrast(i, s)
                assertTrue("dark: $iName on $sName is $ratio, under 4.5:1", ratio >= 4.5f)
            }
        }
    }

    /**
     * The light set does not meet that floor everywhere, and did not before this change
     * either. Its worst pairs are `onSurfaceFaint` at 3.29:1 and `trouble` at 4.10:1, both
     * on a `fillCard` over the canvas — under 4.5 for text at this app's body sizes.
     *
     * Not quietly corrected here: light is the theme the product owner is happy with and
     * is not what this change is for, and moving an anchored ink to satisfy a test is how
     * a palette drifts. So this is a RATCHET rather than a standard — every role is pinned
     * just under where it stands today, so the shortfall can be closed but cannot deepen,
     * and the two numbers above are written down where the next person to open the light
     * theme will find them.
     *
     * The pairs are the cross product of the roles and the surfaces above, which is a
     * wider claim than the app makes today: it includes ink on surfaces that ink may not
     * currently be drawn on. That is deliberate for a ratchet — a component that starts
     * drawing one of those pairs then cannot introduce a regression silently.
     */
    @Test fun theLightSetNeverDropsBelowWhereItStandsToday() {
        val floors = mapOf(
            "onSurface" to 14.0f,
            "onSurfaceDim" to 5.3f,
            "onSurfaceFaint" to 3.25f,
            "primary" to 5.6f,
            "trouble" to 4.05f,
        )
        for ((sName, s) in LightFlickColors.surfaces()) {
            for ((iName, i) in LightFlickColors.inks()) {
                val ratio = contrast(i, s)
                assertTrue(
                    "light: $iName on $sName is $ratio, below the ${floors.getValue(iName)} this " +
                        "role holds today — the light palette regressed",
                    ratio >= floors.getValue(iName),
                )
            }
        }
    }

    /** The ink a filled control carries has to hold up on the fill. */
    @Test fun theInkOnAFilledControlClearsFourAndAHalf() {
        for ((name, c) in palettes) {
            assertTrue(
                "$name: onPrimary on primary is ${contrast(c.onPrimary, c.primary)}",
                contrast(c.onPrimary, c.primary) >= 4.5f,
            )
            assertTrue(
                "$name: onPrimaryContainer on primaryContainer is " +
                    "${contrast(c.onPrimaryContainer, c.primaryContainer)}",
                contrast(c.onPrimaryContainer, c.primaryContainer) >= 4.5f,
            )
            assertTrue(
                "$name: onPrimaryFixed on primaryFixed is " +
                    "${contrast(c.onPrimaryFixed, c.primaryFixed)}",
                contrast(c.onPrimaryFixed, c.primaryFixed) >= 4.5f,
            )
        }
    }

    /**
     * The ink ramp keeps its order. Three roles that have crossed over are three roles
     * that no longer mean anything, and the crossing is invisible in a palette listing.
     */
    @Test fun theInkRampIsMonotonic() {
        for ((name, c) in palettes) {
            val toward = if (c.isLight) -1f else 1f
            val ramp = listOf(c.onSurface, c.onSurfaceDim, c.onSurfaceFaint).map { it.luminance() * toward }
            assertTrue(
                "$name: onSurface/onSurfaceDim/onSurfaceFaint are not in decreasing weight — $ramp",
                ramp.zipWithNext().all { (a, b) -> a > b },
            )
        }
    }

    /**
     * Dark mode is the same product as light mode, not a navy one.
     *
     * The measure is chroma relative to the surface's own brightness: the light canvas
     * spends 5% of its brightest channel on colour, and the cinematic stops this set
     * replaced spent about 80%, which is what made dark mode read as a different app
     * rather than as the same app at night. Dark legitimately carries more than light —
     * there is more room for it down there — but there is a difference between a tint and
     * a wash, and this is where it sits.
     */
    @Test fun theDarkSurfacesAreTintedRatherThanSaturated() {
        val surfaces = with(DarkFlickColors) {
            listOf("canvas" to canvas, "surfaceRaised" to surfaceRaised, "surfaceRaisedAlt" to surfaceRaisedAlt)
        }
        for ((name, s) in surfaces) {
            val channels = listOf(s.red, s.green, s.blue)
            val chroma = (channels.max() - channels.min()) / channels.max()
            assertTrue("dark $name is ${(chroma * 100).toInt()}% chroma — that is a navy, not a tint", chroma < 0.6f)
        }
    }

    // --- WCAG 2.x relative luminance and contrast, on straight sRGB ---

    private fun Color.luminance(): Float {
        fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * lin(red) + 0.7152f * lin(green) + 0.0722f * lin(blue)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** A translucent fill composited over an opaque surface — the colour actually drawn. */
    private fun Color.over(base: Color) = Color(
        red = red * alpha + base.red * (1f - alpha),
        green = green * alpha + base.green * (1f - alpha),
        blue = blue * alpha + base.blue * (1f - alpha),
    )

    private fun Color.hex() =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}

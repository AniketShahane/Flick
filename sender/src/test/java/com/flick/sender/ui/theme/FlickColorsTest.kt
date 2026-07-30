package com.flick.sender.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt

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
            val chroma = s.relativeChroma()
            assertTrue("dark $name is ${(chroma * 100).toInt()}% chroma — that is a navy, not a tint", chroma < 0.6f)
        }
    }

    // --- the floating chrome: the nav pill and the Now-Playing dock ---

    /**
     * `glass` is the one surface role a palette listing cannot state honestly. It is
     * translucent, so the colour actually drawn does not exist until it is drawn over
     * something — and that is how it stayed a grey slate through an entire dark-mode
     * retune with nothing failing. Every rule above reads an opaque role; not one of them
     * could see this.
     *
     * What it is drawn over is a scrolling poster grid, so each rule below is measured
     * twice: over the page, and over the most extreme still that can be behind it. That
     * extreme is white under a dark glass and black under a light one — in each case the
     * backdrop that drags the fill TOWARD the ink standing on it.
     */
    private fun FlickColors.glassOnPage() = glass.over(canvas)

    private fun FlickColors.glassOnExtremeStill() =
        glass.over(if (isLight) Color.Black else Color.White)

    /**
     * …and the sheen laid over the glass is part of the glass, which this file learned the
     * hard way. [FlickGradients.navSheenDark] runs DOWN the pill and closes on a pale blue
     * stop, so the bar's labels sit in the interpolating tail of it rather than on bare
     * glass. Measuring the ink against the fill alone put `onSurfaceDim` at 4.88:1 while the
     * device drew it at 4.31:1 — a label under the floor, passing.
     *
     * The two fractions are where the bar's own layout puts things, as a share of its height:
     * an icon spans roughly 21-51% down (11 dp row padding, 6 dp box padding, a 24 dp glyph)
     * and a label sits at roughly 73%. So the icon is caught in the white opening stop and
     * the label in the blue closing one, and they need separate figures.
     */
    /** Where a label's glyphs sit down the pill, as a share of its height. */
    private val LABEL_DEPTH = 0.73f

    /** Where the top of an icon sits — the worst point for it, being the palest. */
    private val ICON_DEPTH = 0.21f

    private fun FlickColors.sheenedAtLabel(backdrop: Color): Color {
        // Read from the real stop position rather than a copy of it, so moving the wash in
        // the gradient moves this with it — the two drifting apart is the whole defect.
        val share = ((LABEL_DEPTH - NavSheenFootStart) / (1f - NavSheenFootStart)).coerceAtLeast(0f)
        return NavSheenDarkFoot.copy(alpha = NavSheenDarkFoot.alpha * share).over(glass.over(backdrop))
    }

    private fun FlickColors.sheenedAtIcon(backdrop: Color): Color {
        // Between the 0x2E opening stop and the 0x0A stop at 0.44, both plain white.
        val opening = 0x2E / 255f
        val mid = 0x0A / 255f
        val alpha = opening + (ICON_DEPTH / 0.44f) * (mid - opening)
        return Color.White.copy(alpha = alpha).over(glass.over(backdrop))
    }

    /** The colour a surface carries, in channel steps out of 255. A grey scores 0. */
    private fun Color.channelSpread(): Int {
        val channels = listOf(red, green, blue)
        return ((channels.max() - channels.min()) * 255f).roundToInt()
    }

    /**
     * Both themes' floating chrome carries the brand. This is the rule whose absence let
     * the dark pill ship as a grey: it measured 28 channel steps against the light glass's
     * 48 and nothing anywhere failed. Both now sit at 48.
     */
    @Test fun theFloatingGlassCarriesTheBrandInBothThemes() {
        for ((name, c) in palettes) {
            val spread = c.glass.channelSpread()
            assertTrue(
                "$name: glass ${c.glass.hex()} carries $spread channel steps of colour — the nav " +
                    "pill and the Now-Playing dock float over every surface this app has, and " +
                    "this is the one role that reads as a grey slate rather than as Flick",
                spread >= 40,
            )
        }
    }

    /**
     * …and stays the quieter of the two materials, because the travelling selection fill is
     * drawn ON it. Light mode is unambiguous about which one is loud: a 19% glass under a
     * 92% fill.
     *
     * Luminance contrast alone does not catch the inversion — a candidate for this change
     * cleared every ratio in this file at 76% chroma, and would have put a saturated blue
     * fill on a more saturated blue pill.
     */
    @Test fun theGlassStaysQuieterThanTheFillThatTravelsOnIt() {
        for ((name, c) in palettes) {
            assertTrue(
                "$name: glass is ${(c.glass.relativeChroma() * 100).toInt()}% chroma against a " +
                    "${(c.primary.relativeChroma() * 100).toInt()}% selection fill — the fill has " +
                    "to be the loud one, or the nav bar reads as blue on blue",
                c.glass.relativeChroma() < c.primary.relativeChroma(),
            )
        }
    }

    /**
     * The dark chrome separates from every surface it floats over, and has to carry that
     * separation on its own tone. A tinted drop shadow is how light mode makes a pale page
     * darker underneath a pill, and on a near-black canvas there is nothing darker left to
     * make — which is why 1.10:1 over `surfaceRaisedAlt` read as a pill lying on the page
     * rather than floating above it.
     */
    @Test fun theDarkGlassSeparatesFromEverySurfaceItFloatsOver() {
        val c = DarkFlickColors
        val drawn = c.glassOnPage()
        val surfaces = listOf(
            "canvas" to c.canvas,
            "surface" to c.surface,
            "surfaceRaised" to c.surfaceRaised,
            "surfaceRaisedAlt" to c.surfaceRaisedAlt,
        )
        for ((name, s) in surfaces) {
            val step = contrast(drawn, s)
            assertTrue(
                "dark: the drawn glass ${drawn.hex()} is $step from $name ${s.hex()} — the pill " +
                    "and the dock stop reading as floating chrome",
                step >= 1.30f,
            )
        }
    }

    /**
     * Every ink the two glass components put on it, held on the page AND over the worst
     * still. `onSurface` and `onSurfaceDim` are the nav labels and the dock's title and
     * subtitle, so they are text at 4.5; `spark` is the amber cast mark, a graphic at 3.
     */
    @Test fun everyInkOnTheDarkGlassHoldsWhateverIsBehindIt() {
        val c = DarkFlickColors
        val backdrops = listOf("the page" to c.canvas, "a blown-out still" to Color.White)
        val inks = listOf(
            Triple("onSurface", c.onSurface, 4.5f),
            Triple("onSurfaceDim", c.onSurfaceDim, 4.5f),
            Triple("spark", c.spark, 3.0f),
        )
        for ((bName, backdrop) in backdrops) {
            for ((iName, i, floor) in inks) {
                // The label row, under the sheen's closing stop — this is the figure the
                // device actually draws, and the one that was missing.
                val onLabel = contrast(i, c.sheenedAtLabel(backdrop))
                assertTrue(
                    "dark: $iName on the glass at the LABEL row over $bName is $onLabel, under " +
                        "$floor:1 — the sheen's closing stop lands on the labels, so the bare " +
                        "glass figure is not the one that matters",
                    onLabel >= floor,
                )
                // The icon row sits in the white opening stop instead. An icon is a
                // graphical object, so 3:1 is its floor rather than 4.5.
                val onIcon = contrast(i, c.sheenedAtIcon(backdrop))
                assertTrue(
                    "dark: $iName on the glass at the ICON row over $bName is $onIcon, under 3:1",
                    onIcon >= 3.0f,
                )
            }
        }
    }

    /** …and the selection fill reads as an object on the glass, not a lighter patch of it. */
    @Test fun theTravellingFillReadsAsAnObjectOnTheDarkGlass() {
        val step = contrast(DarkFlickColors.primary, DarkFlickColors.glassOnPage())
        assertTrue(
            "dark: the nav selection fill is $step from the glass it travels across, under the " +
                "3:1 a UI component needs against its own background",
            step >= 3.0f,
        )
    }

    /**
     * Light's chrome is a RATCHET rather than a standard, on the same grounds as
     * [theLightSetNeverDropsBelowWhereItStandsToday]: its worst pair is `onSurfaceDim` at
     * 4.29:1 over a black still, under the floor for text, and light is not the theme this
     * change is for. Pinned just under where it stands so the gap can close but not deepen.
     */
    @Test fun theLightGlassNeverDropsBelowWhereItStandsToday() {
        val c = LightFlickColors
        val backdrops = listOf("the page" to c.glassOnPage(), "a black still" to c.glassOnExtremeStill())
        for ((name, b) in backdrops) {
            assertTrue(
                "light: onSurface on the glass over $name is ${contrast(c.onSurface, b)}",
                contrast(c.onSurface, b) >= 11.3f,
            )
            assertTrue(
                "light: onSurfaceDim on the glass over $name is ${contrast(c.onSurfaceDim, b)} — " +
                    "the light glass regressed",
                contrast(c.onSurfaceDim, b) >= 4.25f,
            )
        }
    }

    // --- WCAG 2.x relative luminance and contrast, on straight sRGB ---

    /** Chroma as a share of the surface's own brightest channel. */
    private fun Color.relativeChroma(): Float {
        val channels = listOf(red, green, blue)
        return (channels.max() - channels.min()) / channels.max()
    }

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

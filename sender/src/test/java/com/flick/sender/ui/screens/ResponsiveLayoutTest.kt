package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsiveLayoutTest {
    @Test
    fun compactHeightTargetsShortLandscapeWindowsWithoutChangingPortraitLayouts() {
        assertTrue(isCompactHeight(384))
        assertFalse(isCompactHeight(480))
        assertFalse(isCompactHeight(800))
    }

    @Test
    fun compactWidthShortensDenseHeadersOnlyOnNarrowPhones() {
        assertTrue(isCompactWidth(360))
        assertFalse(isCompactWidth(380))
        assertFalse(isCompactWidth(411))
    }

    @Test
    fun posterNeverLeavesItsBandForAnyViewportOrTypeScale() {
        // The band is what keeps the poster elastic and still a poster: no viewport,
        // however tall or short, and no type scale may push the figure outside it.
        val viewports = listOf(0, 1, 120, 300, 479, 480, 694, 900, 2400)
        val scales = listOf(0.85f, 1f, 1.15f, 1.3f, 2f)
        for (viewport in viewports) {
            for (scale in scales) {
                val poster = remoteHeightPlan(viewport, scale).posterHeightDp
                assertTrue("$viewport dp @ ${scale}x -> $poster", poster >= 72)
                assertTrue("$viewport dp @ ${scale}x -> $poster", poster <= 192)
            }
        }
    }

    @Test
    fun posterGivesBackHeightAsTheViewportShrinksAndTheTypeScaleGrows() {
        val roomy = remoteHeightPlan(viewportHeightDp = 694, fontScale = 1f)
        val largeType = remoteHeightPlan(viewportHeightDp = 694, fontScale = 1.3f)
        val shortWindow = remoteHeightPlan(viewportHeightDp = 520, fontScale = 1f)
        assertTrue(largeType.posterHeightDp < roomy.posterHeightDp)
        assertTrue(shortWindow.posterHeightDp < roomy.posterHeightDp)
    }

    @Test
    fun crampedViewportsDropIntoTheCompactPosterBand() {
        // 200 dp of body sits under every ceiling, so the figure is the compact band's
        // own floor — not the roomy band's, which would be half the window.
        assertEquals(72, remoteHeightPlan(viewportHeightDp = 200, fontScale = 1f).posterHeightDp)
        // The first roomy viewport: the same share, clamped by the roomy band instead.
        assertEquals(115, remoteHeightPlan(viewportHeightDp = 480, fontScale = 1f).posterHeightDp)
    }

    @Test
    fun spacingTightensBeforeTheStackIsAskedToScroll() {
        val phone = remoteHeightPlan(viewportHeightDp = 694, fontScale = 1f)
        assertFalse(phone.dense)

        // Same window, larger type: every control in the stack grows with it, so the
        // gaps close before the body has to give anything up.
        val largeType = remoteHeightPlan(viewportHeightDp = 694, fontScale = 1.15f)
        assertTrue(largeType.dense)
        assertTrue(largeType.gapDp < phone.gapDp)
        assertTrue(largeType.captionGapDp < phone.captionGapDp)
        assertTrue(largeType.clusterGapDp < phone.clusterGapDp)
        assertTrue(remoteHeightPlan(viewportHeightDp = 300, fontScale = 1f).dense)
    }

    /**
     * The owner's phone, measured: 1440×3120 at density 600 — 384 × 832 dp, font scale 1,
     * gesture navigation. The stack's cost is the screen's own arithmetic, not a copy of
     * it: grow any control the remote lays out, or the spacing it chose, and this is the
     * promise that gives way — the segmented row and the stop control on screen before
     * anybody scrolls. The body still scrolls unconditionally; this is what keeps it from
     * having to.
     */
    @Test
    fun theBottomClusterClearsTheFoldOnTheReferencePhone() {
        // 832 screen − 40 status bar − 24 gesture bar − 8 top pad − 22 bottom pad
        // − 48 top row = the box BoxWithConstraints hands the scrolled body.
        val viewport = 690
        val plan = remoteHeightPlan(viewportHeightDp = viewport, fontScale = 1f)
        assertFalse(plan.dense)
        assertFalse(plan.reservePreview)

        // Two lines, because that is what a real filename usually takes — and it is the
        // taller of the two cases, so it carries the one-line title with it.
        val stack = remoteStackCostDp(plan, titleLines = 2)
        assertTrue("$stack dp of stack in a $viewport dp body", stack <= viewport)
    }

    @Test
    fun onlyABodyShortEnoughToClipThePreviewReservesHeadroomForIt() {
        // Scrolled to the foot, the bar still sits the whole transport region above the
        // bottom edge, so a roomy body can never carry it into the top 96 dp where the
        // preview would be cut — and the 104 dp reservation would be blank the user
        // scrolls through on every remote.
        assertFalse(remoteHeightPlan(viewportHeightDp = 694, fontScale = 1f).reservePreview)
        assertTrue(remoteHeightPlan(viewportHeightDp = 420, fontScale = 1f).reservePreview)
        // Large type grows the stack the bar has to clear, so the same body reserves.
        assertTrue(remoteHeightPlan(viewportHeightDp = 694, fontScale = 1.5f).reservePreview)
    }

    @Test
    fun aSmallTypeScaleNeverBuysThePosterMoreThanTheDesignAllows() {
        assertEquals(
            remoteHeightPlan(viewportHeightDp = 694, fontScale = 1f).posterHeightDp,
            remoteHeightPlan(viewportHeightDp = 694, fontScale = 0.85f).posterHeightDp,
        )
    }
}

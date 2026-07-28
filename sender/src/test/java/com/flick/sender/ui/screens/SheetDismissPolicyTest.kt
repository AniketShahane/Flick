package com.flick.sender.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions a dragged sheet makes, in pixels, at a plausible 2.5x density:
 * 96 dp of floor is 240 px and 500 dp/s of flick is 1250 px/s.
 */
class SheetDismissPolicyTest {
    private val tallSheet = 900f
    private val shortSheet = 300f
    private val minTravel = 240f
    private val fling = 1250f

    private fun dismissed(travel: Float, velocity: Float, height: Float) =
        sheetDismissedByDrag(travel, velocity, height, minTravel, fling)

    @Test
    fun aReleaseWithNoSpeedInItIsDecidedByDistance() {
        // 35% of a 900 px sheet is 315 px, which clears the floor and therefore decides.
        assertFalse(dismissed(travel = 300f, velocity = 0f, height = tallSheet))
        assertTrue(dismissed(travel = 315f, velocity = 0f, height = tallSheet))
        assertFalse(dismissed(travel = 0f, velocity = 0f, height = tallSheet))
    }

    @Test
    fun aShortSheetStillHasToBeDraggedAsFarAsTheFloor() {
        // 35% of a 300 px sheet is 105 px — a nudge. The floor is what applies.
        assertFalse(dismissed(travel = 120f, velocity = 0f, height = shortSheet))
        assertTrue(dismissed(travel = 240f, velocity = 0f, height = shortSheet))
    }

    @Test
    fun aFlickDownLetsGoFromAnywhereAndAFlickBackUpKeepsIt() {
        assertTrue(dismissed(travel = 20f, velocity = 1250f, height = tallSheet))
        // …but a flick has to have moved the sheet at all.
        assertFalse(dismissed(travel = 0f, velocity = 4000f, height = tallSheet))
        // Speed outranks distance in both directions: most of the way down, thrown back.
        assertFalse(dismissed(travel = 800f, velocity = -1250f, height = tallSheet))
        assertTrue(dismissed(travel = 800f, velocity = -1249f, height = tallSheet))
    }

    @Test
    fun theScrimLightensWithTheSheetAndIsGoneWhenTheSheetIs() {
        assertEquals(0.6f, sheetScrimAlpha(fade = 0.6f, travelPx = 0f, heightPx = 900f), 1e-4f)
        assertEquals(0.3f, sheetScrimAlpha(fade = 0.6f, travelPx = 450f, heightPx = 900f), 1e-4f)
        assertEquals(0f, sheetScrimAlpha(fade = 0.6f, travelPx = 900f, heightPx = 900f), 1e-4f)
        // A spring that rings past the end must not drive the alpha back up.
        assertEquals(0f, sheetScrimAlpha(fade = 0.6f, travelPx = 1400f, heightPx = 900f), 1e-4f)
        // The entrance's own fade still runs on the frames before the sheet is measured.
        assertEquals(0.42f, sheetScrimAlpha(fade = 0.42f, travelPx = 0f, heightPx = 0f), 1e-4f)
    }
}

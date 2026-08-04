package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityTest {

    @Test fun lumaIsWeightedTheWayAnEyeIs() {
        assertEquals(255, lumaOf(WHITE))
        assertEquals(0, lumaOf(BLACK))
        assertTrue(lumaOf(GREEN) > lumaOf(RED))
        assertTrue(lumaOf(RED) > lumaOf(BLUE))
    }

    @Test fun lumaIgnoresAlpha() {
        // A frame decoded without an alpha channel and one decoded with a full one are the
        // same picture, and a judgement that disagreed about them would be a judgement of
        // the decoder rather than of the film.
        assertEquals(lumaOf(WHITE), lumaOf(WHITE and 0x00FFFFFF))
        assertEquals(lumaOf(GREEN), lumaOf(GREEN and 0x00FFFFFF))
    }

    @Test fun aFadeToBlackIsBlank() {
        assertTrue(frameStats(flat(0)).blank)
        assertTrue(frameStats(flat(4)).blank)
    }

    @Test fun aFlatFrameIsBlankAtEveryBrightness() {
        // The failure this exists for is black, but a slate and a blown-out flash are the
        // same absence of a picture and neither belongs on a tile.
        assertTrue(frameStats(flat(128)).blank)
        assertTrue(frameStats(flat(255)).blank)
    }

    @Test fun aLogoOnBlackIsStillBlank() {
        // The distributor card a film opens on: a few lit samples in an otherwise dead
        // frame clear the uniformity floor and come nowhere near the brightness one.
        val logo = IntArray(SAMPLES) { if (it < 6) 200 else 0 }
        assertTrue(frameStats(logo).blank)
    }

    @Test fun aLitSceneIsNotBlank() {
        assertFalse(frameStats(alternating(40, 200)).blank)
    }

    @Test fun aDarkSceneWithDetailIsNotBlank() {
        // A night exterior is the case both floors are set low for: dim, but a picture.
        assertFalse(frameStats(alternating(6, 40)).blank)
    }

    @Test fun eachFloorIsTheOneThatCatchesItsOwnFailure() {
        // Mean 12, deviation 6 — the first frame that clears both.
        assertFalse(frameStats(alternating(6, 18)).blank)
        // Same deviation, a shade darker: caught by brightness alone.
        assertTrue(frameStats(alternating(5, 17)).blank)
        // Same brightness, a shade flatter: caught by uniformity alone.
        assertTrue(frameStats(alternating(7, 17)).blank)
    }

    @Test fun noSamplesAtAllJudgeAsBlank() {
        assertTrue(frameStats(IntArray(0)).blank)
        assertEquals(0, frameStats(IntArray(0)).meanLuma)
        assertEquals(0, frameStats(IntArray(0)).spread)
    }

    @Test fun contrastOutscoresBrightness() {
        // What the score decides is which frame to keep when the whole search came back
        // blank. A blown-out one must never win that on brightness alone.
        assertTrue(frameStats(alternating(80, 160)).score > frameStats(alternating(250, 255)).score)
        assertTrue(frameStats(alternating(40, 200)).score > frameStats(alternating(110, 130)).score)
    }

    @Test fun aLitFrameOutscoresTheSameFrameInTheDark() {
        // Equal contrast, and the brighter of the two is the better tile.
        assertTrue(frameStats(alternating(60, 140)).score > frameStats(alternating(0, 80)).score)
    }

    @Test fun theStatsAreTheMeanAndTheMeanDeviationFromIt() {
        val stats = frameStats(alternating(40, 200))
        assertEquals(120, stats.meanLuma)
        assertEquals(80, stats.spread)
    }

    private fun flat(value: Int) = IntArray(SAMPLES) { value }

    private fun alternating(low: Int, high: Int) = IntArray(SAMPLES) { if (it % 2 == 0) low else high }

    private companion object {
        // The whole sampling grid: an even count, so an alternating fixture is exactly half
        // of each value.
        const val SAMPLES = SampleRows * SampleColumns

        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}

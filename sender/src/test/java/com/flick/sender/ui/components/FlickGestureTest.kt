package com.flick.sender.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickGestureTest {

    @Test
    fun aBeatIsClampedAtBothEndsOfItsOwnWindow() {
        assertEquals(0f, flickBeat(0f, 300, 540), EPS)
        assertEquals(0f, flickBeat(300f, 300, 540), EPS)
        assertEquals(0.5f, flickBeat(420f, 300, 540), EPS)
        assertEquals(1f, flickBeat(540f, 300, 540), EPS)
        assertEquals(1f, flickBeat(2400f, 300, 540), EPS)
    }

    @Test
    fun aBeatRetimedToNothingIsOverRatherThanUndefined() {
        // The windows are absolute constants that get edited by hand; a from == to pair is
        // a division by zero on the draw path, which is a NaN in a transform matrix.
        assertEquals(0f, flickBeat(499f, 500, 500), EPS)
        assertEquals(1f, flickBeat(500f, 500, 500), EPS)
        assertEquals(1f, flickBeat(900f, 500, 400), EPS)
    }

    @Test
    fun theLoopPointIsTheRestPose() {
        // The cycle restarts by snapping the clock back to zero, so anything whose value at
        // the end of the cycle differs from its value at the start is a visible jump on
        // every repetition.
        val end = FlickCycleMs.toFloat()
        assertEquals(thumbTravel(0f), thumbTravel(end), EPS)
        assertEquals(0f, thumbTravel(end), EPS)
        assertEquals(cardSquash(0f), cardSquash(end), EPS)
        assertEquals(0f, cardSquash(end), EPS)
        assertEquals(0f, liftProgress(0f), EPS)
    }

    @Test
    fun theDeckRolesRotateExactlyOnceAcrossTheCycle() {
        // Three cards, one seat: the flyer leaves, the riser takes the seat, and a new card
        // fades in at the back. The loop only closes if each one ends the cycle in the pose
        // the next one starts it in.
        val end = FlickCycleMs.toFloat()
        assertEquals(flyerAlpha(0f), riserAlpha(end), EPS)
        assertEquals(riserAlpha(0f), dealAlpha(end), EPS)
        assertEquals(dealAlpha(0f), flyerAlpha(end), EPS)
        // …and the poses that go with those alphas: the riser is at the deck when the
        // cycle opens and in the seat when it closes.
        assertEquals(0f, riserProgress(0f), EPS)
        assertEquals(1f, riserProgress(end), EPS)
        assertEquals(1f, flyerAlpha(0f), EPS)
        assertEquals(0f, flyerAlpha(end), EPS)
    }

    @Test
    fun theWakeIsGoneAtBothEndsOfTheCycle() {
        val end = FlickCycleMs.toFloat()
        for (index in 0..2) {
            assertEquals("bar $index", 0f, barAlpha(barLife(0f, index)), EPS)
            assertEquals("bar $index", 0f, barAlpha(barLife(end, index)), EPS)
            assertEquals("bar $index", 0f, barLength(barLife(0f, index)), EPS)
        }
        // A bar holds full weight while it is still drawing itself and only then dissolves.
        assertEquals(1f, barAlpha(0.2f), EPS)
        assertTrue(barAlpha(0.8f) < 1f)
    }

    @Test
    fun theThumbWindsBackBeforeItStrikes() {
        // Negative is behind the rest position: the anticipation is what makes the strike
        // read as a flick rather than as a slide.
        assertTrue(thumbTravel(540f) < 0f)
        assertEquals(thumbTravel(540f), thumbTravel(580f), EPS)
        assertTrue(thumbTravel(880f) > 0f)
        assertTrue(thumbTravel(880f) > thumbTravel(700f))
    }

    @Test
    fun theWristLaysBackToLoadAndRollsThroughToRelease() {
        val rest = thumbAngle(0f)
        assertTrue(thumbAngle(-1f) > rest)
        assertTrue(thumbAngle(1f) < rest)
    }

    @Test
    fun theStrikeBowsAboveItsOwnChord() {
        // Zero at both ends of the travel, so the arc never displaces the rest pose, and
        // clamped past full reach — the recovery walks back down the same numbers.
        assertEquals(0f, arcBow(0f), EPS)
        assertEquals(0f, arcBow(1000f), EPS)
        assertTrue(arcBow(thumbTravel(730f)) > 0f)
    }

    @Test
    fun theCardIsCompressedWhileLoadedAndStretchedOnRelease() {
        assertTrue(cardSquash(540f) < 0f)
        assertEquals(cardSquash(540f), cardSquash(620f), EPS)
        assertTrue(cardSquash(700f) > 0f)
    }

    @Test
    fun theCycleEndsInAWholeSecondOfStillness() {
        // The rest hold is what keeps a loop on a route the user is reading from being a
        // fidget. Every beat has to have landed well before the clock wraps.
        val end = FlickCycleMs.toFloat()
        for (ms in listOf(1400f, 1800f, 2200f, end)) {
            assertEquals("$ms", 0f, thumbTravel(ms), EPS)
            assertEquals("$ms", 0f, cardSquash(ms), EPS)
            assertEquals("$ms", 1f, riserAlpha(ms), EPS)
            assertEquals("$ms", dealAlpha(end), dealAlpha(ms), EPS)
            assertEquals("$ms", 0f, flyerAlpha(ms), EPS)
        }
    }
}

private const val EPS = 1e-4f

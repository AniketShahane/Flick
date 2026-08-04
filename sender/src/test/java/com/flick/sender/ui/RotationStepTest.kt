package com.flick.sender.ui

import com.flick.sender.model.VideoRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The walk behind the phone's only orientation control. With no second surface to
 * reach a choice from, every choice has to be reachable from every other AND every
 * press has to turn the picture — so the order of the walk, not the fact of it, is
 * what these test.
 */
class RotationStepTest {

    /** A quarter turn added on every press, the whole way round. */
    @Test fun eachPressAddsAQuarterTurn() {
        assertEquals(VideoRotation.Quarter, nextRotation(VideoRotation.Auto))
        assertEquals(VideoRotation.Half, nextRotation(VideoRotation.Quarter))
        assertEquals(VideoRotation.ThreeQuarter, nextRotation(VideoRotation.Half))
        assertEquals(VideoRotation.AsFiled, nextRotation(VideoRotation.ThreeQuarter))
    }

    /**
     * The full circle hands the picture back to the receiver's own reading. Without
     * this one step the key could take Auto away and never return it, which is the
     * only way a forward-only control can trap a viewer.
     */
    @Test fun theCircleClosesBackOnAuto() {
        assertEquals(VideoRotation.Auto, nextRotation(VideoRotation.AsFiled))
    }

    /**
     * Why the walk is not [VideoRotation.ALL]'s own order, which opens on 0°. A file
     * with no rotation in its container is read as 0° by the receiver, so a first press
     * onto the 0° choice asserts the degrees already in force: nothing is re-prepared,
     * the label steps from AUTO to 0° and the sideways picture stays sideways — on
     * precisely the film the key was pressed about.
     */
    @Test fun thePressOutOfAutoIsNotTheChoiceThatAddsNothing() {
        val addsNothing = VideoRotation.ALL.first { it.extraDegrees == 0 }
        assertNotEquals(addsNothing, nextRotation(VideoRotation.Auto))
    }

    /**
     * The same property everywhere else on the walk, in the terms the receiver decides
     * it in — the extra degrees a choice resolves to, against the ones already in force.
     * Auto is the one choice carrying none of its own, because it is a reading rather
     * than an assertion, and the two pairs it is in are the ones that take that reading
     * away and give it back.
     */
    @Test fun noPressAssertsTheDegreesItStartedFrom() {
        VideoRotation.ALL.forEach { start ->
            val from = start.extraDegrees
            val to = nextRotation(start).extraDegrees
            if (from != null && to != null) assertNotEquals(from, to)
        }
    }

    /** Five presses from anywhere are the whole set — no choice is off the walk. */
    @Test fun everyChoiceIsReachedWithinFivePressesFromAnyStart() {
        VideoRotation.ALL.forEach { start ->
            val walked = generateSequence(start, ::nextRotation)
                .take(VideoRotation.ALL.size)
                .toList()
            assertEquals(VideoRotation.ALL.size, walked.distinct().size)
            assertEquals(VideoRotation.ALL.toSet(), walked.toSet())
        }
    }

    /** And they close: the sixth press is where the first one started. */
    @Test fun fivePressesReturnToWhereTheyStarted() {
        VideoRotation.ALL.forEach { start ->
            var at = start
            repeat(VideoRotation.ALL.size) { at = nextRotation(at) }
            assertEquals(start, at)
        }
    }

    /**
     * Read off the model rather than compared against a copy of it: a choice added to
     * [VideoRotation.ALL] has to be some press's landing place without this test being
     * edited, and no two choices may share one — a set this size out of a list that size
     * is both at once, and one cycle rather than a walk with a strand hanging off it.
     */
    @Test fun everyChoiceIsSomePressesLandingPlace() {
        assertEquals(VideoRotation.ALL.toSet(), VideoRotation.ALL.map(::nextRotation).toSet())
    }
}

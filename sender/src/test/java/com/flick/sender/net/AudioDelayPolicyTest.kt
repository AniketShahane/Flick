package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioDelayPolicyTest {

    @Test fun boundsAndStepAreTheOnesTheWireContractNames() {
        // The receiver validates against these same three and refuses anything outside
        // them, so a change here that the TV has not made costs the nudge entirely — and
        // a refused frame costs the whole control socket, not just the nudge.
        assertEquals(-2_000, AudioDelayPolicy.MIN_MS)
        assertEquals(2_000, AudioDelayPolicy.MAX_MS)
        assertEquals(25, AudioDelayPolicy.STEP_MS)
        assertEquals(0, AudioDelayPolicy.IN_SYNC_MS)
    }

    @Test fun theBoundsThemselvesSitOnTheGridTheReceiverValidates() {
        // The TV refuses a value off the step grid, so a bound that was not itself on the
        // grid would be a bound the phone could clamp to and the TV would then drop.
        assertEquals(0, AudioDelayPolicy.MAX_MS % AudioDelayPolicy.STEP_MS)
        assertEquals(0, AudioDelayPolicy.MIN_MS % AudioDelayPolicy.STEP_MS)
        // Symmetric, which is what puts in-sync on the blade's centre detent rather than
        // somewhere the track has no landmark for.
        assertEquals(AudioDelayPolicy.MAX_MS, -AudioDelayPolicy.MIN_MS)
    }

    @Test fun theRangeFitsTheSmallestBufferTheReceiverWillEverBuild() {
        // A delay claims |delay| of the TV's forward buffer in either direction: positive
        // makes the video renderer read that far ahead of the audio clock, negative keeps
        // that much of the video queue resident behind it. The smallest budget
        // BufferBudgetPolicy will build — its 32 MiB byte floor — holds 2,282 ms of
        // 100 Mbps content, and 96 MB of heap holds 2,738 ms (both pinned by the
        // receiver's own BufferBudgetPolicyTest). A bound above those is a renderer that
        // runs out of samples, which is a stall. Widening past this means re-deriving it
        // from that policy, not raising this number.
        assertTrue(AudioDelayPolicy.MAX_MS <= 2_282)
        assertTrue(-AudioDelayPolicy.MIN_MS <= 2_282)
    }

    @Test fun clampHoldsTheRangeAndTheGrid() {
        assertEquals(2_000, AudioDelayPolicy.clamp(9_000))
        assertEquals(-2_000, AudioDelayPolicy.clamp(-9_000))
        assertEquals(0, AudioDelayPolicy.clamp(0))
        assertEquals(150, AudioDelayPolicy.clamp(150))
        assertEquals(1_500, AudioDelayPolicy.clamp(1_500))
        // A blade is dragged to arbitrary milliseconds; every one of them has to land on
        // a step the two buttons beside it can also reach.
        assertEquals(150, AudioDelayPolicy.clamp(146))
        assertEquals(-150, AudioDelayPolicy.clamp(-146))
        assertEquals(1_325, AudioDelayPolicy.clamp(1_331))
        assertEquals(-1_325, AudioDelayPolicy.clamp(-1_331))
        assertEquals(0, AudioDelayPolicy.clamp(-11))
    }

    @Test fun clampNeverLeavesTheGridEvenAtTheBounds() {
        (-2_500..2_500).forEach { candidate ->
            val clamped = AudioDelayPolicy.clamp(candidate)
            assertTrue("$candidate escaped the range", clamped in AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS)
            assertEquals("$candidate left the grid", 0, clamped % AudioDelayPolicy.STEP_MS)
            // Idempotent, which is what lets the session clamp a value that has already
            // been through here without moving it.
            assertEquals(clamped, AudioDelayPolicy.clamp(clamped))
        }
    }

    @Test fun stepsWalkTheGridAndStopAtTheBounds() {
        assertEquals(25, AudioDelayPolicy.stepUp(0))
        assertEquals(-25, AudioDelayPolicy.stepDown(0))
        assertEquals(2_000, AudioDelayPolicy.stepUp(2_000))
        assertEquals(-2_000, AudioDelayPolicy.stepDown(-2_000))
        // A hundred and sixty presses cross the whole range and the next changes nothing.
        // Nobody travels it that way — that is the blade's job — but the arithmetic has to
        // agree with the blade about where the far end is.
        var value = AudioDelayPolicy.MIN_MS
        repeat(160) { value = AudioDelayPolicy.stepUp(value) }
        assertEquals(AudioDelayPolicy.MAX_MS, value)
        assertEquals(AudioDelayPolicy.MAX_MS, AudioDelayPolicy.stepUp(value))
    }

    @Test fun aPressIsTwentyFiveMillisecondsAtEveryOffsetAndNotOnlyNearZero() {
        // The design decision the wide range rests on: the error a badly muxed film
        // carries can sit anywhere in this range, and the trimming happens around THAT
        // value. A step that coarsened with distance would leave a film 1.3 s out with no
        // way to reach the 25 ms either side of where it actually needs to be.
        listOf(-1_900, -1_500, -525, -50, 0, 50, 525, 1_500, 1_900).forEach { value ->
            assertEquals("stepUp coarsened at $value", value + 25, AudioDelayPolicy.stepUp(value))
            assertEquals("stepDown coarsened at $value", value - 25, AudioDelayPolicy.stepDown(value))
        }
    }

    @Test fun aStepperIsDisabledExactlyWhereItWouldDoNothing() {
        assertFalse(AudioDelayPolicy.canStepUp(AudioDelayPolicy.MAX_MS))
        assertTrue(AudioDelayPolicy.canStepDown(AudioDelayPolicy.MAX_MS))
        assertFalse(AudioDelayPolicy.canStepDown(AudioDelayPolicy.MIN_MS))
        assertTrue(AudioDelayPolicy.canStepUp(AudioDelayPolicy.MIN_MS))
        assertTrue(AudioDelayPolicy.canStepUp(0))
        assertTrue(AudioDelayPolicy.canStepDown(0))
        // The old bound is an ordinary value now, and both steppers work there.
        assertTrue(AudioDelayPolicy.canStepUp(500))
        assertTrue(AudioDelayPolicy.canStepDown(-500))
        // An out-of-range value is answered for the value it will actually become.
        assertFalse(AudioDelayPolicy.canStepUp(9_000))
        assertFalse(AudioDelayPolicy.canStepDown(-9_000))
    }

    @Test fun steppableExactlyWhenTheStepMoves() {
        (AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS step AudioDelayPolicy.STEP_MS).forEach { value ->
            assertTrue(
                "canStepUp disagreed with stepUp at $value",
                AudioDelayPolicy.canStepUp(value) == (AudioDelayPolicy.stepUp(value) != value),
            )
            assertTrue(
                "canStepDown disagreed with stepDown at $value",
                AudioDelayPolicy.canStepDown(value) == (AudioDelayPolicy.stepDown(value) != value),
            )
        }
    }

    @Test fun aMoveInsideTheJumpBoundArrivesWhole() {
        // A stepper press and an ordinary drag sample are already small enough, so they
        // must not be walked — the finger would be answered a frame late for nothing.
        assertEquals(25, AudioDelayPolicy.approach(0, 25))
        assertEquals(-25, AudioDelayPolicy.approach(0, -25))
        assertEquals(100, AudioDelayPolicy.approach(0, 100))
        assertEquals(-100, AudioDelayPolicy.approach(0, -100))
        // A quick drag across a four-second track moves further than 100 ms between two
        // pointer samples; that is the case the jump bound had to grow for.
        assertEquals(200, AudioDelayPolicy.approach(0, 200))
        assertEquals(-200, AudioDelayPolicy.approach(0, -200))
        assertEquals(1_700, AudioDelayPolicy.approach(1_500, 1_700))
        assertEquals(0, AudioDelayPolicy.approach(0, 0))
    }

    @Test fun aLargerMoveIsWalkedAndNeverJumpsFurtherThanTheBound() {
        assertEquals(200, AudioDelayPolicy.approach(0, 2_000))
        assertEquals(-200, AudioDelayPolicy.approach(0, -2_000))
        assertEquals(-1_800, AudioDelayPolicy.approach(-2_000, 2_000))
        // Every hop of the widest move there is stays inside the bound, stays on the
        // grid, and the walk converges rather than orbiting its target.
        var value = AudioDelayPolicy.MIN_MS
        var hops = 0
        while (value != AudioDelayPolicy.MAX_MS) {
            val next = AudioDelayPolicy.approach(value, AudioDelayPolicy.MAX_MS)
            assertTrue("hop $hops jumped ${next - value}", next - value <= AudioDelayPolicy.MAX_JUMP_MS)
            assertEquals(0, next % AudioDelayPolicy.STEP_MS)
            value = next
            hops++
            assertTrue("walk did not converge", hops <= 100)
        }
        assertEquals((AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS) / AudioDelayPolicy.MAX_JUMP_MS, hops)
    }

    @Test fun everyHopFromEveryLegalValueIsBoundedAndConverging() {
        val values = (AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS step AudioDelayPolicy.STEP_MS).toList()
        values.forEach { from ->
            values.forEach { to ->
                val next = AudioDelayPolicy.approach(from, to)
                assertTrue("$from -> $to jumped to $next", abs(next - from) <= AudioDelayPolicy.MAX_JUMP_MS)
                assertTrue("$from -> $to overshot to $next", abs(to - next) < abs(to - from) || from == to)
                assertEquals("$from -> $to left the grid", 0, next % AudioDelayPolicy.STEP_MS)
            }
        }
    }

    @Test fun theJumpBoundIsAWholeNumberOfStepsAndStaysOffTheKeyframeBranch() {
        // Walking by it is what keeps a walked move on the same grid a press lands on.
        assertEquals(0, AudioDelayPolicy.MAX_JUMP_MS % AudioDelayPolicy.STEP_MS)
        // Clear of the half-second at which the receiver's player stops dropping frames
        // one at a time and abandons decoded buffers forward to the next keyframe. That
        // threshold does not move with the range, so the jump bound may not approach it
        // however wide the bounds get.
        assertEquals(200, AudioDelayPolicy.MAX_JUMP_MS)
        assertTrue(AudioDelayPolicy.MAX_JUMP_MS <= 500 / 2)
    }

    @Test fun aFullSpanSlamIsABurstThisChannelIsAlreadySizedFor() {
        // A tap on the far end of the blade from the other end, a Reset from a bound, a
        // screen reader setting the value outright: one frame in, twenty absolute values
        // out. The first hop goes inline, so nineteen waits stand between the finger and
        // the value landing.
        val hops = (AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS) / AudioDelayPolicy.MAX_JUMP_MS
        assertEquals(20, hops)
        assertEquals(40L, AudioDelayPolicy.WALK_INTERVAL_MS)
        assertEquals(760L, (hops - 1) * AudioDelayPolicy.WALK_INTERVAL_MS)
        // The rate is what the control channel cares about, and it is unchanged by the
        // wider range: 25 frames a second, the order the seek throttle already allows.
        assertEquals(25L, 1_000L / AudioDelayPolicy.WALK_INTERVAL_MS)
    }

    @Test fun theFeltGridIsCoarserThanTheGridTheValueMovesOn() {
        assertEquals(100, AudioDelayPolicy.HAPTIC_TICK_MS)
        assertEquals(0, AudioDelayPolicy.HAPTIC_TICK_MS % AudioDelayPolicy.STEP_MS)
        // Four steps of travel inside one tick: the value keeps its 25 ms resolution and
        // the finger keeps the texture it had when the track was a quarter as wide.
        assertEquals(AudioDelayPolicy.tickIndex(0), AudioDelayPolicy.tickIndex(75))
        assertTrue(AudioDelayPolicy.tickIndex(100) != AudioDelayPolicy.tickIndex(75))
        // Floored, not truncated: in-sync is a bucket edge, so arriving at it is felt,
        // and the bucket around it is no wider than any other.
        assertTrue(AudioDelayPolicy.tickIndex(-25) != AudioDelayPolicy.tickIndex(0))
        assertEquals(AudioDelayPolicy.tickIndex(-100), AudioDelayPolicy.tickIndex(-25))
        // A drag from end to end is felt as forty detents — what the ±500 ms track gave,
        // over four times the distance.
        val ticks = (AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS step AudioDelayPolicy.STEP_MS)
            .map(AudioDelayPolicy::tickIndex)
            .zipWithNext()
            .count { (previous, next) -> previous != next }
        assertEquals(40, ticks)
    }

    @Test fun theReadoutSignsTheFigureWithAMinusAndNotAHyphen() {
        assertEquals("0", AudioDelayPolicy.signed(0))
        assertEquals("+150", AudioDelayPolicy.signed(150))
        assertEquals("−150", AudioDelayPolicy.signed(-150))
        assertEquals("+2000", AudioDelayPolicy.signed(2_000))
        assertEquals("−2000", AudioDelayPolicy.signed(-9_000))
        assertEquals("+2000", AudioDelayPolicy.signed(9_000))
        // U+2212, the glyph Format.remaining sets its minus in.
        assertEquals('−', AudioDelayPolicy.signed(-25).first())
    }

    @Test fun theAdjustableStepCountCoversEveryLegalValue() {
        val values = (AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS step AudioDelayPolicy.STEP_MS).count()
        // A screen reader's own increment walks steps BETWEEN the two endpoints, so the
        // reported figure is two short of the values it can land on.
        assertEquals(values - 2, AudioDelayPolicy.STEPS_BETWEEN_BOUNDS)
        // And that increment must still be one press, not a proportion of a wider range.
        assertEquals(
            AudioDelayPolicy.STEP_MS,
            (AudioDelayPolicy.MAX_MS - AudioDelayPolicy.MIN_MS) / (AudioDelayPolicy.STEPS_BETWEEN_BOUNDS + 1),
        )
    }
}

package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioDelayPolicyTest {

    @Test fun boundsAndStepAreTheOnesTheWireContractNames() {
        // The receiver validates against these same three and refuses anything outside
        // them, so a change here that the TV has not made costs the nudge entirely.
        assertEquals(-500, AudioDelayPolicy.MIN_MS)
        assertEquals(500, AudioDelayPolicy.MAX_MS)
        assertEquals(25, AudioDelayPolicy.STEP_MS)
        assertEquals(0, AudioDelayPolicy.IN_SYNC_MS)
    }

    @Test fun clampHoldsTheRangeAndTheGrid() {
        assertEquals(500, AudioDelayPolicy.clamp(9_000))
        assertEquals(-500, AudioDelayPolicy.clamp(-9_000))
        assertEquals(0, AudioDelayPolicy.clamp(0))
        assertEquals(150, AudioDelayPolicy.clamp(150))
        // A blade is dragged to arbitrary milliseconds; every one of them has to land on
        // a step the two buttons beside it can also reach.
        assertEquals(150, AudioDelayPolicy.clamp(146))
        assertEquals(-150, AudioDelayPolicy.clamp(-146))
        assertEquals(0, AudioDelayPolicy.clamp(-11))
    }

    @Test fun clampNeverLeavesTheGridEvenAtTheBounds() {
        (-800..800).forEach { candidate ->
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
        assertEquals(500, AudioDelayPolicy.stepUp(500))
        assertEquals(-500, AudioDelayPolicy.stepDown(-500))
        // Forty presses cross the whole range and the forty-first changes nothing.
        var value = AudioDelayPolicy.MIN_MS
        repeat(40) { value = AudioDelayPolicy.stepUp(value) }
        assertEquals(AudioDelayPolicy.MAX_MS, value)
        assertEquals(AudioDelayPolicy.MAX_MS, AudioDelayPolicy.stepUp(value))
    }

    @Test fun aStepperIsDisabledExactlyWhereItWouldDoNothing() {
        assertFalse(AudioDelayPolicy.canStepUp(AudioDelayPolicy.MAX_MS))
        assertTrue(AudioDelayPolicy.canStepDown(AudioDelayPolicy.MAX_MS))
        assertFalse(AudioDelayPolicy.canStepDown(AudioDelayPolicy.MIN_MS))
        assertTrue(AudioDelayPolicy.canStepUp(AudioDelayPolicy.MIN_MS))
        assertTrue(AudioDelayPolicy.canStepUp(0))
        assertTrue(AudioDelayPolicy.canStepDown(0))
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
        assertEquals(0, AudioDelayPolicy.approach(0, 0))
    }

    @Test fun aLargerMoveIsWalkedAndNeverJumpsFurtherThanTheBound() {
        assertEquals(100, AudioDelayPolicy.approach(0, 500))
        assertEquals(-100, AudioDelayPolicy.approach(0, -500))
        assertEquals(-400, AudioDelayPolicy.approach(-500, 500))
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
            assertTrue("walk did not converge", hops <= 64)
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

    @Test fun theJumpBoundIsAWholeNumberOfSteps() {
        // Walking by it is what keeps a walked move on the same grid a press lands on.
        assertEquals(0, AudioDelayPolicy.MAX_JUMP_MS % AudioDelayPolicy.STEP_MS)
        // Comfortably under the half-second at which the receiver's player abandons
        // decoded buffers forward to the next keyframe.
        assertTrue(AudioDelayPolicy.MAX_JUMP_MS < 500)
    }

    @Test fun theReadoutSignsTheFigureWithAMinusAndNotAHyphen() {
        assertEquals("0", AudioDelayPolicy.signed(0))
        assertEquals("+150", AudioDelayPolicy.signed(150))
        assertEquals("−150", AudioDelayPolicy.signed(-150))
        assertEquals("−500", AudioDelayPolicy.signed(-9_000))
        assertEquals("+500", AudioDelayPolicy.signed(9_000))
        // U+2212, the glyph Format.remaining sets its minus in.
        assertEquals('−', AudioDelayPolicy.signed(-25).first())
    }

    @Test fun theAdjustableStepCountCoversEveryLegalValue() {
        val values = (AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS step AudioDelayPolicy.STEP_MS).count()
        // A screen reader's own increment walks steps BETWEEN the two endpoints, so the
        // reported figure is two short of the values it can land on.
        assertEquals(values - 2, AudioDelayPolicy.STEPS_BETWEEN_BOUNDS)
    }
}

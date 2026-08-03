package com.flick.sender.net

import com.flick.sender.model.VideoRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `setRotation` verb's two shapes, from the phone's side. The receiver
 * validates each against its own EXACT field set, so a frame that carries the
 * wrong one is not a weaker command — it is malformed, and a malformed frame
 * costs the whole control socket.
 */
class RotationWireTest {
    /** What the phone puts around whichever field [rotationField] chose. */
    private val envelope = setOf("t", "v", "castId")

    @Test fun everyQuarterTurnGoesOutAsAnIntegralDegreesField() {
        assertEquals("degrees" to 0, ControlProtocolV2.rotationField(VideoRotation.AsFiled))
        assertEquals("degrees" to 90, ControlProtocolV2.rotationField(VideoRotation.Quarter))
        assertEquals("degrees" to 180, ControlProtocolV2.rotationField(VideoRotation.Half))
        assertEquals("degrees" to 270, ControlProtocolV2.rotationField(VideoRotation.ThreeQuarter))
    }

    /**
     * Auto is its own field, never a value inside `degrees`. A sentinel there
     * would put a mode in the value domain of a numeric field, and the receiver
     * could no longer call a value off the quarter-turn grid malformed on sight.
     */
    @Test fun autoIsItsOwnFieldRatherThanASentinelDegree() {
        assertEquals("auto" to true, ControlProtocolV2.rotationField(VideoRotation.Auto))
        assertNotEquals("degrees", ControlProtocolV2.rotationField(VideoRotation.Auto).first)
    }

    /** The whole frame, as the receiver's two exact field sets see it. */
    @Test fun eachChoiceProducesExactlyOneOfTheTwoAcceptedFieldSets() {
        val explicit = setOf("t", "v", "castId", "degrees")
        val auto = setOf("t", "v", "castId", "auto")
        VideoRotation.ALL.forEach { choice ->
            val fields = envelope + ControlProtocolV2.rotationField(choice).first
            assertEquals(
                if (choice == VideoRotation.Auto) auto else explicit,
                fields,
            )
        }
    }

    /** The cells the phone offers are the cells the TV offers, in the same order. */
    @Test fun theChoicesAreTheTvsOwnWithTheSameAdditiveMeaning() {
        assertEquals(
            listOf(null, 0, 90, 180, 270),
            VideoRotation.ALL.map { it.extraDegrees },
        )
        // 0° is a real choice, not the absence of one: it is what corrects an Auto
        // that read the file wrong, and it must reach the wire as a degrees frame.
        assertTrue(VideoRotation.AsFiled.extraDegrees == 0)
        assertEquals("degrees" to 0, ControlProtocolV2.rotationField(VideoRotation.AsFiled))
    }
}

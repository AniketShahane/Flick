package com.flick.sender.net

import com.flick.sender.model.VideoRotation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `setRotation` verb's two shapes, from the phone's side. The receiver
 * validates each against its own EXACT field set, so a frame that carries the
 * wrong one is not a weaker command — it is malformed, and a malformed frame
 * costs the whole control socket.
 *
 * Everything below runs the production builders — [ControlProtocolV2.command] for the
 * envelope `PlaybackSession.cmd` wraps every verb in, [ControlProtocolV2.rotationField]
 * for the one field this verb adds, and `JSONObject` for the bytes — rather than
 * restating any of them, so a bumped `v` or a dropped field breaks these tests and not
 * only the wire.
 *
 * KEY ORDER is the one thing not asserted, and deliberately: the `org.json` on this
 * module's unit-test classpath is the reference implementation, which stores fields in a
 * `HashMap` and says so, while the platform's own class stores them in a `LinkedHashMap`.
 * Pinning the order this JVM happens to produce would pin bytes no device emits. Nothing
 * reads it either — JSON does not order an object's members and the receiver validates
 * against a SET.
 */
class RotationWireTest {

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

    /**
     * The envelope every verb this phone sends is wrapped in, which the frames below
     * would otherwise only assert by coincidence: three fields and no more, `v` at the
     * version the receiver's schema accepts, and the cast the verb belongs to.
     */
    @Test fun theEnvelopeIsTheThreeFieldsTheReceiverExpectsAndNoOthers() {
        assertEquals(
            mapOf<String, Any>("t" to "setRotation", "v" to 2, "castId" to CAST),
            fieldsOf(ControlProtocolV2.command("setRotation", CAST)),
        )
    }

    /** The whole frame, as the receiver's two exact field sets see it. */
    @Test fun eachChoiceProducesExactlyOneOfTheTwoAcceptedFieldSets() {
        val explicit = setOf("t", "v", "castId", "degrees")
        val auto = setOf("t", "v", "castId", "auto")
        VideoRotation.ALL.forEach { choice ->
            assertEquals(
                if (choice == VideoRotation.Auto) auto else explicit,
                keysOf(frame(choice)),
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

    /**
     * The RUNTIME type of the value, because that is the only thing `JSONObject`
     * consults when it serialises: a Boolean and a Number are written as bare JSON
     * tokens and every other type is written quoted. `auto` carrying the STRING
     * `"true"` would therefore be a well-typed Kotlin pair that reaches the TV as a
     * shape its schema refuses — and a refused frame costs the whole control socket,
     * not merely the turn that was asked for.
     */
    @Test fun theValueIsTypedSoThatJsonWritesItBareAndNeverQuoted() {
        assertSame(
            Boolean::class.javaObjectType,
            ControlProtocolV2.rotationField(VideoRotation.Auto).second.javaClass,
        )
        VideoRotation.ALL.filterNot { it == VideoRotation.Auto }.forEach { choice ->
            assertSame(
                Int::class.javaObjectType,
                ControlProtocolV2.rotationField(choice).second.javaClass,
            )
        }
    }

    /**
     * The bytes, through the serialiser and back through a parser. Every one of these
     * five is a string the receiver's own `RotationCommandSchemaTest` feeds to
     * `RotationCommandSchema.read` and asserts a command out of, so the two modules are
     * pinned to the same five frames from opposite ends.
     *
     * Compared field by field rather than as text: the fields carry every difference the
     * wire has — a missing `castId`, a `v` of 3, a `degrees` of `"90"` or `90.0` all fail
     * here — while the order they are written in carries none (see the class comment).
     */
    @Test fun eachShapeSerialisesToTheFramesTheReceiverAlreadyAccepts() {
        assertSerialises("""{"t":"setRotation","v":2,"castId":"$CAST","auto":true}""", VideoRotation.Auto)
        assertSerialises("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":0}""", VideoRotation.AsFiled)
        assertSerialises("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":90}""", VideoRotation.Quarter)
        assertSerialises("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":180}""", VideoRotation.Half)
        assertSerialises("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":270}""", VideoRotation.ThreeQuarter)
    }

    /**
     * [expected] is what the receiver accepts; [choice] is what this phone builds. The
     * production frame is serialised and re-parsed before the comparison, so the token
     * each value is written as — bare `true`, bare `90`, quoted castId — is part of what
     * is being asserted rather than something the in-memory objects could agree on
     * without ever reaching text.
     */
    private fun assertSerialises(expected: String, choice: VideoRotation) {
        assertEquals(expected, fieldsOf(JSONObject(expected)), fieldsOf(JSONObject(frame(choice).toString())))
    }

    /** What `PlaybackSession.setRotation` puts on the wire, built exactly as it builds it. */
    private fun frame(choice: VideoRotation): JSONObject {
        val (field, value) = ControlProtocolV2.rotationField(choice)
        return ControlProtocolV2.command("setRotation", CAST).put(field, value)
    }

    private fun keysOf(json: JSONObject): Set<String> = json.keys().asSequence().toSet()

    /** Name to value, which — with the values' own runtime types — is all JSON specifies. */
    private fun fieldsOf(json: JSONObject): Map<String, Any> =
        json.keys().asSequence().associateWith { json.get(it) }

    private companion object {
        /** The receiver's own fixture id — 16 random bytes, url-safe base64, unpadded. */
        const val CAST = "ABEiM0RVZneImaq7zN3u_w"
    }
}

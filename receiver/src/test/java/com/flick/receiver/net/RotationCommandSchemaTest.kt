package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `setRotation` verb's two accepted shapes, and everything that is not one
 * of them. Parsed through the real [StrictJson] so the field sets, the value
 * domain and the JSON token rules are exercised together — a `degrees` of `90.0`
 * is a different rejection from a `degrees` of `45`, and both have to be one.
 */
class RotationCommandSchemaTest {
    @Test fun theExplicitShapeCarriesTheFourQuarterTurnsAndNothingElse() {
        assertEquals(RotationCommand.Explicit(0), read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":0}"""))
        assertEquals(RotationCommand.Explicit(90), read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":90}"""))
        assertEquals(RotationCommand.Explicit(180), read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":180}"""))
        assertEquals(RotationCommand.Explicit(270), read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":270}"""))
    }

    @Test fun theAutoShapeIsItsOwnFieldRatherThanASentinelInsideDegrees() {
        assertEquals(RotationCommand.Auto, read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":true}"""))
        // A sentinel is what this shape exists to avoid: -1 and 360 stay malformed.
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":-1}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":360}"""))
    }

    /** There is no "not auto" to command — the other shape carries the degrees. */
    @Test fun autoAcceptsOnlyTrue() {
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":false}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":1}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":"true"}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":null}"""))
    }

    @Test fun aValueOffTheQuarterTurnGridIsMalformedRatherThanSnapped() {
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":45}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":89}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":-90}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":9999999999}"""))
    }

    /** JSON keeps 90 and 90.0 apart, and so does the field this reads. */
    @Test fun degreesMustBeAnIntegralToken() {
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":90.0}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":9e1}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":"90"}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":true}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":null}"""))
    }

    /**
     * Two EXACT field sets, never a permissive check: neither shape may carry the
     * other's field, an extra one, or one fewer.
     */
    @Test fun neitherShapeToleratesAFieldTheOtherOwns() {
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":90,"auto":true}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST"}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","degrees":90,"extra":1}"""))
        assertNull(read("""{"t":"setRotation","v":2,"castId":"$CAST","auto":true,"extra":1}"""))
        assertNull(read("""{"t":"setRotation","v":2,"degrees":90}"""))
        assertNull(read("""{"t":"setRotation","v":2,"auto":true}"""))
    }

    /** The two sets differ in exactly one field, which is what makes them a pair. */
    @Test fun theTwoShapesShareTheEnvelopeAndDifferInOneField() {
        assertEquals(
            setOf("t", "v", "castId"),
            RotationCommandSchema.EXPLICIT_FIELDS intersect RotationCommandSchema.AUTO_FIELDS,
        )
        assertEquals(setOf("degrees"), RotationCommandSchema.EXPLICIT_FIELDS - RotationCommandSchema.AUTO_FIELDS)
        assertEquals(setOf("auto"), RotationCommandSchema.AUTO_FIELDS - RotationCommandSchema.EXPLICIT_FIELDS)
    }

    private fun read(json: String): RotationCommand? =
        RotationCommandSchema.read(requireNotNull(StrictJson.objectOnly(json)) { "fixture is not JSON: $json" })

    private companion object {
        const val CAST = "ABEiM0RVZneImaq7zN3u_w"
    }
}

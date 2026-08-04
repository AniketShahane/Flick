package com.flick.receiver.net

import com.flick.receiver.player.AudioDelayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two checks `setAudioDelay` is made of, exercised on real frame text.
 * `ControlServer`'s dispatch needs a Ktor session and a live control lease, so
 * what a JVM test can reach is the pair of decisions that settle whether a frame
 * is a command at all: the exact field set and the accepted `delayMs`. Ownership
 * is deliberately not reproduced here — it is `setVolume`'s, unchanged.
 */
class AudioDelayWireTest {

    private val castId = "MDEyMzQ1Njc4OWFiY2RlZg"

    private fun frame(body: String) = StrictJson.objectOnly(body)

    /** The server's two gates in the order it applies them; null is `return false`. */
    private fun command(body: String): Long? {
        val parsed = frame(body) ?: return null
        if (!parsed.exactly(AUDIO_DELAY_FIELDS)) return null
        val value = parsed.integer("delayMs") ?: return null
        return value.takeIf(AudioDelayPolicy::accepts)
    }

    // --- field set ------------------------------------------------------------

    @Test fun theFrameIsExactlyTheFourFields() {
        assertEquals(setOf("t", "v", "castId", "delayMs"), AUDIO_DELAY_FIELDS)
    }

    @Test fun acceptsTheCanonicalFrame() {
        assertEquals(250L, command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":250}"""))
        assertEquals(-250L, command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":-250}"""))
        assertEquals(0L, command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":0}"""))
        assertEquals(-2000L, command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":-2000}"""))
        assertEquals(2000L, command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":2000}"""))
    }

    @Test fun rejectsAnExtraOrMissingField() {
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":25,"units":"ms"}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId"}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"delayMs":25}"""))
    }

    // --- delayMs --------------------------------------------------------------

    @Test fun rejectsAValueOutsideTheRangeOrOffTheStep() {
        // On the step grid, so it is the BOUND refusing these, not the step.
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":2025}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":-2025}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":10}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":-10}"""))
    }

    /** A fractional or exponent token is malformed, never rounded onto a step. */
    @Test fun rejectsAValueThatIsNotAJsonInteger() {
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":25.0}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":2.5e1}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":"25"}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":null}"""))
        assertNull(command("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":true}"""))
    }

    @Test fun rejectsADuplicateKeyBeforeEitherValueCanBeRead() {
        assertNull(frame("""{"t":"setAudioDelay","v":2,"castId":"$castId","delayMs":25,"delayMs":500}"""))
    }
}

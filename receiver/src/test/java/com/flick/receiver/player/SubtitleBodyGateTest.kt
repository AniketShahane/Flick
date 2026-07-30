package com.flick.receiver.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubtitleBodyGateTest {

    private val unset = C.LENGTH_UNSET.toLong()

    @Test fun onlyTheSubtitleRouteIsCapped() {
        assertTrue(isSubtitleRoute("/s/AAAAAAAAAAAAAAAAAAAAAA"))
        // The media route streams gigabytes through the same factory.
        assertFalse(isSubtitleRoute("/v/AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(isSubtitleRoute("/"))
        assertFalse(isSubtitleRoute("/sub/AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(isSubtitleRoute(""))
        assertFalse(isSubtitleRoute(null))
    }

    /** The TV's own number, not the phone's promise about it. */
    @Test fun theCapMatchesTheSendersConvention() {
        assertEquals(5L * 1024L * 1024L, SUBTITLE_BODY_MAX_BYTES)
    }

    @Test fun anOversizedDeclaredLengthIsRefusedBeforeAByteIsRead() {
        val gate = SubtitleBodyGate(SUBTITLE_BODY_MAX_BYTES)
        try {
            gate.verifyDeclaredLength(SUBTITLE_BODY_MAX_BYTES + 1)
            fail("an over-cap declared length must be refused")
        } catch (error: SubtitleTooLargeException) {
            assertEquals(SUBTITLE_BODY_MAX_BYTES, error.limitBytes)
        }
        assertEquals(0L, gate.produced)
    }

    @Test fun aDeclaredLengthAtTheCapIsAccepted() {
        SubtitleBodyGate(SUBTITLE_BODY_MAX_BYTES).verifyDeclaredLength(SUBTITLE_BODY_MAX_BYTES)
    }

    /**
     * A whole-file 200 with no `Content-Length` is what the sender's subtitle route
     * actually answers, so an unset length may not itself be a refusal.
     */
    @Test fun anUnsetLengthIsNotARefusal() {
        SubtitleBodyGate(SUBTITLE_BODY_MAX_BYTES).verifyDeclaredLength(unset)
    }

    /** The half a provider that under-reports its size would otherwise walk past. */
    @Test fun aBodyThatOverrunsAnHonestHeaderIsRefused() {
        val gate = SubtitleBodyGate(1_000L)
        gate.verifyDeclaredLength(10L)
        repeat(4) { gate.verifyProduced(250) }
        assertEquals(1_000L, gate.produced)
        try {
            gate.verifyProduced(1)
            fail("bytes past the cap must be refused however the body was declared")
        } catch (error: SubtitleTooLargeException) {
            assertEquals(1_000L, error.limitBytes)
        }
    }

    @Test fun aBodyWithNoDeclaredLengthIsStillBounded() {
        val gate = SubtitleBodyGate(4_096L)
        gate.verifyDeclaredLength(unset)
        var chunks = 0
        try {
            while (chunks < 1_000) {
                gate.verifyProduced(1_024)
                chunks++
            }
            fail("an undeclared body must not read without limit")
        } catch (_: SubtitleTooLargeException) {
            assertEquals(4, chunks)
        }
    }

    @Test fun aBodyExactlyAtTheCapIsAccepted() {
        val gate = SubtitleBodyGate(1_024L)
        gate.verifyProduced(1_024)
        assertEquals(1_024L, gate.produced)
    }

    /** The media route shares this factory and must be measured against nothing. */
    @Test fun anUncappedRouteAcceptsAnyLengthAndAnyBody() {
        val gate = SubtitleBodyGate(null)
        gate.verifyDeclaredLength(64L * 1024L * 1024L * 1024L)
        repeat(64) { gate.verifyProduced(1_048_576) }
        assertEquals(64L * 1_048_576L, gate.produced)
    }
}

package com.flick.sender.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which frames get a handoff line, and how slow a write has to be to earn a second one.
 *
 * The pair exists to settle one question a hung cast cannot answer from either end
 * alone: whether a frame sat in Ktor's outgoing queue because the socket write was
 * blocked, or went out promptly and the TV sat on it. Both halves are worthless if the
 * lines that carry them are evicted before anyone reads them — the diagnostics ring
 * holds 200 entries, and one scrub can produce that many.
 */
class ControlSendLoggingTest {

    /**
     * The verb the reproduction turns on. A `loadMedia` is issued once per cast and once
     * per subtitle swap, so its handoff is affordable and it is the frame whose timing
     * decides the diagnosis.
     */
    @Test fun aLoadIsWorthALine() {
        assertTrue(logsSendHandoff("loadMedia"))
    }

    @Test fun theOneOffTransportVerbsAreWorthALine() {
        listOf("play", "pause", "stop", "setRotation").forEach {
            assertTrue(it, logsSendHandoff(it))
        }
    }

    /**
     * A scrub throttles to one `seek` every 50 ms and a walked audio nudge emits one
     * `setAudioDelay` every 40 ms; a volume drag is not throttled at all. Any of the three
     * would evict the whole cast's log to say nothing.
     */
    @Test fun aGesturesOwnFramesAreSilent() {
        assertFalse(logsSendHandoff("seek"))
        assertFalse(logsSendHandoff("setVolume"))
        assertFalse(logsSendHandoff("setAudioDelay"))
    }

    /**
     * The elapsed line's floor has to clear ordinary jitter, and the rate-driven verbs
     * above are what defines ordinary: a write that finishes inside the interval its own
     * producer runs at is keeping up by definition. A threshold near that interval would
     * fire on a single dispatch hiccup and bury the ring exactly as a per-send line would.
     */
    @Test fun theSlowThresholdOutlastsTheFastestProducerOnThisChannel() {
        assertTrue(CONTROL_SLOW_SEND_MS > AudioDelayPolicy.WALK_INTERVAL_MS * 4)
    }

    /**
     * And stays far under the event it exists to catch. The reproduction's gap was 33
     * seconds; anything of that order must be impossible to hide under this floor.
     */
    @Test fun theSlowThresholdIsNowhereNearTheStallItReports() {
        assertTrue(CONTROL_SLOW_SEND_MS < 1_000L)
    }
}

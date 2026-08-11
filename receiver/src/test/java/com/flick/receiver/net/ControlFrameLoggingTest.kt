package com.flick.receiver.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which arriving frames get a line, and how slow handling has to be to earn one anyway.
 *
 * The diagnostics ring holds 200 entries and is the only record a TV has without a laptop
 * attached. One walked audio nudge put 92 frames on the wire in a single observed gesture,
 * and at two lines a frame that is the entire buffer — the cast's load, its first frame and
 * its subtitle reload all evicted to say the same thing ninety-two times.
 *
 * So these are not tests about set membership. Each one is a question the buffer can still
 * answer afterwards.
 */
class ControlFrameLoggingTest {

    /**
     * The frame a cast is diagnosed from. A `loadMedia` is issued once per cast and once
     * per subtitle swap, so its arrival is affordable and it is the line every other
     * timestamp is read relative to.
     */
    @Test fun aLoadIsWorthALine() {
        assertTrue(logsFrameArrival("loadMedia"))
    }

    @Test fun theOneOffTransportVerbsAreWorthALine() {
        listOf("play", "pause", "stop", "setRotation").forEach {
            assertTrue(it, logsFrameArrival(it))
        }
    }

    /**
     * A scrub throttles to one `seek` every 50 ms and a walked nudge emits one
     * `setAudioDelay` every 40 ms; a volume drag is not throttled at all. Any of the three
     * would evict the whole cast's history to say nothing anyone reads.
     */
    @Test fun aGesturesOwnFramesArriveSilently() {
        assertFalse(logsFrameArrival("seek"))
        assertFalse(logsFrameArrival("setVolume"))
        assertFalse(logsFrameArrival("setAudioDelay"))
    }

    /**
     * The half that must survive the silence. The pair of lines exists to tell a frame the
     * socket delivered late from one that arrived on time and took thirty seconds to
     * handle, and a gesture's frames are no less capable of the second than any other.
     */
    @Test fun aGesturesFrameThatHangsIsStillReported() {
        assertTrue(logsFrameCompletion("seek", CONTROL_SLOW_FRAME_MS))
        assertTrue(logsFrameCompletion("setAudioDelay", 30_000L))
    }

    @Test fun aGesturesFrameThatKeptUpIsNot() {
        assertFalse(logsFrameCompletion("setAudioDelay", 0L))
        assertFalse(logsFrameCompletion("setVolume", CONTROL_SLOW_FRAME_MS - 1))
    }

    /** A rare verb is never held to the threshold; its completion is always worth a line. */
    @Test fun aRareVerbsCompletionNeverWaitsOnTheThreshold() {
        assertTrue(logsFrameCompletion("loadMedia", 0L))
    }

    /**
     * The floor has to clear ordinary jitter, and the rate-driven verbs define what
     * ordinary is on this channel: the phone's walk is one frame every 40 ms, so anything
     * handled inside that interval is keeping up by definition. Four of those intervals is
     * a backlog that grew rather than one that wobbled. The interval is written out rather
     * than imported because it is the phone's constant and the modules do not share code.
     */
    @Test fun theSlowThresholdOutlastsTheFastestProducerOnThisChannel() {
        val phoneWalkIntervalMs = 40L
        assertTrue(CONTROL_SLOW_FRAME_MS > phoneWalkIntervalMs * 4)
    }

    /** And stays far under the stall it exists to catch, which is tens of seconds. */
    @Test fun theSlowThresholdIsNowhereNearTheStallItReports() {
        assertTrue(CONTROL_SLOW_FRAME_MS < 1_000L)
    }
}

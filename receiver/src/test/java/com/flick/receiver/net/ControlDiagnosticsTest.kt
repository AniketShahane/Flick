package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the control socket's frame tracing turns on. `ControlServer` binds
 * a real Ktor engine and cannot be built on the JVM, so both live in pure
 * functions and are exercised here.
 */
class ControlDiagnosticsTest {
    @Test fun theCommandNameIsTheOnlyThingATraceLineTakesOffAFrame() {
        assertEquals("loadMedia", controlCommandLabel("loadMedia"))
        assertEquals("ping", controlCommandLabel("ping"))
    }

    /** A peer chooses `t`, so a peer would otherwise choose how a log line is framed. */
    @Test fun aCommandNameCarryingALineBreakIsNotLogged() {
        assertEquals("unnamed", controlCommandLabel("ping\ncast reload reason=subtitle"))
        assertEquals("unnamed", controlCommandLabel("ping\r"))
        assertEquals("unnamed", controlCommandLabel("\tping"))
    }

    /** Terminal escapes, NUL and DEL are as unwelcome in the ring as a newline. */
    @Test fun aCommandNameCarryingAControlCodeIsNotLogged() {
        assertEquals("unnamed", controlCommandLabel("ping" + Char(0x1b) + "[2J"))
        assertEquals("unnamed", controlCommandLabel("ping" + Char(0x00)))
        assertEquals("unnamed", controlCommandLabel("ping" + Char(0x7f)))
        assertEquals("unnamed", controlCommandLabel("ping" + Char(0xe9)))
    }

    /** Reported as absent rather than trimmed: a truncation is still the peer's bytes. */
    @Test fun aCommandNameLongerThanAnyVerbIsReportedAsAbsent() {
        assertEquals("unnamed", controlCommandLabel("p".repeat(33)))
        assertEquals("p".repeat(32), controlCommandLabel("p".repeat(32)))
    }

    /** `cmd=` with nothing after it reads as a truncated line, not as a nameless frame. */
    @Test fun aFrameWithNoCommandNameStillSaysSo() {
        assertEquals("unnamed", controlCommandLabel(null))
        assertEquals("unnamed", controlCommandLabel(""))
    }

    /** The stall that matters is the one the sender's 15 s ping does not outlive. */
    @Test fun theHeartbeatResolvesAStallShorterThanTheSenderPingInterval() {
        assertTrue(heartbeatResolvesStall(CONTROL_HEARTBEAT_MS, 15_000L))
        assertTrue(heartbeatResolvesStall(CONTROL_HEARTBEAT_MS, 33_000L))
    }

    @Test fun aBeatTooSlowToProduceARunOfMissesProvesNothing() {
        assertFalse(heartbeatResolvesStall(5_000L, 15_000L))
        assertFalse(heartbeatResolvesStall(15_000L, 15_000L))
    }

    /** A beat that never fires reports nothing, whatever the arithmetic says. */
    @Test fun aHeartbeatWithNoIntervalIsNotAHeartbeat() {
        assertFalse(heartbeatResolvesStall(0L, 15_000L))
        assertFalse(heartbeatResolvesStall(-1L, 15_000L))
    }

    /**
     * The whole reason this line can ship. A beat that arrived when it asked to says
     * nothing a reader did not already assume, and one per interval for the length of a
     * film is what evicts the session the reader actually came for.
     */
    @Test fun aBeatThatArrivedOnTimeSaysNothingAndIsNotSaid() {
        assertFalse(heartbeatStalled(0L))
        assertFalse(heartbeatStalled(-1L))
    }

    /** A TV decoding 4K reschedules a timer late constantly; none of that is a stall. */
    @Test fun ordinarySchedulingJitterIsNotAStall() {
        assertFalse(heartbeatStalled(1L))
        assertFalse(heartbeatStalled(CONTROL_HEARTBEAT_MS - 1))
    }

    /** Nothing else on this dispatcher runs long enough to lose a whole interval. */
    @Test fun aWholeMissedIntervalIsTheDispatcherReportingItself() {
        assertTrue(heartbeatStalled(CONTROL_HEARTBEAT_MS))
        assertTrue(heartbeatStalled(30_000L))
    }

    /** Named several beats before the sender's ping tolerance costs anyone a cast. */
    @Test fun aStallIsNamedWellInsideTheSenderPingTolerance() {
        assertTrue(heartbeatStalled(CONTROL_HEARTBEAT_MS))
        assertTrue(heartbeatResolvesStall(CONTROL_HEARTBEAT_MS, 15_000L))
    }
}

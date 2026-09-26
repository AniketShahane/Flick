package com.flick.sender.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this phone may trust a control socket it is holding, and when it must hang it up.
 *
 * The failure this answers: Back on the TV moves Flick to the background without closing
 * the control socket, the platform then freezes the app, and the phone — which only learns
 * of a dead socket from a ping watchdog 30 to 45 seconds later — handed every cast in the
 * meantime to a socket nobody was reading. Each failed as a startup timeout, and each
 * retry reused the same dead socket.
 */
class ControlLivenessTest {

    /**
     * A live cast carries `state` at 10 Hz. Replacing its film must not pay a round trip
     * the feed has already answered for.
     */
    @Test fun aFrameInsideTheFreshnessWindowIsTrustedWithoutAPing() {
        assertEquals(
            ControlLiveness.Step.TRUST,
            ControlLiveness.step(lastInboundAtMs = 50_000L, outstandingSentAtMs = null, budgetAvailable = true, nowMs = 50_100L),
        )
        assertTrue(ControlLiveness.heardRecently(lastInboundAtMs = 50_000L, nowMs = 50_000L + ControlLiveness.FRESH_MS))
    }

    /** Idle sockets carry nothing — the receiver's state feed runs only while a cast is owned. */
    @Test fun aQuietSocketIsAskedRatherThanTrusted() {
        assertFalse(ControlLiveness.heardRecently(lastInboundAtMs = 50_000L, nowMs = 50_001L + ControlLiveness.FRESH_MS))
        assertEquals(
            ControlLiveness.Step.PING,
            ControlLiveness.step(lastInboundAtMs = 50_000L, outstandingSentAtMs = null, budgetAvailable = true, nowMs = 90_000L),
        )
    }

    /** Zero is "never heard", not "heard at boot", and a clock read backwards proves nothing. */
    @Test fun noStampAndABackwardsStampAreNotEvidence() {
        assertFalse(ControlLiveness.heardRecently(lastInboundAtMs = 0L, nowMs = 1_000L))
        assertFalse(ControlLiveness.heardRecently(lastInboundAtMs = 5_000L, nowMs = 4_000L))
    }

    /**
     * A check whose caller walked away leaves its ping on the wire. The next check waits on
     * that one rather than sending a second behind it — pings stay sequential, which is
     * what the budget's arithmetic against the receiver's gate depends on.
     */
    @Test fun anUnansweredPingIsAwaitedNotRepeated() {
        assertEquals(
            ControlLiveness.Step.AWAIT,
            ControlLiveness.step(lastInboundAtMs = 1_000L, outstandingSentAtMs = 60_000L, budgetAvailable = true, nowMs = 60_500L),
        )
    }

    /** Once its timeout has passed, the outstanding ping is itself the verdict. */
    @Test fun anOverduePongIsADeadSocket() {
        assertEquals(
            ControlLiveness.Step.OVERDUE,
            ControlLiveness.step(
                lastInboundAtMs = 1_000L,
                outstandingSentAtMs = 60_000L,
                budgetAvailable = true,
                nowMs = 60_000L + ControlLiveness.PONG_TIMEOUT_MS,
            ),
        )
    }

    /**
     * An overdue ping outranks an empty budget: the answer is already known, and it is the
     * one thing that must not be downgraded to "trust the socket".
     */
    @Test fun anOverduePongIsDeadEvenWithTheBudgetSpent() {
        assertEquals(
            ControlLiveness.Step.OVERDUE,
            ControlLiveness.step(lastInboundAtMs = 1_000L, outstandingSentAtMs = 60_000L, budgetAvailable = false, nowMs = 70_000L),
        )
    }

    /** With no budget left the check does not ask; the caller trusts the socket as before. */
    @Test fun aSpentBudgetMeansNoPing() {
        assertEquals(
            ControlLiveness.Step.UNBUDGETED,
            ControlLiveness.step(lastInboundAtMs = 1_000L, outstandingSentAtMs = null, budgetAvailable = false, nowMs = 90_000L),
        )
    }

    /**
     * The one pong window a tap can see must stay short, and it must sit far below the
     * watchdog it stands in front of or it would buy nothing.
     */
    @Test fun thePongTimeoutIsShortAndFarUnderTheWatchdog() {
        assertTrue(ControlLiveness.PONG_TIMEOUT_MS <= 2_000L)
        assertTrue(ControlLiveness.PONG_TIMEOUT_MS * 10 <= ControlClient.PING_INTERVAL_MS * 2)
    }

    /** The two failures that waited on the socket itself, with nothing heard back. */
    @Test fun aLoadThatNeverLeftOrWasNeverAcceptedHangsUp() {
        assertTrue(
            ControlLiveness.hangsUpAfterStartupFailure("load_not_sent", StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = false),
        )
        assertTrue(
            ControlLiveness.hangsUpAfterStartupFailure("startup_timeout", StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = false),
        )
    }

    /**
     * Reaching the first-frame wait took a `loadAccepted`, which is the TV answering. A
     * film that then fails to start indicts the link or the file, never the socket.
     */
    @Test fun aFirstFrameTimeoutKeepsTheSocket() {
        assertFalse(
            ControlLiveness.hangsUpAfterStartupFailure("startup_timeout", StartupStage.AWAITING_FIRST_FRAME, heardSinceLoad = false),
        )
    }

    /** Any frame after the load is a receiver that is still there, whatever else failed. */
    @Test fun anythingHeardSinceTheLoadKeepsTheSocket() {
        assertFalse(
            ControlLiveness.hangsUpAfterStartupFailure("startup_timeout", StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = true),
        )
        assertFalse(
            ControlLiveness.hangsUpAfterStartupFailure("load_not_sent", StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = true),
        )
    }

    /** Failures raised on this phone before a load, and every receiver verdict, say nothing about the socket. */
    @Test fun otherFailuresKeepTheSocket() {
        listOf("source_unavailable", "source_start_timeout", "no_compatible_lan", "control_unreachable", "unknown").forEach { code ->
            StartupStage.entries.forEach { stage ->
                assertFalse("$code at $stage", ControlLiveness.hangsUpAfterStartupFailure(code, stage, heardSinceLoad = false))
            }
        }
    }

    /** The foreground check runs only on a socket nothing else is deciding the fate of. */
    @Test fun theForegroundCheckRunsOnlyOnAnIdleHeldSocket() {
        assertTrue(ControlLiveness.checksHeldLine(held = true, castLive = false, castQueued = false, dialing = false, checking = false))
        assertFalse(ControlLiveness.checksHeldLine(held = false, castLive = false, castQueued = false, dialing = false, checking = false))
        assertFalse(ControlLiveness.checksHeldLine(held = true, castLive = true, castQueued = false, dialing = false, checking = false))
        assertFalse(ControlLiveness.checksHeldLine(held = true, castLive = false, castQueued = true, dialing = false, checking = false))
        assertFalse(ControlLiveness.checksHeldLine(held = true, castLive = false, castQueued = false, dialing = true, checking = false))
    }

    /**
     * The Connect screen and the activity both report the foreground. Two checks at once
     * would spend the budget twice to learn one thing.
     */
    @Test fun aCheckAlreadyRunningIsNotStacked() {
        assertFalse(ControlLiveness.checksHeldLine(held = true, castLive = false, castQueued = false, dialing = false, checking = true))
    }

    /**
     * The ping this phone puts on the wire, against the receiver's exact field set and id
     * grammar. A frame with one field too many is malformed there, and a malformed frame
     * costs the whole control socket.
     */
    @Test fun thePingIsTheFrameTheReceiverAccepts() {
        val id = ControlProtocolV2.randomId()
        val ping = ControlProtocolV2.command("ping", null).put("id", id)
        assertEquals(setOf("t", "v", "id"), ping.fieldNames())
        assertEquals("ping", ping.getString("t"))
        assertEquals(2, ping.getInt("v"))
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(ping.getString("id")))
        // The castId argument is ignored for ping, so no caller can smuggle one in.
        assertEquals(setOf("t", "v"), ControlProtocolV2.command("ping", "MDEyMzQ1Njc4OWFiY2RlZg").fieldNames())
    }

    /** The receiver's answer passes this phone's inbound schema, so it never closes the socket here. */
    @Test fun thePongIsAnEventThisPhoneAccepts() {
        val id = ControlProtocolV2.randomId()
        val pong = JSONObject().put("t", "pong").put("v", 2).put("id", id)
        assertTrue(ControlFrameSchema.event(pong.fieldNames().associateWith { key -> pong.get(key) }))
    }

    private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()
}

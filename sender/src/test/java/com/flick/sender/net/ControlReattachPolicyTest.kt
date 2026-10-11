package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a control link lost under a playing film is put back rather than the film re-cast.
 *
 * The fault is a Wi-Fi blip of a second or two. It destroys every socket bound to the
 * address of whichever side blipped, while the TV holds tens of seconds of buffer and the
 * receiver keeps the orphaned cast for a grace period of its own.
 */
class ControlReattachPolicyTest {

    private fun reattaches(
        reachedActive: Boolean = true,
        serverRunning: Boolean = true,
        canDial: Boolean = true,
        address: ServedAddress = ServedAddress.SAME,
        attempt: Int = 1,
    ) = ControlReattachPolicy.reattaches(reachedActive, serverRunning, canDial, address, attempt)

    @Test fun aPlayingCastStillBeingServedIsReattached() {
        assertTrue(reattaches())
    }

    /** The blip the phone itself took: the address is gone for now and may come back. */
    @Test fun anAbsentAddressIsStillWorthWaitingFor() {
        assertTrue(reattaches(address = ServedAddress.ABSENT))
    }

    /** The URL the TV holds names an address this phone no longer has. */
    @Test fun aChangedAddressGoesStraightToTheOldRules() {
        assertFalse(reattaches(address = ServedAddress.CHANGED))
    }

    /** A startup has no film on the TV to keep, and keeps its own one quiet re-dial. */
    @Test fun aStartupIsNeverReattached() {
        assertFalse(reattaches(reachedActive = false))
    }

    @Test fun nothingBeingServedMeansNothingToReattachTo() {
        assertFalse(reattaches(serverRunning = false))
    }

    @Test fun noPairingMeansNoDial() {
        assertFalse(reattaches(canDial = false))
    }

    /** A link that keeps dropping inside a minute is surfaced rather than patched forever. */
    @Test fun theStreakIsBounded() {
        assertTrue(reattaches(attempt = ControlReattachPolicy.MAX_ATTEMPTS))
        assertFalse(reattaches(attempt = ControlReattachPolicy.MAX_ATTEMPTS + 1))
    }

    /** A dial at the window's very end still has a load to deliver before the TV lets go. */
    @Test fun theWindowClosesInsideTheReceiversGrace() {
        assertTrue(ControlReattachPolicy.WINDOW_MS < ControlReattachPolicy.RECEIVER_ORPHAN_GRACE_MS)
    }

    @Test fun theWindowIsOpenUntilItsEnd() {
        assertTrue(ControlReattachPolicy.open(armedAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(ControlReattachPolicy.open(armedAtMs = 1_000L, nowMs = 1_000L + ControlReattachPolicy.WINDOW_MS - 1))
        assertFalse(ControlReattachPolicy.open(armedAtMs = 1_000L, nowMs = 1_000L + ControlReattachPolicy.WINDOW_MS))
        assertFalse(ControlReattachPolicy.open(armedAtMs = 0L, nowMs = 5_000L))
    }

    @Test fun theServedAddressStillHeldIsTheSame() {
        assertEquals(ServedAddress.SAME, ControlReattachPolicy.address("192.168.42.17", servedOwned = true, interfaceAddress = "192.168.42.17"))
        assertEquals(ServedAddress.SAME, ControlReattachPolicy.address("192.168.42.17", servedOwned = false, interfaceAddress = "192.168.42.17"))
    }

    @Test fun anInterfaceWithNoAddressIsABlip() {
        assertEquals(ServedAddress.ABSENT, ControlReattachPolicy.address("192.168.42.17", servedOwned = false, interfaceAddress = null))
    }

    @Test fun anInterfaceBackOnAnotherAddressHasMoved() {
        assertEquals(ServedAddress.CHANGED, ControlReattachPolicy.address("192.168.42.17", servedOwned = false, interfaceAddress = "192.168.42.88"))
    }

    @Test fun onlyAHeldAddressIsWorthADial() {
        assertTrue(ControlReattachPolicy.dials(ServedAddress.SAME))
        assertFalse(ControlReattachPolicy.dials(ServedAddress.ABSENT))
        assertFalse(ControlReattachPolicy.dials(ServedAddress.CHANGED))
    }

    @Test fun dialsBackOffByDoublingToTheCap() {
        val waits = (0..7).map { ControlReattachPolicy.nextDelayMs(dials = it, addressAbsent = false) }
        assertEquals(listOf(500L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), waits)
    }

    @Test fun aLargeDialCountNeverOverflowsTheCap() {
        assertEquals(ControlReattachPolicy.RETRY_CAP_MS, ControlReattachPolicy.nextDelayMs(dials = 10_000, addressAbsent = false))
    }

    /** The address coming back is the moment to dial, and a doubled wait would sleep past it. */
    @Test fun anAbsentAddressIsPolledAtTheBaseRate() {
        assertEquals(ControlReattachPolicy.RETRY_BASE_MS, ControlReattachPolicy.nextDelayMs(dials = 6, addressAbsent = true))
    }

    @Test fun noWaitSleepsPastTheWindow() {
        assertEquals(3_000L, ControlReattachPolicy.delayWithinWindowMs(8_000L, armedAtMs = 0L, nowMs = ControlReattachPolicy.WINDOW_MS - 3_000L))
        assertEquals(500L, ControlReattachPolicy.delayWithinWindowMs(500L, armedAtMs = 0L, nowMs = 1_000L))
        assertEquals(0L, ControlReattachPolicy.delayWithinWindowMs(500L, armedAtMs = 0L, nowMs = ControlReattachPolicy.WINDOW_MS + 1))
    }

    /** The whole backoff fits the window several times over, so a short blip is met early. */
    @Test fun theFirstFiveDialsLandInsideTheWindow() {
        val firstFive = (0..4).sumOf { ControlReattachPolicy.nextDelayMs(dials = it, addressAbsent = false) }
        assertTrue(firstFive < ControlReattachPolicy.WINDOW_MS)
    }

    @Test fun aResumeIsTheWayBack() {
        val endpoint = ControlClient.AuthenticatedEndpoint("tv", "key", "Living room", "192.168.42.17", "192.168.42.190", 8765)
        assertEquals(ReattachDial.RESUMED, ControlReattachPolicy.classify(ControlClient.Result.Resumed(endpoint)))
    }

    /** No path yet, or a receiver still holding this phone's own half-open socket. */
    @Test fun aPathNotBackYetIsRetried() {
        listOf(
            ControlClient.Result.Unreachable(fault = DialFault.NO_NETWORK),
            ControlClient.Result.TimedOut(),
            ControlClient.Result.RejectedByTv(),
            ControlClient.Result.Busy,
            ControlClient.Result.Denied("busy"),
            ControlClient.Result.Denied("surface"),
        ).forEach { assertEquals(it.toString(), ReattachDial.RETRY, ControlReattachPolicy.classify(it)) }
    }

    /** The TV answering with a failure ends the window; a further dial would only repeat it. */
    @Test fun aTvThatRefusesEndsTheWindow() {
        listOf(
            ControlClient.Result.Denied("proof"),
            ControlClient.Result.Denied(null),
            ControlClient.Result.ProtocolError(),
            ControlClient.Result.UpdateRequired,
        ).forEach { assertEquals(it.toString(), ReattachDial.REFUSED, ControlReattachPolicy.classify(it)) }
    }

    @Test fun theReloadStartsWhereTheTvLastWas() {
        assertEquals(1_234_000L, ControlReattachPolicy.loadStartMs(confirmedMs = 1_234_000L, wireDurationMs = 7_200_000L))
    }

    /** The receiver closes the socket on startMs > durationMs, which would end the cast. */
    @Test fun theReloadNeverStartsPastTheWireDuration() {
        assertEquals(7_200_000L, ControlReattachPolicy.loadStartMs(confirmedMs = 7_200_040L, wireDurationMs = 7_200_000L))
        assertEquals(0L, ControlReattachPolicy.loadStartMs(confirmedMs = -5L, wireDurationMs = 7_200_000L))
        assertEquals(9_000L, ControlReattachPolicy.loadStartMs(confirmedMs = 9_000L, wireDurationMs = 0L))
    }

    /** A re-adopt answers loadReady with 0/0; anything a startup measured is a fresh start. */
    @Test fun aFreshStartIsToldFromAReadopt() {
        assertFalse(ControlReattachPolicy.restarted(probeLatencyMs = 0L, startupMs = 0L))
        assertTrue(ControlReattachPolicy.restarted(probeLatencyMs = 0L, startupMs = 840L))
        assertTrue(ControlReattachPolicy.restarted(probeLatencyMs = 35L, startupMs = 0L))
    }

    /** The re-cast fallback keeps the serving gate, taken at either end of the window. */
    @Test fun theFallbackStillAsksForBytes() {
        assertTrue(ControlReattachPolicy.fallbackServing(servingAtLoss = true, servingNow = false))
        assertTrue(ControlReattachPolicy.fallbackServing(servingAtLoss = false, servingNow = true))
        assertFalse(ControlReattachPolicy.fallbackServing(servingAtLoss = false, servingNow = false))
    }
}

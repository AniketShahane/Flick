package com.flick.sender.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class DialDiagnosisTest {

    @Test
    fun `each kernel answer keeps its own diagnosis`() {
        assertEquals(DialFault.NO_ROUTE, dialDiagnosis(NoRouteToHostException("EHOSTUNREACH"), upgraded = false))
        assertEquals(DialFault.REFUSED, dialDiagnosis(ConnectException("ECONNREFUSED"), upgraded = false))
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(ConnectTimeoutException("dial"), upgraded = false))
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(SocketTimeoutException("read"), upgraded = false))
        assertEquals(DialFault.NO_NETWORK, dialDiagnosis(SocketException("ENETUNREACH"), upgraded = false))
    }

    /**
     * Half the reason the arm order in [dialDiagnosis] is load-bearing: both of these
     * extend [SocketException], so a `when` that tested the supertype first would report
     * a router keeping two devices apart as a phone with no network at all.
     */
    @Test
    fun `the SocketException subclasses are never read as no network`() {
        assertEquals(DialFault.NO_ROUTE, dialDiagnosis(NoRouteToHostException(), upgraded = false))
        assertEquals(DialFault.REFUSED, dialDiagnosis(ConnectException(), upgraded = false))
    }

    /**
     * The other half, and the one that is easy to get wrong: Ktor's own
     * [ConnectTimeoutException] EXTENDS [ConnectException]. Read through that supertype
     * it would report the TV as having answered and refused — the one outcome that proves
     * the TV is awake — about a dial that got nothing back at all.
     */
    @Test
    fun `a connect timeout is never read as a refusal`() {
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(ConnectTimeoutException("dial"), upgraded = false))
    }

    // The enclosing withTimeoutOrNull returned: no throwable was ever produced, which is
    // silence and nothing more.
    @Test
    fun `nothing thrown at all is silence`() {
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(null, upgraded = false))
    }

    @Test
    fun `an unclassified throwable degrades to silence`() {
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(IOException("Ping timeout"), upgraded = false))
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(UnknownHostException("tv"), upgraded = false))
        assertEquals(DialFault.NO_ANSWER, dialDiagnosis(IllegalStateException(), upgraded = false))
    }

    // Past the upgrade "nothing is listening" is provably wrong, whatever threw.
    @Test
    fun `an upgraded socket outranks every throwable`() {
        for (error in listOf<Throwable?>(
            null,
            NoRouteToHostException(),
            ConnectException(),
            ConnectTimeoutException("dial"),
            SocketTimeoutException(),
            SocketException(),
            IOException(),
        )) {
            assertEquals(DialFault.REJECTED, dialDiagnosis(error, upgraded = true))
        }
    }
}

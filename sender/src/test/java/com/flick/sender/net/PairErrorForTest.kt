package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * One dial, two vocabularies. The rule this file exists to hold is that no arm ever
 * produces copy telling the user to check a Wi-Fi both devices are demonstrably on.
 */
class PairErrorForTest {

    @Test
    fun `each dial fault keeps its own pairing error`() {
        assertEquals(
            PairErrorKind.REFUSED,
            pairErrorForFault(DialFault.REFUSED, sameSubnet = false, offNetwork = false),
        )
        assertEquals(
            PairErrorKind.NO_ROUTE,
            pairErrorForFault(DialFault.NO_ROUTE, sameSubnet = false, offNetwork = false),
        )
        assertEquals(
            PairErrorKind.NO_NETWORK,
            pairErrorForFault(DialFault.NO_NETWORK, sameSubnet = false, offNetwork = true),
        )
        assertEquals(
            PairErrorKind.REJECTED,
            pairErrorForFault(DialFault.REJECTED, sameSubnet = false, offNetwork = false),
        )
    }

    // The one arm the subnet moves: silence is the only outcome the phone cannot resolve
    // on its own, and a shared /24 is what lets it rule the Wi-Fi out.
    @Test
    fun `silence on a shared subnet stops blaming the Wi-Fi`() {
        assertEquals(
            PairErrorKind.NO_ANSWER,
            pairErrorForFault(DialFault.NO_ANSWER, sameSubnet = true, offNetwork = false),
        )
        assertEquals(
            PairErrorKind.UNREACHABLE,
            pairErrorForFault(DialFault.NO_ANSWER, sameSubnet = false, offNetwork = false),
        )
    }

    /**
     * NO_NETWORK is mapped from a bare `SocketException`, which a phone sitting on its own
     * Wi-Fi reaches through a VPN that blocks LAN traffic, a torn-down socket or an
     * exhausted descriptor table. "This phone isn't on a Wi-Fi network" may be said only
     * where the phone was asked and agreed.
     */
    @Test
    fun `no network is claimed only where the phone has none`() {
        assertEquals(
            PairErrorKind.NO_NETWORK,
            pairErrorForFault(DialFault.NO_NETWORK, sameSubnet = false, offNetwork = true),
        )
        assertEquals(
            PairErrorKind.UNREACHABLE,
            pairErrorForFault(DialFault.NO_NETWORK, sameSubnet = false, offNetwork = false),
        )
        assertEquals(
            PairErrorKind.UNREACHABLE,
            pairErrorForFault(DialFault.NO_NETWORK, sameSubnet = true, offNetwork = false),
        )
    }

    @Test
    fun `the subnet moves nothing else`() {
        for (fault in DialFault.entries) {
            if (fault == DialFault.NO_ANSWER) continue
            for (offNetwork in listOf(false, true)) {
                assertEquals(
                    fault.name,
                    pairErrorForFault(fault, sameSubnet = false, offNetwork = offNetwork),
                    pairErrorForFault(fault, sameSubnet = true, offNetwork = offNetwork),
                )
            }
        }
    }

    @Test
    fun `an unreachable result carries its fault through`() {
        assertEquals(
            PairErrorKind.NO_ROUTE,
            pairErrorFor(
                ControlClient.Result.Unreachable(fault = DialFault.NO_ROUTE),
                sameSubnet = true,
                offNetwork = false,
            ),
        )
        assertEquals(
            PairErrorKind.REFUSED,
            pairErrorFor(
                ControlClient.Result.Unreachable(fault = DialFault.REFUSED),
                sameSubnet = true,
                offNetwork = false,
            ),
        )
    }

    /**
     * A code that left the phone means the receiver was reached and a person did not
     * answer at the TV, which is the only case the shipped timeout copy is about. A dial
     * that never landed sent no code and gets a dial face instead.
     */
    @Test
    fun `a timeout splits on whether the code was spent`() {
        assertEquals(
            PairErrorKind.TIMED_OUT,
            pairErrorFor(
                ControlClient.Result.TimedOut(pairCodeSent = true),
                sameSubnet = true,
                offNetwork = false,
            ),
        )
        assertEquals(
            PairErrorKind.NO_ANSWER,
            pairErrorFor(
                ControlClient.Result.TimedOut(pairCodeSent = false),
                sameSubnet = true,
                offNetwork = false,
            ),
        )
        assertEquals(
            PairErrorKind.UNREACHABLE,
            pairErrorFor(
                ControlClient.Result.TimedOut(pairCodeSent = false),
                sameSubnet = false,
                offNetwork = false,
            ),
        )
    }

    @Test
    fun `an active rejection is the TV's own answer`() {
        assertEquals(
            PairErrorKind.REJECTED,
            pairErrorFor(ControlClient.Result.RejectedByTv(), sameSubnet = false, offNetwork = false),
        )
    }

    /**
     * Past the upgrade the address, the port and the code were all demonstrably fine, so
     * telling the user to retype three correct fields is the textbook wrong instruction.
     */
    @Test
    fun `a protocol error never asks for the fields again`() {
        val kind = pairErrorFor(ControlClient.Result.ProtocolError(), sameSubnet = false, offNetwork = false)
        assertEquals(PairErrorKind.UPDATE_REQUIRED, kind)
        assertNotEquals(PairErrorKind.INVALID_ENTRY, kind)
    }

    @Test
    fun `an explicit version refusal keeps its own answer`() {
        assertEquals(
            PairErrorKind.UPDATE_REQUIRED,
            pairErrorFor(ControlClient.Result.UpdateRequired, sameSubnet = false, offNetwork = false),
        )
    }

    @Test
    fun `each dial fault keeps its own local terminal code`() {
        assertEquals("control_refused", controlFaultCode(DialFault.REFUSED, offNetwork = false))
        assertEquals("control_no_route", controlFaultCode(DialFault.NO_ROUTE, offNetwork = false))
        assertEquals("control_no_answer", controlFaultCode(DialFault.NO_ANSWER, offNetwork = false))
        assertEquals("control_no_network", controlFaultCode(DialFault.NO_NETWORK, offNetwork = true))
        assertEquals("control_rejected", controlFaultCode(DialFault.REJECTED, offNetwork = false))
    }

    /**
     * The cast face has the same duty as the pairing copy: the no-Wi-Fi face tells the
     * user to join a network, and a phone holding a link and an address is already on one.
     */
    @Test
    fun `the no-Wi-Fi code needs the phone to agree it has no network`() {
        assertEquals("control_unreachable", controlFaultCode(DialFault.NO_NETWORK, offNetwork = false))
        for (fault in DialFault.entries) {
            if (fault == DialFault.NO_NETWORK) continue
            assertEquals(
                fault.name,
                controlFaultCode(fault, offNetwork = false),
                controlFaultCode(fault, offNetwork = true),
            )
        }
    }

    /**
     * The wire vocabulary is frozen. These codes are raised in-process and never leave
     * the phone, and `ControlFrameSchema.failureCodes` is an inbound allow-list — so a
     * new one must never become something a receiver is permitted to send.
     */
    @Test
    fun `the four new dial codes are not wire codes`() {
        val wire = setOf(
            "update_required", "control_unreachable", "source_unavailable", "no_compatible_lan",
            "media_bind_failed", "host_mismatch", "media_unreachable", "sender_not_serving",
            "http_rejected", "tv_backgrounded", "malformed_media", "unsupported_container",
            "unsupported_video_format", "unsupported_video_codec", "unsupported_hdr_profile",
            "decoder_init", "startup_timeout", "control_disconnected", "active_cast_busy",
            "protocol_error", "unknown",
        )
        for (fault in DialFault.entries) {
            for (offNetwork in listOf(false, true)) {
                val code = controlFaultCode(fault, offNetwork)
                // The one residual: NO_NETWORK without the evidence for it falls back to
                // the shipped wire code rather than inventing a new claim.
                if (code == "control_unreachable") continue
                assertEquals(fault.name, false, code in wire)
            }
        }
    }
}

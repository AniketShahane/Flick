package com.flick.sender.ui.screens

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.model.TerminalOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every terminal code the control channel defines, plus one from a receiver newer than
 * this build. The wire list is `ControlFrameSchema.failureCodes`; a code added there
 * without a face here still lands on a real face, it just lands on the generic one.
 */
class CastErrorPresentationTest {

    private fun face(
        code: String,
        kind: CastErrorKind = CastErrorKind.GENERIC,
        linkStarved: Boolean = false,
        origin: TerminalOrigin = TerminalOrigin.RECEIVER,
        httpStatus: Int? = null,
    ): CastErrorFace = castErrorFace(code, kind, linkStarved, origin, httpStatus)

    private fun present(
        code: String,
        kind: CastErrorKind = CastErrorKind.GENERIC,
        retryable: Boolean = false,
        canPlayOnPhone: Boolean = true,
        linkStarved: Boolean = false,
        origin: TerminalOrigin = TerminalOrigin.RECEIVER,
    ): CastErrorPresentation = castErrorPresentation(
        kind,
        CastFailure(code = code, retryable = retryable, origin = origin),
        canPlayOnPhone = canPlayOnPhone,
        linkStarved = linkStarved,
    )

    @Test
    fun `media diagnoses get their own faces`() {
        assertEquals(CastErrorFace.UNSUPPORTED_CONTAINER, face("unsupported_container"))
        assertEquals(CastErrorFace.UNSUPPORTED_VIDEO, face("unsupported_video_format"))
        assertEquals(CastErrorFace.UNSUPPORTED_VIDEO, face("unsupported_video_codec"))
        assertEquals(CastErrorFace.UNSUPPORTED_HDR, face("unsupported_hdr_profile"))
        assertEquals(CastErrorFace.DAMAGED_FILE, face("malformed_media"))
        assertEquals(CastErrorFace.UNREADABLE_SOURCE, face("source_unavailable"))
        assertEquals(CastErrorFace.DECODER_UNAVAILABLE, face("decoder_init"))
    }

    @Test
    fun `session diagnoses get their own faces`() {
        assertEquals(CastErrorFace.TV_APP_CLOSED, face("tv_backgrounded"))
        assertEquals(CastErrorFace.TV_BUSY, face("active_cast_busy"))
        assertEquals(CastErrorFace.UPDATE_REQUIRED, face("update_required"))
        assertEquals(CastErrorFace.SLOW_START, face("startup_timeout"))
    }

    // The whole of the local re-facing: one code, two faces, decided by what the phone
    // measured while it was serving. Nothing else on the wire may move when it flips.
    @Test
    fun `a starved link re-faces the startup timeout and nothing else`() {
        assertEquals(CastErrorFace.SLOW_LINK, face("startup_timeout", linkStarved = true))
        assertEquals(CastErrorFace.SLOW_START, face("startup_timeout", linkStarved = false))
        for (code in WireCodes + "a_code_from_a_newer_receiver") {
            if (code == "startup_timeout") continue
            for (kind in CastErrorKind.entries) {
                assertEquals(
                    "$code/$kind changed face on a starved link",
                    face(code, kind, linkStarved = false),
                    face(code, kind, linkStarved = true),
                )
            }
        }
    }

    // `startup_timeout` still arrives retryable, and a short link is the one timeout where
    // a second attempt is worth leading with — but the phone must be the move under it.
    @Test
    fun `the slow link face keeps retry and offers the phone beneath it`() {
        val retried = present("startup_timeout", retryable = true, linkStarved = true)
        assertEquals(CastErrorFace.SLOW_LINK, retried.face)
        assertEquals(CastErrorAction.RETRY, retried.primary)
        assertEquals(CastErrorAction.PLAY_ON_PHONE, retried.secondary)

        // The same face with the retry withdrawn leads with the phone rather than the
        // library: the file and the TV are both fine, so those bytes still decode here.
        val permanent = present("startup_timeout", linkStarved = true)
        assertEquals(CastErrorAction.PLAY_ON_PHONE, permanent.primary)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, permanent.secondary)

        val noFile = present("startup_timeout", linkStarved = true, canPlayOnPhone = false)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, noFile.primary)
        assertNull(noFile.secondary)
    }

    // The codes with no diagnosis of their own keep the kind the controller derived.
    @Test
    fun `transport codes fall back to their kind`() {
        assertEquals(CastErrorFace.NO_LAN, face("host_mismatch", CastErrorKind.NO_LAN))
        assertEquals(CastErrorFace.UNREACHABLE, face("control_unreachable", CastErrorKind.UNREACHABLE))
        assertEquals(CastErrorFace.GENERIC, face("protocol_error"))
        assertEquals(CastErrorFace.GENERIC, face("unknown"))
        // The floor for a kind with no code this build recognises. It survives so a
        // receiver newer than this phone still lands on a face rather than a guess.
        assertEquals(
            CastErrorFace.NOT_SERVING,
            face("a_code_from_a_newer_receiver", CastErrorKind.REACHABLE_NOT_SERVING),
        )
    }

    /**
     * The three codes that used to wear "Your TV is there — but not listening / Wake the
     * TV app". Every one of them is raised by a fault on THIS phone: an RST from its own
     * 8080, its own bind failing, and its own server answering with a refusal.
     */
    @Test
    fun `the phone's own server faults name the phone`() {
        assertEquals(CastErrorFace.PHONE_NOT_SERVING, face("sender_not_serving"))
        assertEquals(CastErrorFace.SERVER_NOT_STARTED, face("media_bind_failed"))
        assertEquals(CastErrorFace.PHONE_REFUSED, face("http_rejected"))
        assertEquals(CastErrorFace.SOURCE_LOST, face("source_lost"))
        assertEquals(CastErrorFace.PHONE_SLOW_START, face("source_start_timeout"))
        assertEquals(CastErrorFace.COMMAND_NOT_SENT, face("load_not_sent"))
    }

    /**
     * Android refusing the foreground-service start is not a port another app is holding.
     * Nothing was bound when it fired, so the bind failure's face — which names that port —
     * would be a cause invented for a refusal that named itself. It is reachable from the
     * window that waits a router block out: the path can come back while Flick is in
     * someone's pocket.
     */
    @Test
    fun `a refused service start never borrows the bind failure's face`() {
        assertEquals(CastErrorFace.SERVER_NOT_ALLOWED, face("media_start_refused"))
        assertNotEquals(CastErrorFace.SERVER_NOT_STARTED, face("media_start_refused"))
        val presentation = present("media_start_refused", origin = TerminalOrigin.LOCAL)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, presentation.primary)
        assertNull(presentation.secondary)
    }

    // Only the transfer cap gets the capacity face; every other refusal is a refusal.
    @Test
    fun `a 503 from this phone's own server is capacity, not a refusal`() {
        assertEquals(CastErrorFace.SERVER_BUSY, face("http_rejected", httpStatus = 503))
        assertEquals(CastErrorFace.PHONE_REFUSED, face("http_rejected", httpStatus = 403))
        assertEquals(CastErrorFace.PHONE_REFUSED, face("http_rejected", httpStatus = 404))
        assertEquals(CastErrorFace.PHONE_REFUSED, face("http_rejected", httpStatus = null))
    }

    @Test
    fun `each dial fault gets its own face`() {
        assertEquals(CastErrorFace.RECEIVER_NOT_OPEN, face("control_refused"))
        assertEquals(CastErrorFace.ROUTER_BLOCKING, face("control_no_route"))
        assertEquals(CastErrorFace.NO_ANSWER, face("control_no_answer"))
        assertEquals(CastErrorFace.NO_LAN, face("control_no_network"))
        assertEquals(CastErrorFace.NO_LAN, face("no_lan_address"))
        // The upgrade completed and the receiver then closed, which proves the TV is awake
        // with Flick running — the two things the residual unreachable face denies.
        assertEquals(CastErrorFace.RECEIVER_TURNED_AWAY, face("control_rejected"))
    }

    /**
     * The TV could not fetch the film from THIS phone, and it said so over the control
     * socket the phone is holding — so the one face it may not wear is "can't reach the
     * TV", and the one move it may not offer is a rescan for a TV that just spoke.
     */
    @Test
    fun `a blocked media path names the direction that was blocked`() {
        assertEquals(
            CastErrorFace.MEDIA_PATH_BLOCKED,
            face("media_unreachable", CastErrorKind.UNREACHABLE),
        )
        for (retryable in listOf(false, true)) {
            val presentation = present("media_unreachable", CastErrorKind.UNREACHABLE, retryable = retryable)
            assertEquals(CastErrorFace.MEDIA_PATH_BLOCKED, presentation.face)
            assertNotEquals(CastErrorAction.OPEN_CONNECT, presentation.primary)
            assertNotEquals(CastErrorAction.OPEN_CONNECT, presentation.secondary)
        }
    }

    /**
     * The same disconnect, split on the one thing the phone can prove about it: no LAN
     * address of its own at the moment the terminal was raised. That has a control behind
     * it, and the link-dropped face offers none.
     */
    @Test
    fun `a disconnect under a phone with no LAN offers Wi-Fi settings`() {
        val presentation = present("control_disconnected_no_lan", CastErrorKind.UNREACHABLE)
        assertEquals(CastErrorFace.PHONE_LEFT_NETWORK, presentation.face)
        assertEquals(CastErrorAction.OPEN_WIFI_SETTINGS, presentation.primary)
    }

    // A dial that succeeded and a write to this phone that did not. Nothing here may name
    // the TV: it answered, and the pairing form has always said so honestly.
    @Test
    fun `a failed pairing write never blames the TV`() {
        val presentation = present("pairing_store_failed", origin = TerminalOrigin.LOCAL)
        assertEquals(CastErrorFace.PAIRING_NOT_SAVED, presentation.face)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, presentation.primary)
        assertNull(presentation.secondary)
    }

    /**
     * Its copy indicts this phone, so a button about the TV under it is the contradiction
     * the phone-side faces were split out to end.
     */
    @Test
    fun `the not-serving floor never offers a move about the TV`() {
        for (retryable in listOf(false, true)) {
            val presentation = present(
                "a_code_from_a_newer_receiver",
                CastErrorKind.REACHABLE_NOT_SERVING,
                retryable = retryable,
            )
            assertEquals(CastErrorFace.NOT_SERVING, presentation.face)
            assertNotEquals(CastErrorAction.OPEN_CONNECT, presentation.primary)
            assertNotEquals(CastErrorAction.OPEN_CONNECT, presentation.secondary)
        }
    }

    /**
     * The one code whose meaning depends on who raised it. The receiver sends it when the
     * TV's own address went away mid-cast; this phone raises the same code about itself
     * before a byte leaves. Nothing in the code separates them.
     */
    @Test
    fun `no compatible lan means opposite things by origin`() {
        assertEquals(
            CastErrorFace.NO_LAN,
            face("no_compatible_lan", CastErrorKind.NO_LAN, origin = TerminalOrigin.LOCAL),
        )
        assertEquals(
            CastErrorFace.TV_LOST_NETWORK,
            face("no_compatible_lan", CastErrorKind.NO_LAN, origin = TerminalOrigin.RECEIVER),
        )
    }

    /**
     * A link that carried tens of megabits a second a minute ago is not client isolation:
     * that fault cannot appear mid-stream, so this must not wear the unreachable face.
     */
    @Test
    fun `a mid-film disconnect is not an unreachable TV`() {
        assertEquals(CastErrorFace.LINK_DROPPED, face("control_disconnected", CastErrorKind.UNREACHABLE))
    }

    // The same bytes are unreadable on this phone too, so offering to play them here
    // would be a button that provably cannot work.
    @Test
    fun `a lost source never offers the phone`() {
        for (retryable in listOf(false, true)) {
            val presentation = present("source_lost", retryable = retryable)
            assertEquals(CastErrorFace.SOURCE_LOST, presentation.face)
            assertNotEquals(CastErrorAction.PLAY_ON_PHONE, presentation.primary)
            assertNotEquals(CastErrorAction.PLAY_ON_PHONE, presentation.secondary)
        }
    }

    /**
     * The fix is on the router, not on the phone. Sending the user to rejoin a network
     * they are provably already on is the shipped mistake this face exists to end.
     */
    @Test
    fun `a blocking router never offers Wi-Fi settings`() {
        for (retryable in listOf(false, true)) {
            val presentation = present("control_no_route", retryable = retryable)
            assertEquals(CastErrorFace.ROUTER_BLOCKING, presentation.face)
            assertNotEquals(CastErrorAction.OPEN_WIFI_SETTINGS, presentation.primary)
            assertNotEquals(CastErrorAction.OPEN_WIFI_SETTINGS, presentation.secondary)
        }
    }

    @Test
    fun `every new face still offers a move that is not a retry`() {
        for (code in LocalCodes) {
            val permanent = present(code, origin = TerminalOrigin.LOCAL)
            assertTrue("$code had no move at all", permanent.primary != CastErrorAction.RETRY)
            val retried = present(code, retryable = true, origin = TerminalOrigin.LOCAL)
            assertEquals(code, CastErrorAction.RETRY, retried.primary)
            assertNotNullAndNotRetry(code, retried.secondary)
        }
    }

    // A receiver from the future is the case that must not crash or invent a diagnosis.
    @Test
    fun `an unrecognised code degrades to the generic face`() {
        assertEquals(CastErrorFace.GENERIC, face("unsupported_audio_atmos_v9"))
        assertEquals(CastErrorFace.UNREACHABLE, face("", CastErrorKind.UNREACHABLE))
        val presentation = present("quantum_desync")
        assertEquals(CastErrorFace.GENERIC, presentation.face)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, presentation.primary)
        assertNull(presentation.secondary)
    }

    // The observed failure, end to end: permanent, precisely named, and never a retry.
    @Test
    fun `unsupported container offers the phone and the library, never a retry`() {
        val presentation = present("unsupported_container")
        assertEquals(CastErrorFace.UNSUPPORTED_CONTAINER, presentation.face)
        assertEquals(CastErrorAction.PLAY_ON_PHONE, presentation.primary)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, presentation.secondary)
    }

    @Test
    fun `no code that is not retryable may offer a retry`() {
        for (code in WireCodes + "a_code_from_a_newer_receiver") {
            for (kind in CastErrorKind.entries) {
                for (starved in listOf(false, true)) {
                    val presentation = present(code, kind, linkStarved = starved)
                    assertNotEquals("$code/$kind primary", CastErrorAction.RETRY, presentation.primary)
                    assertNotEquals("$code/$kind secondary", CastErrorAction.RETRY, presentation.secondary)
                }
            }
        }
    }

    @Test
    fun `a retryable failure leads with retry and keeps its permanent move`() {
        for (code in WireCodes) {
            for (starved in listOf(false, true)) {
                val presentation = present(code, retryable = true, linkStarved = starved)
                assertEquals(code, CastErrorAction.RETRY, presentation.primary)
                assertNotNullAndNotRetry(code, presentation.secondary)
            }
        }
    }

    // A dead button is the defect this screen was rebuilt for: with no remembered item
    // there is nothing to hand an external player, so nothing may offer to.
    @Test
    fun `play on phone is never offered without a file to play`() {
        for (code in WireCodes) {
            for (retryable in listOf(true, false)) {
                for (starved in listOf(false, true)) {
                    val presentation = present(
                        code,
                        retryable = retryable,
                        canPlayOnPhone = false,
                        linkStarved = starved,
                    )
                    assertNotEquals("$code primary", CastErrorAction.PLAY_ON_PHONE, presentation.primary)
                    assertNotEquals("$code secondary", CastErrorAction.PLAY_ON_PHONE, presentation.secondary)
                }
            }
        }
    }

    @Test
    fun `the two slots never carry the same move`() {
        for (code in WireCodes + "unheard_of") {
            for (kind in CastErrorKind.entries) {
                for (retryable in listOf(true, false)) {
                    for (canPlay in listOf(true, false)) {
                        for (starved in listOf(false, true)) {
                            val presentation = present(code, kind, retryable, canPlay, starved)
                            assertTrue(
                                "$code/$kind/$retryable/$canPlay/$starved repeated a move",
                                presentation.secondary != presentation.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every face is reachable from some code this build can raise`() {
        val faces = buildSet {
            for (code in WireCodes + LocalCodes) {
                for (kind in CastErrorKind.entries) {
                    for (starved in listOf(false, true)) {
                        for (origin in TerminalOrigin.entries) {
                            for (status in listOf(null, 503)) {
                                add(castErrorFace(code, kind, starved, origin, status))
                            }
                        }
                    }
                }
            }
        }
        assertEquals(CastErrorFace.entries.toSet(), faces)
    }

    private fun assertNotNullAndNotRetry(code: String, action: CastErrorAction?) {
        assertTrue("$code lost its permanent move", action != null)
        assertFalse("$code offered retry twice", action == CastErrorAction.RETRY)
    }

    private companion object {
        /**
         * Codes this phone raises about itself. None is on the wire —
         * `ControlFrameSchema.failureCodes` is an INBOUND allow-list, so adding one there
         * would only widen what an un-updated receiver may say.
         */
        val LocalCodes = listOf(
            "source_lost", "no_lan_address", "source_start_timeout", "load_not_sent",
            "control_refused", "control_no_route", "control_no_answer", "control_no_network",
            "control_rejected", "control_disconnected_no_lan", "pairing_store_failed",
            "media_start_refused",
        )

        /** Verbatim `ControlFrameSchema.failureCodes`, which is the wire's own list. */
        val WireCodes = listOf(
            "update_required", "control_unreachable", "source_unavailable", "no_compatible_lan",
            "media_bind_failed", "host_mismatch", "media_unreachable", "sender_not_serving",
            "http_rejected", "tv_backgrounded", "malformed_media", "unsupported_container",
            "unsupported_video_format", "unsupported_video_codec", "unsupported_hdr_profile",
            "decoder_init", "startup_timeout", "control_disconnected", "active_cast_busy",
            "protocol_error", "unknown",
        )
    }
}

package com.flick.sender.ui.screens

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
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
    ): CastErrorFace = castErrorFace(code, kind, linkStarved)

    private fun present(
        code: String,
        kind: CastErrorKind = CastErrorKind.GENERIC,
        retryable: Boolean = false,
        canPlayOnPhone: Boolean = true,
        linkStarved: Boolean = false,
    ): CastErrorPresentation = castErrorPresentation(
        kind,
        CastFailure(code = code, retryable = retryable),
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
        assertEquals(CastErrorFace.NO_LAN, face("no_compatible_lan", CastErrorKind.NO_LAN))
        assertEquals(CastErrorFace.NO_LAN, face("host_mismatch", CastErrorKind.NO_LAN))
        assertEquals(
            CastErrorFace.NOT_SERVING,
            face("sender_not_serving", CastErrorKind.REACHABLE_NOT_SERVING),
        )
        assertEquals(CastErrorFace.NOT_SERVING, face("http_rejected", CastErrorKind.REACHABLE_NOT_SERVING))
        assertEquals(CastErrorFace.NOT_SERVING, face("media_bind_failed", CastErrorKind.REACHABLE_NOT_SERVING))
        assertEquals(CastErrorFace.UNREACHABLE, face("media_unreachable", CastErrorKind.UNREACHABLE))
        assertEquals(CastErrorFace.UNREACHABLE, face("control_unreachable", CastErrorKind.UNREACHABLE))
        assertEquals(CastErrorFace.UNREACHABLE, face("control_disconnected", CastErrorKind.UNREACHABLE))
        assertEquals(CastErrorFace.GENERIC, face("protocol_error"))
        assertEquals(CastErrorFace.GENERIC, face("unknown"))
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
    fun `every face is reachable from some wire code`() {
        val faces = buildSet {
            for (code in WireCodes) {
                for (kind in CastErrorKind.entries) {
                    for (starved in listOf(false, true)) add(castErrorFace(code, kind, starved))
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

package com.flick.sender.net

import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.model.TerminalOrigin
import com.flick.sender.ui.screens.CastErrorAction
import com.flick.sender.ui.screens.CastErrorFace
import com.flick.sender.ui.screens.castErrorFace
import com.flick.sender.ui.screens.castErrorPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure this answers: a TV that had just booted and was compacting another app's
 * heap answered a ping in 23 ms and then took more than two seconds to adopt the load.
 * The phone called that `startup_timeout` and raised "the TV accepted the cast but never
 * reached its first frame" — false on both counts — and the next tap played normally.
 */
class CastStartupPolicyTest {

    private val accept = CastStartupPolicy.ACCEPT_TIMEOUT_CODE

    @Test fun theAcceptWaitOutlastsAStallAndStaysInsideTheFirstFrameWait() {
        assertTrue(CastStartupPolicy.ACCEPT_TIMEOUT_MS >= 10_000L)
        assertTrue(CastStartupPolicy.ACCEPT_TIMEOUT_MS < CastStartupPolicy.FIRST_FRAME_TIMEOUT_MS)
        assertEquals(18_000L, CastStartupPolicy.FIRST_FRAME_TIMEOUT_MS)
    }

    @Test fun onlyAnAcceptanceSlowerThanTheOldBudgetIsLogged() {
        assertFalse(CastStartupPolicy.acceptWasSlow(3L))
        assertFalse(CastStartupPolicy.acceptWasSlow(CastStartupPolicy.SLOW_ACCEPT_MS))
        assertTrue(CastStartupPolicy.acceptWasSlow(CastStartupPolicy.SLOW_ACCEPT_MS + 1))
    }

    /** The measured case: nothing at all came back after the load. */
    @Test fun aSilentAcceptTimeoutIsWorthOneQuietRedial() {
        assertEquals(
            StartupRetryReason.ACCEPT_SILENT,
            CastStartupPolicy.reasonForFailure(accept, StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = false),
        )
    }

    /** Any frame after the load is a receiver that is present, whatever it did not say. */
    @Test fun anAcceptTimeoutOnASocketThatSpokeIsNotRetried() {
        assertNull(CastStartupPolicy.reasonForFailure(accept, StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = true))
    }

    /**
     * Reaching the first-frame wait took an accept, and the receiver's own verdicts carry
     * what a second attempt would only repeat. Faults this phone raised about its own
     * server or file are not the TV's to be given another chance at.
     */
    @Test fun noOtherStartupFailureIsRetried() {
        assertNull(CastStartupPolicy.reasonForFailure("startup_timeout", StartupStage.AWAITING_FIRST_FRAME, heardSinceLoad = false))
        assertNull(CastStartupPolicy.reasonForFailure(accept, StartupStage.AWAITING_FIRST_FRAME, heardSinceLoad = false))
        for (code in listOf(
            "load_not_sent", "source_start_timeout", "source_unavailable", "media_bind_failed",
            "media_start_refused", "no_lan_address", "no_compatible_lan", "control_unreachable",
            "unsupported_video_codec", "http_rejected", "media_unreachable", "tv_backgrounded",
            "active_cast_busy", "decoder_init", "unknown",
        )) {
            for (stage in StartupStage.entries) {
                assertNull("$code/$stage", CastStartupPolicy.reasonForFailure(code, stage, heardSinceLoad = false))
            }
        }
    }

    /** A loss after Active is [ControlRecoveryPolicy]'s, weighed against the media path. */
    @Test fun aControlLossIsAStartupRetryOnlyBeforeActive() {
        assertEquals(StartupRetryReason.CONTROL_LOST, CastStartupPolicy.reasonForControlLoss(reachedActive = false))
        assertNull(CastStartupPolicy.reasonForControlLoss(reachedActive = true))
    }

    @Test fun exactlyOneRetryPerUserCast() {
        for (reason in StartupRetryReason.entries) {
            assertTrue(CastStartupPolicy.retries(reason, retriesSpent = 0, canDial = true, phoneOnLan = true, foregroundAllowed = true))
            assertFalse(CastStartupPolicy.retries(reason, retriesSpent = 1, canDial = true, phoneOnLan = true, foregroundAllowed = true))
            assertFalse(CastStartupPolicy.retries(reason, retriesSpent = 2, canDial = true, phoneOnLan = true, foregroundAllowed = true))
        }
    }

    @Test fun noReasonNoRetry() {
        assertFalse(CastStartupPolicy.retries(null, retriesSpent = 0, canDial = true, phoneOnLan = true, foregroundAllowed = true))
    }

    /** A resume needs a trusted pairing, and a phone off the network is its own face. */
    @Test fun aRetryNeedsAPairingAndALan() {
        for (reason in StartupRetryReason.entries) {
            assertFalse(CastStartupPolicy.retries(reason, retriesSpent = 0, canDial = false, phoneOnLan = true, foregroundAllowed = true))
            assertFalse(CastStartupPolicy.retries(reason, retriesSpent = 0, canDial = true, phoneOnLan = false, foregroundAllowed = true))
        }
    }

    /**
     * The retry's teardown stops the cast's media service and the re-dial must start a new
     * one, which a backgrounded phone is refused: it keeps the original, retryable face.
     */
    @Test fun aBackgroundedPhoneKeepsTheOriginalFace() {
        for (reason in StartupRetryReason.entries) {
            assertFalse(CastStartupPolicy.retries(reason, retriesSpent = 0, canDial = true, phoneOnLan = true, foregroundAllowed = false))
        }
    }

    /** The two names the log line carries, which the diagnostics doc quotes. */
    @Test fun retryReasonsLogUnderStableNames() {
        assertEquals("accept_silent", StartupRetryReason.ACCEPT_SILENT.logName)
        assertEquals("control_lost", StartupRetryReason.CONTROL_LOST.logName)
    }

    /** Never on the wire: the inbound vocabulary may not learn a code only this phone raises. */
    @Test fun theAcceptCodeIsPhoneLocal() {
        val castId = "MDEyMzQ1Njc4OWFiY2RlZg"
        assertTrue(ControlFrameSchema.event(mapOf("t" to "loadFailed", "v" to 2, "castId" to castId, "code" to "startup_timeout", "retryable" to true)))
        assertFalse(ControlFrameSchema.event(mapOf("t" to "loadFailed", "v" to 2, "castId" to castId, "code" to accept, "retryable" to true)))
        assertTrue(ControlLiveness.hangsUpAfterStartupFailure(accept, StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = false))
        assertFalse(ControlLiveness.hangsUpAfterStartupFailure(accept, StartupStage.AWAITING_ACCEPTANCE, heardSinceLoad = true))
    }

    /** The face may not claim an accept the TV never gave, and must offer the film back. */
    @Test fun theAcceptTimeoutWearsItsOwnFaceAndOffersRetry() {
        for (kind in CastErrorKind.entries) {
            for (starved in listOf(false, true)) {
                assertEquals(
                    CastErrorFace.LOAD_UNANSWERED,
                    castErrorFace(accept, kind, starved, TerminalOrigin.LOCAL, null),
                )
            }
        }
        val presentation = castErrorPresentation(
            CastErrorKind.GENERIC,
            CastFailure(code = accept, retryable = true, origin = TerminalOrigin.LOCAL),
            canPlayOnPhone = true,
            linkStarved = false,
        )
        assertEquals(CastErrorAction.RETRY, presentation.primary)
        assertEquals(CastErrorAction.BACK_TO_LIBRARY, presentation.secondary)
        assertTrue(castRetryOffered(retryable = true, hasCastRecord = true))
    }

    /** A TV that was busy says nothing about the file. */
    @Test fun theAcceptTimeoutNeverMarksTheFile() {
        assertFalse(marksFileUnplayable(accept))
        assertFalse(marksFileUnplayable(accept, repeatedDecoderFault = true))
    }

    /**
     * The silent accept's re-dial waits out the TV's own adoption budget, or a TV catching
     * up would adopt the abandoned load and answer the resume `busy` over it.
     */
    @Test fun aSilentAcceptRedialWaitsOutTheReceiversAdoptionBudget() {
        val receiverAdoptionBudgetMs = 4_000L
        assertTrue(CastStartupPolicy.redialHoldMs(StartupRetryReason.ACCEPT_SILENT) > receiverAdoptionBudgetMs)
        assertEquals(0L, CastStartupPolicy.redialHoldMs(StartupRetryReason.CONTROL_LOST))
    }

    /** A busy met by the retry's own dial is the original failure, never another phone. */
    @Test fun aBusyDuringTheRetryWearsTheOriginalFace() {
        assertEquals(accept, CastStartupPolicy.busyFaceCode(StartupRetryReason.ACCEPT_SILENT))
        assertEquals("control_disconnected", CastStartupPolicy.busyFaceCode(StartupRetryReason.CONTROL_LOST))
        assertEquals("active_cast_busy", CastStartupPolicy.busyFaceCode(null))
    }

    /** A dial that ended with no cast releases the connecting face, and nothing else does. */
    @Test fun onlyAStartupFaceWithNoCastBehindItIsOrphaned() {
        val id = "A"
        for (state in listOf(
            CastStartState.ConnectingControl(id), CastStartState.StartingSource(id),
            CastStartState.AwaitingAcceptance(id), CastStartState.AwaitingFirstFrame(id),
            CastStartState.Active(id),
        )) {
            assertTrue("$state", CastStartupPolicy.orphanedStart(state, currentCastId = null))
            assertFalse("$state", CastStartupPolicy.orphanedStart(state, currentCastId = id))
        }
        assertFalse(CastStartupPolicy.orphanedStart(CastStartState.Idle, currentCastId = null))
        assertFalse(CastStartupPolicy.orphanedStart(CastStartState.Failed(id, accept), currentCastId = null))
    }
}

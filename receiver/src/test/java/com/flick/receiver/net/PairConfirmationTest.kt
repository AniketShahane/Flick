package com.flick.receiver.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-TV "Allow this phone?" confirmation.
 *
 * It exists because the QR carries the live code (payload v4), so reading the pairing
 * screen is enough to submit a correct code on the FIRST try — and the cumulative
 * failure ceiling cannot bound that, because it charges only wrong codes. The
 * confirmation puts physical presence in the room back as the real factor.
 *
 * The properties pinned here are the ones the feature is worth nothing without: a
 * wrong code cannot reach the long window and is answered exactly as it always was,
 * the window can only ever resolve to a refusal, and one decision can mint at most one
 * key.
 */
class PairConfirmationTest {

    private fun ticket(windowMs: Long = PairingManager.CONFIRM_WINDOW_MS) = PairConfirmation(
        deviceLabel = "Pixel",
        generation = 7L,
        expiresAtElapsedMs = windowMs,
        peerHost = "192.168.1.20",
    )

    // --- The phase gate: what may outlive the six-second deadline ---------------

    /**
     * The whole security claim. `ControlServer` wraps `authenticate` — and nothing
     * else — in the six-second absolute deadline, so exactly one outcome is allowed to
     * leave that window. If anything but a proven code could produce it, the slowloris
     * defence the audit credits that deadline with would be gone.
     */
    @Test fun onlyAProvenCodeOpensTheDecisionWindow() {
        assertEquals(
            PairOutcomePhase.CONFIRM,
            pairOutcomePhase(PairAttemptResult.NeedsConfirmation(ticket())),
        )
        val refused = listOf<PairAttemptResult?>(
            PairAttemptResult.InvalidCode,
            PairAttemptResult.Expired,
            PairAttemptResult.SurfaceClosed,
            PairAttemptResult.LockedOut(0L),
            PairAttemptResult.PersistenceFailed,
            // The ownership busy short-circuit, which never reaches PairingManager.
            null,
        )
        for (result in refused) {
            assertEquals(
                "a $result must be answered inside the six-second window",
                PairOutcomePhase.REFUSED,
                pairOutcomePhase(result),
            )
        }
    }

    /**
     * A wrong code must be indistinguishable from what it was before the prompt
     * existed, or the prompt becomes an oracle for a correct one. These are the
     * reasons and the frame shape as they were, asserted as literals rather than
     * against the mapping that produces them.
     */
    @Test fun everyRefusalAnswersExactlyWhatItAnsweredBefore() {
        assertEquals("code", deniedReasonFor(PairAttemptResult.InvalidCode))
        assertEquals("expired", deniedReasonFor(PairAttemptResult.Expired))
        assertEquals("surface", deniedReasonFor(PairAttemptResult.SurfaceClosed))
        assertEquals("locked", deniedReasonFor(PairAttemptResult.LockedOut(0L)))
        assertEquals("storage", deniedReasonFor(PairAttemptResult.PersistenceFailed))
        assertEquals("busy", deniedReasonFor(null))
        val frame = deniedFrameFields(deniedReasonFor(PairAttemptResult.InvalidCode))
        assertEquals(listOf("t", "v", "reason"), frame.keys.toList())
        assertEquals("denied", frame["t"])
        assertEquals(2, frame["v"])
        assertEquals("code", frame["reason"])
    }

    /** No new frame type: every confirmation answer is the frozen v2 `denied` vocabulary. */
    @Test fun aConfirmationDenialUsesOnlyTheFrozenVocabulary() {
        for (outcome in PairConfirmationOutcome.entries) {
            assertTrue(
                "$outcome produced a reason outside the v2 contract set",
                deniedReasonForConfirmation(outcome) in DENIED_REASONS,
            )
        }
        // Expiry says the code expired, which is true in every part of it: the window
        // ran out, the code was consumed proving itself, and a fresh one is on screen.
        assertEquals(DENIED_EXPIRED, deniedReasonForConfirmation(PairConfirmationOutcome.EXPIRED))
        // A deny and a withdrawal both say `surface`. Nothing in the vocabulary names
        // "declined at the TV"; `code` would tell the user to retype digits that were
        // right, and `unknown` is the reason that costs a stored pairing its trust.
        assertEquals(DENIED_SURFACE, deniedReasonForConfirmation(PairConfirmationOutcome.DENIED))
        assertEquals(DENIED_SURFACE, deniedReasonForConfirmation(PairConfirmationOutcome.WITHDRAWN))
        assertNotEquals(DENIED_CODE, deniedReasonForConfirmation(PairConfirmationOutcome.DENIED))
        assertNotEquals(DENIED_PROOF, deniedReasonForConfirmation(PairConfirmationOutcome.DENIED))
    }

    // --- The window resolves to a refusal, never an allow -----------------------

    @Test fun expiryDenies() = runBlocking {
        val ticket = ticket()
        assertEquals(PairConfirmationOutcome.EXPIRED, ticket.resolve(PairConfirmationOutcome.EXPIRED))
        assertEquals(PairConfirmationOutcome.EXPIRED, ticket.await())
        assertNotEquals(PairConfirmationOutcome.ALLOWED, ticket.decided)
    }

    @Test fun anExplicitDenyDenies() = runBlocking {
        val ticket = ticket()
        assertEquals(PairConfirmationOutcome.DENIED, ticket.resolve(PairConfirmationOutcome.DENIED))
        assertEquals(PairConfirmationOutcome.DENIED, ticket.await())
    }

    /**
     * Three parties can settle one decision — a button on the TV, the manager's tick,
     * and the waiting socket's own deadline or hang-up. The first one wins and the
     * others read that answer back, which is what stops the screen and the wire
     * reporting different outcomes.
     */
    @Test fun theDecisionIsSingleShotAndTheFirstAnswerStands() {
        val expired = ticket()
        assertEquals(PairConfirmationOutcome.EXPIRED, expired.resolve(PairConfirmationOutcome.EXPIRED))
        // A late Allow — someone pressing the button as the window closed — must not
        // revive a decision that has already refused.
        assertEquals(PairConfirmationOutcome.EXPIRED, expired.resolve(PairConfirmationOutcome.ALLOWED))
        assertEquals(PairConfirmationOutcome.EXPIRED, expired.decided)

        val allowed = ticket()
        assertEquals(PairConfirmationOutcome.ALLOWED, allowed.resolve(PairConfirmationOutcome.ALLOWED))
        // And the converse: a hang-up or a deadline arriving behind a real Allow does
        // not take the pairing away from a phone the owner admitted.
        assertEquals(PairConfirmationOutcome.ALLOWED, allowed.resolve(PairConfirmationOutcome.WITHDRAWN))
        assertEquals(PairConfirmationOutcome.ALLOWED, allowed.resolve(PairConfirmationOutcome.EXPIRED))
    }

    @Test fun nothingIsDecidedUntilSomethingDecidesIt() {
        assertEquals(null, ticket().decided)
    }

    /** One press, one key. The commit is guarded by this and not by liveness alone. */
    @Test fun oneConfirmationMintsAtMostOneKey() {
        val ticket = ticket()
        ticket.resolve(PairConfirmationOutcome.ALLOWED)
        assertTrue(ticket.consume())
        assertFalse(ticket.consume())
        assertFalse(ticket.consume())
    }

    // --- Asking for a code while a decision is open -----------------------------

    /**
     * Every route that wants a code asks with nobody pressing anything —
     * `onForeground` on a lifecycle event, leaving Settings on the way out — so a
     * prompt that any request could paper over would be papered over by the app being
     * looked at, and the phone waiting on the decision would be holding digits this TV
     * had already rotated away.
     */
    @Test fun aPendingConfirmationRefusesEveryRequestForACode() {
        assertEquals(OpenRefusal.CONFIRMING, openRefusal(sealed = false, confirming = true))
    }

    /**
     * The seal stays on top. It is durable and it is the ceiling from the audit's M3;
     * a confirmation is over within thirty seconds either way, so it may never
     * outrank the one restriction built to survive a process restart.
     */
    @Test fun aSealStillOutranksAConfirmation() {
        assertEquals(OpenRefusal.SEALED, openRefusal(sealed = true, confirming = true))
        assertEquals(OpenRefusal.SEALED, openRefusal(sealed = true, confirming = false))
    }

    @Test fun anOrdinaryRequestIsStillHonoured() {
        assertEquals(OpenRefusal.NONE, openRefusal(sealed = false, confirming = false))
    }

    /** The confirmation did not displace the existing surface ordering. */
    @Test fun theSurfaceOrderingIsUnchanged() {
        assertEquals(SurfaceDecision.SEALED, surfaceDecision(sealed = true, visible = true, lockedOut = false))
        assertEquals(SurfaceDecision.STANDBY, surfaceDecision(sealed = false, visible = false, lockedOut = false))
        assertEquals(SurfaceDecision.LOCKED, surfaceDecision(sealed = false, visible = true, lockedOut = true))
        assertEquals(SurfaceDecision.CODE, surfaceDecision(sealed = false, visible = true, lockedOut = false))
    }

    // --- The window itself ------------------------------------------------------

    /**
     * The deadline is a product and a security claim expressed as an integer, so both
     * bounds are asserted rather than left in a comment.
     */
    @Test fun theWindowIsGenerousEnoughToAnswerAndShortEnoughToHoldASocketOpenFor() {
        val window = PairingManager.CONFIRM_WINDOW_MS
        assertTrue(
            "$window ms is not enough to notice a card, pick up a remote and press one button",
            window >= 20_000L,
        )
        // The pre-auth socket's whole life is the six-second authentication phase plus
        // this window, and Ktor CIO's server connection-idle timeout is 45 s. Crossing
        // it would make the decision losable to a reaped socket.
        assertTrue(
            "6 s of authentication plus a $window ms decision must stay inside CIO's " +
                "45-second idle timeout",
            6_000L + window < 45_000L,
        )
    }
}

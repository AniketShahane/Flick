package com.flick.receiver.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cumulative-failure ceiling.
 *
 * The escalating lockout on its own has a steady state, and the steady state was
 * the whole problem: five wrong codes per eight-minute round is ~900 a day against
 * a 10,000-code keyspace, which is ~9 % a day and about even odds inside a week.
 * Rounds that get slower do not fix it, because nothing ever stops.
 *
 * These tests pin the two properties that make it stop: the ceiling outranks the
 * lockout, and a seal outranks every request to reopen — including the one
 * `onForeground` makes on its own with nothing paired.
 */
class PairingCeilingTest {

    // --- The ordering rule --------------------------------------------------

    @Test fun aSealOutranksEveryRequestToShowACode() {
        // requestOpen -> visible = true. Sealed refuses it anyway; that is the
        // ceiling. A naive implementation returns CODE here and the whole finding
        // comes back, because onForeground calls requestOpen by itself.
        assertEquals(
            SurfaceDecision.SEALED,
            surfaceDecision(sealed = true, visible = true, lockedOut = false),
        )
        // And it outranks a running lockout too: a seal has no deadline to show.
        assertEquals(
            SurfaceDecision.SEALED,
            surfaceDecision(sealed = true, visible = true, lockedOut = true),
        )
    }

    @Test fun aSealSurvivesTheSurfaceBeingClosedAndReopened() {
        // Closing (entering Settings, backgrounding) sets visible = false. If that
        // published a plain Standby, the screen would stop saying why pairing
        // stopped and the trip back out — which asks for a code again — would find
        // nothing to refuse.
        assertEquals(
            SurfaceDecision.SEALED,
            surfaceDecision(sealed = true, visible = false, lockedOut = false),
        )
    }

    @Test fun belowTheSealTheExistingOrderIsUntouched() {
        assertEquals(SurfaceDecision.STANDBY, surfaceDecision(sealed = false, visible = false, lockedOut = false))
        assertEquals(SurfaceDecision.LOCKED, surfaceDecision(sealed = false, visible = true, lockedOut = true))
        assertEquals(SurfaceDecision.CODE, surfaceDecision(sealed = false, visible = true, lockedOut = false))
        // A lockout still wins over minting a code — the ceiling did not displace it.
        assertNotEquals(
            surfaceDecision(sealed = false, visible = true, lockedOut = true),
            surfaceDecision(sealed = false, visible = true, lockedOut = false),
        )
    }

    // --- What one wrong code costs ------------------------------------------

    @Test fun theCeilingSealsExactlyOnItsOwnAttempt() {
        val ceiling = PairingManager.MAX_SURFACE_FAILURES
        assertEquals(FailureCharge.SEAL, failureCharge(surfaceFailures = ceiling, roundFailures = 1))
        assertNotEquals(FailureCharge.SEAL, failureCharge(surfaceFailures = ceiling - 1, roundFailures = 1))
    }

    @Test fun theCeilingOutranksStartingAnotherLockoutRound() {
        // The two budgets run out together on the ceiling attempt, because the
        // ceiling is a multiple of the round size. Starting a round there would
        // publish a countdown that expires onto a surface which is still shut.
        assertEquals(
            FailureCharge.SEAL,
            failureCharge(
                surfaceFailures = PairingManager.MAX_SURFACE_FAILURES,
                roundFailures = PairingManager.MAX_FAILURES,
            ),
        )
    }

    @Test fun theRoundBudgetStillOwnsOrdinaryFumbling() {
        assertEquals(
            FailureCharge.LOCKOUT,
            failureCharge(surfaceFailures = PairingManager.MAX_FAILURES, roundFailures = PairingManager.MAX_FAILURES),
        )
        assertEquals(FailureCharge.RECORDED, failureCharge(surfaceFailures = 1, roundFailures = 1))
        assertEquals(
            FailureCharge.RECORDED,
            failureCharge(surfaceFailures = 4, roundFailures = PairingManager.MAX_FAILURES - 1),
        )
    }

    // --- The number itself ---------------------------------------------------

    /**
     * The ceiling is a security claim expressed as an integer, so the claim is
     * asserted rather than left in a comment. Both bounds matter: dialled down it
     * starts sealing on honest mistyping, and dialled up it stops bounding the
     * guessing run it exists to bound.
     */
    @Test fun theCeilingSitsFarAboveMistypingAndFarBelowTheKeyspace() {
        val ceiling = PairingManager.MAX_SURFACE_FAILURES
        assertTrue(
            "the ceiling ($ceiling) must be several full lockout rounds above the round " +
                "budget (${PairingManager.MAX_FAILURES}), or an ordinary fumble seals the TV",
            ceiling >= PairingManager.MAX_FAILURES * 4,
        )
        assertTrue(
            "the ceiling ($ceiling) must stay under 1 % of the ${PairingManager.CODE_KEYSPACE}-code " +
                "keyspace, or one surface-open is a meaningful share of a guessing run",
            ceiling * 100 < PairingManager.CODE_KEYSPACE,
        )
    }

    /**
     * The guessing rate the finding measured, and what the ceiling does to it.
     * Before: unbounded over time — 5 attempts per 480 s round is ~900/day, ~9 % a
     * day against 10,000 codes. After: bounded per surface-open, full stop.
     */
    @Test fun theCeilingRemovesTheSteadyStateEntirely() {
        val perDayWithoutCeiling = (24 * 60 * 60 / 480) * PairingManager.MAX_FAILURES
        assertTrue("the finding's rate should still be the rate", perDayWithoutCeiling >= 900)
        assertTrue(
            "a whole day of grinding must now cost less than one attempt in a hundred " +
                "of the keyspace, because it can no longer exceed one surface-open",
            PairingManager.MAX_SURFACE_FAILURES * 100 < PairingManager.CODE_KEYSPACE,
        )
        assertTrue(
            "and it must be a large reduction on the uncapped rate, not a trim",
            PairingManager.MAX_SURFACE_FAILURES * 10 < perDayWithoutCeiling,
        )
    }

    // --- Reopening -----------------------------------------------------------

    /**
     * `resumePairing` clears a restriction, so it takes the durable-first path:
     * a resume that did not persist must not be reported as one, or a restart
     * would silently re-seal a surface the user was just told is open. (Sealing
     * goes the other way on purpose — see `PairingManager.sealSurface`.)
     */
    @Test fun aResumeThatDidNotPersistIsNotAResume() {
        var applied = false
        assertEquals(false, commitPairing(commit = { false }, afterCommit = { applied = true }))
        assertEquals(false, applied)
        assertEquals(true, commitPairing(commit = { true }, afterCommit = { applied = true }))
        assertEquals(true, applied)
    }
}

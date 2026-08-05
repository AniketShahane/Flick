package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a "this film's picture could not be turned" verdict lasts.
 *
 * Per film was already right and per ATTEMPT was the defect: `resetVideoRotation`
 * handed the verdict back on every load, so the receiver's own startup retry
 * re-prepared the identical media, engaged the identical turn, and failed
 * identically for the whole retry ladder. The counterweight is the other half of
 * every pair here: the verdict must not reach a film that never earned it, and
 * must not outlive the process, because a turn that failed on one boot may be
 * fine on the next.
 */
class FilmsWithoutTurnTest {

    private val filmA = "http://host/v/token-a"
    private val filmB = "http://host/v/token-b"

    @Test fun aFilmIsRememberedAboutNothingUntilItsTurnFails() {
        val memory = FilmsWithoutTurn()
        assertFalse(memory.remembers(filmA))
    }

    @Test fun aCondemnedFilmIsStillCondemnedOnTheRetry() {
        val memory = FilmsWithoutTurn()
        memory.remember(filmA)
        assertTrue(memory.remembers(filmA))
        // And on every retry after it, because the verdict is about the film.
        assertTrue(memory.remembers(filmA))
    }

    @Test fun oneFilmsVerdictNeverReachesAnother() {
        val memory = FilmsWithoutTurn()
        memory.remember(filmA)
        assertFalse(memory.remembers(filmB))
    }

    /**
     * A fresh instance is a fresh session, which is the whole retention policy:
     * nothing is persisted, so a new run of the app gives every film its attempt
     * back.
     */
    @Test fun aNewSessionGivesEveryFilmItsAttemptBack() {
        val first = FilmsWithoutTurn()
        first.remember(filmA)
        assertFalse(FilmsWithoutTurn().remembers(filmA))
    }

    /** No key is no film. A missing one must never behave as a wildcard. */
    @Test fun anAbsentKeyIsRememberedAboutNothing() {
        val memory = FilmsWithoutTurn()
        memory.remember(null)
        memory.remember("")
        assertFalse(memory.remembers(null))
        assertFalse(memory.remembers(""))
        memory.remember(filmA)
        assertFalse(memory.remembers(null))
        assertFalse(memory.remembers(""))
    }

    // --- Bounded --------------------------------------------------------------

    @Test fun theMemoryNeverGrowsPastItsBound() {
        val memory = FilmsWithoutTurn(capacity = 3)
        repeat(50) { memory.remember("http://host/v/token-$it") }
        val kept = (0 until 50).count { memory.remembers("http://host/v/token-$it") }
        assertTrue(kept == 3)
    }

    @Test fun theFilmCondemnedLongestAgoIsTheOneEvicted() {
        val memory = FilmsWithoutTurn(capacity = 2)
        memory.remember(filmA)
        memory.remember(filmB)
        memory.remember("http://host/v/token-c")
        assertFalse(memory.remembers(filmA))
        assertTrue(memory.remembers(filmB))
        assertTrue(memory.remembers("http://host/v/token-c"))
    }

    /**
     * A film condemned again is the one a viewer is retrying now, so it must not
     * be the next thing evicted.
     */
    @Test fun condemningAFilmAgainMakesItTheMostRecent() {
        val memory = FilmsWithoutTurn(capacity = 2)
        memory.remember(filmA)
        memory.remember(filmB)
        memory.remember(filmA)
        memory.remember("http://host/v/token-c")
        assertTrue(memory.remembers(filmA))
        assertFalse(memory.remembers(filmB))
    }
}

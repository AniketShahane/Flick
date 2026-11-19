package com.flick.receiver.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubCadenceTest {

    @Test fun aLoneSeekIsNotADrag() {
        assertFalse(ScrubCadence().onPhoneSeek(10_000L))
    }

    @Test fun aSeekAtThePhonesDragCadenceIsADrag() {
        val cadence = ScrubCadence()
        cadence.onPhoneSeek(10_000L)
        assertTrue(cadence.onPhoneSeek(10_050L))
        assertTrue(cadence.onPhoneSeek(10_100L))
    }

    @Test fun theWindowIsInclusive() {
        val cadence = ScrubCadence()
        cadence.onPhoneSeek(10_000L)
        assertTrue(cadence.onPhoneSeek(10_000L + ScrubCadence.DRAG_WINDOW_MS))
    }

    @Test fun aSeekAfterTheWindowStartsOver() {
        val cadence = ScrubCadence()
        cadence.onPhoneSeek(10_000L)
        assertFalse(cadence.onPhoneSeek(10_000L + ScrubCadence.DRAG_WINDOW_MS + 1L))
        // Each seek is recorded, so the one after a late seek is judged against it.
        assertTrue(cadence.onPhoneSeek(10_300L))
    }

    @Test fun resetForgetsThePreviousSeek() {
        val cadence = ScrubCadence()
        cadence.onPhoneSeek(10_000L)
        cadence.reset()
        assertFalse(cadence.onPhoneSeek(10_050L))
    }

    @Test fun aClockThatRunsBackwardsIsNotADrag() {
        val cadence = ScrubCadence()
        cadence.onPhoneSeek(10_000L)
        assertFalse(cadence.onPhoneSeek(9_900L))
    }
}

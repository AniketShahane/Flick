package com.flick.sender.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCandidatesTest {

    @Test fun theScheduleWalksTheBodyOfTheFilm() {
        // A two-hour film: a third in first — where this app has always looked — then
        // outward through the middle, and one early position as the last resort.
        assertEquals(
            listOf(2_376_000L, 3_600_000L, 4_896_000L, 864_000L),
            thumbnailCandidatesMs(7_200_000L),
        )
    }

    @Test fun theFirstCandidateIsStillTheFrameAThirdIn() {
        // The frame the fixed-offset path decodes. A film whose one-third frame is fine
        // must keep showing exactly the still it showed before the search existed.
        val aThirdIn = 90_000L / 3L
        val first = thumbnailCandidatesMs(90_000L).first()
        assertTrue("$first is not about $aThirdIn", first in (aThirdIn - 1_000L)..(aThirdIn + 1_000L))
    }

    @Test fun theSearchIsCappedAtFourFrames() {
        // The whole budget: this runs on a phone that may be serving a multi-gigabyte file
        // over its own Wi-Fi, and an unbounded search is a stall on the television.
        val durations = listOf(1L, 500L, 3_000L, 90_000L, 7_200_000L, 36_000_000L)
        for (durationMs in durations) {
            assertTrue("$durationMs", thumbnailCandidatesMs(durationMs).size <= 4)
        }
    }

    @Test fun everyCandidateIsInsideTheFilm() {
        var durationMs = 1L
        while (durationMs <= 36_000_000L) {
            for (position in thumbnailCandidatesMs(durationMs)) {
                assertTrue("$position is outside $durationMs", position in 0L until durationMs)
            }
            durationMs *= 3L
        }
    }

    @Test fun noPositionIsAskedForTwice() {
        // A short clip collapses several fractions onto the same millisecond, and decoding
        // the same frame twice would spend the budget on one answer.
        assertEquals(thumbnailCandidatesMs(3L).distinct(), thumbnailCandidatesMs(3L))
        assertEquals(listOf(0L), thumbnailCandidatesMs(1L))
    }

    @Test fun anUnmeasuredDurationAsksForOneEarlyFrame() {
        // Zero is MediaStore's silence, not a zero-length film: with no length to divide
        // there is nowhere to spread a search, so it falls back to the one position the
        // fixed-offset path uses.
        assertEquals(listOf(1_000L), thumbnailCandidatesMs(0L))
        assertEquals(listOf(1_000L), thumbnailCandidatesMs(-1L))
    }

    @Test fun aLongFilmIsCountedExactly() {
        // Ten hours in milliseconds is past where a Float still counts whole numbers, and
        // a schedule computed in floats would drift by seconds at that length.
        val tenHours = 36_000_000L
        assertEquals(tenHours * 33L / 100L, thumbnailCandidatesMs(tenHours).first())
    }
}

package com.flick.receiver.ui.library

import com.flick.receiver.library.LibraryItem
import com.flick.receiver.library.LibraryItemKind
import com.flick.receiver.library.LibraryPlay
import com.flick.receiver.library.LibraryPlayFailure
import com.flick.receiver.library.LibraryShelves
import com.flick.receiver.library.LibraryShelving
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFormatTest {

    @Test
    fun minutesLeftRoundsUpAndNeverReadsZero() {
        assertEquals(31, LibraryFormat.minutesLeft(film("a", duration = 3_130_000L, resumeMs = 1_270_000L)))
        assertEquals(32, LibraryFormat.minutesLeft(film("a2", duration = 3_130_000L, resumeMs = 1_260_000L)))
        assertEquals(1, LibraryFormat.minutesLeft(film("b", duration = 600_000L, resumeMs = 580_000L)))
        assertEquals(2, LibraryFormat.minutesLeft(film("c", duration = 600_000L, resumeMs = 480_001L)))
    }

    @Test
    fun minutesLeftNeedsAResumePointAndAKnownRunningTime() {
        assertNull(LibraryFormat.minutesLeft(film("a", duration = 600_000L)))
        assertNull(LibraryFormat.minutesLeft(film("b", duration = 0L, resumeMs = 5_000L)))
        assertNull(LibraryFormat.minutesLeft(film("c", duration = 600_000L, resumeMs = 600_000L)))
    }

    @Test
    fun progressIsTheResumeFractionClamped() {
        assertEquals(0.5f, LibraryFormat.progress(film("a", duration = 600_000L, resumeMs = 300_000L))!!, 0.0001f)
        assertEquals(1f, LibraryFormat.progress(film("b", duration = 600_000L, resumeMs = 700_000L))!!, 0.0001f)
        assertNull(LibraryFormat.progress(film("c", duration = 0L, resumeMs = 5_000L)))
        assertNull(LibraryFormat.progress(film("d", duration = 600_000L)))
    }

    @Test
    fun runtimeMinutesFloorsLikeThePhoneAndHidesUnknown() {
        assertEquals(12, LibraryFormat.runtimeMinutes(734_000L))
        assertEquals(10, LibraryFormat.runtimeMinutes(634_000L))
        assertEquals(1, LibraryFormat.runtimeMinutes(20_000L))
        assertNull(LibraryFormat.runtimeMinutes(0L))
    }

    @Test
    fun clockReadsMinutesAndSecondsThenHours() {
        assertEquals("2:26", LibraryFormat.clock(146_000L))
        assertEquals("52:10", LibraryFormat.clock(3_130_000L))
        assertEquals("0:05", LibraryFormat.clock(5_400L))
        assertEquals("1:02:03", LibraryFormat.clock(3_723_000L))
        assertEquals("0:00", LibraryFormat.clock(-1L))
    }

    @Test
    fun episodeLabelsReadSeasonColonEpisode() {
        assertEquals("S2:E5", LibraryFormat.episodeLabel(2, 5))
        assertEquals("S0:E12", LibraryFormat.episodeLabel(ep("e", 0, 12)))
        assertNull(LibraryFormat.episodeLabel(film("f")))
    }

    @Test
    fun resolutionClassesByTheShortSideOrAWideScopeFrame() {
        assertEquals(LibraryResolution.UHD, LibraryFormat.resolution(3840, 2160))
        assertEquals(LibraryResolution.UHD, LibraryFormat.resolution(2160, 3840))
        assertEquals(LibraryResolution.UHD, LibraryFormat.resolution(3840, 1600))
        assertEquals(LibraryResolution.FHD, LibraryFormat.resolution(1920, 1080))
        assertEquals(LibraryResolution.FHD, LibraryFormat.resolution(1920, 800))
        assertEquals(LibraryResolution.HD, LibraryFormat.resolution(1280, 720))
        assertEquals(LibraryResolution.SD, LibraryFormat.resolution(720, 480))
        assertNull(LibraryFormat.resolution(0, 1080))
    }

    @Test
    fun folderNameIsTheLastSegment() {
        assertEquals("Lantern Coast", LibraryFormat.folderName("Movies/Shows/Lantern Coast/"))
        assertEquals("Camera", LibraryFormat.folderName("DCIM/Camera"))
        assertNull(LibraryFormat.folderName(null))
        assertNull(LibraryFormat.folderName("/"))
    }

    @Test
    fun heroPrefersContinueWatchingThenTheNewest() {
        val resumed = film("r", duration = 600_000L, resumeMs = 5_000L, added = 1L)
        val newest = film("n", added = 9L)
        val withResume = LibraryShelving.shelves(catalog(newest, resumed))
        assertEquals(HeroPick(resumed, continuing = true), LibraryFormat.heroPick(withResume))
        val without = LibraryShelving.shelves(catalog(film("o", added = 1L), newest))
        assertEquals(HeroPick(newest, continuing = false), LibraryFormat.heroPick(without))
        assertNull(LibraryFormat.heroPick(LibraryShelves(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun askingCoversBothWaitsForThatItemOnly() {
        assertTrue(LibraryFormat.asking(LibraryPlay.Asking("a"), "a"))
        assertTrue(LibraryFormat.asking(LibraryPlay.Starting("a"), "a"))
        assertFalse(LibraryFormat.asking(LibraryPlay.Asking("b"), "a"))
        assertFalse(LibraryFormat.asking(LibraryPlay.Failed("a", LibraryPlayFailure.BUSY), "a"))
        assertFalse(LibraryFormat.asking(LibraryPlay.Idle, "a"))
    }

    @Test
    fun everyFailureHasItsOwnMessage() {
        val copies = LibraryPlayFailure.entries.map(LibraryFormat::failureCopy)
        assertEquals(LibraryPlayFailure.entries.size, copies.toSet().size)
        assertEquals(PlayFailureCopy.PHONE_IN_BACKGROUND, LibraryFormat.failureCopy(LibraryPlayFailure.BACKGROUND))
        assertEquals(PlayFailureCopy.NO_ANSWER, LibraryFormat.failureCopy(LibraryPlayFailure.NO_ANSWER))
        assertEquals(PlayFailureCopy.OFFLINE, LibraryFormat.failureCopy(LibraryPlayFailure.OFFLINE))
    }

    @Test
    fun countdownRoundsUpToWholeSeconds() {
        assertEquals(10, LibraryFormat.secondsUntil(autoplayAtMs = 20_000L, nowMs = 10_000L))
        assertEquals(10, LibraryFormat.secondsUntil(autoplayAtMs = 20_000L, nowMs = 10_001L))
        assertEquals(1, LibraryFormat.secondsUntil(autoplayAtMs = 20_000L, nowMs = 19_999L))
        assertEquals(0, LibraryFormat.secondsUntil(autoplayAtMs = 20_000L, nowMs = 20_000L))
        assertEquals(0, LibraryFormat.secondsUntil(autoplayAtMs = 20_000L, nowMs = 25_000L))
    }

    private fun catalog(vararg items: LibraryItem) = com.flick.receiver.library.LibraryCatalog("AAAAAAAAAAAAAAAAAAAAAA", items.toList())

    private fun film(id: String, duration: Long = 0L, resumeMs: Long? = null, added: Long = 0L) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.FILM,
        durationMs = duration, resumeMs = resumeMs, addedSeconds = added,
    )

    private fun ep(id: String, season: Int, episode: Int) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.EPISODE,
        show = "Lantern", showKey = "tv:1", season = season, episode = episode,
    )
}

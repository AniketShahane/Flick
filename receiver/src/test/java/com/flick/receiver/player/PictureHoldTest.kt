package com.flick.receiver.player

import com.flick.receiver.ui.components.STAGE_HOLD_CAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureHoldTest {

    @Test fun aHeldFilmStartsOnlyAtPictureUpOrTheCap() {
        assertTrue(playbackMayStart(pictureUp = true, firstFrameAtMs = null, nowMs = 0L))
        assertTrue(playbackMayStart(pictureUp = true, firstFrameAtMs = 1_000L, nowMs = 1_000L))
        assertFalse("no first frame yet", playbackMayStart(pictureUp = false, firstFrameAtMs = null, nowMs = 99_000L))
        assertFalse(playbackMayStart(false, 1_000L, 1_000L + PICTURE_HOLD_CAP_MS - 1))
        assertTrue(playbackMayStart(false, 1_000L, 1_000L + PICTURE_HOLD_CAP_MS))
    }

    @Test fun theSafetyNetNeverBeatsTheStagesOwnCap() {
        // Every wait before picture-up ends by STAGE_HOLD_CAP_MS from the seam; the
        // seam itself lands a composition after the first frame.
        assertTrue(PICTURE_HOLD_CAP_MS >= STAGE_HOLD_CAP_MS + 500L)
    }

    @Test fun aStartupIsHeldPausedAndReleasedPlaying() {
        val hold = PictureHold()
        hold.arm()
        assertTrue(hold.holding)
        assertFalse("the player stays paused while held", hold.request(true))
        hold.onFirstFrame(2_000L)
        assertEquals(2_000L, hold.firstFrameAtMs)
        assertEquals(true, hold.release())
        assertFalse(hold.holding)
        assertNull("a second release is a no-op", hold.release())
    }

    @Test fun aPausePressedDuringTheCardIsHonouredAtRelease() {
        val hold = PictureHold()
        hold.arm()
        assertFalse(hold.request(false))
        assertFalse(hold.wanted)
        assertEquals(false, hold.release())
    }

    @Test fun aPlayAfterAPauseDuringTheCardStillWaitsForTheRelease() {
        val hold = PictureHold()
        hold.arm()
        hold.request(false)
        assertFalse(hold.request(true))
        assertEquals(true, hold.release())
    }

    @Test fun aRecoveryInsideTheHoldKeepsTheFilmAtItsFrame() {
        val hold = PictureHold()
        hold.arm()
        hold.request(false)
        assertFalse(hold.gate(true))
        assertFalse("a recovery is not the viewer's word", hold.wanted)
        hold.clear()
        assertTrue(hold.gate(true))
        assertFalse(hold.gate(false))
    }

    @Test fun nothingHeldPassesRequestsStraightThrough() {
        val hold = PictureHold()
        assertTrue(hold.request(true))
        assertFalse(hold.request(false))
        hold.onFirstFrame(1_000L)
        assertNull("a first frame outside a hold arms nothing", hold.firstFrameAtMs)
        assertNull(hold.release())
    }

    @Test fun onlyTheFirstFrameStartsTheClock() {
        val hold = PictureHold()
        hold.arm()
        hold.onFirstFrame(1_000L)
        // A re-prepare inside the hold renders a first frame again.
        hold.onFirstFrame(1_800L)
        assertEquals(1_000L, hold.firstFrameAtMs)
    }

    @Test fun aNewCastRearmsAFreshHold() {
        val hold = PictureHold()
        hold.arm()
        hold.request(false)
        hold.onFirstFrame(1_000L)
        hold.arm()
        assertTrue(hold.wanted)
        assertNull(hold.firstFrameAtMs)
    }
}

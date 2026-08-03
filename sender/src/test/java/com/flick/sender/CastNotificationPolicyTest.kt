package com.flick.sender

import com.flick.sender.model.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the phone's media notification is allowed to offer, say and re-post.
 *
 * The rule under most of this is one sentence: a media control the user can press and that
 * does nothing is worse than an absent one. The TV only has a player to command once the
 * cast is Active on a live socket, so every transport verb is gated on that — and Stop is
 * not, because Stop is a local intent to the source service and works regardless.
 */
class CastNotificationPolicyTest {

    // --- what the platform is told playback is doing -------------------------

    @Test fun everyPhaseMapsToAStage() {
        assertEquals(CastNotificationPolicy.Stage.IDLE, CastNotificationPolicy.stage(PlaybackPhase.IDLE))
        assertEquals(CastNotificationPolicy.Stage.BUFFERING, CastNotificationPolicy.stage(PlaybackPhase.BUFFERING))
        assertEquals(CastNotificationPolicy.Stage.READY, CastNotificationPolicy.stage(PlaybackPhase.PLAYING))
        assertEquals(CastNotificationPolicy.Stage.READY, CastNotificationPolicy.stage(PlaybackPhase.PAUSED))
        assertEquals(CastNotificationPolicy.Stage.ENDED, CastNotificationPolicy.stage(PlaybackPhase.ENDED))
    }

    /**
     * A receiver error becomes a terminal that tears this cast down. For the frames before
     * that lands the honest report is "nothing is playing", not a player error — Media3
     * also allows a player error only in IDLE, so the two agree.
     */
    @Test fun anErrorReportsIdleRatherThanAPlayerError() {
        assertEquals(CastNotificationPolicy.Stage.IDLE, CastNotificationPolicy.stage(PlaybackPhase.ERROR))
    }

    // --- which controls are real --------------------------------------------

    @Test fun aLiveActiveCastOffersTheWholeTransport() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.PLAYING, commandable = true, durationMs = FILM_MS)
        assertTrue(controls.playPause)
        assertTrue(controls.skip)
        assertTrue(controls.seek)
        assertTrue(controls.stop)
    }

    @Test fun aCastStillConnectingOffersNoTransportAtAll() {
        for (phase in PlaybackPhase.entries) {
            val controls = CastNotificationPolicy.controls(phase, commandable = false, durationMs = FILM_MS)
            assertFalse(phase.name, controls.anyTransport)
        }
    }

    /** Stop tears down the source service, so it is the one verb that always does something. */
    @Test fun stopIsOfferedInEveryStateIncludingBeforeAndAfterTheCast() {
        for (phase in PlaybackPhase.entries) {
            for (commandable in listOf(true, false)) {
                assertTrue(
                    "$phase commandable=$commandable",
                    CastNotificationPolicy.controls(phase, commandable, FILM_MS).stop,
                )
            }
        }
    }

    /** The media controls draw the seek bar from the session's duration; without one there is no bar. */
    @Test fun noKnownDurationWithholdsTheScrubberAndNothingElse() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.PLAYING, commandable = true, durationMs = 0L)
        assertFalse(controls.seek)
        assertTrue(controls.playPause)
        assertTrue(controls.skip)
    }

    @Test fun aPausedCastKeepsItsTransport() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.PAUSED, commandable = true, durationMs = FILM_MS)
        assertTrue(controls.playPause)
        assertTrue(controls.skip)
        assertTrue(controls.seek)
    }

    @Test fun aTvStillBufferingKeepsItsTransport() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.BUFFERING, commandable = true, durationMs = FILM_MS)
        assertTrue(controls.playPause)
        assertTrue(controls.skip)
    }

    /** A film that ran out has no running player; only Stop is honest there. */
    @Test fun aFinishedFilmOffersOnlyStop() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.ENDED, commandable = true, durationMs = FILM_MS)
        assertFalse(controls.anyTransport)
        assertTrue(controls.stop)
    }

    @Test fun anErroredCastOffersOnlyStop() {
        val controls = CastNotificationPolicy.controls(PlaybackPhase.ERROR, commandable = true, durationMs = FILM_MS)
        assertFalse(controls.anyTransport)
        assertTrue(controls.stop)
    }

    // --- what the second line says ------------------------------------------

    @Test fun theLineFollowsTheCast() {
        assertEquals(CastNotificationPolicy.Line.CONNECTING, CastNotificationPolicy.line(PlaybackPhase.BUFFERING, commandable = false))
        assertEquals(CastNotificationPolicy.Line.CASTING, CastNotificationPolicy.line(PlaybackPhase.PLAYING, commandable = true))
        assertEquals(CastNotificationPolicy.Line.CASTING, CastNotificationPolicy.line(PlaybackPhase.PAUSED, commandable = true))
        assertEquals(CastNotificationPolicy.Line.FINISHED, CastNotificationPolicy.line(PlaybackPhase.ENDED, commandable = true))
    }

    @Test fun noCastAtAllIsTheServingLineWithNoTitleAndNoTransport() {
        val shape = CastNotificationPolicy.shape(null)
        assertNull(shape.title)
        assertNull(shape.deviceName)
        assertEquals(CastNotificationPolicy.Line.SERVING, shape.line)
        assertFalse(shape.controls.anyTransport)
        assertTrue(shape.controls.stop)
    }

    // --- when the notification is worth re-posting ---------------------------

    /**
     * The whole reason [CastNotificationPolicy.Shape] exists: `state` frames arrive several
     * times a second, and the platform advances the scrubber off the session on its own.
     * Re-posting on every frame would repaint the shade continuously for nothing.
     */
    @Test fun aPositionThatMovedIsNotWorthRePosting() {
        val before = snapshot(positionMs = 600_000L, bufferedMs = 640_000L)
        val after = before.copy(positionMs = 604_000L, bufferedMs = 644_000L)
        assertEquals(CastNotificationPolicy.shape(before), CastNotificationPolicy.shape(after))
    }

    @Test fun aPauseIsWorthRePosting() {
        val playing = snapshot()
        assertNotEquals(
            CastNotificationPolicy.shape(playing),
            CastNotificationPolicy.shape(playing.copy(playing = false, phase = PlaybackPhase.PAUSED)),
        )
    }

    @Test fun aReTargetToAnotherFilmIsWorthRePosting() {
        val first = snapshot()
        assertNotEquals(
            CastNotificationPolicy.shape(first),
            CastNotificationPolicy.shape(first.copy(castId = "b", title = "Another Film")),
        )
    }

    @Test fun reachingActiveIsWorthRePostingBecauseItArmsTheTransport() {
        val connecting = snapshot(commandable = false, phase = PlaybackPhase.BUFFERING)
        assertNotEquals(
            CastNotificationPolicy.shape(connecting),
            CastNotificationPolicy.shape(connecting.copy(commandable = true)),
        )
    }

    // --- whether the scrubber may run on its own -----------------------------

    @Test fun onlyATvThatSaysItIsPlayingAdvancesTheScrubber() {
        assertTrue(CastNotificationPolicy.positionAdvances(PlaybackPhase.PLAYING, playing = true, headHeld = false))
        assertFalse(CastNotificationPolicy.positionAdvances(PlaybackPhase.BUFFERING, playing = true, headHeld = false))
        assertFalse(CastNotificationPolicy.positionAdvances(PlaybackPhase.PAUSED, playing = false, headHeld = false))
        assertFalse(CastNotificationPolicy.positionAdvances(PlaybackPhase.ENDED, playing = true, headHeld = false))
    }

    /** Mid-gesture the head is where the user is pointing, not where the film is. */
    @Test fun aHeldHeadNeverRunsForward() {
        assertFalse(CastNotificationPolicy.positionAdvances(PlaybackPhase.PLAYING, playing = true, headHeld = true))
    }

    // --- the buffered position ----------------------------------------------

    @Test fun theBufferedPositionIsTheTvs() {
        assertEquals(640_000L, CastNotificationPolicy.bufferedPositionMs(600_000L, 640_000L, FILM_MS))
    }

    /**
     * During a ±10s run the head leads a TV that has not landed the seek, so the reported
     * buffered position sits behind it. The platform reads this as "buffered up to here",
     * so it may never qualify less than the position it is describing.
     */
    @Test fun aHeadLeadingTheTvNeverReportsABufferBehindItself() {
        assertEquals(650_000L, CastNotificationPolicy.bufferedPositionMs(650_000L, 610_000L, FILM_MS))
    }

    @Test fun theBufferedPositionCannotExceedTheFilm() {
        assertEquals(FILM_MS, CastNotificationPolicy.bufferedPositionMs(FILM_MS - 1_000L, FILM_MS + 60_000L, FILM_MS))
    }

    @Test fun withNoKnownDurationTheBufferedPositionIsNotClampedToZero() {
        assertEquals(640_000L, CastNotificationPolicy.bufferedPositionMs(600_000L, 640_000L, durationMs = 0L))
    }

    /** The ±10s the notification sends is the ten seconds every other Flick surface sends. */
    @Test fun theSkipIncrementIsTenSeconds() {
        assertEquals(10_000L, CastNotificationPolicy.SKIP_INCREMENT_MS)
    }

    private fun snapshot(
        positionMs: Long = 600_000L,
        bufferedMs: Long = 640_000L,
        commandable: Boolean = true,
        phase: PlaybackPhase = PlaybackPhase.PLAYING,
    ) = CastTransportSnapshot(
        castId = "a",
        title = "The Wailing (2016)",
        deviceName = "Living Room",
        durationMs = FILM_MS,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        playing = true,
        phase = phase,
        headHeld = false,
        commandable = commandable,
    )

    private companion object {
        const val FILM_MS = 7_200_000L
    }
}

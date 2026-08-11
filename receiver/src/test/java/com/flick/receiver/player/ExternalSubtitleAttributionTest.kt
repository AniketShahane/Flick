package com.flick.receiver.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution is what stands between a bad subtitle and a dead cast: the receiver survives a
 * subtitle that will not load by recognising it as one and dropping the text track, and an
 * error it does not recognise is a plain playback error — terminal on a live cast by design.
 *
 * So these are not tests about string comparison. Each one is a cast that does or does not
 * survive.
 */
class ExternalSubtitleAttributionTest {

    @Test fun aLoadOfTheAttachedSubtitleIsAttributedUnderEitherSpelling() {
        assertTrue(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = SUBTITLE, eventUri = REDIRECTED))
        assertTrue(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = REDIRECTED, eventUri = SUBTITLE))
        assertTrue(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = SUBTITLE, eventUri = SUBTITLE))
    }

    @Test fun theFilmsOwnLoadIsNeverTheSubtitles() {
        assertFalse(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = VIDEO, eventUri = VIDEO))
    }

    @Test fun nothingIsAttributedWhileNoSubtitleIsAttached() {
        assertFalse(isExternalSubtitleLoad(null, dataSpecUri = SUBTITLE, eventUri = SUBTITLE))
    }

    /**
     * A subtitle served under one token is a different subtitle from one served under
     * another, so a load of the previous one — still in flight when the viewer changed
     * their mind — must not be recorded against the subtitle now attached.
     */
    @Test fun aSupersededSubtitlesLoadIsNotTheCurrentOnes() {
        assertFalse(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = SUPERSEDED, eventUri = SUPERSEDED))
    }

    /**
     * The regression this exists for.
     *
     * A subtitle attached mid-cast settles, and only later does its file go — evicted from
     * the cache, or unreadable. Attribution once required the load to belong to the reload
     * attempt still pending, and settling the reload had already cleared that; the failure
     * was therefore read as the film's, no rollback was offered, and the cast died. Nothing
     * about WHEN a load happens may reach this decision, which is why the only inputs it
     * takes are the three below.
     */
    @Test fun aFailureLongAfterTheReloadSettledIsStillTheSubtitles() {
        val state = ExternalSubtitleFailureState()
        state.recordLoadSuccess()

        assertTrue(isExternalSubtitleLoad(SUBTITLE, dataSpecUri = SUBTITLE, eventUri = SUBTITLE))
        state.recordLoadFailure()

        assertTrue(state.shouldRollbackAfterPlayerError(hasSubtitle = true))
    }

    /** And the rollback is offered once per film, so a second error surfaces as itself. */
    @Test fun aFilmThatAlreadySpentItsRollbackDoesNotHideTheNextError() {
        val state = ExternalSubtitleFailureState()
        state.recordLoadFailure()
        assertTrue(state.shouldRollbackAfterPlayerError(hasSubtitle = true))

        state.recordRollback()
        state.recordLoadFailure()

        assertFalse(state.shouldRollbackAfterPlayerError(hasSubtitle = true))
    }

    private companion object {
        const val SUBTITLE = "http://192.168.42.17:8080/s/9f2c1d"
        const val SUPERSEDED = "http://192.168.42.17:8080/s/0a7b44"
        const val REDIRECTED = "http://192.168.42.17:8080/s/9f2c1d?r=1"
        const val VIDEO = "http://192.168.42.17:8080/v/3e81aa"
    }
}

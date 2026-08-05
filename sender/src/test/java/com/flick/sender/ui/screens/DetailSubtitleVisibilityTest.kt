package com.flick.sender.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Detail sheet may say a film is carrying a subtitle.
 *
 * There is one selection in the app at a time, and it belongs to the film it was picked or
 * recalled for. Every rule that keeps those two together lives in the coordinator, so the
 * screen's only job is to refuse to draw a selection that is not this film's — and drawing
 * one anyway would look exactly like the feature working.
 */
class DetailSubtitleVisibilityTest {

    private val thisFilm = "content://media/external/video/media/41"
    private val anotherFilm = "content://media/external/video/media/77"

    @Test
    fun theFilmTheSelectionBelongsToNamesIt() {
        assertTrue(showAttachedSubtitle(ownerKey = thisFilm, itemKey = thisFilm))
    }

    /**
     * The ordinary way to arrive here holding somebody else's subtitle: a live cast owns
     * the selection, so browsing the library mid-cast cannot clear it. `startCast` drops it
     * rather than attach one film's cues to another, so a sheet that had named it would be
     * promising something the next cast is already going to refuse.
     */
    @Test
    fun anotherFilmsSelectionIsNeverDrawnOnThisOne() {
        assertFalse(showAttachedSubtitle(ownerKey = anotherFilm, itemKey = thisFilm))
    }

    /** Before anything has ever been picked there is no film to compare against. */
    @Test
    fun noOwnerNamesNothing() {
        assertFalse(showAttachedSubtitle(ownerKey = null, itemKey = thisFilm))
    }
}

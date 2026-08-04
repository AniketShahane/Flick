package com.flick.receiver.ui.screens

import com.flick.receiver.player.VideoRotation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tile, the hint over the film and the panel's Auto readout all draw the same
 * turn, and they all get it from here. A reading that disagreed with the picture
 * would send a viewer to correct something that is already correct.
 */
class OrientationTileLabelTest {

    @Test fun anExplicitChoiceIsWhatTheTileShows() {
        for (choice in VideoRotation.ALL.filter { it != VideoRotation.Auto }) {
            // The auto verdict is deliberately a different turn: an explicit
            // choice is the one the decoder was given, so it must win.
            assertEquals(choice.name, choice, shownVideoRotation(choice, autoDegrees = 90))
        }
    }

    @Test fun autoShowsTheTurnItActuallyApplied() {
        assertEquals(VideoRotation.AsFiled, shownVideoRotation(VideoRotation.Auto, autoDegrees = 0))
        assertEquals(VideoRotation.Quarter, shownVideoRotation(VideoRotation.Auto, autoDegrees = 90))
        assertEquals(VideoRotation.Half, shownVideoRotation(VideoRotation.Auto, autoDegrees = 180))
        assertEquals(
            VideoRotation.ThreeQuarter,
            shownVideoRotation(VideoRotation.Auto, autoDegrees = 270),
        )
    }

    /**
     * `autoRotation` cannot produce a turn off the quarter-turn grid, and neither
     * can the wire — but the tile may not invent a glyph for one either. It reads
     * as the file's own, which is what "no correction applied" means.
     */
    @Test fun aTurnOffTheGridReadsAsTheFilesOwn() {
        assertEquals(VideoRotation.AsFiled, shownVideoRotation(VideoRotation.Auto, autoDegrees = 45))
        assertEquals(VideoRotation.AsFiled, shownVideoRotation(VideoRotation.Auto, autoDegrees = -90))
        assertEquals(VideoRotation.AsFiled, shownVideoRotation(VideoRotation.Auto, autoDegrees = 360))
    }

    /** Every choice has a label, so the tile can never be asked to draw nothing. */
    @Test fun everyChoiceCarriesALabel() {
        val labels = VideoRotation.ALL.map(::rotationLabelRes)
        assertEquals(VideoRotation.ALL.size, labels.distinct().size)
    }
}

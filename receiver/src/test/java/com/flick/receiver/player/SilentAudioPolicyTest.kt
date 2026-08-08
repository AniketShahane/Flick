package com.flick.receiver.player

import com.flick.receiver.ui.theme.FlickMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one failure the picture cannot reveal, and the rules for saying it once.
 * Every case here is silent on a real TV, so none of it is visible in a screenshot
 * of a working cast — which is exactly why it is arithmetic here instead.
 */
class SilentAudioPolicyTest {

    private val dts = "audio/vnd.dts"

    // --- The reading that goes on the wire and on the screen --------------------

    @Test fun aFormatTheContainerNamedIsCarriedThrough() {
        assertEquals(dts, silentAudioMimeReading(dts))
        assertEquals("audio/vnd.dts.hd", silentAudioMimeReading("audio/vnd.dts.hd"))
        assertEquals("audio/vnd.dts.hd;profile=lbr", silentAudioMimeReading("audio/vnd.dts.hd;profile=lbr"))
    }

    @Test fun aFormatTheContainerDidNotNameIsStillAReading() {
        assertEquals(SILENT_AUDIO_MIME_UNKNOWN, silentAudioMimeReading(null))
        assertEquals(SILENT_AUDIO_MIME_UNKNOWN, silentAudioMimeReading(""))
    }

    /**
     * The frame is validated strictly at the phone and a refusal costs the whole
     * control socket, so a file may not put anything on the wire that a reader
     * would have to refuse. It reads as unknown instead, which is a true answer.
     */
    @Test fun aFormatNoReaderWouldAcceptReadsAsUnknown() {
        listOf(
            "audio/x dts",
            "audio/\"dts\"",
            "audio/vnd.dts\n",
            "audio/vnd.dtsé",
            "a".repeat(41),
        ).forEach { declared ->
            assertEquals(declared, SILENT_AUDIO_MIME_UNKNOWN, silentAudioMimeReading(declared))
        }
    }

    // --- When it may be seen ----------------------------------------------------

    @Test fun nothingToSayMeansNothingToWaitFor() {
        assertEquals(
            SilentAudioNoticePhase.Waiting,
            silentAudioNoticePhase(
                mimeType = null,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    /** The reading lands before the first frame; the clock may not start there. */
    @Test fun theReadingWaitsForTheFilmToBeOnScreen() {
        assertEquals(
            SilentAudioNoticePhase.Waiting,
            silentAudioNoticePhase(
                mimeType = dts,
                filmVisible = false,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
        assertEquals(
            SilentAudioNoticePhase.Showing,
            silentAudioNoticePhase(
                mimeType = dts,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    /** Both live in the band under the top pill row, and that card is full-bleed. */
    @Test fun theQualityFlourishGetsTheBandFirst() {
        assertEquals(
            SilentAudioNoticePhase.Waiting,
            silentAudioNoticePhase(
                mimeType = dts,
                filmVisible = true,
                qualityShowing = true,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    /**
     * Where this parts company with the orientation hint. An open panel spends the
     * hint, because the panel is what the hint was pointing at; no panel says a
     * word about missing sound, so this only has to keep off the glass.
     */
    @Test fun anOpenPanelDelaysTheNoticeRatherThanSpendingIt() {
        assertEquals(
            SilentAudioNoticePhase.Waiting,
            silentAudioNoticePhase(
                mimeType = dts,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = true,
                alreadyShown = false,
            ),
        )
        assertEquals(
            SilentAudioNoticePhase.Showing,
            silentAudioNoticePhase(
                mimeType = dts,
                filmVisible = true,
                qualityShowing = false,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
        // The hint, on the identical inputs, is finished with instead.
        assertEquals(
            OrientationHintPhase.Spent,
            orientationHintPhase(
                OrientationHint.ShownAsFiled,
                filmVisible = true,
                qualityShowing = false,
                silentAudioShowing = false,
                panelOpen = true,
                alreadyShown = false,
            ),
        )
    }

    @Test fun onceGivenItNeverComesBack() {
        listOf(true, false).forEach { filmVisible ->
            assertEquals(
                SilentAudioNoticePhase.Spent,
                silentAudioNoticePhase(
                    mimeType = dts,
                    filmVisible = filmVisible,
                    qualityShowing = false,
                    panelOpen = false,
                    alreadyShown = true,
                ),
            )
        }
    }

    // --- Handing the band over --------------------------------------------------

    /**
     * The queue has to be a queue on SCREEN and not only in the phase. Both cards
     * draw at `TopCenter` under the same 42 dp offset, and a dismissal turns the
     * outgoing card off and the incoming one on in ONE recomposition — so a phase
     * alone leaves a 500 ms fade-out and a 200 ms fade-in running at identical
     * coordinates, with the card that is leaving still legible for most of it.
     *
     * `ReceiverApp` therefore holds the band claimed across the exit, and this is
     * the arithmetic that keeps the hold honest: shorter re-opens the overlap,
     * longer is dead air on the film the viewer came for.
     */
    @Test fun theBandStaysClaimedForExactlyAsLongAsTheCardTakesToLeave() {
        assertEquals(FlickMotion.CHROME_FADE_OUT_MS, FlickMotion.BAND_HANDOVER_MS)
    }

    /**
     * And the hint is held for the whole of that, not merely while the notice
     * shows. This is the value the caller passes DURING the exit: the notice's own
     * phase has already left Showing, and the card is still on the glass.
     */
    @Test fun theHintIsStillWaitingWhileTheNoticeFadesOut() {
        assertEquals(
            OrientationHintPhase.Waiting,
            orientationHintPhase(
                OrientationHint.TurnedUpright,
                filmVisible = true,
                qualityShowing = false,
                silentAudioShowing = true,
                panelOpen = false,
                alreadyShown = false,
            ),
        )
    }

    @Test fun itIsLongEnoughToOutliveTheChromeThatFallsAwayUnderIt() {
        // The chrome auto-hide is 4 s and the quality flourish holds 4.5 s.
        assertTrue(SILENT_AUDIO_NOTICE_MS > 4_500L)
        assertEquals(ORIENTATION_HINT_MS, SILENT_AUDIO_NOTICE_MS)
    }
}

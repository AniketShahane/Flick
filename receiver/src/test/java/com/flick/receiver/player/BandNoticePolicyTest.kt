package com.flick.receiver.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandNoticePolicyTest {

    private fun pending(
        audio: Boolean = false,
        subtitle: Boolean = false,
        turn: Boolean = false,
        shown: Set<BandNotice> = emptySet(),
    ) = pendingBandNotice(audio, subtitle, turn, shown)

    @Test fun nothingOutstandingIsNoNotice() {
        assertNull(pending())
    }

    @Test fun eachTriggerRaisesItsOwnNotice() {
        assertEquals(BandNotice.AudioRestart, pending(audio = true))
        assertEquals(BandNotice.SubtitleDropped, pending(subtitle = true))
        assertEquals(BandNotice.TurnUnavailable, pending(turn = true))
    }

    /** The card the viewer can do least about goes first. */
    @Test fun theQueueServesTheAudioRebuildAheadOfTheOtherTwo() {
        assertEquals(BandNotice.AudioRestart, pending(audio = true, subtitle = true, turn = true))
    }

    @Test fun aDroppedSubtitleOutranksARefusedTurn() {
        assertEquals(BandNotice.SubtitleDropped, pending(subtitle = true, turn = true))
    }

    @Test fun oneAlreadyGivenStepsAsideForTheNextOutstandingOne() {
        assertEquals(
            BandNotice.SubtitleDropped,
            pending(audio = true, subtitle = true, shown = setOf(BandNotice.AudioRestart)),
        )
    }

    /** Each is spent independently: a film can lose its subtitle AND refuse a turn. */
    @Test fun aSpentNoticeNeverComesBack() {
        assertNull(
            pending(
                audio = true,
                subtitle = true,
                turn = true,
                shown = setOf(
                    BandNotice.AudioRestart,
                    BandNotice.SubtitleDropped,
                    BandNotice.TurnUnavailable,
                ),
            ),
        )
    }

    // --- The phase --------------------------------------------------------------

    private fun phase(
        notice: BandNotice? = BandNotice.AudioRestart,
        filmVisible: Boolean = true,
        qualityShowing: Boolean = false,
        bandClaimed: Boolean = false,
        panelOpen: Boolean = false,
    ) = bandNoticePhase(notice, filmVisible, qualityShowing, bandClaimed, panelOpen)

    @Test fun aNoticeOverAVisibleFilmIsShown() {
        assertEquals(BandNoticePhase.Showing, phase())
    }

    @Test fun nothingToSayIsNotAShowing() {
        assertEquals(BandNoticePhase.Waiting, phase(notice = null))
    }

    @Test fun theConnectingScreenAndTheQualityFlourishBothOnlyDelayIt() {
        assertEquals(BandNoticePhase.Waiting, phase(filmVisible = false))
        assertEquals(BandNoticePhase.Waiting, phase(qualityShowing = true))
    }

    /** A card still fading out is still on the glass, and these share coordinates. */
    @Test fun aClaimedBandHoldsTheNoticeBack() {
        assertEquals(BandNoticePhase.Waiting, phase(bandClaimed = true))
    }

    @Test fun aPanelDelaysTheTwoNoticesNothingElseSays() {
        assertEquals(BandNoticePhase.Waiting, phase(notice = BandNotice.AudioRestart, panelOpen = true))
        assertEquals(BandNoticePhase.Waiting, phase(notice = BandNotice.SubtitleDropped, panelOpen = true))
    }

    /** The orientation panel's own eyebrow already says this one in front of the viewer. */
    @Test fun aPanelSpendsTheTurnNotice() {
        assertEquals(
            BandNoticePhase.Spent,
            phase(notice = BandNotice.TurnUnavailable, panelOpen = true),
        )
    }

    @Test fun theNoticeStaysUpForTheSameSpanTheShippedCardsDo() {
        assertEquals(ORIENTATION_HINT_MS, BAND_NOTICE_MS)
    }
}

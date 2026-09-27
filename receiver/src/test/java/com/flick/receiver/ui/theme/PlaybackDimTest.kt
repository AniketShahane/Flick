package com.flick.receiver.ui.theme

import com.flick.receiver.player.PlaybackPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDimTest {

    @Test fun everyCauseHasItsTarget() {
        assertEquals(0.50f, playbackDimTarget(DimCause.Ended), 0f)
        assertEquals(0.34f, playbackDimTarget(DimCause.Paused), 0f)
        assertEquals(0.30f, playbackDimTarget(DimCause.Seeking), 0f)
        assertEquals(0.38f, playbackDimTarget(DimCause.Buffering), 0f)
        assertEquals(0f, playbackDimTarget(DimCause.None), 0f)
    }

    @Test fun phaseMapsToItsCause() {
        assertEquals(DimCause.Ended, playbackDimCause(PlaybackPhase.Ended, seeking = false))
        assertEquals(DimCause.Paused, playbackDimCause(PlaybackPhase.Paused, seeking = false))
        assertEquals(DimCause.Buffering, playbackDimCause(PlaybackPhase.Buffering, seeking = false))
        assertEquals(DimCause.Seeking, playbackDimCause(PlaybackPhase.Playing, seeking = true))
        assertEquals(DimCause.None, playbackDimCause(PlaybackPhase.Playing, seeking = false))
        assertEquals(0.30f, playbackDimTarget(PlaybackPhase.Playing, seeking = true), 0f)
    }

    @Test fun precedenceIsEndedPausedSeekingBuffering() {
        assertEquals(DimCause.Ended, playbackDimCause(PlaybackPhase.Ended, seeking = true))
        assertEquals(DimCause.Paused, playbackDimCause(PlaybackPhase.Paused, seeking = true))
        assertEquals(DimCause.Seeking, playbackDimCause(PlaybackPhase.Buffering, seeking = true))
    }

    @Test fun easeFollowsTheChangeInTarget() {
        assertEquals(DimEase.Darken, dimEase(DimCause.None, DimCause.Seeking))
        assertEquals(DimEase.LiftSlow, dimEase(DimCause.Seeking, DimCause.None))
        assertEquals(DimEase.LiftPrompt, dimEase(DimCause.Paused, DimCause.None))
        assertEquals(DimEase.SettleEnded, dimEase(DimCause.None, DimCause.Ended))
        assertEquals(DimEase.Darken, dimEase(DimCause.Seeking, DimCause.Buffering))
        assertEquals(DimEase.LiftPrompt, dimEase(DimCause.Ended, DimCause.None))
        assertEquals(DimEase.None, dimEase(DimCause.Paused, DimCause.Paused))
    }
}

package com.flick.receiver.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import com.flick.receiver.player.PlaybackPhase

/**
 * The one table for the playback state dim. PlaybackScreen draws it, and the re-cast
 * veil is seeded from the dim PlaybackScreen last drew over the film, so a paused,
 * seeking or ended frame never brightens for a frame on its way into a new handshake.
 */
internal enum class DimCause { None, Buffering, Seeking, Paused, Ended }

/** Ended > Paused > Seeking > Buffering: the film being over outranks everything. */
internal fun playbackDimCause(phase: PlaybackPhase, seeking: Boolean): DimCause = when {
    phase == PlaybackPhase.Ended -> DimCause.Ended
    phase == PlaybackPhase.Paused -> DimCause.Paused
    seeking -> DimCause.Seeking
    phase == PlaybackPhase.Buffering -> DimCause.Buffering
    else -> DimCause.None
}

/** Ended is the deepest because the film is over: the last frame is a still, not the content. */
internal fun playbackDimTarget(cause: DimCause): Float = when (cause) {
    DimCause.Ended -> 0.50f
    DimCause.Paused -> 0.34f
    DimCause.Seeking -> 0.30f
    DimCause.Buffering -> 0.38f
    DimCause.None -> 0f
}

internal fun playbackDimTarget(phase: PlaybackPhase, seeking: Boolean): Float =
    playbackDimTarget(playbackDimCause(phase, seeking))

internal enum class DimEase { None, Darken, SettleEnded, LiftSlow, LiftPrompt }

/**
 * Chosen from the change in TARGET, never from the animated value, so it can be
 * resolved in composition without reading the animation.
 */
internal fun dimEase(from: DimCause, to: DimCause): DimEase = when {
    from == to -> DimEase.None
    to == DimCause.Ended -> DimEase.SettleEnded
    playbackDimTarget(to) > playbackDimTarget(from) -> DimEase.Darken
    from == DimCause.Seeking -> DimEase.LiftSlow
    else -> DimEase.LiftPrompt
}

/** Full-screen alpha over a film, so every branch is a pure-alpha tween. */
internal fun dimSpec(ease: DimEase): FiniteAnimationSpec<Float> = when (ease) {
    DimEase.None, DimEase.Darken, DimEase.LiftPrompt -> FlickMotion.chromeFadeIn()
    DimEase.SettleEnded -> FlickMotion.crossDissolve()
    DimEase.LiftSlow -> FlickMotion.chromeFadeOut()
}

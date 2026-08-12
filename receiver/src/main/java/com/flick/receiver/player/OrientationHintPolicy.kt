package com.flick.receiver.player

/**
 * What the TV tells the viewer about the picture's orientation.
 *
 * The control is the transport's own orientation tile, and the transport is not
 * on screen: the chrome auto-hides four seconds into a film, which is about when
 * a viewer decides the picture is wrong. Only the TV knows the picture turned, or
 * that it is presenting a tall strip on a landscape panel — the phone is handed
 * no such reading — so the TV is where the viewer is told what to look for, at
 * the moment it is worth knowing.
 */
enum class OrientationHint {
    /** Flick overruled the container and stood a sideways film back up. */
    TurnedUpright,

    /** The picture is portrait and Flick left it exactly as the file says. */
    ShownAsFiled,
}

/** Where the hint is in its one life per cast. */
enum class OrientationHintPhase {
    /** Nothing to say yet, or nothing to say it over. */
    Waiting,

    /** On screen. */
    Showing,

    /** Said once, or reached for — either way it does not come back this cast. */
    Spent,
}

/**
 * How long it stays up.
 *
 * Longer than the 4.5 s quality flourish and longer than the 4 s chrome
 * auto-hide, both deliberately: two short lines have to be read from ten feet,
 * and outliving the chrome that fell away underneath it is what stops the hint
 * reading as one more control that just left.
 */
const val ORIENTATION_HINT_MS = 6_000L

/**
 * The reading to offer for a film, or null when there is nothing worth saying.
 *
 * Exactly two cases earn it, and both are cases where the viewer might want the
 * control and cannot currently find it:
 *
 *  1. **Auto corrected the film.** The picture is right, but the viewer has been
 *     shown something other than what the file asked for, and is owed both the
 *     fact and the way back.
 *  2. **The picture is portrait and Flick did not correct it.** That covers a
 *     mis-tagged film the evidence rule was too conservative to touch AND a
 *     genuine portrait recording — see [autoRotation], where the whole
 *     difficulty is that nothing in the video track separates them. In both the
 *     viewer is looking at a tall strip; in neither can they currently find out
 *     how to turn it.
 *
 * [choice] is what tells a Flick decision from a viewer's own. Anything but
 * [VideoRotation.Auto] means they are already holding the control — they turned
 * the picture from the panel or from the phone — and being told where the
 * control is would be noise. It is the honest place to draw the line, because it
 * is the same value the decision itself was made from: an explicit choice is the
 * only way the picture moves without Flick having decided anything.
 */
fun orientationHintFor(
    shape: MediaShape,
    auto: AutoRotation,
    choice: VideoRotation,
): OrientationHint? {
    if (choice != VideoRotation.Auto) return null
    // Not a verdict about the film — a re-prepare publishes it between items.
    if (auto.verdict == AutoRotationVerdict.NoVideoTrack) return null
    if (auto.extraDegrees != 0) return OrientationHint.TurnedUpright
    if (presentedShape(shape.video, auto.extraDegrees) != PictureShape.Portrait) return null
    return OrientationHint.ShownAsFiled
}

/**
 * Whether the hint may be on screen, and whether it is finished.
 *
 * [panelOpen] spends it rather than merely hiding it, and both halves of that are
 * deliberate. A viewer with any panel open has the chrome up and the tile in front
 * of them, so they no longer need the sign — and a panel is 200 dp to 488 dp of
 * glass reaching up the frame, which this would sit on top of. A viewer who opened
 * one before the reading was even made is in the same position, so the hint is
 * spent there too rather than ambushing them with it when the panel closes.
 *
 * [filmVisible] and [qualityShowing] only delay. The reading lands while the cast
 * is still starting — `onTracksChanged` arrives before the first frame — so a
 * clock started there would spend the hint behind the connecting screen. And the
 * T8 quality flourish holds the same band for its first 4.5 s: its rows are
 * `fillMaxWidth`, so on a real panel that card is full-bleed and the two would be
 * drawn one on top of the other. Waiting it out is also the better read — two
 * transient cards at once is two things talking, and a viewer 4.5 s into a
 * sideways film has had time to wonder what to do about it.
 *
 * [bandClaimed] is that same rule against every other card the band carries, and
 * this side is the one that always yields. A viewer can find the orientation tile
 * later and turn the picture themselves; nothing anywhere restores the sound, and
 * nothing brings back a subtitle or an audio route mid-film — so those notices are
 * not pointers anyone can act on and cannot be offered again. They go first, and the
 * hint takes the band after them.
 *
 * What the caller passes is the band being OCCUPIED, not any card's phase. Every
 * card lands on identical coordinates, so one still fading out is still on the glass
 * and the hint may not fade up underneath it; the caller holds this true across the
 * exit — `FlickMotion.BAND_HANDOVER_MS` — which is a claim on the band that a phase
 * alone cannot express.
 */
fun orientationHintPhase(
    hint: OrientationHint?,
    filmVisible: Boolean,
    qualityShowing: Boolean,
    bandClaimed: Boolean,
    panelOpen: Boolean,
    alreadyShown: Boolean,
): OrientationHintPhase = when {
    alreadyShown -> OrientationHintPhase.Spent
    hint == null -> OrientationHintPhase.Waiting
    panelOpen -> OrientationHintPhase.Spent
    !filmVisible || qualityShowing || bandClaimed -> OrientationHintPhase.Waiting
    else -> OrientationHintPhase.Showing
}

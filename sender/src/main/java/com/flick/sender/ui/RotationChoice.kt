package com.flick.sender.ui

import androidx.annotation.StringRes
import com.flick.sender.R
import com.flick.sender.model.VideoRotation

/**
 * The label for each choice; the model enum carries no user-facing text.
 *
 * Shared rather than owned by either surface. The subtitles sheet lays the five
 * choices out as cells and the remote's poster steps through them with one key, and a
 * second copy of this mapping is how the same choice ends up called two things.
 */
@StringRes
internal fun rotationLabelRes(rotation: VideoRotation): Int = when (rotation) {
    VideoRotation.Auto -> R.string.subs_orientation_auto
    VideoRotation.AsFiled -> R.string.subs_orientation_as_filed
    VideoRotation.Quarter -> R.string.subs_orientation_quarter
    VideoRotation.Half -> R.string.subs_orientation_half
    VideoRotation.ThreeQuarter -> R.string.subs_orientation_three_quarter
}

private const val FullTurn = 360

/**
 * Where a choice sits on the circle the key walks. [VideoRotation.Auto] has no degrees
 * to place and opens it; 0° is read as the whole turn it completes rather than the one
 * it starts, which is what puts it last.
 */
private fun VideoRotation.cyclePosition(): Int =
    extraDegrees?.let { if (it == 0) FullTurn else it } ?: 0

/**
 * Sorted from the model rather than written down as a chain, so a choice added to
 * [VideoRotation.ALL] takes its place among the turns instead of dropping off the walk.
 */
private val RotationCycle: List<VideoRotation> = VideoRotation.ALL.sortedBy { it.cyclePosition() }

/**
 * Where one press of a single-key orientation control lands: a quarter turn further on,
 * and past a full circle, [VideoRotation.Auto] again —
 * `Auto → 90° → 180° → 270° → 0° → Auto`.
 *
 * Not [VideoRotation.ALL]'s own order, which opens on 0°. A control that only moves
 * forwards has to spend every press on a turn, and that first press spends one on
 * asserting no extra rotation at all. The receiver resolves a choice to EXTRA degrees —
 * Auto to whatever it read the file as — and re-prepares nothing when the new ones equal
 * the ones already in force, so on the very film this key exists for, the sideways one
 * whose container declares nothing and which Auto therefore reads as 0°, the label would
 * step from AUTO to 0° and the picture would stand still.
 *
 * 0° keeps its seat, last: it is the choice that means honour the file exactly, and a
 * full circle of turns is when a viewer wants it. The walk closes on Auto because Auto
 * is the receiver reading the file for itself — a forward-only control that could take
 * that reading away with no press that hands it back is the one thing the sheet's five
 * cells cannot do to a viewer, and so the one thing this must not do either.
 */
internal fun nextRotation(current: VideoRotation): VideoRotation =
    RotationCycle[(RotationCycle.indexOf(current) + 1) % RotationCycle.size]

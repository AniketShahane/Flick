package com.flick.receiver.player

/**
 * The events the top band reports that are neither the silent-audio reading nor the
 * orientation hint. All three describe something that ALREADY happened to the film on
 * screen and that nothing on either device can undo, so none of them offers an action
 * and none of them may be shown twice for one cast.
 *
 * They share one slot in the band's queue for the reason the two shipped cards share
 * their coordinates: two cards drawn at once is two things talking.
 */
enum class BandNotice(
    /**
     * Whether an open side panel SPENDS this notice rather than merely delaying it —
     * [orientationHintPhase]'s distinction, applied per notice.
     */
    val spentByPanel: Boolean,
) {
    /**
     * The audio sink is being rebuilt to decode a bitstream the output refused. The
     * film's sound and picture stop and the phase flips to Buffering, so without this
     * the viewer gets the generic rebuffer plate for something that is not a network
     * event at all. It must not promise the sound comes back: a second refusal after
     * the rebuild is a real outcome and ends the cast.
     */
    AudioRestart(spentByPanel = false),

    /**
     * A sideloaded subtitle was dropped — either its load failed outright or its
     * reload missed the 12 s deadline. One notice for both, because from the viewer's
     * side they are one event, and because two cards racing for the band is exactly
     * what a slow subtitle that does both would cause.
     */
    SubtitleDropped(spentByPanel = false),

    /**
     * The turn was refused for this film. Spent by an open panel: the orientation
     * panel's own eyebrow already says it in front of a viewer who has it open, and
     * this card exists for the two cases that eyebrow cannot reach — a viewer who
     * never opens the panel, and a turn commanded from the phone.
     */
    TurnUnavailable(spentByPanel = true),
}

/** Where the slot's current notice is in its one life per cast. */
enum class BandNoticePhase { Waiting, Showing, Spent }

/**
 * How long a notice stays up — [ORIENTATION_HINT_MS]'s span, for its reasons: two short
 * lines have to be read from ten feet, and outliving the 4 s chrome auto-hide is what
 * stops the card reading as one more control that left.
 */
const val BAND_NOTICE_MS = ORIENTATION_HINT_MS

/**
 * Which notice the slot serves, of those outstanding and not yet given.
 *
 * The order is the queue's own rule — the card the viewer can do least about goes
 * first. A sink rebuild is happening to the film right now and has already taken the
 * sound away; a dropped subtitle is a thing they asked for and no longer have; a
 * refused turn is the only one of the three whose evidence is visible in the picture
 * itself.
 */
fun pendingBandNotice(
    audioRestarting: Boolean,
    subtitleDropped: Boolean,
    turnUnavailable: Boolean,
    alreadyShown: Set<BandNotice>,
): BandNotice? = when {
    audioRestarting && BandNotice.AudioRestart !in alreadyShown -> BandNotice.AudioRestart
    subtitleDropped && BandNotice.SubtitleDropped !in alreadyShown -> BandNotice.SubtitleDropped
    turnUnavailable && BandNotice.TurnUnavailable !in alreadyShown -> BandNotice.TurnUnavailable
    else -> null
}

/**
 * Whether the slot's notice may be on screen.
 *
 * [bandClaimed] is the band being OCCUPIED by a card ahead of this one, held across
 * that card's exit — see [orientationHintPhase] for why a phase alone cannot express
 * it. [filmVisible] and [qualityShowing] only delay, exactly as they do for the two
 * shipped cards.
 */
fun bandNoticePhase(
    notice: BandNotice?,
    filmVisible: Boolean,
    qualityShowing: Boolean,
    bandClaimed: Boolean,
    panelOpen: Boolean,
): BandNoticePhase = when {
    notice == null -> BandNoticePhase.Waiting
    panelOpen && notice.spentByPanel -> BandNoticePhase.Spent
    !filmVisible || qualityShowing || bandClaimed || panelOpen -> BandNoticePhase.Waiting
    else -> BandNoticePhase.Showing
}

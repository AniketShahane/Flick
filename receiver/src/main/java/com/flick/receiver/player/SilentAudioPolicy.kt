package com.flick.receiver.player

/**
 * Where the notice is in its one life per cast.
 *
 * What the notice is FOR: silence is the one failure the picture cannot reveal. A
 * film whose only audio track has no decoder on this TV plays with a perfect
 * picture and nothing to hear, and every other reading a viewer could reach for —
 * the picture, the transport, the metrics panel — says the cast is healthy,
 * because it is. So the TV is the only place the fact exists, and saying it is the
 * whole of what can be done about it: nothing here restores the sound, and nothing
 * reads this to make a playback decision.
 */
enum class SilentAudioNoticePhase {
    /** Nothing to say yet, or nothing to say it over. */
    Waiting,

    /** On screen. */
    Showing,

    /** Said once — it does not come back this cast. */
    Spent,
}

/**
 * How long it stays up — [ORIENTATION_HINT_MS]'s span, for [ORIENTATION_HINT_MS]'s
 * reasons: two short lines have to be read from ten feet, and outliving the 4 s
 * chrome auto-hide is what stops the card reading as one more control that left.
 */
const val SILENT_AUDIO_NOTICE_MS = 6_000L

/** What the wire and the screen carry when the container named no audio format. */
const val SILENT_AUDIO_MIME_UNKNOWN = "unknown"

/** Long enough for every format media3 names; see [silentAudioMimeReading]. */
private const val MIME_READING_MAX_LENGTH = 40

/**
 * The reading to publish for a silent film's audio format — never null, and never
 * anything but a short plain token.
 *
 * In practice an extractor maps a container's codec id onto one of media3's own
 * `MimeTypes` constants or onto null, so the fallback is unreachable for the files
 * this app plays. It is here because the value leaves the device: the phone
 * validates this frame strictly and a refused frame costs the whole control
 * socket, so an unrecognisable format reads as unknown rather than ending the
 * control link of a film that is still playing.
 */
fun silentAudioMimeReading(sampleMimeType: String?): String {
    if (sampleMimeType == null || sampleMimeType.length !in 1..MIME_READING_MAX_LENGTH) {
        return SILENT_AUDIO_MIME_UNKNOWN
    }
    val plain = sampleMimeType.all { char ->
        char.code in 0x20..0x7e && (char.isLetterOrDigit() || char in "/.+-;=_")
    }
    return if (plain) sampleMimeType else SILENT_AUDIO_MIME_UNKNOWN
}

/**
 * Whether the notice may be on screen, and whether it is finished.
 *
 * [filmVisible] and [qualityShowing] carry [orientationHintPhase]'s reasoning
 * unchanged: the reading lands while the cast is still starting, so a clock
 * started at `onTracksChanged` would spend the notice behind the connecting
 * screen, and the T8 quality flourish holds the same band for its first 4.5 s.
 *
 * [panelOpen] is where this deliberately parts company with the hint. There an
 * open panel SPENDS the reading, because the panel is the control the hint was
 * pointing at and a viewer holding it no longer needs the sign. Nothing in any
 * panel says a word about missing sound, so there is nothing here for a panel to
 * make redundant — it only has to wait, because a panel is 200 dp to 488 dp of
 * glass reaching up the frame and this card would sit on top of it. A viewer who
 * closes the panel still gets told.
 */
fun silentAudioNoticePhase(
    mimeType: String?,
    filmVisible: Boolean,
    qualityShowing: Boolean,
    panelOpen: Boolean,
    alreadyShown: Boolean,
): SilentAudioNoticePhase = when {
    alreadyShown -> SilentAudioNoticePhase.Spent
    mimeType == null -> SilentAudioNoticePhase.Waiting
    !filmVisible || qualityShowing || panelOpen -> SilentAudioNoticePhase.Waiting
    else -> SilentAudioNoticePhase.Showing
}

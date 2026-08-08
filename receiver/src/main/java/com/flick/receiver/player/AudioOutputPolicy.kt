package com.flick.receiver.player

import androidx.media3.common.PlaybackException

/**
 * Whether a failure is the audio OUTPUT refusing what it was handed, rather than
 * anything about the film.
 *
 * ## The failure this exists for
 *
 * When the TV's media audio is routed to a Bluetooth speaker, that route carries
 * PCM stereo and nothing else. The platform nevertheless keeps advertising AC-3
 * direct playback, because the advertisement is derived from the HDMI EDID rather
 * than from the route in use — `AudioManager.getDirectProfilesForAttributes`,
 * which media3 1.10.1 trusts exclusively on API 33+, is documented to reflect only
 * the active route and does not on the verified hardware. media3 therefore selects
 * passthrough for an AC-3 track — no audio decoder is instantiated at all — and
 * AudioFlinger refuses the track:
 *
 * ```
 * createTrack_l() Bad parameter: format 0x9000000 for output ... with format 0x1
 * AudioSink$InitializationException: AudioTrack init failed 0 ... audio/ac3 ... [6, 48000]
 * ```
 *
 * `0x9000000` is `AUDIO_FORMAT_AC3`; `0x1` is PCM. The film is fine — on the file
 * that exposed this, the picture had already reached first frame at 3840x2160
 * before the audio ended the cast.
 *
 * Left alone this is a wide failure: most film rips and broadcast recordings carry
 * AC-3 or E-AC-3, and every one of them fails for a viewer whose TV sends sound to
 * a Bluetooth speaker, while the same files play for someone on HDMI.
 *
 * ## Why all four codes, and why a rebuild is the answer
 *
 * All four mean the same thing at the only level this app can act on: the output
 * would not take what the renderer handed it. Decoding to PCM instead of passing
 * the bitstream through is the one lever that changes what gets handed over, and
 * it is a property of the `AudioSink` — fixed when the sink is built, so acting on
 * it costs a new player rather than a re-prepare.
 *
 * The write-failure codes are included even though the observed fault is an init
 * failure. A route that changes mid-film lands there instead, and the answer is
 * the same. The attempt is made once per cast, so a device that is simply gone
 * costs one rebuild and then reports honestly.
 *
 * This deliberately does NOT pre-empt. Passthrough is correct on an HDMI route and
 * is what gives that viewer their surround sound; guessing from the route type
 * would take it away from people it works for, and would still be a guess about a
 * route that can change. Reacting to the actual refusal is never wrong.
 */
object AudioOutputPolicy {

    /** The output refused the track. Nothing here indicts the file. */
    fun isOutputRefusal(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> true
        else -> false
    }

    /**
     * Whether this cast should be rebuilt to decode compressed audio instead of
     * passing it through.
     *
     * [alreadyDecoding] is the once-per-cast bound. A second refusal after the sink
     * has already been rebuilt is not about passthrough any more, so it must fall
     * through to the ordinary diagnosis rather than loop.
     */
    fun shouldDecodeInstead(errorCode: Int, alreadyDecoding: Boolean): Boolean =
        !alreadyDecoding && isOutputRefusal(errorCode)
}

package com.flick.receiver.player

import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.flick.receiver.util.FlickLog

/**
 * One consumption of a rotation command by the decoder's format path — what the
 * decoder was handed, which command that came from, and what turn it achieved.
 *
 * Returned and recorded by the same [VideoRotationOverride.takeForDecoder] call,
 * from a single read of the command, so the number the decoder gets and the
 * number the log names can never be two different commands.
 */
class DecoderTurn internal constructor(
    /** Written into `Format.rotationDegrees`, and from there `KEY_ROTATION`. */
    val correctedDegrees: Int,
    /**
     * The container's own declared turn, which [correctedDegrees] was resolved
     * against. Kept because it is the only honest way to ask, from the main
     * thread, what the decoder's configuration WOULD be under a new command:
     * that answer is a function of the container, and the container is knowable
     * only here.
     */
    internal val containerDegrees: Int,
    /** The extra turn commanded when this read happened. */
    val commandedDegrees: Int,
    /**
     * The extra turn the decoder actually ends up applying, which is 0 — whatever
     * was commanded — in two cases. A container off the quarter-turn grid:
     * `KEY_ROTATION` accepts nothing else, so such a file is presented as filed
     * and no command can move it. And a command the video surface is carrying:
     * the decoder is then deliberately configured at 0 so the turn happens once.
     */
    val appliedDegrees: Int,
    /** Whether the surface's own transform is carrying this turn instead of the codec. */
    val viaView: Boolean,
    /** Which command this read consumed. */
    internal val serial: Long,
)

/**
 * The turn the video decoder is commanded to apply, and — the other direction —
 * the configuration the decoder actually last took.
 *
 * ## What the question is, and what it is not
 *
 * [needsDecoderReconfigure] asks whether the DECODER's configuration still has to
 * be changed, and deliberately not whether the commanded number moved. The two
 * part company in both directions, and each mistake costs the viewer something:
 *
 *  - A turn handed from the codec to the video surface on a film whose container
 *    declares nothing changes the command and changes the decoder's configuration
 *    by nothing at all — it was 0 and stays 0. Re-preparing for that would spend
 *    a re-buffer of seconds at 4K to reach the configuration already in force.
 *    This is the ordinary case: a viewer pressing a rotation key on a normal film
 *    now costs a matrix on a view and nothing else.
 *  - A container declaring something off the quarter-turn grid consumes every
 *    command and moves nothing, so a rule written on degrees would find the
 *    decoder behind forever and re-buffer forever to prove it.
 *
 * Which mechanism is carrying the turn is therefore recorded for the log and for
 * [DecoderTurn.appliedDegrees], and is never by itself a reason to re-prepare.
 *
 * ## Why a serial
 *
 * A command reaches the decoder only through a re-prepare, and that costs a
 * re-buffer measured in seconds at 4K over Wi-Fi. The decoder's record cannot
 * change until it reads a format at the far end of that window, so the symptom of
 * the re-buffer is a picture that has not turned — which is exactly what invites
 * the viewer to press the same key again. Each repeat would then restart the
 * re-buffer from the beginning, without bound. The serial separates "not yet, and
 * one is already on its way" from "not yet, and nothing is coming": the first is
 * absorbed, the second is repaired. Re-commanding a turn already commanded does
 * not mint a new serial, which is what makes a repeat the SAME request rather
 * than a fresh one.
 *
 * ## Threading
 *
 * [command] is written on the main thread and read on the playback thread;
 * [consumed] the other way about. Each is one volatile reference, so a single
 * read yields one coherent set of fields rather than a mixture of two states.
 * [issuedSerial] is touched only on the main thread. Volatile rather than
 * synchronized because the playback thread reads this inside codec
 * configuration, on the thread that must not be made to wait for a lock; every
 * command that needs one is followed by a `prepare()` on the main thread, which
 * posts to the playback thread and carries the new value across with it.
 *
 * There IS an invariant across the two — [needsDecoderReconfigure] is a function
 * of both and reads them non-atomically. The only interleaving possible is the
 * playback thread recording a consumption between the two reads, and that can
 * only move the answer from "owed" to "not owed" for the command already in
 * flight: the re-prepare it would have issued is the one that has just landed.
 */
class VideoRotationOverride {

    /** A commanded turn and the serial that tells a repeat from a change. */
    private class Command(val degrees: Int, val viaView: Boolean, val serial: Long)

    @Volatile
    private var command = Command(degrees = 0, viaView = false, serial = 0L)

    /** null until the decoder has read a format for this film. */
    @Volatile
    private var consumed: DecoderTurn? = null

    /**
     * The serial a re-prepare was actually issued for, or that needs none.
     * Main-thread only, so a plain field: [markCarried] and
     * [needsDecoderReconfigure] are both reached only from `PlayerController`'s
     * main-thread rotation path.
     */
    private var issuedSerial: Long = 0L

    /** The turn currently commanded, on top of whatever the container declares. */
    val commandedDegrees: Int get() = command.degrees

    /** Whether the current command is the video surface's to carry, not the codec's. */
    val commandedViaView: Boolean get() = command.viaView

    /** The extra turn the decoder last actually applied, or null before any. */
    val decoderExtraDegrees: Int? get() = consumed?.appliedDegrees

    /** How the decoder's last read said the turn was being carried, for the log. */
    val decoderReadViaView: Boolean? get() = consumed?.viaView

    /**
     * Whether a re-prepare has to be spent to get the decoder to the
     * configuration the command now recorded asks for. Asked AFTER
     * [commandTurn], which is what makes "the command now recorded" a thing that
     * exists.
     *
     * Three ways the answer is no, and each is a re-buffer not spent:
     * - the decoder has read no format at all yet, because it is about to be
     *   configured from the commanded value anyway — treating "unknown" as
     *   "wrong" would spend a re-buffer during startup to reach the turn startup
     *   was already going to use;
     * - the decoder is already configured exactly this way, which covers both a
     *   command it has consumed and a command that asks for nothing it does not
     *   already have (a mechanism change that leaves the codec at the same
     *   number, and the off-grid container that no command can move);
     * - a re-prepare for this exact command is in flight, so the repeat that
     *   arrived during its re-buffer is the same request already being served.
     */
    fun needsDecoderReconfigure(): Boolean {
        val cmd = command
        val last = consumed ?: return false
        if (last.correctedDegrees == decoderDegreesFor(last.containerDegrees, cmd)) return false
        // Commanded, not configured: either a re-prepare is carrying it — leave it
        // alone — or one was never issued and the decoder is stranded behind a
        // choice the panel already draws, which only a re-assert can repair.
        return issuedSerial != cmd.serial
    }

    /**
     * Record the turn the decoder is to be given, naming which mechanism carries
     * it. Deliberately not a "did this change anything" verdict: what has to
     * change is the DECODER's configuration, and only [needsDecoderReconfigure]
     * can answer that.
     *
     * A command identical to the one already standing keeps its serial. That is
     * what makes a viewer's repeated press the same request rather than a new
     * one — see the serial's note above — and the phone deliberately does not
     * dedupe `setRotation`, so repeats are the normal traffic on this path.
     */
    fun commandTurn(degrees: Int, viaView: Boolean) {
        val cmd = command
        if (cmd.degrees == degrees && cmd.viaView == viaView) return
        command = Command(degrees, viaView, cmd.serial + 1)
    }

    /**
     * Say that the current command is now being carried — either by a re-prepare
     * that has actually been issued, or by a decoder whose configuration already
     * matches it and which therefore has nothing left to deliver. Never called
     * for a re-prepare that declined, so such a command stays repairable by the
     * next re-assert.
     */
    fun markCarried() {
        issuedSerial = command.serial
    }

    /**
     * The rotation for a container declaring [containerRotationDegrees], recorded
     * as the command the decoder has now consumed. One read of [command], so the
     * value returned, the value recorded and the value logged are one command.
     *
     * Recording here is accurate even though media3 may reuse the codec rather
     * than configure it again: `MediaCodecInfo.canReuseCodec` names a rotation
     * change as a discard reason, so a codec that survives this call is one whose
     * rotation did not change.
     */
    fun takeForDecoder(containerRotationDegrees: Int): DecoderTurn {
        val cmd = command
        val corrected = decoderDegreesFor(containerRotationDegrees, cmd)
        // [effectiveRotationDegrees] returns the container untouched when either
        // turn is off the quarter-turn grid, because `MediaFormat.KEY_ROTATION`
        // accepts nothing else. The decoder is then honouring the file rather than
        // the command, and recording the command as applied would claim a turn
        // nobody can see. Only a zero extra leaves the two equal otherwise.
        val turn = DecoderTurn(
            correctedDegrees = corrected,
            containerDegrees = containerRotationDegrees,
            commandedDegrees = cmd.degrees,
            appliedDegrees = when {
                cmd.viaView -> 0
                corrected == containerRotationDegrees -> 0
                else -> cmd.degrees
            },
            viaView = cmd.viaView,
            serial = cmd.serial,
        )
        consumed = turn
        return turn
    }

    /**
     * A new film: no turn commanded, and its decoder is configured from scratch,
     * so it has consumed nothing yet. The fresh command counts as carried because
     * the film's own first codec configuration is what delivers it.
     */
    fun reset() {
        command = Command(degrees = 0, viaView = false, serial = command.serial + 1)
        consumed = null
        markCarried()
    }

    /**
     * What the codec is configured with under [cmd]. Under the video surface's
     * transform it is 0 — not the container's own value — so the turn happens
     * exactly once and media3's reported `VideoSize` stays equal to the coded
     * size the transform is computed from. See [pictureTurnFor].
     */
    private fun decoderDegreesFor(containerDegrees: Int, cmd: Command): Int =
        if (cmd.viaView) 0 else effectiveRotationDegrees(containerDegrees, cmd.degrees)
}

/**
 * The video renderer, with one line changed: the rotation it hands the decoder.
 *
 * This is deliberately the SAME zero-cost path a correctly tagged file already
 * travels. `MediaCodecVideoRenderer.getMediaFormat` copies `rotationDegrees`
 * into `MediaFormat.KEY_ROTATION`, and the platform turns that into a buffer
 * transform on the codec's output surface — media3 1.10.1's
 * `onOutputFormatChanged` then swaps width/height and inverts the sample aspect
 * for a 90/270 turn, which is why `VideoSize.unappliedRotationDegrees` is
 * deprecated to a permanent 0 and why `PlayerView` needs no help to letterbox
 * the result. Rewriting the format the renderer is fed therefore costs exactly
 * what the file's own rotation costs: nothing. No extra surface, no extra pass —
 * the film stays on the `SurfaceView`, with tunneling and HDR still on the table.
 *
 * What it cannot do is make the transform actually happen. The verified Google
 * TV Streamer's display pipeline drops it, so a film whose picture must genuinely
 * be turned is carried by the video surface's own transform instead and this
 * class is then handed a 0 to write — see [VideoRotationOverride.takeForDecoder]
 * and [pictureTurnFor]. Both mechanisms come through this one method, which is
 * why the log line below names which one is in force.
 *
 * Media3 itself rewrites `formatHolder.format` in `BaseRenderer.readSource`, so
 * mutating the holder before delegating is the supported shape rather than a
 * trick; the `SampleQueue` behind it keeps its own reference and is not touched.
 *
 * Constructed by [FlickRenderersFactory], which then wraps it in
 * [AudioDelayVideoRenderer]. Being a SUBCLASS rather than a stock
 * `MediaCodecVideoRenderer` is load-bearing for one thing beyond rotation: it
 * takes media3's pre-warming path off the table permanently — see
 * `FlickRenderersFactory.createSecondaryRenderer`.
 */
internal class RotationCorrectingVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val rotation: VideoRotationOverride,
) : MediaCodecVideoRenderer(builder) {

    // The last line logged. Playback-thread only — this method is the only reader
    // and the only writer — so plain fields, not volatiles.
    private var loggedContainerDegrees: Int = NOT_LOGGED
    private var loggedDecoderDegrees: Int = NOT_LOGGED
    private var loggedSerial: Long = NOT_LOGGED_SERIAL

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val format = formatHolder.format
        if (format != null) {
            val container = format.rotationDegrees
            val turn = rotation.takeForDecoder(container)
            if (turn.correctedDegrees != container) {
                formatHolder.format =
                    format.buildUpon().setRotationDegrees(turn.correctedDegrees).build()
            }
            // The one place that can say a commanded turn actually became the
            // decoder's configuration rather than only the receiver's intent. The
            // command's serial is part of the key, so every re-prepare's read
            // shows up beside the `rotation` line that caused it — without it, a
            // container whose corrected rotation never changes (one off the
            // quarter-turn grid) would go silent and read as a media3 failure.
            // A film's own format changes carry neither a new pair nor a new
            // serial, so they cost a comparison and nothing else.
            if (container != loggedContainerDegrees ||
                turn.correctedDegrees != loggedDecoderDegrees ||
                turn.serial != loggedSerial
            ) {
                loggedContainerDegrees = container
                loggedDecoderDegrees = turn.correctedDegrees
                loggedSerial = turn.serial
                FlickLog.i(
                    "player",
                    "rotationToDecoder container=$container decoder=${turn.correctedDegrees} " +
                        "extraDegrees=${turn.commandedDegrees} applied=${turn.appliedDegrees} " +
                        "via=${if (turn.viaView) "view" else "decoder"} command=${turn.serial}",
                )
            }
        }
        return super.onInputFormatChanged(formatHolder)
    }

    private companion object {
        /** Outside every rotation a container can declare, so the first pair logs. */
        const val NOT_LOGGED = Int.MIN_VALUE
        const val NOT_LOGGED_SERIAL = Long.MIN_VALUE
    }
}

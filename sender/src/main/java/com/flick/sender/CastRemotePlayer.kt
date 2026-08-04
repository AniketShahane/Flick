package com.flick.sender

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.SimpleBasePlayer.State
import androidx.media3.common.util.UnstableApi
import com.flick.sender.media.CastArtwork
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A `Player` whose playback is happening on the television.
 *
 * `SimpleBasePlayer` exists for exactly this shape of app — a controller with no decoder
 * of its own — so the platform media controls, the lock screen and a Bluetooth headset all
 * talk to one ordinary Media3 session while the frames are decoded on the TV.
 *
 * **It re-implements no transport.** Every verb forwards to the same [CastTransportState]
 * the phone's own remote spends, which is the one `PlaybackSession`: the coalescing of a
 * ±10s run into a single seek, the optimistic play/pause hold against stale in-flight
 * frames, and the idempotent absolute seek all belong there. A second copy of any of them
 * here would be a second answer to the same question.
 *
 * Main-confined by construction: the application looper IS the main looper, Media3 raises
 * on a call from any other thread, and the session the verbs reach is main-confined too.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CastRemotePlayer(looper: Looper) : SimpleBasePlayer(looper) {

    // The extrapolating position supplier measures from the instant it is built, so a
    // fresh one per getState() call would restart its clock on every read and leave the
    // scrubber frozen between TV frames. One State per snapshot, cached until the
    // snapshot itself changes.
    private var lastSnapshot: CastTransportSnapshot? = null
    // The still is decoded off the start path and arrives after the cast it belongs to, so
    // it invalidates the cached State on its own terms: identity is enough, because the
    // service publishes one instance per cast and never mutates it.
    private var lastArtwork: CastArtwork? = null
    private var lastState: State = idleState()
    private var lastMetadata: MediaMetadata? = null
    private var lastMetadataOf: Triple<String, String?, CastArtwork?>? = null

    override fun getState(): State {
        val snapshot = CastTransportState.state.value
        val artwork = CastArtworkState.artworkFor(snapshot?.castId)
        if (snapshot == lastSnapshot && artwork === lastArtwork) return lastState
        lastSnapshot = snapshot
        lastArtwork = artwork
        lastState = if (snapshot == null) idleState() else stateOf(snapshot, artwork)
        return lastState
    }

    /** Re-read the cast. `invalidateState` is protected, and the service is the caller. */
    fun refresh() = invalidateState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        CastTransportState.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    /**
     * `BasePlayer.seekBack`/`seekForward` arrive here as an already-computed absolute
     * position tagged with the command that produced it. The tag is what matters: a ±10s
     * tap must reach [CastTransportState.skip] so a run of them still costs the TV one
     * decoder flush, and only a scrubber landing — which has no run to join — is taken as
     * the absolute seek it is.
     */
    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK ->
                CastTransportState.skip(-CastNotificationPolicy.SKIP_INCREMENT_MS)
            Player.COMMAND_SEEK_FORWARD ->
                CastTransportState.skip(CastNotificationPolicy.SKIP_INCREMENT_MS)
            else -> CastTransportState.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        CastTransportState.stop()
        return Futures.immediateVoidFuture()
    }

    private fun stateOf(snapshot: CastTransportSnapshot, artwork: CastArtwork?): State {
        val controls = CastNotificationPolicy.controls(
            snapshot.phase,
            snapshot.commandable,
            snapshot.durationMs,
        )
        val stage = CastNotificationPolicy.stage(snapshot.phase)
        val position = snapshot.positionMs.coerceAtLeast(0L)
        val buffered = CastNotificationPolicy.bufferedPositionMs(
            position,
            snapshot.bufferedMs,
            snapshot.durationMs,
        )
        val metadata = metadataFor(snapshot, artwork)
        // The cast id is the item's identity: a re-target mints a new one, which is
        // exactly when the platform should read this as a different film.
        val item = MediaItemData.Builder(snapshot.castId)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(snapshot.castId)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setMediaMetadata(metadata)
            .setDurationUs(
                if (snapshot.durationMs > 0L) snapshot.durationMs * 1_000L else C.TIME_UNSET,
            )
            .setIsSeekable(controls.seek)
            .setIsDynamic(false)
            .build()
        return State.Builder()
            .setAvailableCommands(commandsFor(controls))
            .setPlaybackState(playbackStateOf(stage))
            .setPlayWhenReady(snapshot.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                if (
                    CastNotificationPolicy.positionAdvances(
                        snapshot.phase,
                        snapshot.playing,
                        snapshot.headHeld,
                    )
                ) {
                    PositionSupplier.getExtrapolating(position, 1f)
                } else {
                    PositionSupplier.getConstant(position)
                },
            )
            .setContentBufferedPositionMs(PositionSupplier.getConstant(buffered))
            .setTotalBufferedDurationMs(PositionSupplier.getConstant(buffered - position))
            .setSeekBackIncrementMs(CastNotificationPolicy.SKIP_INCREMENT_MS)
            .setSeekForwardIncrementMs(CastNotificationPolicy.SKIP_INCREMENT_MS)
            .setPlaybackParameters(PlaybackParameters.DEFAULT)
            .setIsLoading(stage == CastNotificationPolicy.Stage.BUFFERING)
            .build()
    }

    /**
     * The media card's own facts, and none of what a `state` frame carries.
     *
     * Memoized because `MediaMetadata` copies the artwork bytes into every instance it
     * builds and a frame arrives several times a second: rebuilding it per position would
     * churn a still's worth of bytes for a card that did not change.
     */
    private fun metadataFor(snapshot: CastTransportSnapshot, artwork: CastArtwork?): MediaMetadata {
        val identity = Triple(snapshot.title, snapshot.deviceName, artwork)
        lastMetadata?.takeIf { lastMetadataOf == identity }?.let { return it }
        return MediaMetadata.Builder()
            .setTitle(snapshot.title)
            // The subtitle line of the media card. Naming the TV is the one thing this
            // surface can say that the phone's own screens cannot.
            .setArtist(snapshot.deviceName)
            // Compressed bytes rather than a URI: this frame was chosen and decoded out of
            // the film itself, so there is no address a bitmap loader could fetch it from.
            //
            // It is what the Android 13+ media controls draw on the shade and the lock
            // screen — the album art of a cast — and it takes precedence there over the
            // notification's own large icon, which SystemUI reaches for only when a session
            // carries no art. It is therefore also the picture the platform extracts that
            // surface's ENTIRE colour scheme from, which is why it arrives already matted on
            // Flick's amber; `mattedArtwork` holds that argument. Its absence costs only the
            // picture — and the amber with it.
            .setArtworkData(artwork?.data, artwork?.let { MediaMetadata.PICTURE_TYPE_FRONT_COVER })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
            .also {
                lastMetadata = it
                lastMetadataOf = identity
            }
    }

    /**
     * Nothing is casting. An empty playlist is legal only in IDLE or ENDED, and with no
     * commands available the platform drops the media card rather than leaving a dead
     * transport on the lock screen.
     */
    private fun idleState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.EMPTY)
        .setPlaybackState(Player.STATE_IDLE)
        .build()

    private fun playbackStateOf(stage: CastNotificationPolicy.Stage): Int = when (stage) {
        CastNotificationPolicy.Stage.IDLE -> Player.STATE_IDLE
        CastNotificationPolicy.Stage.BUFFERING -> Player.STATE_BUFFERING
        CastNotificationPolicy.Stage.READY -> Player.STATE_READY
        CastNotificationPolicy.Stage.ENDED -> Player.STATE_ENDED
    }

    /**
     * COMMAND_GET_CURRENT_MEDIA_ITEM and COMMAND_GET_METADATA are not optional: Media3
     * withholds duration and metadata from the platform session without them, and the
     * Android 13+ media controls draw no seek bar at all when the duration is unknown.
     * COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM is what becomes `ACTION_SEEK_TO`, which is what
     * makes that bar draggable.
     */
    private fun commandsFor(controls: CastNotificationPolicy.Controls): Player.Commands =
        Player.Commands.Builder()
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .addIf(Player.COMMAND_PLAY_PAUSE, controls.playPause)
            .addIf(Player.COMMAND_SEEK_BACK, controls.skip)
            .addIf(Player.COMMAND_SEEK_FORWARD, controls.skip)
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, controls.seek)
            .addIf(Player.COMMAND_STOP, controls.stop)
            .build()
}

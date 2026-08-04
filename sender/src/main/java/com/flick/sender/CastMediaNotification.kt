package com.flick.sender

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.flick.sender.ui.theme.Spark

/** The four PendingIntents a posted cast notification can carry, minted per cast id. */
internal data class CastNotificationIntents(
    val open: PendingIntent,
    val stop: PendingIntent,
    val playPause: PendingIntent,
    val skipBack: PendingIntent,
    val skipForward: PendingIntent,
)

/**
 * The ±10s buttons, as Media3 media-button preferences.
 *
 * They have to be preferences backed by a **player command** rather than plain
 * notification actions, because that is the only shape the Android 13+ media controls
 * render: that surface builds its own five-slot layout out of the session's playback
 * state, and only `PlaybackStateCompat` custom actions can fill the slots either side of
 * play/pause. Media3 converts a player-command button in the BACK/FORWARD slot into
 * exactly such a custom action and executes it back through `Player.seekBack`/`seekForward`
 * — which is how a shade tap and a headset button end up in the same [CastRemotePlayer]
 * hook. The default slot for `COMMAND_SEEK_BACK`/`COMMAND_SEEK_FORWARD` is already
 * BACK/FORWARD, so this states no layout of its own.
 *
 * Media3 disables a button whose player command the session does not currently offer, so
 * these disappear on their own while the cast is still connecting.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun castMediaButtonPreferences(context: Context): List<CommandButton> = listOf(
    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setDisplayName(context.getString(R.string.notif_action_back10))
        .setCustomIconResId(
            CommandButton.getIconResIdForIconConstant(CommandButton.ICON_SKIP_BACK_10),
        )
        .build(),
    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setDisplayName(context.getString(R.string.notif_action_forward10))
        .setCustomIconResId(
            CommandButton.getIconResIdForIconConstant(CommandButton.ICON_SKIP_FORWARD_10),
        )
        .build(),
)

/**
 * Build the foreground-service notification for the current cast.
 *
 * [session] is what promotes this from an ongoing-service line to a media notification:
 * `MediaStyle` carries the platform session token, and every surface that renders media
 * controls — the shade, the lock screen, Android Auto — reads the transport off that
 * session rather than off this notification. The explicit actions below are the
 * compatibility path for the releases that still draw a `MediaStyle` notification
 * themselves, and they reach the same verbs.
 *
 * With no [session] (a Media3 session this device refused to create) the result is the
 * plain ongoing notification the service has always posted: serving never depends on it.
 *
 * [artwork] is the film's own still, and null is its ordinary state for the first moment
 * of a cast — a foreground service must post promptly and a frame takes a decode to
 * produce, so the art arrives in a later post. Null draws the notification exactly as this
 * app drew it before there was any art at all.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun buildCastNotification(
    context: Context,
    channelId: String,
    session: MediaSession?,
    snapshot: CastTransportSnapshot?,
    intents: CastNotificationIntents,
    artwork: Bitmap?,
): Notification {
    val shape = CastNotificationPolicy.shape(snapshot)
    val controls = shape.controls
    val device = shape.deviceName ?: context.getString(R.string.notif_device_generic)
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(shape.title ?: context.getString(R.string.notif_title))
        .setContentText(
            when (shape.line) {
                CastNotificationPolicy.Line.SERVING ->
                    context.getString(R.string.notif_text_serving)
                CastNotificationPolicy.Line.CONNECTING ->
                    context.getString(R.string.notif_text_connecting, device)
                CastNotificationPolicy.Line.CASTING ->
                    context.getString(R.string.notif_text_casting, device)
                CastNotificationPolicy.Line.FINISHED ->
                    context.getString(R.string.notif_text_finished, device)
            },
        )
        // The album art of a cast. On Android 13+ the media controls draw the artwork the
        // SESSION carries instead of this one; both are set from the same still, and this
        // is what the releases that still render a MediaStyle notification themselves show.
        .setLargeIcon(artwork)
        // Flick's amber, read from the palette token rather than restated as a hex — and the
        // brand VALUE rather than the `spark` role, which inverts to blue in the app's dark
        // set: the shade is not this app's canvas and takes one colour under both. Not
        // `setColorized`, which a foreground service is one of the few things allowed to be —
        // amber is this product's mark and never its ground. It tints what draws this as an
        // ordinary row, and MediaStyle before 13; the Android 13+ media controls take their
        // colours from the artwork above and ignore it, which is not a fault to chase.
        .setColor(Spark.toArgb())
        .setContentIntent(intents.open)
        .setOngoing(true)
        // A transport, not a background chore. The category is what tells the platform
        // and the accessibility services what this row is for.
        .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        // The user asked for these controls on the lock screen, and the media-controls
        // surface renders the title and the TV name there from the session regardless of
        // what this notification hides. Nothing here names a URL, a token or an address.
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setShowWhen(false)

    if (controls.anyTransport) {
        builder.addAction(
            androidx.media3.session.R.drawable.media3_icon_skip_back_10,
            context.getString(R.string.notif_action_back10),
            intents.skipBack,
        )
        builder.addAction(
            if (shape.playing) {
                androidx.media3.session.R.drawable.media3_icon_pause
            } else {
                androidx.media3.session.R.drawable.media3_icon_play
            },
            context.getString(
                if (shape.playing) R.string.notif_action_pause else R.string.notif_action_play,
            ),
            intents.playPause,
        )
        builder.addAction(
            androidx.media3.session.R.drawable.media3_icon_skip_forward_10,
            context.getString(R.string.notif_action_forward10),
            intents.skipForward,
        )
    }
    builder.addAction(
        androidx.media3.session.R.drawable.media3_icon_stop,
        context.getString(R.string.notif_action_stop),
        intents.stop,
    )

    if (session != null) {
        val style = MediaStyleNotificationHelper.MediaStyle(session)
        // The collapsed row shows the three transport actions and nothing else, which is
        // the layout the user named: back, play/pause, forward. With no transport to show
        // it falls back to Stop rather than to a collapsed row with no action at all.
        style.setShowActionsInCompactView(*if (controls.anyTransport) intArrayOf(0, 1, 2) else intArrayOf(0))
        builder.setStyle(style)
    }
    return builder.build()
}

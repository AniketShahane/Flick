package com.flick.receiver.ui.library

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.library.LibraryImages
import com.flick.receiver.library.UpNextOffer
import com.flick.receiver.ui.components.FocusBeaconHost
import com.flick.receiver.ui.components.GlassPanel
import com.flick.receiver.ui.components.GlassPanelTone
import com.flick.receiver.ui.components.LocalShellRetained
import com.flick.receiver.ui.components.landTvFocus
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickMotion
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType
import com.flick.receiver.ui.theme.LocalHouseLit
import com.flick.receiver.ui.theme.LocalReducedMotion
import com.flick.receiver.ui.theme.tvOverscanSafeArea
import kotlinx.coroutines.delay

/** The autoplay window the hub counts down (§10: `play` after 10 s); the ring is drawn against it. */
private const val AUTOPLAY_WINDOW_SECONDS = 10

/**
 * The Up next card over the end of an episode: bottom right, over the full-size film, which
 * it never scales or covers. It positions itself inside [modifier]'s bounds, so the caller
 * passes the whole screen and the card takes only its corner.
 *
 * With autoplay on it counts down to [UpNextOffer.autoplayAtMs]; the hub sends the play,
 * this only draws the time left. When [UpNextOffer.stillWatching] it asks instead, with no
 * countdown at all.
 */
@Composable
fun UpNextCard(
    offer: UpNextOffer,
    onPlayNow: () -> Unit,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalHouseLit provides false) {
        val context = LocalContext.current
        val loader = remember(context) { libraryImageLoader(context) }
        val next = offer.next
        val playFocus = remember { FocusRequester() }
        val inside = remember { mutableStateOf(false) }
        val retained = LocalShellRetained.current
        LaunchedEffect(next.id, offer.stillWatching) {
            if (!retained) landTvFocus(playFocus, playFocus) { inside.value }
        }
        val label = LibraryFormat.episodeLabel(next)

        Box(modifier.tvOverscanSafeArea()) {
            GlassPanel(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = FlickSpace.Sm)
                    .width(390.dp)
                    .tracksFocus(inside),
                shape = FlickShape.Lg,
                tone = GlassPanelTone.Panel,
                contentPadding = PaddingValues(13.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(150.dp, 84.dp)
                            .clip(FlickShape.Md),
                    ) {
                        val url = LibraryImages.tileBackdrop(next.art?.backdrop) ?: LibraryImages.tileBackdrop(offer.show?.art?.backdrop)
                        if (url != null) {
                            RemoteImage(url, loader, Modifier.fillMaxSize())
                        } else {
                            TitleCard(offer.show?.title ?: next.title, Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = if (label != null) {
                                stringResource(R.string.libui_upnext_eyebrow, label)
                            } else {
                                stringResource(R.string.libui_upnext_eyebrow_plain)
                            },
                            style = FlickType.monoEyebrow(sizeSp = 14, trackingEm = 0.2f),
                            color = FlickColor.Spark,
                        )
                        Text(
                            text = if (offer.stillWatching) stringResource(R.string.libui_still_watching) else next.title,
                            style = FlickType.display(sizeSp = 20, weight = FontWeight.Bold),
                            color = FlickColor.OnSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val minutes = LibraryFormat.runtimeMinutes(next.durationMs)?.let { pluralText(R.plurals.libui_minutes_sentence, it, it) }
                        val subline = if (offer.stillWatching) next.title else minutes
                        if (subline != null) {
                            Text(
                                text = subline,
                                style = FlickType.body(sizeSp = 14, weight = FontWeight.Medium),
                                color = FlickColor.OnSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val autoplayAt = offer.autoplayAtMs
                    if (!offer.stillWatching && autoplayAt != null) CountdownRing(autoplayAt)
                }
                FocusBeaconHost {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        PrimaryAction(
                            text = stringResource(if (offer.stillWatching) R.string.libui_keep_watching else R.string.libui_play_now),
                            onClick = onPlayNow,
                            focusRequester = playFocus,
                        )
                        SecondaryAction(
                            text = stringResource(R.string.libui_back_to_library),
                            onClick = onBackToLibrary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Seconds left until autoplay, as a number in a draining ring. Ticks once a second and stops
 * at zero — a bounded ten-second loop, never a standing animation over the film.
 */
@Composable
private fun CountdownRing(autoplayAtMs: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(autoplayAtMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            val left = autoplayAtMs - now
            if (left <= 0L) break
            // Wake on the next whole-second boundary, so the number changes on time.
            delay((left % 1_000L).takeIf { it > 0L } ?: 1_000L)
        }
    }
    val seconds = LibraryFormat.secondsUntil(autoplayAtMs, now)
    val reduced = LocalReducedMotion.current
    val sweep by animateFloatAsState(
        targetValue = (seconds.toFloat() / AUTOPLAY_WINDOW_SECONDS).coerceIn(0f, 1f),
        animationSpec = FlickMotion.orSnap(reduced, tween<Float>(durationMillis = 1_000, easing = LinearEasing)),
        label = "upNextCountdown",
    )
    val spoken = pluralText(R.plurals.libui_countdown_a11y, seconds, seconds)
    Box(
        Modifier
            .size(46.dp)
            .semantics { contentDescription = spoken }
            .drawWithCache {
                val strokePx = 3.dp.toPx()
                val track = Stroke(width = strokePx)
                val arc = Stroke(width = strokePx, cap = StrokeCap.Round)
                val topLeft = Offset(strokePx / 2f, strokePx / 2f)
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                onDrawBehind {
                    drawArc(
                        color = FlickColor.TrackBase,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = track,
                    )
                    drawArc(
                        color = FlickColor.Spark,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = arc,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = seconds.toString(), style = FlickType.monoTabular(sizeSp = 18), color = FlickColor.OnSurface)
    }
}

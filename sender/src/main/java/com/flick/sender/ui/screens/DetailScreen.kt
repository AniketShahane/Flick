package com.flick.sender.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FlickController
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.SheetShape
import com.flick.sender.ui.theme.TileShadow
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale

/**
 * S4 — detail / "cast this". A risen sheet over the video's own frame: honest
 * badges, the direct-play promise, one blue CTA. Back and the scrim both route
 * through [FlickController.back], so the shell's own BackHandler stays the only
 * one on this route.
 */
@Composable
fun DetailScreen(controller: FlickController, item: MediaItem) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val connectedTv by controller.connectedTv.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val rise = rememberSheetRise()
    val tvName = connectedTv?.name ?: stringResource(R.string.np_tv_generic)
    val castDescription = stringResource(R.string.a11y_cast_video, item.name, tvName)
    val dismissDescription = stringResource(R.string.a11y_back_to_library)
    val hdr by produceState(initialValue = HdrType.NONE, item.uri) {
        value = MediaProbe.detectHdr(context, item.uri)
    }

    val request = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .videoFrameMillis((item.durationMs / 3L).coerceAtLeast(1000L))
            .crossfade(true)
            .build()
    }
    val scrimSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }
    val playHereSource = remember { MutableInteractionSource() }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // The scrim and the sheet body below it are a dismiss target and a click
        // consumer, not controls: a state layer on either would advertise a tap target
        // that isn't one, so both stay unindicated.
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .semantics { contentDescription = dismissDescription }
                .clickable(interactionSource = scrimSource, indication = null) { controller.back() },
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .sheetRiseTransform(rise)
                .clip(SheetShape)
                .background(colors.surface)
                .clickable(interactionSource = sheetSource, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
        ) {
            SheetGrabber()
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 122.dp, height = 78.dp)
                        .shadow(
                            elevation = 14.dp,
                            shape = RoundedCornerShape(FlickCorners.detailPoster),
                            clip = false,
                            ambientColor = TileShadow,
                            spotColor = TileShadow,
                        )
                        .clip(RoundedCornerShape(FlickCorners.detailPoster))
                        .background(colors.surfaceRaisedAlt),
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.name,
                        style = FlickText.titleLarge.copy(color = colors.onSurface),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detailMeta(item),
                        style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(17.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                // Resolution, dynamic range and size are the only source facts the
                // phone can read; codec and frame rate never leave MediaStore.
                DetailChip(
                    text = resolutionText(item),
                    containerColor = colors.inverseSurface,
                    contentColor = colors.onInverseSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                DetailChip(
                    text = hdrChipLabel(hdr),
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                )
                DetailChip(
                    text = Format.bytes(item.sizeBytes),
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                )
            }

            Spacer(Modifier.height(17.dp))
            val promiseLead = stringResource(R.string.detail_directplay_title)
            val promiseBody = stringResource(R.string.detail_directplay_body)
            Row(
                Modifier
                    .clip(RoundedCornerShape(FlickCorners.qualityCard))
                    .background(colors.spark)
                    .padding(15.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = FlickIcons.CheckCircle,
                    contentDescription = null,
                    tint = colors.onSpark,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(promiseLead) }
                        append(" ")
                        append(promiseBody)
                    },
                    style = FlickText.bodySmall.copy(color = colors.onSpark),
                )
            }

            Spacer(Modifier.height(17.dp))
            FlickToTvButton(
                text = connectedTv?.let { stringResource(R.string.detail_cta, it.name) }
                    ?: stringResource(R.string.detail_cta_noconnect),
                accessibilityLabel = castDescription,
                onClick = { controller.flickToTv(item) },
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.detail_play_here),
                // WCAG's large-text exemption starts above this size, so the only escape
                // from casting takes the dim ink rather than the faint one.
                style = FlickText.labelLarge.copy(color = colors.onSurfaceDim),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .clickable(
                        interactionSource = playHereSource,
                        indication = flickRipple(colors.primary),
                        role = Role.Button,
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(item.uri, "video/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            )
                        }
                    }
                    .heightIn(min = 48.dp)
                    .padding(vertical = 15.dp),
            )
        }
    }
}

/** The blue "Flick to <TV>" CTA. Falls back to pairing when no TV is connected. */
@Composable
private fun FlickToTvButton(
    text: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(source)
            .shadow(
                elevation = 14.dp,
                shape = PillShape,
                clip = false,
                ambientColor = PrimaryShadow,
                spotColor = PrimaryShadow,
            )
            .clip(PillShape)
            .background(colors.primary)
            .clickable(
                interactionSource = source,
                indication = flickRipple(colors.onPrimary),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) { contentDescription = accessibilityLabel }
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FlickIcons.Cast,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier.size(21.dp),
        )
        Text(text, style = FlickText.titleSmall.copy(color = colors.onPrimary))
    }
}

@Composable
private fun DetailChip(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = FlickText.monoChip.copy(color = contentColor),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(PillShape)
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

/** `4K · 3840 × 2160` when MediaStore knew the pixels, otherwise the class alone. */
@Composable
private fun resolutionText(item: MediaItem): String =
    if (item.width > 0 && item.height > 0) {
        stringResource(R.string.sheet_resolution_pixels, item.resolutionLabel, item.width, item.height)
    } else {
        item.resolutionLabel
    }

@Composable
private fun hdrChipLabel(hdr: HdrType): String = when (hdr) {
    HdrType.DOLBY_VISION -> stringResource(R.string.media_dolby_vision_badge)
    HdrType.HDR10 -> stringResource(R.string.media_hdr10_badge)
    HdrType.NONE -> stringResource(R.string.media_sdr)
}

/** Duration plus the MediaStore bucket, which is the only provenance we hold. */
@Composable
private fun detailMeta(item: MediaItem): String {
    val duration = Format.durationHuman(item.durationMs)
    val bucket = item.bucket?.takeIf { it.isNotBlank() } ?: return duration
    return stringResource(R.string.quality_playing_value, duration, bucket)
}

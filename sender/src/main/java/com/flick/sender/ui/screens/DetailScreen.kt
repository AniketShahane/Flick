package com.flick.sender.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.flick.sender.R
import com.flick.sender.media.MediaProbe
import com.flick.sender.model.HdrType
import com.flick.sender.model.MediaItem
import com.flick.sender.net.FlickController
import com.flick.sender.ui.Format
import com.flick.sender.ui.components.FlickCastButton
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.rememberVideoImageLoader
import com.flick.sender.ui.theme.FlickGradients
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PremiumInk

/** S4 — detail / "cast this". Poster-forward, honest badges, direct-play promise. */
@Composable
fun DetailScreen(controller: FlickController, item: MediaItem) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val connectedTv by controller.connectedTv.collectAsState()
    val imageLoader = rememberVideoImageLoader()
    val castDescription = stringResource(
        R.string.a11y_cast_video,
        item.name,
        connectedTv?.name ?: stringResource(R.string.np_tv_generic),
    )
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

    val backLabel = stringResource(R.string.a11y_back_to_library)
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.name,
                imageLoader = imageLoader,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x2608070C),
                            0.4f to Color.Transparent,
                            1f to Color(0xF20F0D14),
                        ),
                    ),
            )
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(14.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x8008070C))
                    .semantics { contentDescription = backLabel }
                    .clickable { controller.back() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(FlickIcons.Back, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(item.name, style = FlickText.heading.copy(color = Color.White))
                Text(
                    "${Format.durationHuman(item.durationMs)} · ${item.resolutionLabel}",
                    style = FlickText.caption.copy(color = Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        Column(Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.width > 0 && item.height > 0) {
                    TechBadge("${item.resolutionLabel} · ${item.width}×${item.height}")
                } else {
                    TechBadge(item.resolutionLabel)
                }
                when (hdr) {
                    HdrType.DOLBY_VISION -> TechBadge(stringResource(R.string.media_dolby_vision_badge), premium = true)
                    HdrType.HDR10 -> TechBadge(stringResource(R.string.media_hdr10_badge))
                    HdrType.NONE -> {}
                }
                TechBadge(Format.bytes(item.sizeBytes), dim = true)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.outlineHairline, RoundedCornerShape(13.dp))
                    .padding(12.dp),
            ) {
                Icon(FlickIcons.Check, contentDescription = null, tint = colors.live, modifier = Modifier.size(15.dp).padding(top = 1.dp))
                Column(Modifier.padding(start = 9.dp)) {
                    Text(
                        stringResource(R.string.detail_directplay_title),
                        style = FlickText.caption.copy(fontWeight = FontWeight.Bold, color = colors.onSurface),
                    )
                    Text(
                        stringResource(R.string.detail_directplay_body),
                        style = FlickText.caption.copy(color = colors.onSurfaceDim),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 18.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlickCastButton(
                text = connectedTv?.let { stringResource(R.string.detail_cta, it.name) }
                    ?: stringResource(R.string.detail_cta_noconnect),
                onClick = { controller.flickToTv(item) },
                accessibilityLabel = castDescription,
            )
            Spacer(Modifier.height(6.dp))
            FlickSubtleButton(
                text = stringResource(R.string.detail_play_here),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(item.uri, "video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TechBadge(text: String, premium: Boolean = false, dim: Boolean = false) {
    val colors = LocalFlickColors.current
    val shape = RoundedCornerShape(6.dp)
    if (premium) {
        Text(
            text,
            style = FlickText.mono.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PremiumInk),
            modifier = Modifier.clip(shape).background(FlickGradients.premiumSheen).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    } else {
        Text(
            text,
            style = FlickText.mono.copy(fontWeight = FontWeight.Bold, color = if (dim) colors.onSurfaceDim else colors.onSurface),
            modifier = Modifier
                .clip(shape)
                .border(1.dp, if (dim) colors.outlineHairline else colors.outline, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

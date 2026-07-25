package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow

/**
 * S12 — error faces. Diagnosis over apology: name the device, name the fault,
 * offer the one move that fixes it. Retryable failures always win over the
 * per-kind copy so a transient stumble never reads as a broken setup.
 */
@Composable
fun ErrorScreen(
    controller: FlickController,
    kind: CastErrorKind,
    failure: CastFailure,
    onOpenWifiSettings: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val tv by controller.connectedTv.collectAsState()
    val tvName = tv?.name ?: stringResource(R.string.np_tv_generic)

    val amber = kind == CastErrorKind.REACHABLE_NOT_SERVING
    val dotColor = if (amber) colors.caution else colors.trouble

    val title: String
    val body: String
    val primaryLabel: String
    val onPrimary: () -> Unit
    val secondaryLabel: String?
    val pillText: String
    val pillKind: StatusKind

    if (failure.retryable) {
        title = stringResource(R.string.error_generic_title)
        body = stringResource(R.string.error_generic_body)
        primaryLabel = stringResource(R.string.error_generic_primary)
        onPrimary = controller::retryCast
        secondaryLabel = null
        pillText = stringResource(R.string.error_unreachable_pill)
        pillKind = StatusKind.TROUBLE
    } else when (kind) {
        CastErrorKind.REACHABLE_NOT_SERVING -> {
            title = stringResource(R.string.error_reachable_title)
            body = stringResource(R.string.error_reachable_body, tvName)
            primaryLabel = stringResource(R.string.error_reachable_primary)
            onPrimary = { controller.openConnect() }
            secondaryLabel = stringResource(R.string.error_reachable_secondary)
            pillText = stringResource(R.string.error_reachable_pill)
            pillKind = StatusKind.CAUTION
        }
        CastErrorKind.UNREACHABLE -> {
            title = stringResource(R.string.error_unreachable_title, tvName)
            body = stringResource(R.string.error_unreachable_body)
            primaryLabel = stringResource(R.string.error_unreachable_primary)
            onPrimary = { controller.openConnect() }
            secondaryLabel = stringResource(R.string.error_unreachable_secondary)
            pillText = stringResource(R.string.error_unreachable_pill)
            pillKind = StatusKind.TROUBLE
        }
        CastErrorKind.NO_LAN -> {
            title = stringResource(R.string.error_nolan_title)
            body = stringResource(R.string.error_nolan_body)
            primaryLabel = stringResource(R.string.error_nolan_primary)
            onPrimary = onOpenWifiSettings
            secondaryLabel = null
            pillText = stringResource(R.string.error_unreachable_pill)
            pillKind = StatusKind.TROUBLE
        }
        CastErrorKind.GENERIC -> {
            title = stringResource(R.string.error_generic_title)
            body = stringResource(R.string.error_generic_body)
            primaryLabel = stringResource(R.string.error_generic_primary)
            onPrimary = { controller.back() }
            secondaryLabel = null
            pillText = stringResource(R.string.error_unreachable_pill)
            pillKind = StatusKind.TROUBLE
        }
    }

    val statusDescription = stringResource(R.string.a11y_network_status, pillText)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvEmblem(dotColor = dotColor, muted = amber)
            Spacer(Modifier.height(24.dp))
            Text(
                title,
                style = FlickText.headlineSmall.copy(color = colors.onSurface),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                body,
                style = FlickText.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceDim,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = primaryLabel,
                style = FlickText.titleSmall.copy(color = colors.onPrimary),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 14.dp,
                        shape = PillShape,
                        clip = false,
                        ambientColor = PrimaryShadow,
                        spotColor = PrimaryShadow,
                    )
                    .clip(PillShape)
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClick = onPrimary)
                    .heightIn(min = 48.dp)
                    .padding(vertical = 19.dp),
            )
            if (secondaryLabel != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = secondaryLabel,
                    style = FlickText.labelMedium.copy(color = colors.onSurfaceDim),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .clickable(role = Role.Button) { controller.openConnect() }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 15.dp),
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .semantics { contentDescription = statusDescription },
        ) {
            StatusPill(pillText, pillKind)
        }
    }
}

/** An outlined TV with a status lamp — the fault, drawn rather than apologised for. */
@Composable
private fun TvEmblem(dotColor: Color, muted: Boolean) {
    val colors = LocalFlickColors.current
    Box {
        Box(
            Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(FlickCorners.backBtn))
                .background(colors.surfaceTonal)
                .border(
                    width = 2.5.dp,
                    color = if (muted) colors.outline else colors.trouble.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(FlickCorners.backBtn),
                ),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(colors.canvas)
                .padding(3.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

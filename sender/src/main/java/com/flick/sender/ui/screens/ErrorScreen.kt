package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.model.CastErrorKind
import com.flick.sender.model.CastFailure
import com.flick.sender.net.FlickController
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.components.StatusKind
import com.flick.sender.ui.components.StatusPill
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors

/** S12 — error faces. Diagnosis over apology; specific and actionable. */
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
    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Box(
                    Modifier
                        .size(width = 84.dp, height = 52.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            2.dp,
                            if (amber) colors.onSurfaceDim.copy(alpha = 0.4f) else colors.trouble.copy(alpha = 0.55f),
                            RoundedCornerShape(7.dp),
                        ),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 0.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = FlickText.heading.copy(color = colors.onSurface),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                body,
                style = FlickText.body.copy(color = colors.onSurfaceDim),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            FlickPrimaryButton(
                text = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.width(220.dp),
            )
            if (secondaryLabel != null) {
                Spacer(Modifier.height(6.dp))
                FlickSubtleButton(text = secondaryLabel, onClick = { controller.openConnect() })
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

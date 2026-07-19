package com.flick.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.net.ProbeResult

private val LabelColor = Color(0xFF90A4AE)
private val ValueColor = Color(0xFFECEFF1)
private val BadColor = Color(0xFFFF5252)
private val CheckingColor = Color(0xFFFFC107)

/**
 * Brief "checking connection" state shown while the pre-flight probe runs, so the
 * gap between pressing Play and playback (or a diagnosis) is never a dead screen.
 */
@Composable
fun CheckingCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(min = 340.dp, max = 480.dp)
            .background(Color(0xD9000000), RoundedCornerShape(12.dp))
            .border(2.dp, CheckingColor, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.preflight_checking_title),
            color = CheckingColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.preflight_checking_detail),
            color = LabelColor,
            fontSize = 13.sp,
        )
    }
}

/**
 * Specific diagnosis shown when the pre-flight probe fails: which problem it is
 * and how to fix it, plus a Retry action (auto-focused so the remote can fire it).
 */
@Composable
fun DiagnosisCard(
    result: ProbeResult,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }

    val title: String
    val detail: String
    when (result) {
        is ProbeResult.ConnectionRefused -> {
            title = stringResource(R.string.preflight_refused_title)
            detail = stringResource(R.string.preflight_refused_detail)
        }
        is ProbeResult.HttpError -> {
            title = stringResource(R.string.preflight_http_error_title, result.code)
            detail = stringResource(R.string.preflight_http_error_detail, result.code)
        }
        is ProbeResult.BadUrl -> {
            title = stringResource(R.string.preflight_bad_url_title)
            detail = stringResource(R.string.preflight_bad_url_detail)
        }
        // Unreachable (and the never-rendered Ok) share the peer-block copy.
        else -> {
            title = stringResource(R.string.preflight_unreachable_title)
            detail = stringResource(R.string.preflight_unreachable_detail)
        }
    }

    Column(
        modifier = modifier
            .widthIn(min = 340.dp, max = 480.dp)
            .background(Color(0xD9000000), RoundedCornerShape(12.dp))
            .border(2.dp, BadColor, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = title, color = BadColor, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(text = detail, color = ValueColor, fontSize = 14.sp)
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(retryFocus),
        ) {
            Text(stringResource(R.string.preflight_retry))
        }
    }
}

package com.flick.receiver.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.flick.receiver.R
import com.flick.receiver.ui.theme.BrandMark
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickDimens
import com.flick.receiver.ui.theme.FlickShape
import com.flick.receiver.ui.theme.FlickSpace
import com.flick.receiver.ui.theme.FlickType

/**
 * Inset for the focusable form only. Tighter than [FlickDimens.ControlPadding]:
 * the lockup is already large, and the detached ring is what has to clear it —
 * not a fill.
 */
private val WordmarkFocusPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/**
 * The Flick lockup (receiver-expressive-spec.md §5.1): brand mark + wordmark,
 * with the optional `RECEIVER · <TV NAME>` eyebrow stacked beneath. The eyebrow
 * carries the real device name — there is no version string to invent.
 *
 * The streaks are dropped below ~24 dp, where they stop resolving at 10 ft.
 *
 * Sized as a persistent header lockup rather than a headline: it identifies the
 * screen, it does not lead it, so it sits below the display roles in the scale.
 *
 * With [onClick] the lockup becomes a focus target and takes the receiver's one
 * focus vocabulary — the lift, the corner ease and the travelling amber ring of
 * [FlickTvButton] — with no fill and no border of its own, so a focused brand
 * mark is ringed rather than boxed. Without it the lockup is inert chrome and
 * adds nothing to the focus graph.
 */
@Composable
fun FlickWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 30.dp,
    textSizeSp: Int = 18,
    eyebrow: String? = null,
    tint: Color = FlickColor.OnSurface,
    markTint: Color = FlickColor.OnSurface,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val lockup: @Composable RowScope.() -> Unit = {
        BrandMark(size = markSize, tint = markTint, withStreaks = markSize >= 24.dp)
        Column(verticalArrangement = Arrangement.spacedBy(FlickSpace.Xs)) {
            Text(
                text = stringResource(R.string.brand_wordmark),
                style = FlickType.display(sizeSp = textSizeSp),
                color = tint,
            )
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = FlickType.monoEyebrow(trackingEm = 0.2f),
                    color = FlickColor.OnSurfaceMuted,
                )
            }
        }
    }
    if (onClick != null) {
        FlickTvButton(
            onClick = onClick,
            modifier = modifier,
            contentDescription = contentDescription,
            focusRequester = focusRequester,
            shape = FlickShape.Md,
            containerColor = Color.Transparent,
            borderColor = Color.Transparent,
            // Passed explicitly: a transparent fill would otherwise resolve to
            // the doubled outline-only stroke, which there is no outline to draw.
            borderWidth = FlickDimens.Hairline,
            contentPadding = WordmarkFocusPadding,
            horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
            content = lockup,
        )
        return
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlickSpace.Sm),
        content = lockup,
    )
}

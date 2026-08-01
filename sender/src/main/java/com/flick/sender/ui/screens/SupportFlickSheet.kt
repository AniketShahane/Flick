package com.flick.sender.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.support.SupportCatalog
import com.flick.sender.support.SupportOption
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillMorphShape
import com.flick.sender.ui.theme.PressedPillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale

/** The catalog is already all-or-nothing; this sheet never creates or transforms a checkout URL. */
@Composable
fun SupportFlickSheet(
    catalog: SupportCatalog,
    onDismiss: () -> Unit,
    onOpenCheckout: (String) -> Unit,
) {
    val colors = LocalFlickColors.current
    val title = stringResource(R.string.support_sheet_title)
    val checkoutLaunch = remember { SupportCheckoutLaunchGate() }
    BottomSheet(onDismiss = onDismiss, paneLabel = title) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.support_sheet_body),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 5.dp),
        )

        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            catalog.options.forEach { option ->
                SupportOptionRow(
                    option = option,
                    onOpenCheckout = { checkoutUrl ->
                        if (checkoutLaunch.claim()) onOpenCheckout(checkoutUrl)
                    },
                )
            }
        }

        Text(
            text = stringResource(R.string.support_checkout_disclosure),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.support_checkout_browser),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/** Prevents the fading outgoing sheet from launching a second browser hand-off. */
internal class SupportCheckoutLaunchGate {
    private var claimed = false

    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}

/** Compact earned card; dismissal changes only the app-scoped transient after the durable claim. */
@Composable
internal fun SupportInvitationCard(
    onOpenSupport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val buttonShapes = remember { ButtonShapes(shape = PillMorphShape, pressedShape = PressedPillShape) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.warning))
            .background(colors.sparkPale)
            .padding(horizontal = 17.dp, vertical = 15.dp),
    ) {
        Text(
            text = stringResource(R.string.support_sheet_title),
            style = FlickText.titleMedium.copy(color = colors.onSpark),
        )
        Text(
            text = stringResource(R.string.support_invitation_body),
            style = FlickText.bodySmall.copy(color = colors.onSpark.copy(alpha = 0.82f)),
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = onOpenSupport,
            shapes = buttonShapes,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 12.dp),
        ) {
            Text(stringResource(R.string.support_invitation_open), style = FlickText.titleSmall)
        }
        TextButton(
            onClick = onDismiss,
            shapes = buttonShapes,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSpark),
            modifier = Modifier
                .align(Alignment.Start)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.support_invitation_dismiss), style = FlickText.labelLarge)
        }
    }
}

@Composable
private fun SupportOptionRow(
    option: SupportOption,
    onOpenCheckout: (String) -> Unit,
) {
    val colors = LocalFlickColors.current
    val suggested = option.amountDollars == 8
    val interaction = remember { MutableInteractionSource() }
    val amount = stringResource(amountOf(option))
    val label = stringResource(labelOf(option))
    val spoken = if (suggested) {
        "$amount $label, ${stringResource(R.string.support_suggested)}"
    } else {
        "$amount $label"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.qualityCard))
            .background(if (suggested) colors.primaryContainer else colors.fillCard)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                role = Role.Button,
                onClick = { onOpenCheckout(option.checkoutUrl) },
            )
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .heightIn(min = 56.dp)
            .padding(horizontal = 17.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = amount,
            style = FlickText.titleMedium.copy(
                color = if (suggested) colors.onPrimaryContainer else colors.onSurface,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text = label,
            style = FlickText.bodyMedium.copy(color = if (suggested) colors.onPrimaryContainer else colors.onSurface),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (suggested) {
            Text(
                text = stringResource(R.string.support_suggested),
                style = FlickText.labelMedium.copy(color = colors.onPrimary),
                modifier = Modifier
                    .clip(PillMorphShape)
                    .background(colors.primary)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

private fun amountOf(option: SupportOption): Int = when (option.amountDollars) {
    3 -> R.string.support_option_3_amount
    8 -> R.string.support_option_8_amount
    15 -> R.string.support_option_15_amount
    else -> error("SupportCatalog must contain only configured fixed tip amounts")
}

private fun labelOf(option: SupportOption): Int = when (option.amountDollars) {
    3 -> R.string.support_option_3_label
    8 -> R.string.support_option_8_label
    15 -> R.string.support_option_15_label
    else -> error("SupportCatalog must contain only configured fixed tip amounts")
}

package com.flick.sender.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.PrimaryShadow
import com.flick.sender.ui.theme.pressScale

/** Full-width brand-blue action: the pairing sheets' Connect/Pair and the empty-state CTA. */
@Composable
fun FlickPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = PillShape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            disabledContainerColor = colors.primaryContainer,
            disabledContentColor = colors.onPrimaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            // Only the enabled pill is lifted; a disabled one must not read as tappable.
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 14.dp,
                        shape = PillShape,
                        clip = false,
                        ambientColor = PrimaryShadow,
                        spotColor = PrimaryShadow,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Text(text, style = FlickText.titleSmall)
    }
}

/** Quiet secondary action — "Cancel", "play on this phone instead". */
@Composable
fun FlickSubtleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        shape = PillShape,
        interactionSource = interaction,
        // labelLarge is 14.5sp bold, the size onSurfaceFaint needs to clear the
        // contrast floor on the pale canvas (design §7).
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceFaint),
        modifier = modifier.heightIn(min = 48.dp).pressScale(interaction),
    ) {
        Text(text, style = FlickText.labelLarge)
    }
}

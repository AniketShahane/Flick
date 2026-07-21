package com.flick.sender.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.ui.theme.LocalFlickColors

/**
 * A dim-scrim bottom sheet used for the pairing code, manual entry, the quality
 * sheet (S10). Tapping the scrim dismisses; taps on the sheet are swallowed.
 */
@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFlickColors.current
    val dismissDescription = stringResource(R.string.a11y_dismiss_sheet)
    val sheetTitle = stringResource(R.string.a11y_sheet)
    val scrimSource = remember { MutableInteractionSource() }
    val sheetSource = remember { MutableInteractionSource() }
    // Pairing and diagnostics sheets own Back while visible: a route launch must first
    // dismiss or cancel the in-flight UI rather than closing the Activity underneath it.
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xA8000000))
            .semantics { contentDescription = dismissDescription }
            .clickable(interactionSource = scrimSource, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(colors.surfaceRaised)
                .clickable(interactionSource = sheetSource, indication = null, onClick = {})
                .semantics {
                    paneTitle = sheetTitle
                    isTraversalGroup = true
                }
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            content = content,
        )
    }
}

/** The little drag handle at the top of a sheet. */
@Composable
fun SheetGrabber() {
    val colors = LocalFlickColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.onSurfaceFaint.copy(alpha = 0.4f))
                .padding(horizontal = 17.dp, vertical = 2.dp),
        )
    }
}

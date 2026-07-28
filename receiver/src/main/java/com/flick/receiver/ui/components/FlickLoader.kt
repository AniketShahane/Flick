package com.flick.receiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.LocalReducedMotion

/**
 * The TV's indeterminate loader — the same Material 3 Expressive shape-morph the
 * phone runs, in Flick's amber.
 *
 * The receiver could not have this until now: the Compose BOM resolves
 * `material3` 1.4.0, which ships `LoadingIndicatorTokens` but no
 * `LoadingIndicator` composable. The module is pinned to the sender's
 * 1.5.0-alpha24 so both apps run one Compose runtime and one loader vocabulary.
 *
 * Colours are always passed explicitly rather than taken from
 * `LoadingIndicatorDefaults`: those read `MaterialTheme` from `compose.material3`,
 * and this app's theme is tv-material3's, which does not populate it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlickLoader(
    modifier: Modifier = Modifier,
    size: Dp = FlickLoaderDefaults.Size,
    color: Color = FlickColor.Spark,
) {
    val shapes = rememberFlickLoaderShapes()
    if (LocalReducedMotion.current) {
        // A morph has no end state, so there is nothing to snap to: reduced motion
        // gets the first silhouette held still. The copy beside it is what says
        // work is in flight.
        Box(modifier.size(size).clip(shapes.first().toShape()).background(color))
    } else {
        LoadingIndicator(modifier = modifier.size(size), color = color, polygons = shapes)
    }
}

/**
 * The morph sequence, hoisted so the list and its polygons are built once per
 * call site rather than per recomposition — every one of these sits over a live
 * decoder surface.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberFlickLoaderShapes(): List<RoundedPolygon> = androidx.compose.runtime.remember {
    listOf(
        MaterialShapes.Circle,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Pill,
    )
}

object FlickLoaderDefaults {
    /**
     * Sized for a 10-foot read, not a phone's. The TV composes at 960 × 540 dp, so
     * a loader carrying the phone's 48 dp default would subtend a quarter of what
     * it does in the hand. This is the ring size §5.2 already specified, kept.
     */
    val Size: Dp = 56.dp
}

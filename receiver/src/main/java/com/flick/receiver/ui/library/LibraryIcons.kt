package com.flick.receiver.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The library's extra glyphs, on the [com.flick.receiver.ui.theme.FlickIcons] grid: 24 units,
 * 1.8-unit round stroke, drawn white and tinted at the call site.
 */
internal object LibraryIcons {

    private fun stroked(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Search: ImageVector = stroked("M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0 -13z M15.5 15.5L21 21")

    val Back: ImageVector = stroked("M14.5 5.5L8 12l6.5 6.5")

    val Folder: ImageVector = stroked("M3.5 7a1.5 1.5 0 0 1 1.5 -1.5h4.2l2 2.2H19a1.5 1.5 0 0 1 1.5 1.5v8.3a1.5 1.5 0 0 1 -1.5 1.5H5a1.5 1.5 0 0 1 -1.5 -1.5z")

    val Sort: ImageVector = stroked("M4.5 7h15 M7 12h10 M9.5 17h5")

    val Check: ImageVector = stroked("M5.5 12.5l4.2 4.2L18.5 7.8")

    val Phone: ImageVector = stroked("M8.5 3h7a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-7A1.5 1.5 0 0 1 7 19.5v-15A1.5 1.5 0 0 1 8.5 3z M11 18h2")
}

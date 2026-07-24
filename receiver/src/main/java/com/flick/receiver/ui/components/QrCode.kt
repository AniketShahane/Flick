package com.flick.receiver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Side of the centre plate as a fraction of the code — ~4.8 % of the area. */
private const val OverlayPlateFraction = 0.22f

/** The mark inside the plate, leaving a white ring around it. */
private const val OverlayMarkFraction = 0.62f

/** A QR finder pattern is a fixed 7 × 7 block at three of the four corners. */
private const val FinderModules = 7

/**
 * Renders [payload] as a QR code drawn directly onto a Compose Canvas — no
 * Bitmap, no camera. ZXing produces the module matrix at natural size (0×0 asks
 * for the minimal grid); each dark module becomes one crisp cell scaled to the
 * requested [size]. On a rare encode failure the panel simply renders blank
 * (the pairing code remains the fallback path).
 *
 * The three finder patterns are repainted over the matrix so the two upper eyes
 * read brand blue and the lower-left eye amber (receiver-expressive-spec.md
 * §5.1). Repainting only changes module COLOUR, never the module grid, so the
 * payload the phone decodes is byte-identical.
 *
 * [centerOverlay] receives the mark size this component reserves for it. Error
 * correction stays at `M`: the plate covers under 5 % of the symbol area,
 * comfortably inside the ~15 % that level can lose, and the quiet zone is kept.
 */
@Composable
fun QrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    quietZonePadding: Dp = 12.dp,
    contentDescription: String? = null,
    moduleColor: Color = FlickColor.OnLight,
    backgroundColor: Color = Color.White,
    shape: Shape = FlickShape.Hero,
    centerOverlay: (@Composable (markSize: Dp) -> Unit)? = null,
) {
    val matrix: BitMatrix? = remember(payload) {
        runCatching {
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                ),
            )
        }.getOrNull()
    }

    val codeSide = (size - quietZonePadding * 2).coerceAtLeast(0.dp)
    val plateSide = codeSide * OverlayPlateFraction
    val markSize = plateSide * OverlayMarkFraction

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clip(shape)
            .background(backgroundColor)
            .padding(quietZonePadding),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val m = matrix ?: return@Canvas
            val n = m.width
            if (n <= 0) return@Canvas
            val cell = this.size.minDimension / n.toFloat()
            // Tiny overscan removes hairline seams between adjacent cells.
            val bleed = 0.6f

            for (y in 0 until m.height) {
                for (x in 0 until n) {
                    if (m.get(x, y)) {
                        drawRect(
                            color = moduleColor,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell + bleed, cell + bleed),
                        )
                    }
                }
            }

            // The symbol's bounding box is exactly the module grid: the three
            // finder eyes pin its corners, so this locates them without assuming
            // a particular quiet-zone width.
            val bounds: IntArray? = m.enclosingRectangle
            if (bounds != null && bounds.size >= 4 &&
                bounds[2] >= FinderModules && bounds[3] >= FinderModules
            ) {
                val left = bounds[0]
                val top = bounds[1]
                val eyes = listOf(
                    Triple(left, top, FlickColor.Primary),
                    Triple(left + bounds[2] - FinderModules, top, FlickColor.Primary),
                    Triple(left, top + bounds[3] - FinderModules, FlickColor.Spark),
                )
                for ((ex, ey, innerColor) in eyes) {
                    drawRect(
                        color = moduleColor,
                        topLeft = Offset(ex * cell, ey * cell),
                        size = Size(FinderModules * cell + bleed, FinderModules * cell + bleed),
                    )
                    drawRect(
                        color = backgroundColor,
                        topLeft = Offset((ex + 1) * cell, (ey + 1) * cell),
                        size = Size(5 * cell, 5 * cell),
                    )
                    drawRect(
                        color = innerColor,
                        topLeft = Offset((ex + 2) * cell, (ey + 2) * cell),
                        size = Size(3 * cell, 3 * cell),
                    )
                }
            }
        }

        if (centerOverlay != null && plateSide > 0.dp) {
            Box(
                modifier = Modifier
                    .size(plateSide)
                    .clip(RoundedCornerShape(16.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.Center,
            ) {
                centerOverlay.invoke(markSize)
            }
        }
    }
}

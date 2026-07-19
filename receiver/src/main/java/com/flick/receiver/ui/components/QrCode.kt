package com.flick.receiver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flick.receiver.ui.theme.FlickColor
import com.flick.receiver.ui.theme.FlickShape
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [payload] as a QR code drawn directly onto a Compose [Canvas] — no
 * Bitmap, no camera. ZXing produces the module matrix at natural size (0×0 asks
 * for the minimal grid); each dark module becomes one crisp cell scaled to the
 * requested [size]. On a rare encode failure the panel simply renders blank
 * (the 4-digit code remains the fallback pairing path).
 */
@Composable
fun QrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    quietZonePadding: Dp = 12.dp,
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

    Canvas(
        modifier = modifier
            .size(size)
            .clip(FlickShape.Md)
            .background(Color.White)
            .padding(quietZonePadding),
    ) {
        val m = matrix ?: return@Canvas
        val n = m.width
        if (n <= 0) return@Canvas
        val cell = this.size.minDimension / n.toFloat()
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m.get(x, y)) {
                    drawRect(
                        color = FlickColor.Canvas,
                        topLeft = Offset(x * cell, y * cell),
                        // Tiny overscan removes hairline seams between cells.
                        size = Size(cell + 0.6f, cell + 0.6f),
                    )
                }
            }
        }
    }
}

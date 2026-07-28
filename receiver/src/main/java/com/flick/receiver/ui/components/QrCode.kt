package com.flick.receiver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

/** Tiny overscan, removing hairline seams between adjacent cells. */
private const val ModuleBleedPx = 0.6f

/**
 * The ink all three finder eyes are drawn in. Brand navy, and named here rather
 * than spelled at the parameter default so the decodability guard below and its
 * tests bind to the colour the renderer actually uses.
 */
internal val QrEyeInk: Color = FlickColor.Primary

/** Top-left module of one finder pattern, in matrix coordinates. */
internal data class QrFinder(val x: Int, val y: Int)

/**
 * The three finder patterns, located from the symbol's own bounding box
 * ([BitMatrix.getEnclosingRectangle] — x, y, width, height): the eyes pin three of
 * its corners, so this finds them without assuming a particular quiet-zone width.
 * Anything that is not a plausible symbol yields no eyes, and the matrix is then
 * drawn unrepainted rather than corrupted.
 */
internal fun qrFinderOrigins(bounds: IntArray?): List<QrFinder> {
    if (bounds == null || bounds.size < 4) return emptyList()
    val left = bounds[0]
    val top = bounds[1]
    val width = bounds[2]
    val height = bounds[3]
    if (width < FinderModules || height < FinderModules) return emptyList()
    return listOf(
        QrFinder(left, top),
        QrFinder(left + width - FinderModules, top),
        QrFinder(left, top + height - FinderModules),
    )
}

/**
 * BT.601 luma — the channel a scanner's binarizer actually thresholds, and the
 * reason every finder eye now takes ONE dark ink.
 *
 * A binarizer classifies a module by comparing this against a threshold roughly
 * midway between the local light and dark extremes. Amber `#FFB61E` lands at
 * ~0.73 against a 1.0 white plate, i.e. above that midpoint, so an amber eye core
 * binarized as WHITE: the finder pattern's mandatory 1:1:3:1:1 dark/light run was
 * destroyed, only two of the three patterns were findable, and no standard decoder
 * — system camera, Lens, ZXing — could read the symbol at all.
 */
internal fun qrLuma(color: Color): Float =
    0.299f * color.red + 0.587f * color.green + 0.114f * color.blue

/**
 * The luma a binarizer splits dark from light at: roughly midway between the plate
 * and the symbol's own ink. Every module a decoder has to read as dark must sit
 * below it.
 */
internal fun qrBinarizerThreshold(plateColor: Color, moduleColor: Color): Float =
    (qrLuma(plateColor) + qrLuma(moduleColor)) / 2f

/**
 * The ink a finder eye may actually take — [requested] only while it binarizes
 * dark, and the data modules' own [moduleColor] otherwise.
 *
 * The guard lives in the renderer rather than with whoever picks the colour: an
 * eye tinted for the brand instead of for a scanner looks right on every screen it
 * is designed on and is unreadable on every camera it meets, which is exactly how
 * this symbol shipped. Refused rather than thrown for the same reason — a TV that
 * cannot draw its pairing code at all is worse than one that draws it a shade too
 * dark.
 */
internal fun qrEyeInk(requested: Color, moduleColor: Color, plateColor: Color): Color =
    if (qrLuma(requested) < qrBinarizerThreshold(plateColor, moduleColor)) {
        requested
    } else {
        moduleColor
    }

/**
 * Renders [payload] as a QR code — no camera, no file. ZXing produces the module
 * matrix at natural size (0×0 asks for the minimal grid); each dark module becomes
 * one crisp cell scaled to the requested [size]. On a rare encode failure the panel
 * simply renders blank (the pairing code remains the fallback path).
 *
 * The grid is rasterised ONCE per payload and pixel size rather than re-issued as
 * a thousand-odd `drawRect` calls every time the surrounding screen invalidates its
 * draw. The module geometry is a pure function of the payload, so the only thing
 * a frame has to do is blit it.
 *
 * All three finder patterns take [eyeColor], and an [eyeColor] that would not
 * binarize dark is refused in favour of [moduleColor] — see [qrEyeInk]. Repainting
 * them only changes module COLOUR, never the module grid, so the payload the phone
 * decodes stays byte-identical. The mark in the centre plate is where the amber
 * accent lives instead; it sits inside the area the error correction already
 * covers, so it cannot affect decoding.
 *
 * [centerOverlay] receives the mark size this component reserves for it. Error
 * correction stays at `M`: the plate covers under 5 % of the symbol area,
 * comfortably inside the ~15 % that level can lose, and the quiet zone is kept.
 *
 * [quietZonePadding] is an absolute inset and deliberately did NOT shrink with
 * [size] in the TV re-scale. Held constant while the card shrinks it buys *more*
 * modules of quiet zone, never fewer, so a smaller card cannot make the symbol
 * harder to decode.
 */
@Composable
fun QrCode(
    payload: String,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    quietZonePadding: Dp = 12.dp,
    contentDescription: String? = null,
    moduleColor: Color = FlickColor.OnLight,
    backgroundColor: Color = Color.White,
    eyeColor: Color = QrEyeInk,
    shape: Shape = FlickShape.Hero,
    centerOverlay: (@Composable (markSize: Dp) -> Unit)? = null,
) {
    val codeSide = (size - quietZonePadding * 2).coerceAtLeast(0.dp)
    val plateSide = codeSide * OverlayPlateFraction
    val markSize = plateSide * OverlayMarkFraction
    // Rasterised against the layout's own pixel side rather than the draw size, so
    // the symbol survives every recomposition the surrounding column runs (the bind
    // uptime line under the card ticks twice a second). Rounded the way the padding
    // modifier rounds, so the raster is the content box rather than a pixel off it.
    val codeSidePx = with(LocalDensity.current) {
        (size.roundToPx() - quietZonePadding.roundToPx() * 2).coerceAtLeast(0)
    }
    val symbol = remember(payload, codeSidePx, moduleColor, backgroundColor, eyeColor) {
        renderQrSymbol(payload, codeSidePx, moduleColor, backgroundColor, eyeColor)
    }

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
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { symbol?.let { drawImage(it) } },
        )

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

/**
 * The symbol as pixels. Left transparent where the code is light: the card behind
 * it already paints the white plate, so filling it again would be a second
 * full-plate blend for no visible difference.
 */
private fun renderQrSymbol(
    payload: String,
    sidePx: Int,
    moduleColor: Color,
    backgroundColor: Color,
    eyeColor: Color,
): ImageBitmap? {
    if (sidePx <= 0) return null
    val matrix = runCatching {
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
    }.getOrNull() ?: return null
    if (matrix.width <= 0 || matrix.height <= 0) return null

    val target = ImageBitmap(sidePx, sidePx)
    // Everything below is in raw pixels, so the density carries no meaning here.
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(target),
        size = Size(sidePx.toFloat(), sidePx.toFloat()),
    ) {
        drawQrModules(
            matrix = matrix,
            sidePx = sidePx.toFloat(),
            moduleColor = moduleColor,
            backgroundColor = backgroundColor,
            eyeColor = qrEyeInk(eyeColor, moduleColor, backgroundColor),
        )
    }
    return target
}

private fun DrawScope.drawQrModules(
    matrix: BitMatrix,
    sidePx: Float,
    moduleColor: Color,
    backgroundColor: Color,
    eyeColor: Color,
) {
    val n = matrix.width
    val cell = sidePx / n.toFloat()

    for (y in 0 until matrix.height) {
        for (x in 0 until n) {
            if (matrix.get(x, y)) {
                drawRect(
                    color = moduleColor,
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell + ModuleBleedPx, cell + ModuleBleedPx),
                )
            }
        }
    }

    for (eye in qrFinderOrigins(matrix.enclosingRectangle)) {
        drawRect(
            color = moduleColor,
            topLeft = Offset(eye.x * cell, eye.y * cell),
            size = Size(FinderModules * cell + ModuleBleedPx, FinderModules * cell + ModuleBleedPx),
        )
        drawRect(
            color = backgroundColor,
            topLeft = Offset((eye.x + 1) * cell, (eye.y + 1) * cell),
            size = Size(5 * cell, 5 * cell),
        )
        drawRect(
            color = eyeColor,
            topLeft = Offset((eye.x + 2) * cell, (eye.y + 2) * cell),
            size = Size(3 * cell, 3 * cell),
        )
    }
}

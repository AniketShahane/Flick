package com.flick.sender.ui.components

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A poster's own colours, read once from a small thumbnail of it: the hue most of it leans
 * to, its most vivid usable colour, and its darkest and lightest tones. Pure, so the read is
 * a JVM test; [IntertitleColors.stainedGlass] turns it into the synopsis intertitle's inks.
 */
internal data class PosterPalette(
    /** The dominant hue, degrees: the centre of the heaviest 15° bin. */
    val hue: Float,
    /** The most vivid mid-tone's hue and saturation (never under 0.55). */
    val vibrantHue: Float,
    val vibrantSaturation: Float,
    /** Mean of the darkest quarter of pixels and of the lightest tenth, as 0..1 sRGB. */
    val dark: Rgb,
    val light: Rgb,
) {
    data class Rgb(val r: Float, val g: Float, val b: Float)

    companion object {
        /** The read is sized for a thumbnail this big; any size works. */
        const val SAMPLE_WIDTH = 40
        const val SAMPLE_HEIGHT = 60

        private const val BIN_DEGREES = 15f
        private const val BINS = 24

        /** Packed ARGB [pixels], alpha ignored. Empty input reads as black. */
        fun of(pixels: IntArray): PosterPalette {
            if (pixels.isEmpty()) {
                val black = Rgb(0f, 0f, 0f)
                return PosterPalette(BIN_DEGREES / 2f, BIN_DEGREES / 2f, 0.5f, black, black)
            }
            val n = pixels.size
            val r = FloatArray(n)
            val g = FloatArray(n)
            val b = FloatArray(n)
            val h = FloatArray(n)
            val s = FloatArray(n)
            val l = FloatArray(n)
            for (i in 0 until n) {
                val p = pixels[i]
                val rr = ((p shr 16) and 0xFF) / 255f
                val gg = ((p shr 8) and 0xFF) / 255f
                val bb = (p and 0xFF) / 255f
                val mx = max(rr, max(gg, bb))
                val mn = min(rr, min(gg, bb))
                val light = (mx + mn) / 2f
                val chroma = mx - mn
                var hue = 0f
                if (chroma != 0f) {
                    hue = when (mx) {
                        rr -> ((gg - bb) / chroma) % 6f
                        gg -> (bb - rr) / chroma + 2f
                        else -> (rr - gg) / chroma + 4f
                    }
                    hue = (hue * 60f + 360f) % 360f
                }
                r[i] = rr
                g[i] = gg
                b[i] = bb
                h[i] = hue
                s[i] = if (chroma != 0f) chroma / (1f - abs(2f * light - 1f)) else 0f
                l[i] = light
            }

            val bins = FloatArray(BINS)
            for (i in 0 until n) {
                if (s[i] > 0.15f && l[i] > 0.12f && l[i] < 0.9f) {
                    val bin = floor(h[i] / BIN_DEGREES).toInt().coerceIn(0, BINS - 1)
                    bins[bin] += s[i] * (1f - abs(l[i] - 0.5f))
                }
            }
            var heaviest = 0
            for (i in 1 until BINS) if (bins[i] > bins[heaviest]) heaviest = i
            val hue = heaviest * BIN_DEGREES + BIN_DEGREES / 2f

            var vibrant = -1
            var best = -1f
            for (i in 0 until n) {
                if (l[i] < 0.25f || l[i] > 0.82f) continue
                val score = s[i] * (1f - abs(l[i] - 0.55f) * 1.6f)
                if (score > best) {
                    best = score
                    vibrant = i
                }
            }

            // Stable, as the read this was tuned on: equal lightness keeps pixel order.
            val byLight = (0 until n).sortedBy { l[it] }
            val darkCount = n / 4
            val lightCount = n / 10
            fun mean(indices: List<Int>): Rgb {
                if (indices.isEmpty()) return Rgb(0f, 0f, 0f)
                var sr = 0f
                var sg = 0f
                var sb = 0f
                for (i in indices) {
                    sr += r[i]
                    sg += g[i]
                    sb += b[i]
                }
                val k = indices.size.toFloat()
                return Rgb(sr / k, sg / k, sb / k)
            }
            return PosterPalette(
                hue = hue,
                vibrantHue = if (vibrant >= 0) h[vibrant] else hue,
                vibrantSaturation = if (vibrant >= 0) max(0.55f, s[vibrant]) else 0.5f,
                dark = mean(byLight.subList(0, darkCount)),
                light = mean(byLight.subList(n - lightCount, n)),
            )
        }

        /**
         * CSS `hsla()` as the prototype wrote it — hue to a whole degree, saturation and
         * lightness to a whole percent — packed as ARGB.
         */
        fun hsl(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Int {
            val hh = (((hue.roundToInt() % 360) + 360) % 360).toFloat()
            val ss = (saturation * 100f).roundToInt().coerceIn(0, 100) / 100f
            val ll = (lightness * 100f).roundToInt().coerceIn(0, 100) / 100f
            val c = (1f - abs(2f * ll - 1f)) * ss
            val x = c * (1f - abs((hh / 60f) % 2f - 1f))
            val m = ll - c / 2f
            val (r1, g1, b1) = when {
                hh < 60f -> Triple(c, x, 0f)
                hh < 120f -> Triple(x, c, 0f)
                hh < 180f -> Triple(0f, c, x)
                hh < 240f -> Triple(0f, x, c)
                hh < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return argb(alpha, r1 + m, g1 + m, b1 + m)
        }

        /** [rgb] scaled by [k], each channel capped at white, opaque. */
        fun scaled(rgb: Rgb, k: Float): Int = argb(1f, min(1f, rgb.r * k), min(1f, rgb.g * k), min(1f, rgb.b * k))

        fun argb(alpha: Float, r: Float, g: Float, b: Float): Int =
            (channel(alpha) shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

        /**
         * CSS `saturate([amount])` over packed ARGB [pixels], in place: the filter-effects
         * luminance matrix, each channel clamped. Alpha is kept.
         */
        fun saturate(pixels: IntArray, amount: Float) {
            val s = amount
            val rr = 0.213f + 0.787f * s
            val rg = 0.715f - 0.715f * s
            val rb = 0.072f - 0.072f * s
            val gr = 0.213f - 0.213f * s
            val gg = 0.715f + 0.285f * s
            val gb = 0.072f - 0.072f * s
            val br = 0.213f - 0.213f * s
            val bg = 0.715f - 0.715f * s
            val bb = 0.072f + 0.928f * s
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val nr = (rr * r + rg * g + rb * b).roundToInt().coerceIn(0, 255)
                val ng = (gr * r + gg * g + gb * b).roundToInt().coerceIn(0, 255)
                val nb = (br * r + bg * g + bb * b).roundToInt().coerceIn(0, 255)
                pixels[i] = (p and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}

/**
 * The intertitle's inks as packed ARGB: [ground] behind everything, [film] the strip's body,
 * [rule] the card's double frame, [ruleSoft] the outline outside it, [glow] the light around
 * the card, [accent] the year, the ornament and the tagline. The title and the overview are
 * white whatever the poster, so they are not here.
 */
internal data class IntertitleColors(
    val ground: Int,
    val film: Int,
    val rule: Int,
    val ruleSoft: Int,
    val glow: Int,
    val accent: Int,
) {
    companion object {
        /** "Stained glass": the poster's own dark for the ground, its vivid colour for the light. */
        fun stainedGlass(p: PosterPalette): IntertitleColors = IntertitleColors(
            ground = PosterPalette.scaled(p.dark, 0.45f),
            film = PosterPalette.scaled(p.dark, 0.70f),
            rule = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.70f, 0.95f),
            ruleSoft = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.70f, 0.35f),
            glow = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.55f, 0.35f),
            accent = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.80f),
        )

        /**
         * Until a poster has been read: the cinema page's own blue-black and a cool silver,
         * so the first frames of a reveal on a slow decode are quiet rather than wrong.
         */
        val Neutral: IntertitleColors = stainedGlass(
            PosterPalette(
                hue = 222f,
                vibrantHue = 222f,
                vibrantSaturation = 0.3f,
                dark = PosterPalette.Rgb(0.07f, 0.09f, 0.15f),
                light = PosterPalette.Rgb(0.9f, 0.92f, 0.96f),
            ),
        )
    }
}

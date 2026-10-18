package com.flick.sender.ui.components

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A poster's own colours, read once from a small thumbnail of it: its most vivid usable colour
 * and its darkest tone. Pure, so the read is a JVM test; [IntertitleColors.stainedGlass] turns
 * it into the synopsis intertitle's inks.
 */
internal data class PosterPalette(
    /**
     * The most vivid mid-tone's hue and saturation (never under 0.55); with no mid-tone at
     * all, the centre of the heaviest 15° hue bin at 0.5.
     */
    val vibrantHue: Float,
    val vibrantSaturation: Float,
    /** Mean of the darkest quarter of pixels, as 0..1 sRGB. */
    val dark: Rgb,
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
                return PosterPalette(BIN_DEGREES / 2f, 0.5f, Rgb(0f, 0f, 0f))
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
                vibrantHue = if (vibrant >= 0) h[vibrant] else hue,
                vibrantSaturation = if (vibrant >= 0) max(0.55f, s[vibrant]) else 0.5f,
                dark = mean(byLight.subList(0, darkCount)),
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

        /** WCAG relative luminance of packed ARGB, alpha ignored. */
        fun luminance(argb: Int): Float {
            fun lin(c: Int): Float {
                val v = c / 255f
                return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
            }
            return 0.2126f * lin((argb shr 16) and 0xFF) + 0.7152f * lin((argb shr 8) and 0xFF) + 0.0722f * lin(argb and 0xFF)
        }

        fun contrast(a: Int, b: Int): Float {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
        }

        /** The pixel at the 90th percentile of luminance: the bright end, not one stray highlight. */
        fun brightTenth(pixels: IntArray): Int {
            if (pixels.isEmpty()) return 0xFF000000.toInt()
            val sorted = pixels.sortedBy { luminance(it) }
            return sorted[((sorted.size - 1) * 0.9f).roundToInt()]
        }

        /** [a] over [b] at [alpha], channel by channel in sRGB, as the renderer blends. */
        fun over(a: Int, b: Int, alpha: Float): Int {
            fun mix(shift: Int): Float =
                (((a shr shift) and 0xFF) * alpha + ((b shr shift) and 0xFF) * (1f - alpha)) / 255f
            return argb(1f, mix(16), mix(8), mix(0))
        }

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
 * white whatever the poster, so they are not here. [veilTop] and [veilFoot] are the black
 * veil over the card's glass, top to foot.
 */
internal data class IntertitleColors(
    val ground: Int,
    val film: Int,
    val rule: Int,
    val ruleSoft: Int,
    val glow: Int,
    val accent: Int,
    val veilTop: Float = VEIL_TOP,
    val veilFoot: Float = VEIL_FOOT,
) {
    companion object {
        const val VEIL_TOP = 0.45f
        const val VEIL_FOOT = 0.62f
        const val GLASS_ALPHA = 0.75f
        const val ACCENT_LIGHTNESS = 0.80f
        const val BODY_CONTRAST = 7f
        const val ACCENT_CONTRAST = 4.5f
        private const val MAX_VEIL = 0.95f
        private const val MAX_LIGHTNESS = 0.97f
        private const val STEP = 0.01f

        /** The overview's ink, #F5F5F5. */
        const val OVERVIEW_INK: Int = 0xFFF5F5F5.toInt()

        /**
         * "Stained glass": the poster's own dark for the ground, its vivid colour for the light.
         *
         * With [glassBright] (packed ARGB; the brightest tenth of the blurred poster behind the
         * card) the inks are made legible over it: the veil deepened until the overview reads
         * at [BODY_CONTRAST] and the accent lightened until it reads at [ACCENT_CONTRAST], both
         * against the card at its top, where the veil is lightest. A poster dark enough already
         * keeps the theme's own numbers.
         */
        fun stainedGlass(p: PosterPalette, glassBright: Int? = null): IntertitleColors {
            val ground = PosterPalette.scaled(p.dark, 0.45f)
            fun accentAt(lightness: Float) = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, lightness)
            var top = VEIL_TOP
            var lightness = ACCENT_LIGHTNESS
            if (glassBright != null) {
                val glass = PosterPalette.over(glassBright, ground, GLASS_ALPHA)
                fun card(veil: Float) = PosterPalette.over(0xFF000000.toInt(), glass, veil)
                while (top < MAX_VEIL && PosterPalette.contrast(OVERVIEW_INK, card(top)) < BODY_CONTRAST) top += STEP
                val behind = card(top)
                while (lightness < MAX_LIGHTNESS && PosterPalette.contrast(accentAt(lightness), behind) < ACCENT_CONTRAST) {
                    lightness += STEP
                }
            }
            return IntertitleColors(
                ground = ground,
                film = PosterPalette.scaled(p.dark, 0.70f),
                rule = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.70f, 0.95f),
                ruleSoft = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.70f, 0.35f),
                glow = PosterPalette.hsl(p.vibrantHue, p.vibrantSaturation, 0.55f, 0.35f),
                accent = accentAt(lightness),
                veilTop = top,
                veilFoot = max(VEIL_FOOT, top),
            )
        }

        /**
         * Until a poster has been read: the cinema page's own blue-black and a cool silver,
         * so the first frames of a reveal on a slow decode are quiet rather than wrong.
         */
        val Neutral: IntertitleColors = stainedGlass(
            PosterPalette(
                vibrantHue = 222f,
                vibrantSaturation = 0.3f,
                dark = PosterPalette.Rgb(0.07f, 0.09f, 0.15f),
            ),
        )
    }
}

package com.flick.sender.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sender-only semantic roles that Material color roles cannot represent without
 * losing Flick's product meaning. They deliberately describe jobs, not a visual
 * direction: screens may use media and synchronization roles without knowing a
 * hex value.
 */
@Immutable
data class FlickColors(
    val isLight: Boolean,
    // --- surfaces ---
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceRaisedAlt: Color,
    val surfaceTonal: Color,
    val surfaceDisabled: Color,
    val inverseSurface: Color,
    val onInverseSurface: Color,
    val onInverseSurfaceDim: Color,
    val glass: Color,
    val glassBorder: Color,
    // --- ink ---
    val onSurface: Color,
    val onSurfaceDim: Color,
    val onSurfaceFaint: Color,
    // --- outlines ---
    val outline: Color,
    val outlineSoft: Color,
    val outlineHairline: Color,
    // --- action (brand blue) ---
    val primary: Color,
    val onPrimary: Color,
    val onPrimaryMuted: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val primaryFixed: Color,
    val onPrimaryFixed: Color,
    // --- accent (amber spark) ---
    val spark: Color,
    val onSpark: Color,
    val sparkBright: Color,
    val sparkLight: Color,
    val sparkPale: Color,
    val playheadHi: Color,
    val playheadLo: Color,
    // --- status ---
    val link: Color,
    val live: Color,
    val caution: Color,
    val onCaution: Color,
    val trouble: Color,
    val ghost: Color,
    // --- translucent fills, measured against the surface they sit on ---
    val fillCard: Color,
    val fillControl: Color,
    val fillTrack: Color,
    val fillTrackAlt: Color,
    val fillBuffered: Color,
    // --- scrims ---
    val scrim: Color,
    val posterScrim: Color,
)

// Electric blue carries every action; amber carries the media itself.
val Primary = Color(0xFF1240E8)
val PrimaryOnDark = Color(0xFF4A78FF)
val PrimaryContainer = Color(0xFFDCE5FF)
val OnPrimaryContainer = Color(0xFF33477E)
val PrimaryFixed = Color(0xFFB6C8FF)
val OnPrimaryFixed = Color(0xFF0A2A8A)

val Spark = Color(0xFFFFB61E)
val SparkBright = Color(0xFFFFC44D)
val SparkLight = Color(0xFFFFD873)
val SparkPale = Color(0xFFFFEFC6)
val OnSpark = Color(0xFF33240A)
val PlayheadHi = Color(0xFFFFD873)
val PlayheadLo = Color(0xFFF5A100)

val Link = Color(0xFF6FD0FF)
val Ghost = Color(0xFF7FB0FF)
val Caution = Color(0xFFFFA23A)
val OnCaution = Color(0xFF331A00)
val Trouble = Color(0xFFC9314D)
val TroubleOnDark = Color(0xFFFF7A8C)

val Ink = Color(0xFF0A1533)
val InkDim = Color(0xFF4A5A85)
val InkFaint = Color(0xFF6B7BA8)
val CanvasLight = Color(0xFFF2F6FF)

// Cinematic dark stops — the Now-Playing / Connecting backdrops and the sheet
// that has to sit on top of them.
val CinemaTop = Color(0xFF0A1E4A)
val CinemaMid = Color(0xFF08142F)
val CinemaDeep = Color(0xFF050C1D)
val CinemaSheet = Color(0xFF08122B)
val CinemaInk = Color(0xFFEAF0FF)
val CinemaInkDim = Color(0xFFA8B8DC)
val CinemaInkFaint = Color(0xFF8C9CC4)

// Drop shadows are authored as colors because Compose takes them as ambient/spot
// tints rather than as a CSS shadow list.
val PrimaryShadow = Color(0x521240E8)
val FabShadow = Color(0x6BF5A100)
val TileShadow = Color(0x2E0A1533)
val NavShadow = Color(0x260A1533)
val PosterShadow = Color(0x99000000)

val LightFlickColors = FlickColors(
    isLight = true,
    canvas = CanvasLight,
    surface = CanvasLight,
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceRaisedAlt = Color(0xFFE7EEFF),
    surfaceTonal = PrimaryContainer,
    surfaceDisabled = Color(0xFFDDE6F8),
    inverseSurface = Ink,
    onInverseSurface = CanvasLight,
    onInverseSurfaceDim = Color(0xFF9FB0D8),
    glass = Color(0xEBCFE0FF),
    glassBorder = Color(0x8CFFFFFF),
    onSurface = Ink,
    onSurfaceDim = InkDim,
    onSurfaceFaint = InkFaint,
    outline = Color(0xFFCBD8F5),
    outlineSoft = Color(0xFFC3D0EE),
    outlineHairline = Color(0x1A0A1533),
    primary = Primary,
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryMuted = Color(0xFFBFD2FF),
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    primaryFixed = PrimaryFixed,
    onPrimaryFixed = OnPrimaryFixed,
    spark = Spark,
    onSpark = OnSpark,
    sparkBright = SparkBright,
    sparkLight = SparkLight,
    sparkPale = SparkPale,
    playheadHi = PlayheadHi,
    playheadLo = PlayheadLo,
    // Cyan reads at ~1.3:1 on the pale canvas, so the LAN/health jobs borrow the
    // brand blue on light surfaces and only become cyan on the cinematic set.
    link = Primary,
    live = Primary,
    caution = Caution,
    onCaution = OnCaution,
    trouble = Trouble,
    ghost = Ghost,
    fillCard = Color(0x140A1533),
    fillControl = Color(0x1C0A1533),
    fillTrack = Color(0x260A1533),
    fillTrackAlt = Color(0x290A1533),
    fillBuffered = Color(0x420A1533),
    scrim = Color(0x800A1533),
    posterScrim = Color(0x9E000000),
)

val CinematicFlickColors = FlickColors(
    isLight = false,
    canvas = CinemaDeep,
    surface = CinemaMid,
    surfaceRaised = CinemaSheet,
    surfaceRaisedAlt = Color(0xFF0F1F45),
    surfaceTonal = Color(0xFF12275C),
    surfaceDisabled = Color(0xFF142445),
    inverseSurface = CinemaInk,
    onInverseSurface = Ink,
    onInverseSurfaceDim = InkDim,
    glass = Color(0xEB102452),
    glassBorder = Color(0x38FFFFFF),
    onSurface = CinemaInk,
    onSurfaceDim = CinemaInkDim,
    onSurfaceFaint = CinemaInkFaint,
    outline = Color(0x3DEAF0FF),
    outlineSoft = Color(0x42FFFFFF),
    outlineHairline = Color(0x14FFFFFF),
    primary = PrimaryOnDark,
    onPrimary = Color(0xFF041028),
    // The light set's pale-blue telemetry ink would drop under 3:1 on this lighter
    // primary, so the muted role stays the dark ink and only loses weight.
    onPrimaryMuted = Color(0xCC041028),
    primaryContainer = Color(0xFF12275C),
    onPrimaryContainer = Color(0xFFC7D6FF),
    primaryFixed = Primary,
    onPrimaryFixed = Color(0xFFFFFFFF),
    spark = Spark,
    onSpark = OnSpark,
    sparkBright = SparkBright,
    sparkLight = SparkLight,
    sparkPale = SparkPale,
    playheadHi = PlayheadHi,
    playheadLo = PlayheadLo,
    link = Link,
    live = Link,
    caution = Caution,
    onCaution = OnCaution,
    trouble = TroubleOnDark,
    ghost = Ghost,
    fillCard = Color(0x14FFFFFF),
    fillControl = Color(0x1CFFFFFF),
    fillTrack = Color(0x26FFFFFF),
    fillTrackAlt = Color(0x29FFFFFF),
    fillBuffered = Color(0x42FFFFFF),
    scrim = Color(0x990A0406),
    posterScrim = Color(0x9E000000),
)

/** System dark mode lands on the same cinematic set the remote always uses. */
val DarkFlickColors = CinematicFlickColors

object FlickGradients {
    /** 120° HDR/Dolby-Vision badge sheen. */
    val premiumSheen: Brush = angledGradient(120f, 0f to SparkPale, 1f to SparkBright)

    /** 172° Now-Playing backdrop. */
    val nowPlayingBackdrop: Brush =
        angledGradient(172f, 0f to CinemaTop, 0.52f to CinemaMid, 1f to CinemaDeep)

    /** 170° Connecting-overlay backdrop. */
    val connectingBackdrop: Brush = angledGradient(170f, 0f to CinemaTop, 1f to CinemaDeep)

    /** 90° played-fill of the scrub bar. */
    val playhead: Brush = angledGradient(90f, 0f to PlayheadHi, 1f to PlayheadLo)

    /** 150° play/pause FAB fill. */
    val fab: Brush = angledGradient(150f, 0f to PlayheadHi, 1f to PlayheadLo)

    /** 180° poster scrim — transparent until 42%, then down to 62% black. */
    val posterScrim: Brush = angledGradient(
        180f,
        0f to Color.Transparent,
        0.42f to Color.Transparent,
        1f to Color(0x9E000000),
    )

    /** 195° scrim for the taller Now-Playing poster. */
    val nowPosterScrim: Brush = angledGradient(
        195f,
        0f to Color(0x0A000000),
        0.38f to Color(0x0A000000),
        1f to Color(0x9E000000),
    )

    /** 168° sheen laid over the nav-bar glass fill. */
    val navSheen: Brush = angledGradient(
        168f,
        0f to Color(0x99FFFFFF),
        0.44f to Color(0x14FFFFFF),
        0.62f to Color(0x00FFFFFF),
        1f to Color(0x2996B4FF),
    )

    /** The amber pill that runs along the connecting hairline. */
    val travelLight: Brush = Brush.horizontalGradient(
        listOf(Color.Transparent, SparkBright, Color.Transparent),
    )

    /**
     * Ambient amber glow behind Now Playing. Drawn as an ellipse inscribed in the
     * box it is given, matching the mock's `closest-side` radial.
     */
    val ambientGlow: Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val rx = (size.width / 2f).coerceAtLeast(0.01f)
            val ry = (size.height / 2f).coerceAtLeast(0.01f)
            return RadialGradientShader(
                center = Offset(rx, ry),
                radius = rx,
                colors = listOf(Color(0x57FFB61E), Color.Transparent),
            ).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, ry / rx, rx, ry) })
            }
        }
    }

    /**
     * Shimmer sweep for the "SYNCING…" chip. [startX] and [spanPx] are supplied by
     * the caller's animation so the brush itself stays stateless.
     */
    fun syncShimmer(startX: Float, spanPx: Float): Brush = Brush.linearGradient(
        colors = listOf(Color(0x007FB0FF), Color(0x737FB0FF), Color(0x007FB0FF)),
        start = Offset(startX, 0f),
        end = Offset(startX + spanPx, 0f),
    )
}

/**
 * CSS-style angled linear gradient: 0° points up and the angle grows clockwise.
 * Compose only offers explicit endpoints, so the gradient line is re-derived from
 * the drawn size on every shader creation.
 */
private fun angledGradient(degrees: Float, vararg stops: Pair<Float, Color>): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val radians = Math.toRadians(degrees.toDouble())
            val dx = sin(radians).toFloat()
            val dy = -cos(radians).toFloat()
            val length = abs(size.width * dx) + abs(size.height * dy)
            val cx = size.width / 2f
            val cy = size.height / 2f
            return LinearGradientShader(
                from = Offset(cx - dx * length / 2f, cy - dy * length / 2f),
                to = Offset(cx + dx * length / 2f, cy + dy * length / 2f),
                colors = stops.map { it.second },
                colorStops = stops.map { it.first },
            )
        }
    }

val LocalFlickColors = staticCompositionLocalOf { LightFlickColors }

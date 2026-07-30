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

// Dark-mode surfaces, which are NOT the cinematic ones. The stops above are a backdrop:
// they are the bottom of a navy gradient a poster is laid on, and they are that saturated
// and that dark because a still has to be the brightest thing in the frame. A settings
// list is not a poster, and reusing them app-wide gave dark mode two faults light mode
// does not have. Both are visible in the numbers.
//
// Elevation ran BACKWARDS: the sheet stop is darker than the surface stop it is raised
// over (1.017:1, inverted), and the canvas-to-sheet step was 1.051:1 against light mode's
// 1.082:1 — on a canvas where a dark drop shadow renders nothing at all, so a card had
// neither a tonal step nor a shadow to separate it. These step 1.141:1 the right way,
// wider than light because they have to carry the separation alone.
//
// And the hue ran hot: the cinematic stops sit at ~80% chroma relative to their own
// brightness, where the light canvas sits at 5% — light mode is a near-neutral with a
// breath of brand in it, and dark mode was drowning in the brand. These hold one hue
// (225°) at half that chroma, so dark reads as the same product rather than a navy one.
val NightCanvas = Color(0xFF0C0F18)
val NightRaised = Color(0xFF171D2E)
val NightRaisedAlt = Color(0xFF212A3C)
val NightDisabled = Color(0xFF191F2B)
val NightInk = Color(0xFFE4EAF6)
val NightInkDim = Color(0xFFA5B0C6)
val NightInkFaint = Color(0xFF909BB2)

// The action blue for the dark set, lighter than the cinematic one. #4A78FF holds 4.33:1
// on the sheet stop and 3.45:1 on a filled card, which is under the floor for the label
// of a text button; this holds 4.64:1 on the worst surface the set paints, and the
// near-black ink it carries when it is a fill holds 6.6:1 on it.
val PrimaryOnNight = Color(0xFF6E93FF)
val OnPrimaryOnNight = Color(0xFF0A1020)

// Deep enough to read as a tinted CONTROL rather than as one more dark box: 1.348:1 above
// the sheet it sits on, which is the step light mode's #DCE5FF has over white (1.258:1).
val PrimaryContainerOnNight = Color(0xFF1D306B)
val OnPrimaryContainerOnNight = Color(0xFFC7D6FF)

// Drop shadows are authored as colors because Compose takes them as ambient/spot
// tints rather than as a CSS shadow list.
val PrimaryShadow = Color(0x521240E8)
val FabShadow = Color(0x6BF5A100)
val TileShadow = Color(0x2E0A1533)
val NavShadow = Color(0x260A1533)
val PosterShadow = Color(0x99000000)

/**
 * The floating nav pill's shadow on the dark set. A tinted ink shadow is a way of making
 * a pale canvas darker, and there is nothing darker than a near-black canvas to make it —
 * [NavShadow] renders as nothing there, which is what left the pill sitting flat on the
 * page. Black at 55% still darkens this canvas, so the one piece of chrome that has to
 * float is the one thing given a real shadow in dark.
 */
val NavShadowOnNight = Color(0x8C000000)

/**
 * The closing stop of [FlickGradients.navSheenDark], and [NavSheenFootStart] is where it is
 * allowed to begin. Both are named because this stop is not decoration the ink can ignore:
 * the gradient runs DOWN the pill, so a wash at its end lands where the tab labels are.
 *
 * Measured on a device, with the stop interpolating from 62%, a label's local background came
 * out `#38456B` against the bare glass's `#313D61` — `onSurfaceDim` at 4.31:1 where the glass
 * alone gives 4.88:1. Under the 4.5 floor for text, on the role in the bar with the least
 * headroom to begin with.
 *
 * Weakening the stop was the obvious answer and the wrong one: it would have cost the cool
 * edge the pill is meant to have and still only reached 4.47:1 over a bright still. The bar's
 * bottom 14% is row padding with nothing drawn in it, so the fix is to hold the wash off the
 * text entirely rather than to dilute it — full strength, below the labels, where the glass
 * meets its own rim. Ink then sits on bare glass: 4.88:1 on the page and 4.54:1 with a
 * blown-out still behind it, both clear.
 *
 * Worth keeping next to the values: this shipped because FlickColorsTest measured ink against
 * the glass and the glass alone, asserting a background the app never draws. A sheen laid over
 * a surface is part of that surface, and the test now says so.
 */
val NavSheenDarkFoot = Color(0x1F96B4FF)

/** See [NavSheenDarkFoot]: below this the pill is padding, so a wash there touches no ink. */
const val NavSheenFootStart = 0.86f

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

/**
 * A dark resolution — chosen outright in Settings, or inherited from the platform while
 * the preference is Match system.
 *
 * A set of its own, and NOT the cinematic one it used to be an alias for. The two answer
 * different questions: the cinematic set is what a poster is laid on and is tuned to be
 * the darkest, most saturated thing on the screen, and this is what a library grid, a
 * settings list and a folder chooser are laid on. Aliasing them meant every card in the
 * app was drawn in a palette designed to disappear behind artwork — see the stops above
 * for the two measurements that made that concrete.
 *
 * Nothing here is shared BY REFERENCE with the cinematic set beyond the brand accents,
 * which are the same colours in both because they are the brand: amber for the media,
 * cyan for the LAN, and the caution/trouble pair. The surfaces, the ink and the action
 * blue are this set's own, so tuning one theme can never move the other.
 */
val DarkFlickColors = FlickColors(
    isLight = false,
    canvas = NightCanvas,
    // One value for both, as in the light set: the canvas IS the surface, and every step
    // above it is an explicit raise. A separate, slightly different base was the thing
    // that let the sheet stop end up below it.
    surface = NightCanvas,
    surfaceRaised = NightRaised,
    surfaceRaisedAlt = NightRaisedAlt,
    surfaceTonal = PrimaryContainerOnNight,
    surfaceDisabled = NightDisabled,
    inverseSurface = NightInk,
    onInverseSurface = NightCanvas,
    onInverseSurfaceDim = InkDim,
    // The floating chrome — the nav pill and the Now-Playing dock — and the one role that
    // stayed a grey when the rest of this set became a blue. It carried 28 channel steps
    // of colour against the light glass's 48, so the piece of chrome that floats over
    // everything was the piece that dropped the brand. This holds the set's 225° anchor at
    // a spread of 48 — the same amount of blue light mode carries, at this lightness — and
    // separates 1.35:1 from surfaceRaisedAlt where it used to manage 1.10:1, which is a
    // pill sitting flat on the page rather than floating over it.
    //
    // 98% opaque rather than 94%: this is drawn over a scrolling poster grid, and the 6%
    // that showed through moved the inactive nav label across a full stop — 5.98:1 on the
    // page, 4.84:1 once a blown-out still was behind it. A surface that carries controls
    // cannot have a contrast figure that depends on which poster is under it. Nothing is
    // given up, because the frosted read was never the backdrop: Compose cannot sample
    // one, so it is the sheen, the hairline and the shadow doing that work (see
    // [flickGlass]) and all three are untouched.
    glass = Color(0xFA323E62),
    // Held at 18%: the rim steps 1.71:1 off the new fill against 1.77:1 off the old one,
    // so the hairline reads as it did without being retuned for it.
    glassBorder = Color(0x2EFFFFFF),
    onSurface = NightInk,
    onSurfaceDim = NightInkDim,
    onSurfaceFaint = NightInkFaint,
    // Softened with the surfaces they are drawn on: the old pair sat at 24% and 26% white
    // over a near-black canvas, which is a hairline brighter than the card it borders.
    outline = Color(0x33DCE6FF),
    outlineSoft = Color(0x3DE8EEFF),
    // 10%, matching the light set's own hairline rather than the cinematic 8%: it is
    // drawn on the raised surfaces, which are no longer nearly black.
    outlineHairline = Color(0x1AFFFFFF),
    primary = PrimaryOnNight,
    onPrimary = OnPrimaryOnNight,
    // The light set's pale-blue telemetry ink would drop under 3:1 on this lighter
    // primary, so the muted role stays the dark ink and only loses weight.
    onPrimaryMuted = Color(0xCC0A1020),
    primaryContainer = PrimaryContainerOnNight,
    onPrimaryContainer = OnPrimaryContainerOnNight,
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
    // Heavier than the light set's 50%. A pale canvas under a sheet is dimmed until it
    // reads as behind; a canvas that is already near-black has to be taken almost to
    // nothing before the sheet raised over it looks raised rather than adjacent.
    scrim = Color(0xA6060911),
    posterScrim = Color(0x9E000000),
)

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

    /** 168° sheen laid over the nav-bar glass fill on a light surface. */
    val navSheen: Brush = angledGradient(
        168f,
        0f to Color(0x99FFFFFF),
        0.44f to Color(0x14FFFFFF),
        0.62f to Color(0x00FFFFFF),
        1f to Color(0x2996B4FF),
    )

    /**
     * The same sheen for a dark glass, at a fraction of the weight.
     *
     * 60% white is a gloss on a pale blue fill and a blown highlight on a dark one — the
     * pill's top edge came out nearly white while everything around it was near-black,
     * which is the brightest contrast on the screen landing on a decoration. Glass is lit
     * by what is behind it, and behind this one there is almost nothing.
     */
    val navSheenDark: Brush = angledGradient(
        168f,
        0f to Color(0x2EFFFFFF),
        0.44f to Color(0x0AFFFFFF),
        0.62f to Color(0x00FFFFFF),
        // Transparent all the way to the rim, so the closing wash never reaches the labels
        // sitting at ~73%. See [NavSheenDarkFoot] for the measurement that put it here.
        NavSheenFootStart to Color(0x00FFFFFF),
        1f to NavSheenDarkFoot,
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

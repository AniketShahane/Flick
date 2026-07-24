package com.flick.receiver.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The canonical Flick colour tokens (receiver-expressive-spec.md §2). The TV is
 * ALWAYS "the cinema" — fixed cinematic dark; it never re-tints from artwork, so
 * only the dark set lives here. Values match the design system hex exactly.
 *
 * **The role split flipped with the Expressive redesign, and the flip is
 * load-bearing:**
 * - Amber [Spark] `#FFB61E` carries **focus, transport and the playhead** — it is
 *   the one thing the eye tracks while the film is running. Every focus ring,
 *   the play button, the played portion of the scrub bar and the pairing code
 *   are amber, and nothing else is.
 * - Blue [Primary] / [PrimaryOnDark] carries **brand and the ambient field** —
 *   the mark, the glass tint on chrome, the pairing wash, the QR finder eyes. It
 *   never signals focus.
 *
 * (The previous system had this the other way round — cool cyan for focus, warm
 * coral for action. Nothing in the tree may still assume that.)
 */
object FlickColor {

    // ── 2a. Surfaces ────────────────────────────────────────────────────────

    /** App root / idle bed — blue-black. Never pure #000. */
    val Canvas = Color(0xFF04070F)

    /** Behind the film: one stop darker than [Canvas] so HDR peaks read. */
    val CanvasPlayback = Color(0xFF02040A)

    /** Pairing bed — carries the two ambient radial washes. */
    val CanvasPair = Color(0xFF060C1E)

    /** Raised card fill, design `rgba(9,17,42,.78)`. */
    val Surface = Color(0xC709112A)

    /** Nested card / row inside a [Surface] card. */
    val SurfaceRaised = Color(0xFF0E1A3A)

    /** Opaque twin of [Surface] — for rows that must not stack translucency. */
    val SurfaceRaisedAlt = Color(0xFF09112A)

    /** Top-chrome pills floating over video: `#09112A` @ 13 %. */
    val Glass = Color(0x2109112A)

    /** Bottom transport panel + side chrome: `#163A8C` @ 13 %. */
    val GlassChrome = Color(0x21163A8C)

    /** Subtitles / metrics panels: `#163A8C` @ 50 % — denser text needs more body. */
    val GlassPanel = Color(0x80163A8C)

    /** 1 dp hairline on glass, `rgba(255,255,255,.14)`. */
    val GlassBorder = Color(0x24FFFFFF)

    /** Cool hairline on the transport panel + side panels, `#96BEFF` @ 30 %. */
    val GlassBorderCool = Color(0x4D96BEFF)

    // ── 2b. Ink ─────────────────────────────────────────────────────────────

    val OnSurface = Color(0xFFF2F6FF)
    val OnSurfaceDim = Color(0xFFA9BCE6)
    val OnSurfaceMuted = Color(0xFF8FA4D6)
    val OnSurfaceFaint = Color(0xFF7E90BE)

    /** Brighter secondary body — the pairing "Listening…" status row. */
    val OnSurfaceSoft = Color(0xFFC9D8F7)

    /** Mono text on glass chrome (net pill, clock pill, spec chips). */
    val OnChrome = Color(0xFFDCE5FF)

    /** Stat / eyebrow labels inside the glass panels. */
    val OnPanelLabel = Color(0xFF9FB6E6)

    /** Ink on white or near-white fills: QR modules, the selected size cell. */
    val OnLight = Color(0xFF0A1533)

    // ── Outlines ────────────────────────────────────────────────────────────

    /** Unfocused control border, `#BEDCFF` @ 34 %. */
    val Outline = Color(0x57BEDCFF)

    /** Spec-chip / divider hairline, `rgba(255,255,255,.2)`. */
    val OutlineHairline = Color(0x33FFFFFF)

    /** The END SESSION pill's 2 dp outline, `rgba(255,255,255,.18)`. */
    val OutlineSoft = Color(0x2EFFFFFF)

    // ── 2c. Brand & accent — the role split ─────────────────────────────────

    /** Brand blue: mark triangle on light, QR finder eyes, ambient washes. */
    val Primary = Color(0xFF1240E8)

    /** Brand blue that survives a dark surface — the mark in chrome. */
    val PrimaryOnDark = Color(0xFF4A78FF)

    /** Links / connection accents. */
    val Link = Color(0xFF6FA0FF)

    /** Amber — focus, transport, playhead, pairing code. The eye's anchor. */
    val Spark = Color(0xFFFFB61E)

    /** Amber emphasis text (eyebrows, the highlighted word in body copy). */
    val SparkBright = Color(0xFFFFC44D)

    /** Playhead gradient end; ink on a selected amber row. */
    val SparkLight = Color(0xFFFFD87A)

    /** [SparkLight] @ 75 % — the meta chip on a selected amber row. */
    val SparkLightDim = Color(0xBFFFD87A)

    /** Ink on amber fills — the play glyph, the inverted side cards. */
    val OnSpark = Color(0xFF33240A)

    /** Secondary ink on amber fills, `rgba(51,36,10,.7)`. */
    val OnSparkDim = Color(0xB333240A)

    // ── 2c. Semantic ────────────────────────────────────────────────────────

    /** Healthy / listening dot. */
    val Live = Color(0xFF5BE38C)

    /** Degraded / recovering. */
    val Caution = Color(0xFFFFA23A)

    /** Crimson — unreachable / failed. Distinct from [Spark]; do not confuse. */
    val Trouble = Color(0xFFC9314D)

    val Info = Link

    /** [Live] @ 16 % — the healthy health-pill bed. */
    val LiveWash = Color(0x295BE38C)

    /** [Caution] @ 16 % — the degraded health-pill bed. */
    val CautionWash = Color(0x29FFA23A)

    // ── Control fills (focus vocabulary, §3) ────────────────────────────────

    /** Unfocused control fill, `rgba(148,190,255,.14)`. */
    val ControlFill = Color(0x2494BEFF)

    /** Slightly denser control fill for the square transport buttons, @ 16 %. */
    val ControlFillStrong = Color(0x2994BEFF)

    /** Close buttons / panel affordances, `rgba(190,220,255,.16)`. */
    val ChromeButtonFill = Color(0x29BEDCFF)

    /** Selected-but-not-focused fill, [Spark] @ 18 %. */
    val SelectedFill = Color(0x2EFFB61E)

    /** Selected-but-not-focused border, [SparkLight] @ 50 %. */
    val SelectedBorder = Color(0x80FFD87A)

    // ── Focus (§3) ──────────────────────────────────────────────────────────

    /** The detached focus ring — amber on every surface… */
    val FocusRing = Spark

    /** …except on an amber fill, where amber-on-amber would vanish. */
    val FocusRingOnSpark = Color(0xFFFFFFFF)

    /** [Spark] @ 34 % — the scrub knob halo and the soft ring bloom. */
    val FocusRingSoft = Color(0x57FFB61E)

    /** [Spark] @ 16 % — the seek-burst wash and ambient focus glow. */
    val FocusGlow = Color(0x29FFB61E)

    // ── Scrub track (§2d) ───────────────────────────────────────────────────

    /** Track base, `rgba(255,255,255,.16)`. */
    val TrackBase = Color(0x29FFFFFF)

    /** Buffered fill, `rgba(255,255,255,.26)`. */
    val TrackBuffered = Color(0x42FFFFFF)

    // ── Scrims & panel light (§2d) ──────────────────────────────────────────

    /** Bottom playback scrim end, `#02040A` @ 92 %. */
    val ScrimEnd = Color(0xEB02040A)

    /** Top playback scrim start, `#02040A` @ 78 %. */
    val ScrimTop = Color(0xC702040A)

    /** Full-bleed veil behind the handshake card, [Canvas] @ 82 %. */
    val ScrimVeil = Color(0xD104070F)

    /** The transport panel's 1 dp inner top hairline, `rgba(230,242,255,.75)`. */
    val PanelHighlight = Color(0xBFE6F2FF)

    /** Subtitle cue plate behind burned-in text, `rgba(2,4,10,.62)`. */
    val CueBackground = Color(0x9E02040A)

    // ── Throughput histogram (§5.5) ─────────────────────────────────────────

    /** Bars at or above 50 % of the rolling peak, [Spark] @ 85 %. */
    val HistogramBar = Color(0xD9FFB61E)

    /** Bars below 50 % of the rolling peak, [Caution] @ 60 %. */
    val HistogramBarLow = Color(0x99FFA23A)

    // ── Gradients ───────────────────────────────────────────────────────────

    /** The playhead / played fill — `#FFB61E → #FFD87A`, left to right. */
    val SparkGradient = Brush.horizontalGradient(listOf(Spark, SparkLight))
}

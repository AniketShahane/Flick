# Flick TV (Receiver) — Material Expressive redesign spec

Source of truth for the `:receiver` UI rebuild. Derived from the Claude Design
file `Flick TV (Receiver).dc.html` (project `c6178078-4bbb-41ab-aa7d-e0cd0f958cb4`),
reconciled against what the app can actually measure and against Android TV
10-foot constraints.

**Every agent working on this redesign implements against THIS file, not against
the raw design HTML.** Where the two disagree, this file wins — the deviations
below are deliberate and are explained.

---

## 0. Invariants — do not touch

The redesign is a **UI-layer change**. The following are load-bearing behaviour
proven on real hardware and must survive byte-for-byte in intent:

- `net/**` — pairing, control server, NSD, binding gate, preflight probe, LAN
  address reconciliation. **No edits.**
- `session/**` — `SessionController`, cast generation gate, terminal phase,
  startup retry. **No edits.**
- `player/PlayerController.kt` — the `DefaultLoadControl` tuning, hardware-only
  decoder selection, `NoRedirectHttpDataSource`, media-session binding, recovery
  backoff, first-frame gate. **Additive changes only** (§7); no behavioural edits
  to the existing playback path.
- `TvRemoteKeyPolicy.kt` / `TvRemoteKeyDispatcher.kt` — remote capture semantics.
  **No edits.**
- The `ReceiverApp.kt` lifecycle effects (bind reconciliation, 10 Hz confirmed-
  position feed, chrome auto-hide, refresh-rate matching, `BackHandler` order).
  Wiring may be *added*; the existing effects keep their current semantics.
- Never transcode, never screen-mirror. Nothing in the UI may imply otherwise.

### Test contracts that must keep passing

`androidTest` asserts on these exact user-visible strings. Renaming any of them
breaks a test — if a rename is genuinely required, update the test in the same
change and say so.

| String | Where |
|---|---|
| `Play` / `Pause` | transport play/pause `contentDescription` |
| `Skip back 10 seconds` / `Skip forward 10 seconds` | transport `contentDescription` |
| `Volume` | volume control `contentDescription` |
| `Film surface` | playback video surface `contentDescription` |
| `confirmed %s` / `target %s · snap on release` | `TvScrubBar` semantics |
| `Pair another phone`, `Show code bigger`, `Rename TV`, `Done` | pair/idle screens |
| `Playback metrics overlay`, `Diagnostics`, `Forget all phones` | settings |
| `End session` | error screen |

---

## 1. Scale rule

The design canvas is **1920 × 1080 CSS px**. Android TV composes at ~**960 × 540 dp**
(density 2.0). Therefore:

> **design px ÷ 2 = dp** (and → sp for type), then apply the floors and the
> overscan clamp below.

### 1a. Type floors (deliberate deviation)

The design was authored as a browser mockup viewed at desk distance. Several of
its mono micro-labels land at 6–8 sp after ÷2, which is unreadable at 10 feet.
Apply these floors:

| Class | Design px | Rule |
|---|---|---|
| Reading copy — titles, body, button labels, list rows, error text | any | ÷2, then **floor 24 sp** |
| Mono micro-labels — eyebrows, telemetry, stat labels, chips (UPPERCASE, tracking ≥ 0.12 em) | 12–18 | **16 sp** flat |
| Mono running numbers — timecodes, throughput readout | 28–46 | ÷2 exactly (14–23 sp), **floor 20 sp**, always `tnum` |
| Display — headlines, now-playing title | 46–104 | ÷2 exactly |

Nothing renders below **16 sp**. Uppercase tracked mono at 16 sp is the same
treatment the existing `MetricsOverlay` dev HUD already uses and reads cleanly at
10 ft; lowercase reading copy never goes below 24 sp.

### 1b. Overscan clamp (deliberate deviation)

The design places the playback chrome 56 px (28 dp) from the panel edge and the
pairing content at 104 px (52 dp). 28 dp is **inside** the 5 % TV-safe inset and
would be clipped by overscan on real panels.

> All outer chrome — top bar, bottom transport panel, END SESSION pill, pairing
> columns, side panels — anchors to `rememberTvSafeAreaPadding()` (5 % ⇒ 48 dp
> horizontal / 27 dp vertical at 960 × 540 dp). Interior padding and gaps keep
> their ÷2 design values.

---

## 2. Colour — the palette flip

The receiver currently ships coral `#FF6B57` + cyan `#41E5F2` on violet-black.
The new design is **electric blue + amber on blue-black**.

> These hexes match an **in-flight, not-yet-committed** `:sender` redesign — as of
> this spec, `sender/ui/theme/Color.kt` on `main` still carries the old coral/cyan
> palette, and the new values live only in an uncommitted working tree. So the two
> apps will not visually match on a device until that sender work lands. That is
> expected and is not a receiver defect.

Replace the body of `ui/theme/Color.kt` with these tokens. Keep the `FlickColor`
object name and keep every existing property name that still has a job, so the
rest of the tree keeps compiling; delete only what genuinely no longer exists.

### 2a. Surfaces

| Token | Hex | Role |
|---|---|---|
| `Canvas` | `#04070F` | app root / idle bed — blue-black, never `#000` |
| `CanvasPlayback` | `#02040A` | behind the film |
| `CanvasPair` | `#060C1E` | pairing bed |
| `Surface` | `#09112A` | raised card fill (design `rgba(9,17,42,.78)` → use `0xC709112A`) |
| `SurfaceRaised` | `#0E1A3A` | nested card |
| `Glass` | `#09112A` @ **13 %** = `0x2109112A` | top-chrome pills over video |
| `GlassChrome` | `#163A8C` @ **13 %** = `0x21163A8C` | bottom transport panel + side panels |
| `GlassPanel` | `#163A8C` @ **50 %** = `0x80163A8C` | subtitles / metrics panels (more opaque, they carry dense text) |
| `GlassBorder` | `rgba(255,255,255,.14)` = `0x24FFFFFF` | hairline on glass |
| `GlassBorderCool` | `#96BEFF` @ 30 % = `0x4D96BEFF` | border on the bottom transport panel |

### 2b. Ink

| Token | Hex | Role |
|---|---|---|
| `OnSurface` | `#F2F6FF` | primary |
| `OnSurfaceDim` | `#A9BCE6` | secondary body |
| `OnSurfaceMuted` | `#8FA4D6` | tertiary labels |
| `OnSurfaceFaint` | `#7E90BE` | micro-labels, disabled |
| `OnChrome` | `#DCE5FF` | mono text on glass chrome |
| `OnPanelLabel` | `#9FB6E6` | stat labels inside panels |

### 2c. Brand & accent — the role split

| Token | Hex | Role |
|---|---|---|
| `Primary` | `#1240E8` | brand blue: mark triangle, QR finder eyes, ambient washes |
| `PrimaryOnDark` | `#4A78FF` | brand blue on dark surfaces |
| `Link` | `#6FA0FF` | links / connection accents |
| `Spark` | `#FFB61E` | **amber: transport, playhead, focus ring, pairing code** |
| `SparkBright` | `#FFC44D` | amber emphasis text |
| `SparkLight` | `#FFD87A` | playhead gradient end |
| `OnSpark` | `#33240A` | ink on amber fills (play glyph) |
| `Live` | `#5BE38C` | healthy / listening dot |
| `Caution` | `#FFA23A` | degraded / recovering |
| `Trouble` | `#C9314D` | unreachable / failed (kept from current palette) |

**The role split has flipped from the old system and this is intentional.**
Previously cyan carried focus and coral carried action. Now:

- **Amber `#FFB61E` carries focus, transport and the playhead** — it is the one
  thing the eye tracks while watching.
- **Blue `#1240E8` / `#4A78FF` carries brand and the ambient field** — the mark,
  the glass tint, the background washes.

Update the doc comment in `Color.kt` to state this; the old "warm = content,
cool = focus, they never swap jobs" comment is now wrong and must not survive.

### 2d. Gradients & fills

- Playhead: `Brush.horizontalGradient(listOf(Spark, SparkLight))` — `#FFB61E → #FFD87A`.
- Scrub track base `0x29FFFFFF` (16 %), buffered `0x42FFFFFF` (26 %).
- Pairing bed: radial `Primary` @ 50 % from the upper-left + radial `Spark` @ 22 %
  from the lower-right, over `CanvasPair`.
- Playback bottom scrim: vertical `Transparent → #02040A` @ 92 %, covering the
  bottom 56 % of the frame. Top scrim: `#02040A` @ 78 % → transparent over the
  top 26 %. Gradients, never hard bars.
- Transport panel inner highlight: 1 dp top hairline,
  `horizontalGradient(Transparent → rgba(230,242,255,.75) → Transparent)`,
  inset 12 % from each side.

---

## 3. Focus system — amber ring

Rewrite the focus vocabulary in `ui/components/TvFocus.kt`. There is no hover on
TV; this is the entire language:

| State | Treatment |
|---|---|
| **Focused** | **detached amber ring**: 2.5 dp `Spark` border, offset **5.5 dp outside** the element bounds, corner radius = element radius + 5.5 dp. Plus scale **1.06** on `FlickMotion.focusPop()`. No cyan anywhere. |
| **Focused, on an amber fill** (the play button) | ring is **white `#FFFFFF`** — amber-on-amber would vanish |
| **Selected, not focused** | `Spark` @ 18 % fill, `SparkLight` @ 50 % border, **no ring** |
| **Unfocused** | `rgba(148,190,255,.14)` = `0x24 94BEFF` fill, `#BEDCFF` @ 34 % border |
| **Disabled** | 38 % alpha |

The ring is a *detached* ring (design: `inset:-11px; border:5px solid`), not an
inline border — implement it as a sibling `Box` drawn outside the content bounds
so it never resizes the element. Honour `rememberReducedMotion()`: skip the scale
animation, keep the ring.

---

## 4. Typography

Keep the downloadable-Google-Fonts mechanism and the graceful fallback. Change the
families to match the design's voice:

- **Display** (headlines, now-playing title, wordmark): **Archivo**, weights
  500/600/700/800. Falls back to platform default.
- **Body/UI**: **Manrope**, weights 500/600/700/800. Falls back to platform default.
- **Mono** (timecode, telemetry, eyebrows): **IBM Plex Mono**, weights 500/600.
  Falls back to platform monospace. `tnum` mandatory on every running number.

Letter-spacing from the design: display `-0.045em` to `-0.05em` (tight), mono
eyebrows `+0.14em` to `+0.2em` (wide, uppercase).

Update `FlickTvTypography` role sizes to the §1a scale.

---

## 5. Screen specs

### 5.1 Pair screen (`PairScreen.kt`)

Two columns inside the safe area: content `1fr` / QR column `310 dp`, gap `40 dp`.

**Left column** (gap 20 dp):
1. **Lockup** — `BrandMark` 38 dp + column: "Flick" Archivo 800 / 23 sp /
   `-0.045em`, and eyebrow `RECEIVER · <TV NAME>` mono 16 sp / `+0.2em` /
   `OnSurfaceMuted`. (Design's hardcoded "1.4" is replaced by nothing — do not
   invent a version string; use the real TV name.)
2. **Headline** — `pair_title`, Archivo 800, **52 sp**, line-height 0.88,
   `-0.05em`, `#FFFFFF`.
3. **Body** — `pair_instructions`, 24 sp / 600 / `OnSurfaceDim`, max width 410 dp,
   with the word "Flick" in `SparkBright`.
4. **Manual-entry card** — `Surface` fill, 20 dp radius, 21 dp padding,
   `GlassBorder` hairline. Eyebrow `OR ADD THIS TV BY HAND` mono 16 sp. Then a row:
   `IP address` / vertical 1 dp divider / `Port` / divider / `Pairing code`.
   Values in mono 22 sp `tnum`; the pairing code in `Spark`, its label in
   `SparkBright`. Below, a timer row: clock glyph + "one sender at a time"
   (**drop the design's fake "rotates in 4:52" countdown unless
   `PairingManager` actually exposes a remaining-TTL value; if it does, show it**).
5. **Status row** — pulsing `Live` dot 7 dp + "Listening · no account, nothing
   uploaded" 24 sp `#C9D8F7`. When `networkReady` is false, show the existing
   `pair_waiting_network_*` copy instead.
6. Focusables: `Rename TV` and `Show code bigger`, styled per §3. One of them
   takes initial focus. **Do not add the design's "SIMULATE A PHONE CONNECTING"
   button — it is a prototype affordance, not a product feature.**

**Right column**: white card, 26 dp radius, 23 dp padding, containing `QrCode`.
Recolour the QR: modules `#0A1533`; the two upper finder eyes' inner squares
`#1240E8`, the lower-left eye's inner square `#FFB61E`; centre overlay = white
rounded square (16 dp radius) holding `BrandMark` tinted `Primary`. Below the
card, a wifi glyph + `flick://<host>:<port>` mono 16 sp `OnSurfaceMuted`.

> The eye recolouring requires drawing the three finder patterns explicitly over
> the ZXing matrix. Keep error correction at `M` and keep the payload byte-identical
> — the centre overlay must not exceed the ~15 % the `M` level can lose.

### 5.2 Connecting / handshake (`ReceiverApp.ConnectingScreen`)

Full-bleed `Canvas` @ 82 % over the (covered) player surface. Centred card:
450 dp wide, `GlassPanel` fill, 26 dp radius, 32 dp padding, `GlassBorder`
hairline, entering on `FlickMotion.flickSettle()` with a 23 dp rise.

Contents: a 48 dp amber spinner ring (3.5 dp stroke, `Spark` on `Spark` @ 22 %,
continuous rotation — **skip the rotation under `rememberReducedMotion()`**),
then `connecting_title` Archivo 800 / 27 sp, then `connecting_detail` 24 sp
`OnSurfaceDim`. Where a device label is known, the title becomes
"<device> is flicking <title>" using the real session values.

### 5.3 Playback (`PlaybackScreen.kt`)

**Top chrome** (visible with `chromeVisible`, inside safe area):
- Left: glass pill (`Glass`, pill radius, 7 dp/15 dp padding, `GlassBorder`) —
  `BrandMark` 17 dp + `now_playing_from` 24 sp.
- Right: net-health pill — dot tinted `Live`/`Caution` by RSSI + band, then
  `<band> · <rssi> dBm` mono 16 sp `OnChrome`; then a clock pill showing the real
  device time, mono 16 sp. Both `Glass`.
- `END SESSION` outlined pill sits below the left pill, `OnSurfaceDim`, 2 dp
  `rgba(255,255,255,.18)` border — focusable.

**Bottom transport panel** (inside safe area, anchored bottom):
`GlassChrome` fill, **26 dp radius**, padding 22 dp top / 26 dp sides / 20 dp
bottom, `GlassBorderCool` 1 dp border, the §2d inner top hairline, entering on
`flickSettle` with a 21 dp rise. Three rows, 17 dp gap:

1. **Header row** — left: eyebrow `NOW PLAYING · DIRECT FILE` mono 16 sp `SparkBright`,
   then title Archivo 800 **34 sp** `-0.045em` `#FFFFFF` (ellipsize, single line).
   Right: spec chips, 1 dp `rgba(255,255,255,.2)` border, 8 dp radius, mono 16 sp
   `OnChrome`. Chips come from **real telemetry only** (§7): resolution + HDR
   class, audio codec + channel count, video codec. **Drop the design's
   "18.4 GB" chip — file size is not available to the receiver.**
2. **Scrub row** — position mono 20 sp `tnum` `#FFFFFF` (fixed 75 dp width) /
   `TvScrubBar` / remaining `−mm:ss` mono 20 sp `tnum` `OnSurfaceDim` (75 dp,
   right-aligned). Track 8 dp tall, pill; buffered fill `0x42FFFFFF`; played fill
   the §2d amber gradient; knob 15 dp white circle with a 3.5 dp `Spark` @ 34 %
   halo. Ghost/target playhead behaviour and the existing `confirmed …` /
   `target … · snap on release` semantics are **preserved as-is**.
3. **Control row** — `[Subtitles card]  ⟨ back10 · play · fwd10 ⟩  [Stream metrics card]`,
   space-between. Back/forward: 52 dp square, 17 dp radius, `rgba(148,190,255,.16)`
   fill, `#BEDCFF` @ 34 % border, 26 dp glyph `#FFFFFF`. Play: **66 dp**, 22 dp
   radius, `Spark` fill, 35 dp glyph `OnSpark`, amber drop shadow. Side cards:
   13 dp radius, glyph 19 dp + two-line label (title 24 sp / state mono 16 sp);
   when their panel is open they invert to a `Spark` fill with `OnSpark` ink.

**Volume** — the design omits volume, but the app has it and `TransportAndVolumeInteractionTest`
asserts on it. **Keep `VolumeCells`**, restyled: place it in the control row
between the transport cluster and the metrics card, or as a fourth focusable in
the same row. It keeps its `Volume` `contentDescription`.

**Overlays** (unchanged behaviour, restyled):
- Seek burst: 38 % width side wash, radial `Spark` @ 16 %, 60 dp glyph +
  `±10s` Archivo 800 22 sp, on `tvBurst` (0.72 s scale-and-fade).
- Paused chip: at 28 % height, `Glass` pill, 23 dp `Spark` pause glyph + "Paused"
  Archivo 800 17 sp.
- Buffering: keep the existing calm treatment, restyled to the new tokens.
- Quality flourish (`QualityInfo`): restyle to the new glass; keep the 4.5 s
  auto-dismiss.

**Focus order.** The transport reads left-to-right, but focus traverses it **vertically**:
`END SESSION ↕ subtitles ↕ transport cluster ↕ volume ↕ metrics`, wired with explicit
`focusProperties { up/down }` links and `playFocusRequester` still landing on play at
entry.

> This is deliberate, not a compromise. `TvRemoteKeyPolicy` consumes physical
> DPAD Left/Right as ±10 s seek gestures at the Activity boundary *before* the
> `chromeVisible` branch, so horizontal keys never reach Compose focus during
> active playback. That is pre-existing, unit-tested behaviour which spec §0
> forbids changing — and it is also the conventional TV-player pattern
> (Left/Right scrubs the timeline, Up/Down moves between control groups). Within
> the transport cluster, back10 / play / fwd10 are reached by remote seek and by
> DPAD-centre on play, not by horizontal focus movement.

### 5.4 Subtitles panel (new — `ui/screens/SubtitlesPanel.kt`)

Left-anchored above the transport panel, 310 dp wide, `GlassPanel`, 20 dp radius,
16/17 dp padding, entering on `flickSettle` with a rise.

- Header: "Subtitles" Archivo 800 27 sp + a focusable close button (23 dp square,
  8 dp radius).
- Track list from **real Media3 tracks** (§7): each row = check glyph
  (`check_circle` when selected, `radio_button_unchecked` otherwise, tinted
  `Spark`/`OnSurfaceFaint`) + label + a mono 16 sp meta chip showing the real
  format (e.g. `SRT · EMBEDDED`, `PGS · IMAGE`) derived from the track's sample
  MIME. Selected row: `Spark` @ 18 % fill, `SparkLight` text. An explicit **Off**
  row is first.
- Size selector: `SIZE` label + three focusable cells (Small / Medium / Large)
  that drive the Media3 `SubtitleView` fixed text size. Selected cell: `#F2F6FF`
  fill, `#0A1533` ink.

Every row is D-pad focusable per §3. `Back` closes the panel.

### 5.5 Stream metrics panel (new — `ui/screens/StreamMetricsPanel.kt`)

Right-anchored above the transport panel, 370 dp wide, `GlassPanel`, 20 dp radius.

- Header: "Stream metrics" Archivo 800 27 sp + a health pill (`HEALTHY · DIRECT PLAY`
  in `Live`, or `DEGRADED · RECOVERING` in `Caution`, chosen from the real
  `DiagnosticsSnapshot.status`) + a focusable close button.
- **Throughput histogram**: `THROUGHPUT · LAST 40 s` eyebrow + the live value in
  mono 14 sp `Spark`; 40 bars, 2.5 dp gap, 37 dp tall, 2 dp top radius, height
  proportional to the rolling peak. Bars below 50 % of peak tint `Caution`, else
  `Spark` @ 85 %. Fed by the new `ThroughputHistory` ring buffer (§7).
- **Stats grid**: 3 × 3, label mono 16 sp `OnPanelLabel` over value 24 sp. Use only
  real fields: resolution, codec (from `videoMimeType`), frame rate, bitrate,
  buffer ahead, probe latency, dropped frames, decoder name, transport
  (`TCP · <wifiBand>`). Warn-coloured (`Caution`) when degraded; dropped-frames
  zero shows `Live`.

This panel is the *tasteful* read; the existing dense `MetricsOverlay` dev HUD
stays as the separate opt-in Settings toggle (design brief Part 3 item 10).

### 5.6 Idle, Error, Settings, MetricsOverlay

Not drawn in the design file — **re-skin to the new tokens, keep structure and
behaviour**. Specifically: new palette, Archivo/Manrope/IBM Plex Mono, amber
focus rings, glass panels, safe-area anchoring, and the §1a type floors. Keep
every string listed in §0 and keep the existing focus requesters and back
handling. Idle gains the design's ambient blue radial wash + a pulsing `Live`
dot; Error keeps its amber (not-serving) vs crimson (unreachable) split.

---

## 6. Motion

Keep `FlickMotion`'s curve/duration tokens. Map the design's animations onto them:

| Design | Token |
|---|---|
| `tvRise` (panel entrance, 0.38–0.5 s) | `flickSettle()` + slide-up |
| `tvBurst` (seek flash, 0.72 s) | scale 0.7→1→1.14 with fade, `flickSettle` easing |
| `tvPulse` (live dot, 1.9 s) | existing infinite pulse in `StatusPill` |
| `tvSpin` (handshake ring, 1 s linear) | `LinearEasing` infinite rotation |
| chrome fade | `chromeFadeIn()` / `chromeFadeOut()` |
| focus ring/scale | `focusPop()` |

Every infinite/ambient animation must be skipped when `rememberReducedMotion()`
is true — the static end-state still reads correctly.

---

## 7. Data plumbing (additive, `player/**` + `ReceiverApp.kt`)

The design shows telemetry the app does not yet expose. Add exactly this, and
**never fabricate a value** — if it is unavailable, render `—` or omit the element.

1. **`player/ThroughputHistory.kt`** (new) — a fixed 40-slot ring buffer of
   `bitrateEstimateBps` samples plus the rolling peak. Appended from the existing
   ~2 Hz sampling branch in `ReceiverApp`'s polling loop (the `tick % 5` arm); do
   not add a new timer.
2. **`player/SubtitleTrackInfo.kt`** (new) — `data class SubtitleTrackInfo(id, label, mimeType, isSelected)`
   plus a pure mapper from `androidx.media3.common.Tracks` to a list, and a pure
   `subtitleFormatLabel(mimeType)` returning `SRT`, `PGS`, `VTT`, `SSA`, … for the
   meta chip. **Pure functions — unit-test them.**
3. **`PlayerController`** — add `fun subtitleTracks(): List<SubtitleTrackInfo>`
   (read `player.currentTracks`) and `fun selectSubtitleTrack(id: String?)`
   (`null` = off) via `trackSelectionParameters`. Additive only; do not touch the
   load control, decoder policy, or recovery paths.
4. **Audio/video codec chips** — expose the selected audio format's sample MIME
   and channel count, and the video sample MIME, on `DiagnosticsSnapshot` as new
   nullable fields defaulted to `null`. Existing call sites keep compiling.
5. **Subtitle size** — surface the Small/Medium/Large choice as receiver state and
   apply it through the existing `reducedSubtitleTextSizeSp` path in
   `ReceiverApp.configureSubtitles`, preserving the current caption-manager and
   layout listeners.

Not available and therefore **cut from the design**: media file size, "one sender
at a time" countdown (unless `PairingManager` exposes real TTL), the simulate
button, and the fixed "21:47" clock (use the real time).

---

## 8. Launcher icon & banner

Both are currently the **old vermilion** mark (`#FF4B24`) and must be rebuilt in
the new brand:

- `res/drawable/ic_launcher.xml` — blue play triangle `#1240E8` with three amber
  `#FFB61E` motion streaks (alphas 0.5 / 0.85 / 0.5) on the `#04070F` canvas,
  matching the design's SVG geometry on the 64-unit grid: streaks at
  `(9,22.5,13×5)`, `(4,31.5,11×5)`, `(9,40.5,13×5)` r=2.5; triangle
  `M28,15 L56,32 L28,49` with a 9-unit round-join stroke.
- Add a proper **adaptive icon**: `res/mipmap-anydpi-v26/ic_launcher.xml` with
  `ic_launcher_background` (solid `#04070F`) + `ic_launcher_foreground` (the mark
  inset to the 66 dp safe zone of the 108 dp canvas), and point the manifest at
  `@mipmap/ic_launcher`. Keep a `@drawable/ic_launcher` fallback for pre-26
  tooling paths.
- `res/drawable/banner.xml` — 320 × 180 dp leanback banner: the mark plus a real
  "flick" wordmark drawn as paths (not the current grey placeholder bars) on
  `#04070F`, with the amber streaks reading at TV-launcher size.
- `res/values/themes.xml` — window/status/navigation background `#FF04070F`.

Keep everything vector and self-contained; no raster assets.

---

## 9. Definition of done

- `:receiver:assembleDebug` and `:receiver:testDebugUnitTest` pass.
- Existing `androidTest` sources still compile and their asserted strings survive.
- No `FlickColor` reference to a removed token anywhere in the tree.
- No text below 16 sp; no reading copy below 24 sp.
- Every interactive element reachable by D-pad, with the amber focus ring visible.
- All outer chrome inside `rememberTvSafeAreaPadding()`.
- No fabricated telemetry.

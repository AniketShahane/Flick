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
| `Pair another phone`, `Rename TV`, `Settings`, `Done` | pair/idle screens |
| `Open when you cast`, `Playback metrics overlay`, `Diagnostics`, `Forget all phones` | settings |
| `Paired phones`, `Manage`, `Rename`, `Forget`, `Back` | settings + the paired-phones drill-in |
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
| Reading copy — titles, body, button labels, list rows, error text | any | Use the implemented 18 / **16 default** / 15 sp reading hierarchy; `FlickType.body()` clamps only at 14 sp |
| Mono micro-labels — eyebrows, telemetry, stat labels, chips (UPPERCASE, tracking ≥ 0.12 em) | 12–18 | **14 sp** floor, always tabular |
| Mono running numbers — timecodes, throughput readout | 28–46 | **14 sp** floor, always `tnum`; playback timecode currently uses 16 sp |
| Display — headlines, now-playing title | 46–104 | Use `FlickTvTypography`'s 40 / 31 / 27 / 22 / 20 sp display steps |

Nothing renders below **14 sp**. This is a hierarchy-preserving floor, not a target
size: default body copy is 16 sp, and upper-case mono is kept open with its positive
tracking rather than being enlarged into its neighbouring role.

### 1b. Overscan clamp (deliberate deviation)

The design places the playback chrome 56 px (28 dp) from the panel edge and the
pairing content at 104 px (52 dp). 28 dp is **inside** the 5 % TV-safe inset and
would be clipped by overscan on real panels.

> All outer chrome — top bar, bottom transport panel, END SESSION pill, pairing
> columns, side panels — anchors to `rememberTvSafeAreaPadding()` (5 % ⇒ 48 dp
> horizontal / 27 dp vertical at 960 × 540 dp). Because the focus ring is painted
> outside its control, the outermost focusable also keeps `FlickDimens.FocusRingReserve`
> (**10 dp**) inside that safe area. Interior padding and gaps keep their ÷2 design values.

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
| **Focused** | **detached amber ring**: 2 dp `Spark` border, offset **4.5 dp outside** the element bounds, corner radius = element radius + 4.5 dp. Plus scale **1.06** on `FlickMotion.focusSpatial()`. No cyan anywhere. |
| **Focused, on an amber fill** (the play button) | ring is **white `#FFFFFF`** — amber-on-amber would vanish |
| **Selected, not focused** | `Spark` @ 18 % fill, `SparkLight` @ 50 % border, **no ring** |
| **Unfocused** | `rgba(148,190,255,.14)` = `0x24 94BEFF` fill, `#BEDCFF` @ 34 % border |
| **Disabled** | 38 % alpha |

The ring is a *detached* ring (design: `inset:-11px; border:5px solid`), not an
inline border — implement it as a sibling `Box` drawn outside the content bounds
so it never resizes the element. Honour `rememberReducedMotion()`: skip the scale
animation, keep the ring.

### 3a. The traveling ring — one object per focus group

`FocusBeaconHost` installs ONE ring for the group inside it; members mark
themselves with `Modifier.focusBeacon(shape)` and suppress their own. It is
strictly opt-in: with no host above it a member draws its own ring exactly as
before, which is what makes the beacon safe to scope per group rather than per
screen — a ring that flies between unrelated regions reads as a bug.

Hosts ship on the four coherent groups: the **playback transport row**, the
**subtitles panel**, the **Settings column**, and the **pair action row**.

The stream-metrics panel deliberately has none: its close button is its only
focus target, so a host there would be a ring with nowhere to travel. Top chrome
is the same case (END SESSION alone). Both keep the local ring.

Travel is capped — a jump longer than 320 dp fades out and blooms back in at the
destination instead of flying across dead screen — and the travel spring damps at
`TV_FOCUS_DAMPING`, so the painted extent stays inside `FocusRingReserve`.

A member publishes its **pre-scale** layout rect and draws the 1.06 focus lift
itself, so the host builds the ring in that same pre-scale space and puts it
through a draw-phase scale rather than folding the lift into an inset. A uniform
scale of a rounded rect is still a rounded rect: one inset, one radius, concentric
at any aspect ratio. Adding the lift back as an inset instead needs a *different*
amount per axis — 28.5 dp horizontally against 6.6 dp vertically on the 800 × 69 dp
Settings "Device name" row — which leaves no single radius to grow the corner by.
Taking the mean of the two put a **34.5 dp** corner on that row where §3 asks for
17 × 1.06 + 4.5 = **22.5 dp**: 84 % of the ring's own half-height, so a rounded
rectangle rang as a stadium. The error scales with the control's aspect ratio and
vanishes at 1:1 — the transport keys are square, so their two insets were
identical — which is why the Settings column is where it read as a bug.

The offset and both stroke widths are divided by the lift before the scale, so the
ring keeps the 4.5 dp offset and 2 dp stroke §3 names rather than the 6 % more the
lift would carry them to. That is the one place the traveling ring and the local
`flickFocusRing` differ — the local one draws *inside* the member's scaling layer
and lets the lift take its offset to 4.77 dp — and it is 0.27 dp, half a physical
pixel at TV density, in the direction of the spec.

---

## 4. Typography

The faces are bundled `res/font` binaries. There is no downloadable-font provider and
no fallback family: Play Services font catalogues lag hardest on TV hardware, and a lag
would render the platform default silently, with no error.

- **Display** (headlines, now-playing title, wordmark): **Bricolage Grotesque**, weights
  700/800.
- **Body/UI**: **Geist**, weights 500/600/700.
- **Mono** (timecode, telemetry, eyebrows): **Geist Mono**, weights 500/600.
  `tnum, zero` mandatory on every running number.

Letter-spacing at ten feet is *looser* than the phone's, never tighter — tight tracking
closes counters at 3 m. Display sits at `-0.02em`, body and labels at `+0.005em`, mono
eyebrows `+0.14em` to `+0.2em` (wide, uppercase). No weight falls below 500 and no
size falls below 14 sp; `FlickType.body()` defaults to 16 sp and the shipped body
steps are 18 / 16 / 15 sp. `FlickType`'s helpers clamp to the 14sp floor, so a call
site cannot pass its way under it.

Update `FlickTvTypography` role sizes to the §1a scale.

---

## 5. Screen specs

### 5.1 Pair screen (`PairScreen.kt`)

Two columns inside the safe area: content `1fr` / QR column **272 dp**, gap **40 dp**.

**Left column** (gap **10 dp**):
1. **Lockup** — `BrandMark` **30 dp** + column: "Flick" Bricolage 800 / **18 sp** /
   `-0.02em`, and eyebrow `RECEIVER · <TV NAME>` mono **14 sp** / `+0.2em` /
   `OnSurfaceMuted`. (Design's hardcoded "1.4" is replaced by nothing — do not
   invent a version string; use the real TV name.)
2. **Headline** — `pair_title`, Bricolage 800, **40 sp**, `-0.02em`, `#FFFFFF`.
3. **Body** — `pair_instructions`, **18 sp** / 600 / `OnSurfaceDim`, max width **500 dp**,
   with the word "Flick" in `SparkBright`.
4. **Manual-entry card** — `Surface` fill, 20 dp radius, **16 dp horizontal / 10 dp vertical**
   padding, `GlassBorder` hairline. Eyebrow `OR ADD THIS TV BY HAND` mono **14 sp**. Then a row:
   `IP address` / vertical 1 dp divider / `Port` / divider / `Pairing code`.
   Endpoint values are mono **18 sp** `tnum`; the pairing code is **20 sp** in `Spark`, its label in
   `SparkBright`. Below, a timer row: clock glyph + "one sender at a time"
   (**drop the design's fake "rotates in 4:52" countdown unless
   `PairingManager` actually exposes a remaining-TTL value; if it does, show it**).
5. **Status row** — pulsing `Live` dot 7 dp + "Listening · no account, nothing
   uploaded" **16 sp** `OnSurfaceSoft`. When `networkReady` is false, show the existing
   `pair_waiting_network_*` copy instead.
6. Focusables: **`Rename TV` and `Settings`**, styled per §3, in that order, with
   `Rename TV` taking initial focus. **Do not add the design's "SIMULATE A PHONE
   CONNECTING" button — it is a prototype affordance, not a product feature.**

   > **"Show code bigger" and the enlarged-code mode it drove are gone.** The
   > design gave the pair screen a full-screen 96 sp code behind its own modal —
   > a scrim, a Back claim, a focus handoff and a second copy of the same four
   > digits, for a card that already renders them at 20 sp in `Spark` two feet
   > from the QR. Nothing else on the screen was reachable while it was up.
   >
   > `Settings` replaces it, and that is not a swap of equals: with no phone
   > paired the shell routes to **this** screen and never to Idle, so before this
   > change Settings was unreachable on a factory-fresh TV. Opening it takes the
   > pairing surface down and leaving it puts one back — a code may never be live
   > while something else is rendered over it, which is the rule
   > `PairingManager.closeSurface` exists to enforce.

**Right column**: a **248 dp** white `QrCode` card centred in the 272 dp column, with
an 18 dp quiet zone and a 26 dp radius.
Recolour the QR: modules `#0A1533`; **all three** finder eyes' inner squares
`#1240E8`; centre overlay = white rounded square (16 dp radius) holding
`BrandMark` tinted `Primary` over its amber streaks. Below the card, a 14 dp wifi
glyph + `flick://<host>:<port>` mono **14 sp** `OnSurfaceMuted`.

> The eye recolouring requires drawing the three finder patterns explicitly over
> the ZXing matrix. Keep error correction at `M` and keep the payload byte-identical
> — the centre overlay must not exceed the ~15 % the `M` level can lose.
>
> **The lower-left eye was amber `#FFB61E` and that made the symbol undecodable.**
> A binarizer thresholds luma: amber sits at ~0.73 against a 1.0 white plate, above
> the ~0.54 midpoint between the plate and the `#0A1533` ink, so the amber core
> binarized as WHITE. That destroys the finder pattern's mandatory 1:1:3:1:1
> dark/light run, leaves only two of the three patterns findable, and no standard
> scanner — system camera, Lens, ZXing — can read the code. `#1240E8` measures
> ~0.27, comfortably on the dark side. **Amber may never carry a module a scanner
> has to read**; the centre plate is the one place it can live, because the error
> correction already covers that area.

**The payload is v4 and carries the code**: `flick://pair?v=4&h=<host>&p=<port>&c=<4-digit-code>`,
so one scan completes pairing. v3 deliberately left the code out and a scan
authorized nothing on its own; that is no longer true, and the trade is recorded
in `PairingManager.qrPayload` and in `docs/pairing-cast-reliability-spec.md`.

Two consequences for this column, both load-bearing:

- **The symbol dies with its code.** It must be re-encoded on every rotation
  (`CODE_TTL_MS`, 5 min) — a QR built against a consumed code is a QR that fails.
  Re-encoding is a ZXing pass plus a full-card bitmap raster and `ReceiverApp`
  recomposes at 10 Hz, so the payload is **keyed on the code** and the raster
  costs one per rotation, not ten a second.
- **No honest payload, no symbol.** A blank host, an out-of-range port, or the
  `—` placeholder the card shows while the surface is locked or standing by all
  yield a null payload and the QR column is simply not drawn. Drawing a code that
  cannot pair is worse than drawing none; the manual-entry card is the whole
  offer in that state.

The four-digit code stays on screen regardless. The QR is an addition to manual
entry, never a replacement for it — a camera that cannot read the plate is the
case the card exists for.

### 5.2 Connecting / handshake (`HouseLights`)

`ReceiverApp.ConnectingScreen` is now a bare box around the player surface. The veil
and the card belong to `HouseLights` (`ui/components/HouseLights.kt`), the one root
sibling that owns every stage seam, drawn above the unmoved surface. At rest the
handshake is what it was: full-bleed `Canvas` @ 82 % (`VEIL_DENSITY` =
`ScrimVeil.alpha`) over the covered surface, and a centred card 560 dp wide
(`HANDSHAKE_CARD_WIDTH`), `GlassPanel` fill, 26 dp radius, `FlickDimens.PanelPadding`,
`GlassBorder` hairline.

How it arrives and leaves is the room's light:

- **Lights down** (from Idle, Pair, Settings or Error). The outgoing face stays,
  frozen and non-interactive, while a dithered radial aperture closes on the centre
  over `lightsDown()` (560 ms): the corner buttons go first, the clock at ~420–480 ms,
  the mark last. The card enters only once the aperture is at `p ≤ 0.12` (~425 ms),
  into the last light — alpha on `stateEffects()`, a 23 dp rise (`TvRiseCard`) and
  0.97 → 1 scale on `panelSpatial()` — because the glass is 88 % opaque and the last
  pool holds the lit mark and the clock. At full dark the retained face is dropped and
  the curtain snaps to the 82 % veil, invisibly over the black shutter. The aperture's
  edge is a smoothstep ramp from its clear core to full density (8 dithered steps), so
  neither end of the ramp shows a Mach band. A fault while Act I is still closing holds
  the aperture where it reached and dissolves its density uniformly; the aperture never
  moves for a fault.
- **Picture up** (first frame). The card leaves at once and never waits: its semantics
  clear on the first frame, alpha on `crossDissolve()` (400 ms), an 11.5 dp sink on
  `focusSpatial()`; the loader goes with it. After the resync wait (§6.1) and a 120 ms
  lag, the veil lifts uniformly on `filmReveal()` (720 ms).
- **Veil in** (a re-cast over a running film). The veil is seeded at
  `d = max(the dim drawn over the last Active frame, a mid-lift veil)`, so a paused,
  seeking or ended frame never brightens, and rises to 82 % on
  `filmReveal()`; the card enters once 60 % of that travel has landed, alpha on
  `crossDissolve()`.

The headline is a `FlickSwap` (resize on), so it dissolves rather than flips when the
phone's and the film's names resolve. Checking ↔ Preparing and startup retries are
one house stage, so the card and loader keep their phase through them.

Contents: `FlickLoader` — the Material 3 Expressive shape-morph loading indicator
in `Spark`, at `FlickLoaderDefaults.Size` — then `connecting_title` Bricolage 800 /
22 sp, then `connecting_detail` 16 sp `OnSurfaceDim`, separated by `FlickSpace.Md`.
Where a device label is known, the title becomes "<device> is flicking <title>"
using the real session values.

**Re-cut from the numbers above** (450 dp / 32 dp / 27 sp / 24 sp), which were
measured against nothing: the card took **52 % of the 864 dp usable width and ~62 %
of the 486 dp usable height** to say one sentence, its 32 dp padding bypassed the
`PanelPadding` token that exists for this, and its 24 sp *detail* outweighed the
22 sp used for *headings* on every peer surface — `PairScreen`, `SubtitlesPanel`,
`StreamMetricsPanel` — which inverts the hierarchy it was meant to establish. Type
came down and `FlickSpace` did not follow it, per `FlickDimens`' own rule; the loader
keeps `FlickLoaderDefaults.Size` deliberately, because shrinking the copy around it is
what makes the morph the one bold thing on the screen.

**Width answers to the headline, not to the detail.** Seen rendered, the 380 dp that
re-cut produced broke "&lt;device&gt; is flicking &lt;film&gt; (year)" across two lines and left
the year standing on the second by itself: the width had been cut against the *detail*,
which is the shortest thing on the card, while the headline is the longest. 560 dp — 65 %
of the usable width — gives a ~518 dp text column, on which a film name and year of
roughly thirty characters stay on one line; past that the headline wraps inside the name,
which is the acceptable break. Recovering that second title line takes the column to ~40 %
of the usable height, so the card is wider *and* shorter than the one it replaces.
`connecting_detail` carries its own newline rather than trusting the wrapper, which at this
column fits "Press Back" onto the first line and strands "to cancel." on the second.

The hand-drawn 48 dp amber arc this replaces is retired. The loader is the phone's,
so the two apps now speak one vocabulary for the same handshake: see
`sender/.../ConnectingScreen.HandshakeIndicator`. It is **liveness, not progress** —
the handshake sits in one stage for as long as the TV takes to answer, so a
determinate shape would hold still for the whole wait and read as a hang, and
nothing here may imply transcoding, of which this project does none. `FlickLoader`
owns its own reduced-motion fallback (the resting silhouette, held still); call
sites must not wrap it in a second `LocalReducedMotion` branch.

This is only possible because `:receiver` is pinned to material3 **1.5.0-alpha24**
rather than taking the Compose BOM's 1.4.0, which ships `LoadingIndicatorTokens`
but no `LoadingIndicator` composable. Both modules therefore resolve one Compose
runtime (1.12.0-beta01) instead of two that agree by accident.

### 5.3 Playback (`PlaybackScreen.kt`)

**Top chrome** (visible with `chromeVisible`, inside safe area):
- Left: glass pill (`Glass`, pill radius, 7/6/12/6 dp start/top/end/bottom padding,
  `GlassBorder`) — `BrandMark` **14 dp** + `now_playing_from` **16 sp**.
- Right: net-health pill — dot tinted `Live`/`Caution` by RSSI + band, then
  `<band> · <rssi> dBm` mono **14 sp**; then a clock pill showing the real device
  time, mono **14 sp**. Both `Glass`.
- `END SESSION` outlined pill sits below the left pill, `OnSurfaceDim`, 2 dp
  `rgba(255,255,255,.18)` border — focusable.

**Bottom transport panel** (inside safe area, anchored bottom):
`GlassChrome` fill, **26 dp radius**, **21 dp horizontal / 18 dp vertical** padding,
`GlassBorderCool` 1 dp border, the §2d inner top hairline, entering on
`flickSettle` with a 21 dp rise. Three rows, **16 dp** gap:

1. **Header row** — left: eyebrow `NOW PLAYING · DIRECT FILE` mono **14 sp** `SparkBright`,
   then title Bricolage 800 **27 sp** `-0.02em` `#FFFFFF` (ellipsize, single line).
   Right: spec chips, 1 dp `rgba(255,255,255,.2)` border, 8 dp radius, mono **14 sp**
   `OnChrome`. Chips come from **real telemetry only** (§7): resolution + HDR
   class, audio codec + channel count, video codec. **Drop the design's
   "18.4 GB" chip — file size is not available to the receiver.**
2. **Scrub row** — position mono **16 sp** `tnum` `#FFFFFF` (minimum **60 dp** width) /
   `TvScrubBar` / remaining `−mm:ss` mono **16 sp** `tnum` `OnSurfaceDim` (60 dp,
   right-aligned). Track **6 dp** tall, pill; buffered fill `0x42FFFFFF`; played fill
   the §2d amber gradient; knob **12 dp** white circle with an **18 dp** `Spark` @ 34 %
   halo. Ghost/target playhead behaviour and the existing `confirmed …` /
   `target … · snap on release` semantics are **preserved as-is**.
3. **Control row** — `[Subtitles card]  ⟨ back10 · play · fwd10 ⟩  [Stream metrics card]`,
   space-between. Back/forward: **48 dp** square, 17 dp radius, `rgba(148,190,255,.16)`
   fill, `#BEDCFF` @ 34 % border, **24 dp** glyph `#FFFFFF`. Play: **56 dp**, 22 dp
   radius, `Spark` fill, **28 dp** glyph `OnSpark`, amber drop shadow. Side cards:
   13 dp radius, glyph **16 dp** + two-line label (title **16 sp** / state mono **14 sp**);
   when their panel is open they invert to a `Spark` fill with `OnSpark` ink.

**Volume** — the design omits volume, but the app has it and `TransportAndVolumeInteractionTest`
asserts on it. **Keep `VolumeCells`**, restyled: place it in the control row
between the transport cluster and the metrics card, or as a fourth focusable in
the same row. It keeps its `Volume` `contentDescription`.

**Overlays** (unchanged behaviour, restyled):
- Seek burst: 38 % width side wash, radial `Spark` @ 16 %, **48 dp** glyph +
  `±10s` Bricolage 800 **20 sp**, on `tvBurst`'s envelope: 0.7 → 1 in on
  `tvBurstFadeIn()` / `tvBurstScaleIn()` (158 ms), 1 → 1.14 with a fade out on
  `tvBurstExit()` (180 ms, under `SEEK_DELTA_CLEAR_MS`); visibility is owned by the
  held seek (§6).
- Paused chip: at 28 % height, `Glass` pill, **20 dp** `Spark` pause glyph + "Paused"
  Bricolage 800 **20 sp**.
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

**Motion over the film.** Everything here is designed for the 24 Hz pin (§6.1).

- **First arrival.** At the first frame the scrims and both chrome groups start from
  invisible and fade in on `chromeFadeIn()` as the veil lifts, rather than being
  present on frame 0. Telemetry chips already present when the chrome starts entering
  ride its entrance instead of replaying their own pop.
- **Exits into bare film** are `filmExit()`, 250 ms of pure alpha — six vsyncs at
  24 Hz, where `fastStateEffects()` is two and reads as a cut. The top chrome's exit
  is a 10.5 dp sink (`CHROME_EXIT_TRAVEL` × `TvRise`), mirroring the bottom chrome's
  10.5 dp sink, not half its ~130 dp row height: under a 250 ms fade at 24 Hz the
  half-row travel strobes in ~50 px steps. Its fade still clears the film before the
  500 ms scrim lift.
- **The dev HUD hands over to the pills.** The opt-in `MetricsOverlay` leaves on
  `filmExit()` (250 ms). The pills arrive from above and do not cover the plate's
  lower rows.
- **Transport keys do not dim with the bar.** They take no `enabled` from the bar's
  visibility, because the chrome Column's `canFocus` / `clearAndSetSemantics` gate
  already handles a hiding bar. In the transport row only the Ended-without-replay
  play key eases to `DISABLED_ALPHA`.
- **The resting key plays out.** The paused key is a `FlickPresence`; on resume the
  exiting key draws the play state and morphs its glyph while it fades on
  `filmExit()`, instead of vanishing on the frame playback resumes.
- **FINISHED and the buffering plate** are `FlickPresence` overlays: they fade in on
  `chromeFadeIn()`, fade out on `filmExit()`, keep drawing their last value while they
  leave (a STALLED plate stays STALLED), and leave focus and semantics on the first
  exit frame. The plate's two lines are a `FlickSwap`.
- **Words dissolve, numbers snap.** The eyebrow is a `FlickSwap` whose colour follows
  its word; the position timecode is `InkText`, so its digits snap and only the ink
  eases. The same holds for the net pill's band, the spec chips and the metrics
  panel's stat cells.
- **The heard ring** (built). When a seek lands, one amber ring radiates from the
  playhead: born at 11 dp at alpha 0, about 13 dp and lit by 158 ms, reaching 17 dp
  as it goes by 720 ms on `tvBurstAlpha()`, radius on `tvBurstReach()` (720 ms,
  `chromeFade`), still growing as it fades, a 2 dp `Spark` stroke over a
  `FocusRingContour` edge. It fires only when the confirmed clock reached the target
  latched while the seek was in flight (`seekLandingConfirmed`); the 1.5 s deadline
  and teardown draw nothing. Around an unfocused knob it runs 11 → 17 dp; 17 dp stays
  inside the timecodes and clear of the control row's focus rings below. It must also
  clear the bar's own §3 ring, which around a focused knob occupies out to 14.5 dp
  (8 dp knob + 4.5 dp offset + 1 dp half-stroke + 1 dp contour): a ring fired around a
  focused knob starts at 16.5 dp (`FocusedLandingRingStart`), so its contour begins where
  the §3 ring's contour ends, and travels the same 6 dp to 22.5 dp. No row-below ring can
  be lit while the bar holds focus. At the very ends of the bar it may overhang the
  timecode gap while it fades (a few dp unfocused, about 10 dp focused). It is the only
  spatial motion this pass adds at a landing; three non-spatial ones start on the same
  frame: the eyebrow's dissolve (`FlickSwap`), the position timecode's Spark → White ink
  ease (`InkText`), and, when the seek lands during playback, the dim's slow lift on
  `chromeFadeOut()` (§6.1). Two older spatial motions also move there: when
  the seek was driven with the bar unfocused (from the phone, say) the knob and halo
  collapse on `focusSpatial()` (radius 8 → 6 dp and 13 → 9 dp), and the wave's swing
  rises again on `panelSpatial()` once the clock resumes. Reduced motion draws no ring,
  snaps the knob and keeps the wave flat.

### 5.4 Subtitles panel (new — `ui/screens/SubtitlesPanel.kt`)

Left-anchored above the transport panel, **292 dp** wide, `GlassPanel`, 20 dp radius,
**17 dp horizontal / 13 dp vertical** padding, entering on `flickSettle` with a rise.

- Header: "Subtitles" Bricolage 800 **22 sp** + a focusable close button (**19 dp** square,
  12 dp glyph).
- Track list from **real Media3 tracks** (§7): each row = check glyph
  (`check_circle` when selected, `radio_button_unchecked` otherwise, tinted
  `Spark`/`OnSurfaceFaint`) + label **16 sp** + a mono **14 sp** meta chip showing the real
  format (e.g. `SRT · EMBEDDED`, `PGS · IMAGE`) derived from the track's sample
  MIME. Selected row: `Spark` @ 18 % fill, `SparkLight` text. An explicit **Off**
  row is first.
- Size selector: `SIZE` label + three focusable cells (Small / Medium / Large)
  that drive the Media3 `SubtitleView` fixed text size. Selected cell: `#F2F6FF`
  fill, `#0A1533` ink.

Every row is D-pad focusable per §3. `Back` closes the panel.

**Both side panels arrive AND leave by the same origin wipe** (`TvOriginReveal`).
A TV has no finger, so the origin is the focused control: whichever card summoned
the panel is where it is born, and the circle closes back onto that same card when
the panel is dismissed. There is no `AnimatedVisibility` and no fade in either
direction — a fade over either half would be a second transition and a second
compositing layer, i.e. a full-panel offscreen buffer at 4K, on exactly the frames
the panel is most expensive.

That has one structural consequence worth stating, because it is easy to undo by
accident: **the panel's mount lifetime must outlive its close.** A draw-phase
animation cannot run in a subtree that has already left the composition, which is
why the retreat was dead code before — the parent unmounted the panel on the frame
it was dismissed. Presence is now held by `retainedPanel` and released by the
reveal's `onRetreated` report. Focus does *not* wait for that report: the focus and
semantics gates flip the instant the close starts, so the remote is back on the
transport bar while the panel is still being drawn away. A reveal also reports
itself settled-and-hidden when it composes hidden and is never opened, so every
caller qualifies the report with its own open/closed state.

### 5.5 Stream metrics panel (new — `ui/screens/StreamMetricsPanel.kt`)

Right-anchored above the transport panel, **488 dp** wide, `GlassPanel`, 20 dp radius,
with **17 dp horizontal / 11 dp vertical** padding.

- Header: "Stream metrics" Bricolage 800 **22 sp** + a health pill (`HEALTHY · DIRECT PLAY`
  in `Live`, or `DEGRADED · RECOVERING` in `Caution`) derived from present-tense
  diagnostics: `errorMessage`, `playbackStarted`, `currentlyRebuffering`, `isPlaying`, and
  `bufferedAheadMs`. Show no pill before playback starts; mark degraded for an error, active
  rebuffering, or an actively playing stream with no buffer ahead. Otherwise mark healthy.
  Do **not** use `DiagnosticsSnapshot.status`, which can be stale. The close button remains
  focusable.
- **Throughput histogram**: `THROUGHPUT · LAST 40 s` eyebrow mono **14 sp** + the live value in
  mono **16 sp** `Spark`; 40 bars, 2.5 dp gap, **28 dp** tall, 2 dp top radius, height
  proportional to the rolling peak. Bars below 50 % of peak tint `Caution`, else
  `Spark` @ 85 %. Fed by the new `ThroughputHistory` ring buffer (§7).
- **Stats grid**: 3 × 3, label mono **14 sp** `OnPanelLabel` over value mono **16 sp**. Use only
  real fields: resolution, codec (from `videoMimeType`), frame rate, bitrate,
  buffer ahead, probe latency, dropped frames, decoder name, transport
  (`TCP · <wifiBand>`). Warn-coloured (`Caution`) when degraded; dropped-frames
  zero shows `Live`.

The Mb/s readout **snaps** between samples. It is a measurement, and a
measurement that travels between values is a fabricated one — and a roll keyed on
a ~1 Hz sample is an animation running continuously over a live decoder. Only the
bounded histogram gauge animates, on `stateEffects()`, so a bar can never
overshoot into a throughput the receiver never measured; an empty slot means "not
measured" and never grows up out of the floor.

This panel is the *tasteful* read; the existing dense `MetricsOverlay` dev HUD
stays as the separate opt-in Settings toggle (design brief Part 3 item 10).

### 5.6 Idle, Error, Settings, MetricsOverlay

Not drawn in the design file — **re-skin to the new tokens, keep structure and
behaviour**. Specifically: new palette, Bricolage/Geist/Geist Mono, amber
focus rings, glass panels, safe-area anchoring, and the §1a type floors. Keep
every string listed in §0 and preserve back handling. Idle gains the design's ambient blue radial wash + a pulsing `Live`
dot; Error keeps its amber (not-serving) vs crimson (unreachable) split.

**Error is still.** Its whole entrance is a single alpha fade on `stateEffects()`
— no rise, no spring, and the status light on the phone glyph is held rather than
breathing. A card that springs into place under a diagnosed fault reads as an app
being playful about a failure, and the two diagnoses are already separated by
accent, copy and action labels. It is also the surface `ErrorScreenFocusTest` and
two `TvSafeAreaContainmentTest` cases mount and wait for idle on.

Settings begins D-pad focus on **Device name**, its first actionable row. Its
fixed title and the currently focused control, including the detached ring,
stay inside the viewport reserve; preceding non-focused rows and diagnostic
logs remain ordinary scroll context.

**The paired phones are a drill-in.** The column carries one `Paired phones` row —
title, the count as its summary, `Manage` as its disclosure — which opens a
second pane listing every phone with its date (or `settings_paired_undated`; a
v2 record genuinely has none and the app must not invent one) and two keys,
`Rename` and `Forget`. `Back` closes the run and lands focus on the row that
opened it. Rendering the phones inline is what this replaces: that list grows
without bound in a column that also has to reach Done.

It is a **pane swap**, not a `TvOriginReveal`. The reveal is for a disclosure —
the diagnostics log, born at the row that summoned it and pulled back into it —
and it may contain no focusables while its wipe runs. A phone list is a place: its
own heading, its own bounded scroll, its own way out, two focus targets per row.
Each pane carries its own `FocusBeaconHost` for the §3a reason — the ring may not
fly between unrelated regions, least of all across a surface that is itself
sliding — and the drill takes the same from-the-right direction and sixth-of-axis
travel the shell uses to enter Settings, so it reads as that gesture one level down.

`Rename TV`, Settings' `Device name`, and every paired-phone `Rename` open the
same modal, single-line text editor with the current name selected. It requests
the Android TV text keyboard, word capitalization, a centred IME, and a Done
action; voice input belongs to Gboard and requires no receiver microphone
permission. Saving canonicalizes the input to the wire label contract and caps it
at 80 Unicode code points without splitting surrogate pairs; a blank canonical
name cannot save, while Cancel and Back leave state untouched. A TV save persists
the name and refreshes NSD. Pair-screen rename closes the live pairing surface
before opening the editor and reopens it on exit, so no code or confirmation is
hidden under the modal. A phone save goes straight to `PairingManager.rename`
and **changes the label only** — the key id, key, pairing date, and live session
remain untouched. It deliberately does NOT route through `ControlServer` the way
`Forget` must: there is no session to revoke, and that path takes the manager
monitor before `serverLock`.

`Forget` keeps its two-press confirm, armed per key id and disarmed the moment
the D-pad leaves it. `Forget all phones` stays on the main column and stays
hidden below two phones. With **no** phones paired — a real state now that
Settings is reachable from the pair screen — the `Paired phones` row is not
focusable at all: a control that opens an empty list does nothing, which is the
same argument that hides `Forget all phones` at zero.

---

## 6. Motion

TV motion is **settling**: things arrive and come to rest with weight. `FlickTvTheme`
is `androidx.tv.material3.MaterialTheme` only — no `MaterialExpressiveTheme` wraps it.
The Expressive motion scheme's spring stiffnesses are transcribed once into
`FlickMotion` (`ui/theme/Motion.kt`) and every spec comes from there. **No call site
outside `Motion.kt` writes a `spring(...)`, `tween(...)`, `keyframes {}`, `snap()` or an
animation duration**. The one sanctioned exception is `RollingGlyphs`'
`SizeTransform(clip = true) { _, _ -> snap() }`: a size transform that must never
animate, because the monospaced cell keeps its width and the clip cuts the outgoing
glyph at the cell edge. A `delay` that times motion — a hold before an exit, a lag
behind a veil — takes a named `FlickMotion` constant. Dwell timers (the quality
flourish, the seek-delta hold, the chrome auto-hide, the pairing success hold, the
clock and countdown ticks) are UI policy, not motion specs, and live where their state
lives. See `design-tokens.md` §6 for the shared vocabulary and the sender/receiver damping bias.

The one reduced-motion idiom is `FlickMotion.orSnap(reducedMotion, spec)`, mirroring the
sender's `Motion.orSnap`. The reduced branch of an `AnimatedContent` is
`FlickMotion.cut()` (`fadeIn(snap())` / `fadeOut(snap())`), not `EnterTransition.None` /
`ExitTransition.None`: AnimatedContent composes and draws the incoming and outgoing
children together for one frame, so the incoming child must hold at alpha 0 for that
frame, and a `None` / `None` swap would draw both at full opacity. It takes no size
transform, except `RollingGlyphs`' clipping cell, which passes the snapping
`SizeTransform(clip = true)` sanctioned above. The reduced branch of an
`AnimatedVisibility`, which has a single child, is `EnterTransition.None` /
`ExitTransition.None`, or `fadeIn` / `fadeOut` over `orSnap(reducedMotion, spec)` where the
visible spec is a plain fade (the chrome, the HUD, the seek burst). Specs used inside a `transitionSpec` are resolved in composition and
captured, because the `@Composable` accessors cannot be called there.

| Design / use | Token |
|---|---|
| `tvRise` (panel entrance) | `panelSpatial()` + a `graphicsLayer` rise of `FlickMotion.TvRise` (21 dp) |
| chrome / panel exit | playback chrome: alpha on `filmExit()` (250 ms), and both chrome groups sink `CHROME_EXIT_TRAVEL` × `TvRise` (10.5 dp) toward their own edge on `focusSpatial()` (the top group's entrance is still its full row height on `panelSpatial()`); other glass panels: `glassPanelExit()` — `focusSpatial()` over half the entrance travel, alpha on `fastStateEffects()` |
| panel reveal / retreat | `TvOriginReveal` — `panelSpatial()` in, `focusSpatial()` back out, the SAME wipe run backwards onto the same origin |
| `tvBurst` (the design's seek flash) | its 0.7 → 1 → 1.14 scale-and-fade shape, but not its fixed 0.72 s lifetime: the held seek owns visibility, so the seek burst runs on the "seek burst in / out" row below; the 720 ms keyframes live on only as the heard ring's `tvBurstAlpha()` |
| seek-step impulse | snap to 1 → `flickSettleSpatial()` back to 0, one kick per accepted protocol step |
| `tvPulse` (live dot, 1.9 s) | infinite pulse in `LiveDot`, bound to real state only |
| indeterminate loading (handshake, rebuffer) | `FlickLoader` — material3's Expressive shape morph. `tvSpin` is retired with the arcs it drove |
| chrome fade | `chromeFadeIn()` / `chromeFadeOut()` |
| focus ring / scale / beacon travel | `focusSpatial()` |
| colour, alpha, selection fill | `stateEffects()` |
| seek reconcile | `syncSpring()` — now a real spring, so a held D-pad seek retargets instead of stuttering |
| D-pad centre/Enter press confirmation | `pressConfirm()` — 90 ms `ChromeFade`; scale 0.98 when unfocused or `PRESS_FOCUSED_SCALE` 1.02 while focused, with pressed fill feedback |
| the room's light closing (Act I) | `lightsDown()` — 560 ms `CrossDissolve`, aperture only, no film visible; rest rate |
| the room's light opening (launch, lights up) | `pictureUp()` — 640 ms `CrossDissolve`, aperture only, no film visible; rest rate unless the resync hold is cut short (lights up) or the panel is not at rest (launch, never held) |
| the house-lights veil over a film | `filmReveal()` — 720 ms `CrossDissolve`, at most 10.0 % of its span per 24 Hz frame |
| the playback state dim | `PlaybackDim.dimSpec` — `chromeFadeIn()` to darken or lift promptly, `crossDissolve()` to settle on Ended, `chromeFadeOut()` to lift off a seek (§6.1) |
| card-sized alpha over a film (the handshake card's entrance on a re-cast, its exit at the first frame); the fault dissolve | `crossDissolve()` — 400 ms |
| the handshake card's exit on a cancel, a fault or a Rest seam | `fastStateEffects()` — it leaves over the closed curtain, not a film |
| an overlay leaving into bare film | `filmExit()` — 250 ms `ChromeFade`, six vsyncs at 24 Hz |
| a small overlay's presence | `presenceIn(overFilm)` / `presenceOut(overFilm)` — `chromeFadeIn()` / `filmExit()` over a film, `stateEffects()` / `fastStateEffects()` elsewhere |
| the heard ring's envelope | `tvBurstAlpha()` — 0 → 1 by 158 ms → 0 by 720 ms; radius on `tvBurstReach()` (720 ms, `chromeFade`), still growing as it fades — about 13 dp by 158 ms |
| seek burst in / out | `tvBurstFadeIn()` / `tvBurstScaleIn()` (158 ms) and `tvBurstExit()` (180 ms, below `SEEK_DELTA_CLEAR_MS`) |
| reduced motion | `orSnap(reducedMotion, spec)` around every finite spec; `cut()` for an `AnimatedContent` swap |

The helpers that carry these, all in `ui/components/`:

- `HouseLights` — the curtain and the handshake card for every stage seam (§5.2, §6.1).
- `DisplayCadence` — passive observation of the HDMI mode switch the refresh-rate pin
  causes, and the bounded resync wait (`stageHold`, `awaitStageWindow`).
- `FlickPresence` — an overlay shown while a value is non-null: rises in, sinks half
  as far out, keeps drawing its last value while leaving, per-draw alpha with the layer
  dropped once settled, focus and semantics gone on the first exit frame.
  `flickRevealEnter()` / `flickRevealExit()` are its expanding standby-only sibling for
  warning lines.
- `FlickSwap` — an in-place word or glyph exchange; the exit leads so two words are never
  legible together for more than one 24 Hz frame.
- `InkText`, `InkIcon`, `CrossfadeIcon` — colour eased in the draw phase, so a tint
  change repaints without recomposing.
- `RollingGlyphs` — the pairing code's per-glyph roll, shared by the idle clock.
- `LocalShellRetained` — true while a standby or error face is retained under the
  lights-down curtain; the face freezes its loops and requests no focus.
- `LocalShellInteractive` — false while a shell face is retained or on its way out.
  Every focusable control on a face reads it where it takes focus, because the host's
  `canFocus = false` does not cross a scroll container's focus group; `FlickTvButton`
  (and so `FlickTvRow` and `FlickTvIconButton`) already does.
- `PlaybackDim` (`ui/theme/`) — the single source of the playback dim, its cause and
  which ease a change takes.

`chromeFadeOut`'s 500 ms still governs the scrim, which deliberately lags the chrome
out. The playback chrome's own fade is `filmExit()`, 250 ms, so it has cleared the film
before the scrim lifts; its 10.5 dp sink stays a spring.

`focusPop`'s curve is retired — focus is a spring so that a held D-pad retargets it
mid-flight.

### 6.1 The performance fence (playback stack)

The TV is decoding 4K Dolby Vision while this UI composes. Inside `:receiver`:

- no `Modifier.blur` / `RenderEffect`, anywhere;
- no infinite or ambient animation while the decoder runs — ambience belongs to Idle,
  where the player surface is `Hidden`, and the idle wash drift is the **one** deliberate
  ambient loop in the system;
- per-frame values are read inside `drawBehind` / `graphicsLayer` lambdas, never in
  composition;
- chrome motion is `graphicsLayer` transforms, never layout offsets — the full-screen dim
  plus the two scrims are the entire animated-layer budget over the video;
- `sparkShadow` elevation and colour are never animated.

**The 24 Hz step budget.** While a film is on screen the window is pinned to the film's
cadence, so a frame is 41.67 ms and anything composed in `PlaybackScreen` or drawn over
the film is sized for that:

- a full-screen veil over a film (the `HouseLights` veil) uses `filmReveal()` (at most
  10 % of its span per frame); card-sized alpha uses `crossDissolve()`. Exception,
  deliberately kept: the playback state dim (`PlaybackDim.dimSpec`) darkens fast so a
  pause or seek reads at once. Darken and LiftPrompt use `chromeFadeIn()` (200 ms, up to
  ~44 % of its span in one 24 Hz frame, about 0.15 alpha toward the 0.34 paused dim);
  Ended settles on `crossDissolve()` (400 ms, ~18 %), and a lift off a seek uses
  `chromeFadeOut()` (500 ms, ~19 %). It is one uniform alpha rect with no edge, and every
  dim change already ran on `chromeFadeIn()` before this motion pass;
- an overlay leaving into bare film uses `filmExit()`; an in-place swap whose
  replacement fades up in the same spot may exit on `fastStateEffects()`. Exceptions:
  the blind-seek burst exits on `tvBurstExit()` (180 ms, under `SEEK_DELTA_CLEAR_MS`),
  and the band cards on `chromeFadeOut()` (500 ms) plus their scale-out;
- no new moving edge, wipe or aperture over a film, and no new layout-phase animation —
  size transforms are null there, except `clip = false` on the handshake card above the
  veil. Known pre-existing exceptions, deliberately kept and each checked in the 24 Hz
  review: the side panel's `TvOriginReveal` circle wipe (§5.4/§5.5) and its
  `animateBounds(PanelTravel)` glide and resize on a panel swap;
  `animateContentSize(panelSpatial())` on the top net-pill/clock row and on the transport
  spec-chip row; and the band cards' (quality flourish, orientation hint, silent-audio
  notice, band notice) `scaleIn` 0.96 / `scaleOut` 1.02 on `flickSettleSpatial()`; and
  the chrome groups' `TvRise` rise and top slide (graphicsLayer transforms). Moves added
  on purpose by the motion pass, each also checked at 24 Hz: the FINISHED chip's
  `FlickPresence` rise (`TvRiseCard`, 23 dp on `panelSpatial()` in, an 11.5 dp sink on
  `focusSpatial()` out, a `ModulateAlpha` layer dropped once settled), and the heard
  ring's radius, 11 → 17 dp on `tvBurstReach()` (16.5 dp start around a focused knob,
  same travel), a 2 dp stroke inside the transport glass (§5.3);
- alpha on new overlays larger than a small control (`FlickPresence`, the resting key)
  is per draw op (`CompositingStrategy.ModulateAlpha`) with the layer dropped once
  settled. Exception: the two chrome groups (TopChrome and the 864 dp BottomChrome), the
  band cards and the blind-seek burst fade through `AnimatedVisibility` `fadeIn` /
  `fadeOut`, whose layer uses the default strategy and so composites offscreen while
  alpha < 1. The seek burst's layer is `matchParentSize` on the playback root, so it is
  the one full-screen offscreen layer over the film: pre-existing, and alive only for its
  `tvBurstFadeIn()` (158 ms) and `tvBurstExit()` (180 ms). No blur, `RenderEffect` or
  shader over the film.

**Stage covers are siblings.** The film surface is never moved, faded, clipped or
wrapped: both `playerSurface()` call sites sit inside unchanged `when (stage)` branches,
and every cover — the veil, the aperture, the handshake card, the retained standby — is
drawn by `HouseLights` or the non-video shell, composed after them. The aperture is
drawn only with no film visible (launch, Act I, lights up), and lights up opens it only
after the resync hold, so it normally runs at the rest rate. A key, the cap or an unseen
switch that ends that hold first lets it run at the film's cadence or into the switch's
blank; launch is never held and runs at whatever rate the panel is in. Over a film the
veil is always uniform.

**The resync wait is bounded.** The only wait anywhere is `HouseLights` holding its
pixels while a non-seamless HDMI mode switch resyncs: until the observed change + 500 ms,
or 500 ms for a change not yet seen, never past `STAGE_HOLD_CAP_MS` (2.5 s) from the
seam, and any key ends it. The pin does not land on the `Active` frame: the film's rate
reaches the stage up to one 2 Hz snapshot period later, so a reveal first waits up to
`RATE_KNOWN_WAIT_MS` (600 ms, key-cancellable) for a known rate, the 500 ms expect window
runs from when it is known, and the 2.5 s cap includes that wait. It never holds state — the stage, the first frame, `loadReady`,
the 18 s deadline and every key are untouched — and it logs `[stage] hold kind=…
rateWaitMs=… waitedMs=… reason=…` each time.

At most **one** `rememberInfiniteTransition` per screen, and none on a surface an
instrumentation test mounts and waits for idle on.

The playback screen's single one is the **rebuffer loader**, and it is a deliberate
exception to the "no infinite animation while the decoder runs" line: it exists
only while `PlaybackPhase.Buffering` — real state, not ambience — and a frozen
indicator during a rebuffer reads as a hung app on the one app whose entire claim
is that it does not stall. No instrumentation test mounts the buffering phase.

It still pays the fence in full. material3's `LoadingIndicator` drives its
`Animatable`s from a single `LaunchedEffect` and reads their values inside a
`ContentDrawScope` lambda, against a `Path` and a float array hoisted out of the
draw — verified by disassembling `LoadingIndicatorKt` in 1.5.0-alpha24, because
the fence is a hard constraint and an upstream component's word is not evidence.
So a rebuffer repaints one 40 dp indicator and recomposes nothing, exactly as the
hand-drawn arc did. Call sites still give it `Modifier.graphicsLayer()`: the state
plate carries no layer of its own, so without one the morph re-records the
buffering card's type — and the scrims above it — every frame of the state with
the least headroom in the app.

Every infinite/ambient animation must be skipped when `rememberReducedMotion()`
is true — the static end-state still reads correctly. Finite springs snap on their own at
a zero animator scale; anything looping or hand-driven (the idle drift, the staged
entrances, the pairing-code roll) carries an explicit `LocalReducedMotion` gate.

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

The launcher asset follows the adaptive-icon contract: Flick supplies full-bleed
colour layers and the platform launcher owns the visible shape. Circle, squircle,
rounded-square, square, and OEM treatments such as Samsung One UI therefore mask
the same artwork natively instead of clipping or rescaling a smaller shape baked
into the background.

- `res/mipmap-anydpi-v26/ic_launcher.xml` uses the full-bleed
  `@color/ic_launcher_background`, `ic_launcher_foreground`, and the dedicated
  `ic_launcher_monochrome`. The receiver canvas is `#04070F`; the sender canvas
  is `#F2F6FF` in the light resource set and `#171D2E` in the night set. The
  receiver manifest points both icon roles at `@mipmap/ic_launcher`; the sender
  uses `@mipmap/ic_launcher` for `android:icon` and `@mipmap/ic_launcher_round`
  for `android:roundIcon`.
- `res/drawable/ic_launcher.xml` is the flat fallback for tooling paths. It is a
  `layer-list` of the same full-bleed colour and foreground, so it cannot drift
  from the adaptive artwork and does not bake in a launcher mask.
- `res/drawable/banner.xml` remains the 320 × 180 dp Leanback banner: the mark
  plus the real "flick" wordmark drawn as paths on `#04070F`, with the amber
  streaks legible at TV-launcher size. `android:banner` continues to point to it.
- `res/values/themes.xml` keeps window, status, and navigation backgrounds on
  `#FF04070F`.

Keep the APK artwork vector and self-contained; no raster launcher assets.

### 8a. Foreground geometry and safe zone

Adaptive foregrounds use a 108 × 108 canvas centred at (54, 54). Android's
guaranteed safe region is the central 66-unit circle, radius 33. Background colour
bleeds beyond every possible mask; only the painted foreground geometry needs to
fit inside that safe region.

The full-colour mark keeps the blue play triangle `#1240E8` and three amber
`#FFB61E` motion streaks on the 64-unit source grid. Their wide composition is
intentional: about 51.2 × 39.0 after scaling, close to Material's 52 × 36 wide
keyline rather than a square glyph.

- `:receiver` maps the source grid with scale `0.90625` and translation `(25,
  25)`, placing its centre exactly at (54, 54). Its painted bounds are about
  **51.21 × 38.97**; the triangle tip is the farthest point at **25.84** from
  centre, leaving **7.16** units inside the radius-33 safe boundary.
- `:sender` bakes 1.1× into its path coordinates, so its equivalent source-grid
  scale `0.90625` is applied by a group scale of `0.8238636` and translation
  `(9.5113636, 9.5113636)`. Its painted width is **51.20**; the middle streak's
  left cap reaches about **25.67** from centre, leaving about **7.3** units.

Stroke widths, round caps, and joins travel with each group transform, preserving
the mark's internal proportions.

### 8b. Themed icon

`<monochrome>` points to a dedicated solid triangle rather than the colour
foreground. Android preserves source alpha while replacing colour, so reusing the
foreground would retain the streaks' 0.5 / 0.85 alpha as ghosted theme-colour
bands. The one-colour triangle drops those bars, is re-centred on (54, 54), and is
identical in both modules.

The source triangle's painted bounds, including its round stroke, are 42.55 ×
49.45. Both modules apply scale `48 / 49.45 = 0.97067745` and translation
`(1.5834177, 1.5834177)`, producing a centred **41.30 × 48.00** themed mark. Its
conservative maximum radius is **29.60**, leaving **3.40** units inside the
radius-33 safe boundary before the launcher masks and re-tints it.

### 8c. Play listing asset

The Play Store listing icon is separate from the APK resources: a 512 × 512
32-bit PNG uploaded in Play Console, not extracted from the adaptive icon. Export
it as a full square filled with the app's canvas colour and centre the mark using
the current launcher geometry. Do not add a transparent custom silhouette; Play
owns and applies the listing mask independently.

---

## 9. Definition of done

- `:receiver:assembleDebug` and `:receiver:testDebugUnitTest` pass.
- Existing `androidTest` sources still compile and their asserted strings survive.
- No `FlickColor` reference to a removed token anywhere in the tree.
- No text below 14 sp; retain the 18 / 16 / 15 sp reading hierarchy, with 16 sp as
  the default body size.
- Every interactive element reachable by D-pad, with the amber focus ring visible.
- All outer chrome inside `rememberTvSafeAreaPadding()`.
- No fabricated telemetry.

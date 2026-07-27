# Flick Material Expressive design tokens — canonical

Single source of truth for the shared visual language of both apps. The visual source is
[`references/material-expressive-option-2.png`](references/material-expressive-option-2.png),
the user-selected paired phone/TV synchronized-scrub frame (SHA-256
`12ec2bf743202bfee3c87f77ce6ea96009fc9825cd23454a4c9813654a8d692a`). This file—not
`flick-design-system.html`—defines implementation decisions. `:sender` and `:receiver`
share semantic names, color jobs, type scale, and motion intent; they do **not** share
Compose theme or component types.

Design thesis: **warm editorial direct-play.** The phone is a tactile, personal local-film
remote: warm ivory paper, dark plum ink, cream containment, asymmetric media hierarchy,
and a dominant lower-third scrubber. The TV is an uncluttered violet-black cinema canvas.
Coral means phone user action and optimistic target; cyan means live LAN/sync; the receiver's
amber `Spark` ring means TV D-pad focus. Green means serving; restrained gold means verified
premium media. These jobs never swap.

## Selected reference rules

- Use local video frames as content imagery. Never imply cloud upload, accounts,
  mirroring, or transcoding.
- The cyan thread is a short-lived synchronization cue between devices, not a decorative
  permanent connector or a progress value.
- Target and network-confirmed scrub positions are distinct semantic values. During a
  lag, communicate both with labels/semantics as well as color and ring/fill treatment.
- The TV's cinematic image remains visually brightest. Chrome appears only when useful;
  exactly one TV action owns focus whenever chrome or a modal is visible.
- The reference illustrates a hero, not a license to put a large image, cyan glow, or
  asymmetry into errors, advisories, diagnostics, or every list row.

---

## 1. Color

### 1.1 TV dark — "the cinema" (TV always; phone dark theme)

| Token | Hex | Use |
|---|---|---|
| `canvas` | `#0B0912` | fixed TV playback bed — violet-black, **never `#000`** |
| `surface` (base) | `#15111D` | non-media TV background |
| `surfaceRaised` | `#211B2B` | contained TV cards, sheets, and chrome groups |
| `surfaceRaisedAlt` | `#191521` | quiet secondary containment |
| `glass` fill | `rgba(27,21,38,0.72)` + API-gated blur | TV chrome over film only |
| `glassBorder` | `rgba(255,255,255,0.14)` | 1px hairline on glass |
| `onSurface` | `#F6F0F4` | primary 10-foot text/icons |
| `onSurfaceDim` | `#C8BFCA` | secondary text |
| `onSurfaceFaint` | `#988E9B` | captions, mono labels |
| `outline` | `#403747` | dividers, unfocused borders |
| `outlineHairline` | `rgba(255,255,255,0.07)` | card borders |

### 1.2 Phone light — "the film desk" (Material-You-friendly)

| Token | Hex | Use |
|---|---|---|
| `surface` (base) | `#FFF8ED` | warm ivory page, **not clinical white** |
| `surfaceRaised` | `#FFFCF6` | paper-raised media and action cards |
| `surfaceTonal` | `#F6E8D3` | cream tonal fields, chips, and asymmetric hero containment |
| `onSurface` | `#3F3037` | dark-plum primary ink |
| `onSurfaceDim` | `#705B62` | secondary ink |
| `onSurfaceFaint` | `#9A8287` | captions and supporting metadata |
| `outline` | `#E6D4C0` | warm hairlines/dividers |
| `sparkOnLight` | `#C94B3D` | accessible coral text/icon on ivory; target thumb may use Spark fill |
| `linkOnLight` | `#007F91` | accessible cyan text/icon on ivory |

### 1.3 Brand accents — the split (both themes)

| Token | Hex | Gradient | Job |
|---|---|---|---|
| **Spark** | `#FF6B57` | `linear(120°, #FF8D7D → #FF6250)` | action and **optimistic target**: play button, CTA, filled playhead/thumb |
| `sparkLight` (tint) | `#FF8D7D` | — | warm target bloom and contained emphasis |
| `sparkSoft` | `#FFD0C8` | — | target-supporting detail on dark only |
| **Link** | `#41E5F2` | — | live LAN, sync shimmer/thread, and pairing; receiver D-pad focus uses its amber `Spark` ring |

### 1.4 Semantic

| Token | Hex | Meaning |
|---|---|---|
| `live` | `#3A9B62` | serving / healthy; use `#277A4B` for small text on ivory |
| `caution` | `#B87824` | 2.4 GHz · weak signal · battery nudge |
| `trouble` | `#C9314D` | unreachable / failed (**crimson is never Spark**) |
| `info` | `#41E5F2` | sync, pairing, tips (== Link) |

### 1.5 Premium sheen (quality badges only — DV/HDR — never UI chrome)

`linear(115°, #D6A34E → #FAE7B8 45% → #B9822C)`, badge text `#3D2B13`. It is a small,
restrained verified-quality treatment for actual DV/HDR metadata only; it never becomes a
button, focus ring, app background, or fabricated quality claim. HDR10 is the outline variant
(`1px rgba(214,163,78,.62)`, text `#FAE7B8` on TV).

### 1.6 Material You (phone only)

On Android 12+, phone dynamic color may influence low-emphasis tonal containers and media ambience,
but warm ivory, plum ink, Spark action/target, Link sync, and serving green remain anchored.
Never use dynamic color to obscure target-versus-confirmed meaning. The TV never re-tints; it
holds the fixed cinema palette. Poster ambience behind TV transport stays at or below 24% opacity
so HDR video remains brightest.

### 1.7 Semantic role mapping (same intent, platform-native implementation)

| Semantic job | Sender Material Expressive mapping | Receiver TV Material mapping |
|---|---|---|
| page/cinema base | warm-ivory `background` / `surface` | violet-black `background` / retained player surface |
| contained content | cream `surfaceContainer` / `surfaceContainerHigh` | raised `surface` / `surfaceVariant` |
| primary action and target | Spark `primary`; Spark filled thumb/play control | Spark primary/selected treatment, never focus |
| live LAN/sync | Link secondary/container detail and explicit text | Link pairing and sync shimmer; Spark is the focus ring |
| serving health | `tertiary`/custom semantic role | custom semantic role, not a focus cue |
| premium media | custom metadata badge only | custom metadata badge only |
| failure/caution | Material error/custom advisory roles | TV error/custom advisory roles |

Do not make a literal RGB copy of phone Material Expressive roles in TV Material. The semantic
job is shared; component API, focus behavior, and containment stay form-factor-native.

---

## 2. Typography

Faces (bundled `res/font` binaries in both modules; **no downloadable-font provider, no
fallback family** — a device with a lagging Play Services font catalogue would silently render
the platform default and the design would just look wrong, with no error):
- **Display / titles / wordmark:** Bricolage Grotesque, weights 700/800.
- **UI / body / labels / buttons:** Geist — 600/700/800 on the phone, 500/600/700 on the TV.
- **Timecode / telemetry / pairing codes:** Geist Mono, **tabular figures mandatory** —
  `TextStyle(fontFeatureSettings = "tnum, zero")`. Digits must not shimmy while the clock runs,
  and the slashed zero keeps a pairing code read off the TV from being typed in as `O`.

All three are SIL OFL 1.1; the licenses ship at `<module>/src/main/assets/licenses/`.

The two scale tables below are the original design intent, not the shipped numbers. The
implemented receiver scale lives in `receiver/.../ui/theme/Type.kt`: `FlickType` helpers clamp
their inputs at **14 sp**, `FlickType.body()` defaults to **16 sp**, and the shipped reading hierarchy
uses 18 / 16 / 15 sp rather than a blanket 24 sp floor. No receiver weight is below 500.

### Phone scale (arm's length)
| Role | size/line | weight | tracking |
|---|---|---|---|
| Title | 28/34 | 700 | −2% |
| Screen heading | 20/26 | 600 | — |
| Body (file names, advisories) | 15/22 | 400 | — |
| Caption (metadata) | 12/16 | 500 | — |
| Scrub timecode | 17 mono | 600 | tabular |

### TV scale (ten feet) — implemented receiver defaults
| Role | ~size | weight | tracking |
|---|---|---|---|
| Full-screen/display title | 40sp (`displayLarge`) | 800 | −2% |
| Playback chrome title | 27sp (`headlineLarge`), one line | 800 | −2% |
| Section / dialog | 22sp (`headlineMedium`) | 800 | −2% |
| Body | 18 / **16 default** / 15sp | 500–700 | +0.5% |
| Mono labels / timecode | **14sp minimum**; playback timecode uses 16sp | 500–600 | tabular |

10-ft rule: preserve the deliberately stepped hierarchy, at least Medium weight, and
high-contrast on scrim; do not reintroduce the former 24sp blanket clamp.

---

## 3. Shape, hierarchy & spacing

- **8pt grid, 4pt sub-grid.** Spacing ramp: `4 · 8 · 16 · 24 · 32 · 48 · 64`.
- Corner tokens: `sm 12 · md 18 · lg 24 · xl 32 · hero 40 · full 999`.
- Phone hierarchy is intentionally asymmetric: a compact media still can sit above a generous
  title/metadata block, while an off-center cream tonal contour creates the hero field. This is
  one large, purposeful containment gesture per screen—not arbitrary per-row shapes.
- Media cards use `lg` upper corners and `md` lower corners; frame previews use `md`; ordinary
  rows and advisories use `md`; live/status and scrub tracks use `full`. No destructive cutouts
  or non-rectangular touch regions are required.
- Phone: single column, thumb-first. Primary transport and scrubber occupy the **bottom 34%**.
  Minimum touch target **48dp**; scrubber thumb hit area **56dp** even when its visible target is
  smaller.
- TV: 12-column rhythm inside `rememberTvSafeAreaPadding()`'s **5% overscan-safe inset**
  (48×27dp at a 960×540dp canvas; viewport-relative at other sizes). Video is full-bleed; all
  chrome/text stays inside that safe area, and an outermost focusable also keeps
  `FlickDimens.FocusRingReserve` (**10dp**) inside it for the detached ring. TV containers stay
  low and wide rather than copying the phone's asymmetric card geometry.

---

## 4. Elevation, material & focus

- Phone material: `e0` ivory page · `e1` cream tonal contour · `e2` near-white raised media/frame
  preview with a soft warm shadow (`0 8 24 rgba(72,47,36,.16)`). Avoid fake glass over the paper
  remote; opacity is not a substitute for hierarchy.
- TV material: `e0` retained film/cinema canvas · `e1` violet translucent bottom scrim · `e2` a
  restrained `rgba(27,21,38,.72)` chrome panel with 1px `rgba(255,255,255,.14)` border. API-gated
  blur is optional; legibility cannot depend on it.
- Scrim is a soft transparent-to-`rgba(11,9,18,.82)` gradient, never a hard bar.
- **TV focus (no hover):** `scale 1.06 + detached 2dp Spark-amber ring`, drawn **4.5dp outside**
  the element so it never changes layout. A focused amber-filled control uses a white ring for
  contrast. **Selected** uses a Spark tint without a ring, so selection and focus never blur.
  Disabled is 38% opacity. Every TV state defines an explicit D-pad order and exactly one focused
  element; the outermost control reserves 10dp for the painted ring and focus scale. The
  reference's central play/pause is focused only for playback chrome; other states select their
  own single initial actionable target.

---

## 5. Iconography

24dp grid, **1.8px stroke**, round caps/joins. Filled counterparts only for play/pause at ≥48dp
transport size. On TV, `TransportCluster` uses 24dp skip glyphs in 48dp targets and a 28dp
play/pause glyph in its 56dp primary target. Use the rounded Material `Replay10` / `Forward10`
glyphs for TV seeking rather than drawing numerals into custom arrows; the phone may retain its
compact tactile skip treatment. Set: `play, pause, previous, next, volume, cast,
qr-pair, wi-fi, hdr/dv, private(lock), settings, metrics`. The **brand mark** = rounded play
triangle + 3 motion streaks (streaks drop below 24px; triangle alone survives to 16px).

---

## 6. Motion — "flick & settle"

Motion is **spring-first**. Both modules read one vocabulary from
`MaterialTheme.motionScheme` (Material 3 Expressive): the phone through
`MaterialExpressiveTheme` in `sender/ui/theme/Theme.kt`, the TV through the same
`MaterialExpressiveTheme` wrapped *outside* `androidx.tv.material3.MaterialTheme` in
`receiver/ui/theme/Theme.kt`. **No interactive motion hand-writes a duration or a
`spring(...)` at a call site** — hand-picked durations are the classic tell of a
non-Expressive implementation.

Two axes, and the split is the whole rule:

- **Spatial** (position, size, shape, bounds) — *may* overshoot; the overshoot is the point.
- **Effects** (colour, alpha, selection fills) — critically damped, **never** overshoots.
  A bouncing opacity is a rendering glitch, not expression.

The scheme's own values (AOSP `ExpressiveMotionTokens`), given as damping / stiffness:

| Spec | Expressive |
|---|---|
| spatial fast | 0.6 / 800 |
| spatial default | 0.8 / 380 |
| spatial slow | 0.8 / 200 |
| effects fast | 1.0 / 3800 |
| effects default | 1.0 / 1600 |
| effects slow | 1.0 / 800 |

### 6.1 The sender / receiver damping bias

The phone is **ballistic** — the hand is the source of the energy, so geometry keeps the
scheme's damping and is allowed to bounce. The TV is **settling** — a ten-foot screen is a
destination, and an overshoot large enough to see across a room reads as instability. So
`receiver/ui/theme/Motion.kt` clamps the damping *floor* of every spatial spec and never
touches stiffness (stiffness is what carries the Expressive character):

| Receiver token | Source spec | Damping floor | Used for |
|---|---|---|---|
| `focusSpatial()` | spatial fast | `TV_FOCUS_DAMPING` 0.85 | focus lift, press acknowledgement, beacon travel |
| `flickSettleSpatial()` | spatial fast | `TV_SPATIAL_DAMPING` 0.8 | glyph morph, seek swell, chip reveal, chrome exits |
| `panelSpatial()` | spatial default | `TV_SPATIAL_DAMPING` 0.8 | panel and chrome entrances, standby transitions |
| `stateEffects()` | effects default | — (never clamped) | every colour, alpha and selection fill |
| `fastStateEffects()` | effects fast | — | chrome and panel *exits*, which lead with the fade |

0.85 on focus is not a taste call: `FlickDimens.FocusRingReserve` is derived from the
`FOCUS_SCALE` lift with no overshoot budget in it, so the ring must not fly past the
element it surrounds. At 0.85 the peak excursion is ~0.6 %, which the reserve absorbs.

### 6.2 Curves that survive, and why

`focusPop`'s and `syncSpring`'s *curve* values are **retired** — both are now springs,
because both are interruptible: a focus move retargets mid-flight when the D-pad is held,
and a seek reconcile retargets on every key repeat. A tween restarts on a fresh clock and
visibly stutters. `FlickMotion.syncSpring()` is now
`spring(0.72, StiffnessMediumLow, visibilityThreshold = 0.0005f)` — the threshold is in
track fractions, so 0.0005 of an 800 dp bar is under half a pixel at density 2.

These stay tweens, each because nothing can interrupt it:

| Token | Curve | Duration | Why it is not a spring |
|---|---|---|---|
| `flickSettle` | `(.22, 1.2, .36, 1)` | 320 ms | legacy alias; geometry call sites now take `flickSettleSpatial()` |
| `playheadGlide` | linear | continuous | driven by the media clock, not by a target |
| `crossDissolve` | ease-in-out | 400 ms | poster ↔ playback, a pure dissolve |
| `chromeFade` | ease | in 200 / out 500 ms | pure alpha; the out value is the scrim's lag, not the chrome's |
| `tvSpin` / `tvPulse` | linear / ease-in-out | 1 s / 1.9 s | looping ambience |
| `tvBurst` | keyframed | 720 ms | the keyframes *are* the design |
| `pressConfirm` | `chromeFade` easing | 90 ms | below the threshold at which retargeting is visible |

**Cross-device rule:** one event → one motion. The four real protocol events (cast
committed, handshake advancing, first frame confirmed, play/pause toggled) each produce a
*paired* motion — an outbound gesture on the phone, an inbound settle on the TV. **Haptics
are the phone's half of the motion:** tick detents while dragging (every 10 s of film
crossed), firm snap on release, single confirm pulse on play/pause, soft ripple on grip.

**Restraint.** There is no ambient decoration anywhere except the TV idle screensaver,
whose entire job is ambience. Every measured number SNAPS between values — a tweened
measurement is a fabricated measurement — and a gauge *fraction* may animate on an effects
spec only, because a spatial overshoot would draw a reading that was never taken.

---

## 7. Component kit (build once, reuse)

- **Scrub bar — two variants, one clock.**
  - *Phone (tactile):* 10dp coral target fill, 24dp coral thumb (56dp hit); muted dashed/hollow
    confirmed marker; track grows to 12dp while dragging. A near-white raised local frame-preview
    card appears above the thumb, with tabular target time. The confirmed label is exposed to
    accessibility while it differs from target.
  - *TV (cinematic):* 5dp bar; buffered range `rgba(255,255,255,.28)`, played/target in Spark,
    target `●` in solid coral, confirmed `○` in high-contrast pale ring, and cyan shimmer only
    when lagging. See §Hero. Do not use cyan as a second playhead color.
- **Transport cluster:** back-10 / play-pause / fwd-10. Play/pause morphs (triangle ↔ bars via
  `flickSettle`)—never a hard swap. Phone primary play is a 56dp coral circle with warm lift.
  TV uses 48dp skip targets with 24dp glyphs and a 56dp primary target with a 28dp glyph; the
  primary control takes the sole initial playback focus.
- **Minimized playback:** phone Now Playing exposes a downward minimize action that never stops
  the cast. Library keeps a raised mini-player with thumbnail, state, title, and one-tap restore.
  Partial media access keeps a prominent **Add videos** action; full access exposes **Refresh**.
- **Status strip/pills:** phone status is a quiet top strip, `Serving from this phone · 5 GHz`
  with a green dot; `Connecting…` uses cyan; `TV unreachable` uses crimson. **Signal chip:**
  `61 Mb/s · 5 GHz` (mono tabular, Wi-Fi glyph) expands to the quality sheet. Never claim a
  healthy band, serving state, or bitrate without real state.
- **Volume:** continuous slider on phone; **stepped cells on TV** (D-pad friendly).
- **Video tile:** 16:9 filmic still, duration (mono tabular, bottom-right), DV/HDR badge (premium
  sheen, top-left), title + `4K · 8.4 GB` caption.
- **Pair card / advisory:** advisories are **specific, human, actionable** — a tinted card
  (`caution` bg `rgba(255,180,84,.08)` + border), **never a modal, never a toast wall**.

---

## 8. The Hero — optimistic/ghost synchronized scrub (Part 4)

The bar is **one session clock drawn twice**. Contract:
- **Solid coral = optimistic target (thumb)**—leads. **Pale hollow/dashed ○ =
  network-confirmed**—trails. Cyan is the live link/sync shimmer, never either position.
- **Healthy:** ghost and solid are superimposed — *sync is invisible when healthy* (Beat 1).
- **Grab:** track swells, thumb ring blooms, frame-preview pops (`flickSettle`), TV wakes chrome
  (`chromeFade` in 200ms), soft ripple haptic; playback keeps rolling until release (Beat 2).
- **Drag:** TV playhead follows the thumb live (~35ms behind); detent haptic every 10s of film;
  timecodes track in lockstep, tabular (Beat 3).
- **Latency grace (the intellectual core):** on a Wi-Fi hiccup the phone's solid head **leads**;
  the TV shows the **ghost ○ at last-confirmed** trailing the target with a cyan "SYNCING…"
  shimmer. A dropped tick **eases** forward — alive and honest, never a jump-scare (Beat 4).
- **Release:** ghost & target reconcile with `syncSpring` (overshoot); a shared **Spark pulse ring**
  radiates from both playheads in the same instant; preview dismisses; one firm snap haptic; video
  cross-dissolves and plays (Beat 5).
- **Cross-surface:** either surface commands both. Pause on the TV remote → the phone's play/pause
  morphs in the same 320ms, same Spark pulse, plus a confirm haptic in the hand. Skip/volume mirror
  identically (Beat 6).

See `control-channel.md` for the transport that carries target vs. confirmed.

# Flick design tokens — canonical

Single source of truth for the visual language of **both** apps. `:sender` and `:receiver`
implement these values **identically** (same hex, same type scale, same motion curves) in their
own `ui/theme/` package. Source of record: `docs/design/flick-design-system.html` (Part 1 +
Part 4). If this file and the HTML ever disagree, the HTML wins — fix this file.

Design thesis: **"Flick" is both the gesture and the movie.** Warm vermilion (Spark) for content
& action; cool cyan (Link) strictly for connection & focus. They never swap jobs.

---

## 1. Color

### 1.1 Dark — "the cinema" (TV always; phone dark theme)

| Token | Hex | Use |
|---|---|---|
| `canvas` | `#08070C` | TV playback bed — near-black, violet-warmed. **Never `#000`.** |
| `surface` (base) | `#0F0D14` | app background |
| `surfaceRaised` | `#181521` | cards, sheets, tiles |
| `surfaceRaisedAlt` | `#14121A` | secondary raised (kit cards) |
| `glass` fill | `rgba(32,27,44,0.62)` + blur 24 | controls floating over video |
| `glassBorder` | `rgba(255,255,255,0.14)` | 1px hairline on glass |
| `onSurface` | `#EDEAF2` | primary text/icons |
| `onSurfaceDim` | `#9B94A8` | secondary text |
| `onSurfaceFaint` | `#6A6478` | captions, mono labels |
| `outline` | `#2C2838` | dividers, unfocused borders |
| `outlineHairline` | `rgba(255,255,255,0.07)` | card borders |

### 1.2 Light — "the pocket" (phone light theme, Material-You-friendly)

| Token | Hex | Use |
|---|---|---|
| `surface` (base) | `#FAF8F5` | warm paper, **not clinical white** |
| `surfaceRaised` | `#FFFFFF` | cards, sheets |
| `surfaceTonal` | `#F0EBE4` | chips, M3 secondary containers |
| `onSurface` | `#1D1826` | primary |
| `onSurfaceDim` | `#6E6678` | secondary |
| `onSurfaceFaint` | `#98909F` | captions |
| `outline` | `#E7E1D9` | dividers/borders |
| `sparkOnLight` | `#E63E1A` | Spark deepened one step for 4.5:1 on paper |
| `linkOnLight` | `#0E7E9C` | Link deepened for contrast on paper |

### 1.3 Brand accents — the split (both themes)

| Token | Hex | Gradient | Job |
|---|---|---|---|
| **Spark** | `#FF4B24` | `linear(120°, #FF6A47 → #FF4B24)` | content & action: playhead fill, play button, CTAs, wordmark dot |
| `sparkLight` (tint) | `#FF6A47` | — | hover/lift/links-on-dark |
| `sparkSoft` | `#FF8B6E` | — | pressed/hint text |
| **Link** | `#3FD9FF` | — | connection & focus **only**: TV D-pad focus glow, pairing, sync shimmer, "connected" pills |

### 1.4 Semantic

| Token | Hex | Meaning |
|---|---|---|
| `live` | `#34D389` | serving / connected / healthy |
| `caution` | `#FFB454` | 2.4 GHz · weak signal · battery nudge |
| `trouble` | `#FF3B5C` | unreachable / failed (**crimson ≠ Spark** — do not confuse) |
| `info` | `#3FD9FF` | sync, pairing, tips (== Link) |

### 1.5 Premium sheen (quality badges only — DV/HDR — never UI chrome)

`linear(115°, #E9C87C → #F7ECD2 45% → #D9B45F)`, badge text `#3A2E14`. HDR10 = outline variant
(`1px rgba(233,200,124,0.55)`, text `#F7ECD2`).

### 1.6 Material You (phone only)

The phone **may** seed ambient tints + tonal chips from the picked video's dominant color
(dynamic color on Android 12+). **The Spark playhead and play button stay anchored** (never
re-tinted). **The TV never re-tints** — it holds fixed cinematic dark under the film. Ambient
poster-glow behind transport clusters ≤ 30% opacity so HDR video stays the brightest thing.

---

## 2. Typography

Faces (all via Compose; no bundled files):
- **Display / titles / wordmark:** Space Grotesk (geometric, kinetic). Load via **downloadable
  Google Fonts** (`androidx.compose.ui:ui-text-google-fonts`) with graceful fallback to the
  platform default. Do **not** hard-fail if the provider is unavailable.
- **UI / body:** platform default (Roboto/Google Sans Text) — zero font-loading cost.
- **Timecode / telemetry:** Roboto Mono (downloadable) or platform monospace, **tabular figures
  mandatory** — `TextStyle(fontFeatureSettings = "tnum")`. Digits must not shimmy while the clock runs.

### Phone scale (arm's length)
| Role | size/line | weight | tracking |
|---|---|---|---|
| Title | 28/34 | 700 | −2% |
| Screen heading | 20/26 | 600 | — |
| Body (file names, advisories) | 15/22 | 400 | — |
| Caption (metadata) | 12/16 | 500 | — |
| Scrub timecode | 17 mono | 600 | tabular |

### TV scale (ten feet) — **no text smaller than 24dp anywhere on TV**
| Role | ~size | weight | tracking |
|---|---|---|---|
| Now playing | 48dp | 700 | −1% |
| Section / dialog | 32dp | 600 | +1% |
| Body | 24dp min | 500 | +1% |
| Seek timecode | 40dp mono | 700 | tabular |

10-ft rule: one weight step heavier, +1% tracking, high-contrast on scrim.

---

## 3. Shape & spacing

- **8pt grid, 4pt sub-grid.** Spacing ramp: `4 · 8 · 16 · 24 · 32 · 48 · 64`.
- Corner tokens: `sm 8 · md 12 · lg 16 · xl 24 · full 999`.
- **Rounded is the resting personality; full-pill is reserved for _live_ things** — scrub tracks,
  status pills, the connect chip.
- Phone: single column, thumb-first. Primary transport in the **bottom 34%**. Min touch target
  **48dp**; **scrubber thumb 56dp hit**.
- TV: 12-column rhythm inside a **5% overscan-safe inset** (96×54px at 1920×1080). Video is
  full-bleed; **all chrome/text lives inside the safe area**.

---

## 4. Elevation, glass & focus

- Elevation: `e0` base (`surface`) · `e1` raised (`surfaceRaised` + `shadow 0 8 24 rgba(0,0,0,.35)`)
  · `e2` glass (blur 24 · `rgba(32,27,44,.62)` · 1px `rgba(255,255,255,.14)` · top inner-glow).
- Scrim = a soft top-transparent→`rgba(8,7,12,.78)` gradient, **never a hard bar**.
- **TV focus (no hover on TV):** `scale 1.08 + tonal lift + 2dp Link-cyan border + soft glow`
  (`0 0 0 4px rgba(63,217,255,.22), 0 0 24px rgba(63,217,255,.35)`). **Selected** keeps a Spark
  tint **without** the glow so focus and selection never blur. Disabled = 38% opacity. Every TV
  screen defines an explicit D-pad order and **exactly one element is focused at all times**.

---

## 5. Iconography

24dp grid, **1.8px stroke**, round caps/joins. Filled counterparts only for play/pause at ≥48dp
transport size. On TV, icons render 32–48dp at the same stroke ratio. Set (draw as `ImageVector`
or Canvas paths per the HTML §1.5): `play, pause, back-10, fwd-10, previous, next, volume, cast,
qr-pair, wi-fi, hdr/dv, private(lock), settings, metrics`. The **brand mark** = rounded play
triangle + 3 motion streaks (streaks drop below 24px; triangle alone survives to 16px).

---

## 6. Motion — "flick & settle"

Implement each as a reusable Compose `Easing`/`AnimationSpec` in `ui/theme/Motion.kt`. Curves are
cubic-bezier `(a,b,c,d)` → `CubicBezierEasing(a,b,c,d)`.

| Token | Curve | Duration | Used for |
|---|---|---|---|
| `flickSettle` | `(.22, 1.2, .36, 1)` (overshoot ~6%) | 320ms | launch, toss-to-cast, seek confirm, screen transitions, play/pause morph |
| `playheadGlide` | linear | continuous | the bar tracking the running clock |
| `syncSpring` | `(.3, 1.4, .4, 1)` (slight overshoot) | 180ms | ghost→target snap on release |
| `crossDissolve` | ease-in-out | 400ms | poster ↔ playback |
| `chromeFade` | ease | in 200 / out 500ms | TV controls in/out |
| `focusPop` | `(.2, .9, .25, 1.1)` | 160ms | TV D-pad focus |

**Cross-device rule:** one event → one motion. The **same** easing token + accent pulse fire on
**both** surfaces simultaneously. A **traveling light** (cyan mote sliding along the link) appears
at connect, at seek release, and whenever a cross-surface command lands. **Haptics are the phone's
half of the motion:** tick detents while dragging (every 10s of film crossed), firm snap on
release, single confirm pulse on play/pause, soft ripple on grip.

---

## 7. Component kit (build once, reuse)

- **Scrub bar — two variants, one clock.**
  - *Phone (tactile):* 10dp track, 24dp thumb (56dp hit), track grows to 12dp while dragging;
    fill = Spark gradient. Frame-preview pops above the thumb while dragging (decoded on-device).
  - *TV (cinematic):* 5dp bar; shows **buffered range** (`rgba(255,255,255,.28)`), **played** (Spark),
    **target ●** (solid white w/ Link glow), **confirmed ○ ghost** (hollow white ring). See §Hero.
- **Transport cluster:** back-10 / play-pause / fwd-10. Play/pause **morphs** (triangle ↔ bars via
  `flickSettle`) — never a hard swap. Primary play = 56dp Spark-gradient circle.
- **Status pills (full-pill):** `Serving · live` (green, pulsing dot), `Connecting…` (cyan),
  `TV unreachable` (crimson). **Signal chip:** `61 Mb/s · 5 GHz` (mono tabular, wifi glyph) →
  expands to the quality sheet.
- **Volume:** continuous slider on phone; **stepped cells on TV** (D-pad friendly).
- **Video tile:** 16:9 filmic still, duration (mono tabular, bottom-right), DV/HDR badge (premium
  sheen, top-left), title + `4K · 8.4 GB` caption.
- **Pair card / advisory:** advisories are **specific, human, actionable** — a tinted card
  (`caution` bg `rgba(255,180,84,.08)` + border), **never a modal, never a toast wall**.

---

## 8. The Hero — optimistic/ghost synchronized scrub (Part 4)

The bar is **one session clock drawn twice**. Contract:
- **Solid = optimistic (thumb)** — leads. **Ghost ○ = network-confirmed** — trails. Cyan = the live link.
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

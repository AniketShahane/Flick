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
Coral means user action and optimistic target; cyan means live LAN/sync and TV D-pad focus;
green means serving; restrained gold means verified premium media. These jobs never swap.

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
| **Link** | `#41E5F2` | — | live LAN, sync shimmer/thread, pairing, and TV D-pad **focus only** |

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
| live LAN/sync | Link secondary/container detail and explicit text | Link focus border/glow, pairing and sync shimmer |
| serving health | `tertiary`/custom semantic role | custom semantic role, not a focus cue |
| premium media | custom metadata badge only | custom metadata badge only |
| failure/caution | Material error/custom advisory roles | TV error/custom advisory roles |

Do not make a literal RGB copy of phone Material Expressive roles in TV Material. The semantic
job is shared; component API, focus behavior, and containment stay form-factor-native.

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
| Full-screen/display title | 48dp | 700 | −1% |
| Playback chrome title | 30dp, one line | 600 | −1% |
| Section / dialog | 32dp | 600 | +1% |
| Body | 24dp min | 500 | +1% |
| Playback timecode | 28dp mono | 700 | tabular |

10-ft rule: one weight step heavier, +1% tracking, high-contrast on scrim.

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
- TV: 12-column rhythm inside a **5% overscan-safe inset** (96×54px at 1920×1080). Video is
  full-bleed; all chrome/text stays inside the safe area. TV containers stay low and wide rather
  than copying the phone's asymmetric card geometry.

---

## 4. Elevation, material & focus

- Phone material: `e0` ivory page · `e1` cream tonal contour · `e2` near-white raised media/frame
  preview with a soft warm shadow (`0 8 24 rgba(72,47,36,.16)`). Avoid fake glass over the paper
  remote; opacity is not a substitute for hierarchy.
- TV material: `e0` retained film/cinema canvas · `e1` violet translucent bottom scrim · `e2` a
  restrained `rgba(27,21,38,.72)` chrome panel with 1px `rgba(255,255,255,.14)` border. API-gated
  blur is optional; legibility cannot depend on it.
- Scrim is a soft transparent-to-`rgba(11,9,18,.82)` gradient, never a hard bar.
- **TV focus (no hover):** `scale 1.08 + tonal lift + 2dp Link-cyan border + soft glow`
  (`0 0 0 4px rgba(65,229,242,.24)`, `0 0 24px rgba(65,229,242,.38)`). **Selected** uses a Spark
  tint without cyan glow, so selection and focus never blur. Disabled is 38% opacity. Every TV
  state defines an explicit D-pad order and exactly one focused element. The reference's central
  play/pause is focused only for playback chrome; other states select their own single initial
  actionable target.

---

## 5. Iconography

24dp grid, **1.8px stroke**, round caps/joins. Filled counterparts only for play/pause at ≥48dp
transport size. On TV, icons render 32–48dp at the same stroke ratio. Use the rounded Material
`Replay10` / `Forward10` glyphs for TV seeking rather than drawing numerals into custom arrows;
the phone may retain its compact tactile skip treatment. Set: `play, pause, previous, next, volume, cast,
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
  - *Phone (tactile):* 10dp coral target fill, 24dp coral thumb (56dp hit); muted dashed/hollow
    confirmed marker; track grows to 12dp while dragging. A near-white raised local frame-preview
    card appears above the thumb, with tabular target time. The confirmed label is exposed to
    accessibility while it differs from target.
  - *TV (cinematic):* 5dp bar; buffered range `rgba(255,255,255,.28)`, played/target in Spark,
    target `●` in solid coral, confirmed `○` in high-contrast pale ring, and cyan shimmer only
    when lagging. See §Hero. Do not use cyan as a second playhead color.
- **Transport cluster:** back-10 / play-pause / fwd-10. Play/pause morphs (triangle ↔ bars via
  `flickSettle`)—never a hard swap. Phone primary play is a 56dp coral circle with warm lift.
  TV uses 48dp skip targets and a 56dp primary target with glyphs at least 32dp; the primary
  control takes the sole initial playback focus.
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

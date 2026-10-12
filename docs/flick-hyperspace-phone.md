# Hyperspace — the phone side of a cast

Three beats: throw the poster (detail page), ride the warp (connecting), land (flash into
the remote). The poster reaches the page by turning over out of the library tile (§4). These
numbers and the constants in code are one thing: change either, change both.

Code: `ui/components/FlickPad.kt` (`FlickPadMotion`), `ui/components/FlickPadPolicy.kt`,
`ui/components/CardFlip.kt` (`CardFlipMotion`),
`ui/components/Hyperspace.kt` (`Hyperspace`), `ui/screens/ConnectingScreen.kt`,
`ui/screens/FlickApp.kt` (`LandingFlash*`). No protocol change: every send is `controller.flickToTv(item)`.

## 1. Flick pad (detail page, the hero)

The detail page has no send button: the pad is the only send, in every state — no TV
connected (the flick bounces to pairing, as `flickToTv` always has), a remembered refusal,
or a link advisory. Page order, top to bottom: back disc; title logo, meta line, chips (the
film's identity); the pad, centred in what is left (`Arrangement.SpaceBetween` over a page at
least the window tall); the refusal or advisory card at the foot. Wider than tall, the page
splits: identity and card in a scrolling left column, the pad centred on the right.

| What | Value |
|---|---|
| Size | sized to the window, so the page does not scroll: poster height = window − system bars − 24 dp page padding − measured identity block − 2 × hero gap − measured pad chrome − 2 dp slack, minus the card; clamped 140–390 dp, so a card that does not fit beside a 140 dp poster only overflows by the difference (120 dp minimum wide). Hero gap 20 dp, chevron gap 10 dp, caption gap 16 dp; 8 / 4 / 8 dp under 700 dp of height |
| Poster | exact 2:3 (Crop), corner 8% of the width, 1 dp white 12% hairline; action-colour glow: radial, 0.42 alpha at the centre, reach 0.8 × width (× 1.5 vertically), plus a 24 dp shadow tinted the same; TMDB **poster** (never the backdrop), else the video frame. The glow and the shadow are their own flat layer under the card (the card turns over on arrival, the light on the page does not); the poster's layer uses `ModulateAlpha`, so neither is clipped while the poster fades |
| Backdrop | the film's backdrop (else its poster, else its own frame) pre-blurred into a 200 px-wide bitmap at the viewport's aspect (3-pass box, radius 6 px ≈ the old 24 dp live blur), `Crop`/`TopCenter`; scrim 0.60 / 0.70 / 0.92 / 1.0 down the page, to 0.95 as it scrolls |
| Lines | caption, white, one line: "Flick it toward _TV_" ("… your TV" with none), "Let go to send" while armed. Note, dim, one line, seat always kept: "Reaching _TV_…" while committing, else "Picks up at _time_" with a resume point, else "No TV connected yet — you'll pick one first" |
| Chevrons | 3 in a 30 × 32 dp row, stroke 3.2 dp, rise 6 dp, pitch 8 dp, white over a black halo (stroke + 3 dp, dropped 1 dp, 0.35 × the chevron's alpha); ripple 1200 ms, peak at 25%, base alpha 0.55 |
| Chevrons, still | 0.60 / 0.80 / 1.0 bottom to top (reduce motion or paused); 0.3 when disabled; 1.0 when armed |
| Idle nudge | up 12 dp: rise 280 ms, fall 420 ms, rest 2100 ms (2.8 s period); first after 1200 ms; stops for good on first touch, never under reduce motion |
| Tap | never sends. One hint lift of 22 dp (rise 200 ms, then the default spatial spring back); none under reduce motion |
| Follow | up 1:1; down × 0.2; sideways × 0.25; tilt 0.12°/dp, capped ±8°; held scale 1.03 |
| Claim | the page scrolls when it overflows, so the pad takes a drag only once it crosses touch slop with the page unable to scroll further down (`!canScrollForward`), and not a downward one the page could scroll back; anything else is left unconsumed and scrolls the page |
| Arm | finger ≥ 80 dp up from where it went down (touch slop included) → "Let go to send" + `GestureThresholdActivate` haptic |
| Send | armed, or ≥ 20 dp up and ≥ 1000 dp/s upward at release |
| Send motion | `GestureEnd` haptic and `flickToTv` at release; then, decoratively, poster up 640 dp in 250 ms, Release curve (0.12, 0.62, 0.24, 1); fades on (0.4, 0, 1, 1) |
| Not sent | springs back on the scheme's default spatial spring — also when the gesture is cut off mid-drag (the pad disabled, or the page taking the drag) |
| Still composed 700 ms after a send | poster returns to its seat, 220 ms fade |
| Disabled | `!progressReady \|\| castStart.isCommitting()` |

Accessibility: the pad is one node — `Role.Button`, described as "Cast _film_ to _TV_" (or
"Resume _film_ from _time_ on _TV_"), with a click action labelled "Flick to _TV_" / "Resume
from _time_ on _TV_" that runs the same `flickToTv`; `disabled()` while the send is not
available. A link advisory also keeps its "Cast anyway" action.

## 2. Hyperspace (connecting screen)

| What | Value |
|---|---|
| Wash | radial from vanishing point: `#1F3AA8` → `#0B1640` at 45% → `#04070F`; radius 0.75 × the longer side |
| Vanishing point | centre x, 38% of the window height where there is room; the field converges on it wherever it sits |
| Copy | starts at `min(0.38·H + gap, H − 78 dp pill band − nav bar − 212 dp × max(fontScale, 1))`, never closer than gap + 24 dp to the top; the vanishing point is `copy top − gap`, so on a short window (landscape, split screen) the whole scene lifts and Cancel stays above the fold. Gap 72 dp roomy, 40 dp compact |
| Roomy | H ≥ 488 dp × max(fontScale, 1): half the poster (78) + 72 gap + 212 copy + 78 pill band + 48 nav bar |
| Warp | poster (its own 104 × 156 card, corner 8% of the width, same hairline — smaller than the pad's, because the roomy budget below is half of it) holds 280 ms, then scale 1 → 0.02 over 640 ms, ease-in cubic (0.32, 0, 0.67, 0); fades over the last 15% |
| Loop | one 1600 ms linear clock, read in the draw phase of one canvas; ramps in with the warp |
| Rings | 4 phone-shaped (height 2.05 × width, corner 0.16 × width), staggered ¼ period; size ∝ phase²; reach 1.3 × the window; stroke 1.5 → 3 dp; `rgba(111,160,255,.55)`, ring 2 is amber `#FFB61E` at 0.8 |
| Streaks | 12 radial, 30° apart ± fixed jitter; speed 1× or 2× the clock; 2 dp, born 18 dp out, up to 72 dp long; white / `#9FC0FF` / amber |
| Fade | every ring and streak: in over its first 12%, linear out to 0 at end of life (seamless wrap) |
| Copy text | "On its way" (Bricolage 26 sp, white); "film · TV name" (`#A8B8DC`); stage line, slow-link card, Cancel, status pill unchanged |
| Reduce motion | static wash and copy only: no poster, no loop. A paused window stops the loop too |

The shared `CastPosterKey` seat is now a 32 × 18 dp empty box at the vanishing point, so the
remote's poster grows out of the warp when the first frame lands.

## 3. Landing (Connecting → NowPlaying only)

White over the whole window, above the shared-element overlay: alpha 0 → 0.85 in 120 ms
(linear), → 0 in 330 ms (0.4, 0, 1, 1) — 450 ms. `Confirm` haptic — the only `Confirm`
of a cast: the release on the pad is `GestureEnd`, and the connecting screen's `Active` arm has none. No flash under reduce motion; the haptic stays.
System bars: Connecting already declares a dark backdrop (`SenderShellPolicy.darkBackdrop`).

## 4. Card flip (library tile ⇄ detail poster)

The tapped tile lifts, turns over in 3D as it travels to the pad's seat, and lands with the
poster on its back; the page fades up around it. Back is the same path on no-bounce springs and
the lift lets go last, so the card drops into its slot. Transform and opacity only: one
`sharedBounds` per end (`card-flip-<id>`, remeasured, in the overlay, no fades), and the seat's
side draws both faces while the tile's own copy is hidden. The faces swap edge-on (90°) and the
back is turned a further half-turn so it reads unmirrored. Every track is a spring
(damping, stiffness) that waits its delay only when it starts from rest, so a flip reversed
mid-flight turns straight round from where it is.

| Track | Open: delay, damping, stiffness | Back |
|---|---|---|
| Lift — scale 1 + 0.08 × lift × (1 − travel) | 0 ms, 0.75, 900 | 300 ms, 0.85, 1000 |
| Grid badges, running time and slot shadow fade | 0 ms, 1, 2500 | 360 ms, 1, 2500 |
| Travel to the seat (the bounds; also eases the corner 26 dp → 8% of the poster's width) | 60 ms, 0.86, 260 | 0 ms, 1, 520 |
| Flip — rotationY 180° × flip, roll −6° × sin(π × flip), camera distance 12 × density | 60 ms, 0.6, 190 | 0 ms, 1, 420 |
| Page — ground, backdrop and scrim opacity | 60 ms, 1, 360 | 80 ms, 1, 620 |
| Back disc / title / meta / chips (and attached subtitle) — rise 14 dp and fade in | 260 / 290 / 320 / 350 ms, 1, 500 | 0 ms, 1, 1400 |
| Glow and shadow under the seated card — grow from 0.7 × and fade in | 380 ms, 1, 300 | 0 ms, 1, 1100 |
| Chevrons — rise 14 dp | 460 ms, 1, 500 | 0 ms, 1, 1400 |
| Caption, note and notice card — rise 18 dp | 480 ms, 1, 500 | 0 ms, 1, 1400 |

- **Layering.** The page's ground, backdrop, scrim and glow sit under the card; the card is drawn
  in the shared-element overlay, so it also passes over the chrome while it travels. Going back,
  the library is placed beneath the page it is leaving, and every arriving route otherwise takes
  a higher layer, so a flip reversed mid-way keeps the page on top while it fades.
- **Faces.** Front: the tile's own art and scrim (`TileFace`), desaturated when the TV refused
  the file. Back: the pad's poster with its hairline. The poster is requested when the tile is
  tapped, at both sizes the page draws it.
- **The seat is measured.** The page's first frame sizes the seat from the blocks it measured
  last time rather than from an estimate, so the card flies to the seat it lands in.
- **No match** (the page opened from anywhere but the grid, or a grid that no longer holds the
  tile): the card stays seated, poster up, and fades with the page.
- **Taps and the throw.** A tile ignores a second tap while its page is already opening. The
  pad does not take a throw until the card has landed.
- **Reduce motion.** No flip and no travel: library and detail cross-fade.
- **Frame rate.** The flight is in the shell's overlay, so the shell's peak-rate vote while a
  shared transition is active covers it.

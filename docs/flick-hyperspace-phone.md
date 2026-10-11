# Hyperspace — the phone side of a cast

Three beats: throw the poster (detail sheet), ride the warp (connecting), land (flash into
the remote). These numbers and the constants in code are one thing: change either, change both.

Code: `ui/components/FlickPad.kt` (`FlickPadMotion`), `ui/components/FlickPadPolicy.kt`,
`ui/components/Hyperspace.kt` (`Hyperspace`), `ui/screens/ConnectingScreen.kt`,
`ui/screens/FlickApp.kt` (`LandingFlash*`). No protocol change: every send is `controller.flickToTv(item)`.

## 1. Flick pad (detail sheet, above the CTA)

Shown only in the seat the old gesture strip held: never with a refusal or a link advisory.

| What | Value |
|---|---|
| Poster | 132 × 198 dp, corner 18 dp, 18 dp shadow; TMDB poster, else the video frame (Crop) |
| Chevrons | 3, stroke 3 dp, rise 7 dp, pitch 10 dp, `colors.primary`; ripple 1200 ms, peak at 25%, base alpha 0.25 |
| Chevrons, still | 0.35 / 0.60 / 0.85 bottom to top (reduce motion or paused); 0.2 when disabled; 1.0 when armed |
| Idle nudge | up 12 dp: rise 280 ms, fall 420 ms, rest 2100 ms (2.8 s period); first after 1200 ms; stops for good on first touch, never under reduce motion |
| Follow | up 1:1; down × 0.2; sideways × 0.25; tilt 0.12°/dp, capped ±8°; held scale 1.03 |
| Arm | finger ≥ 80 dp up → "Let go to send" + `GestureThresholdActivate` haptic |
| Send | armed, or ≥ 20 dp up and ≥ 1000 dp/s upward at release |
| Send motion | `Confirm` haptic; poster up 640 dp in 250 ms, Release curve (0.12, 0.62, 0.24, 1); fades on (0.4, 0, 1, 1); then `flickToTv` |
| Not sent | springs back on the scheme's default spatial spring |
| Still composed 700 ms after a send | poster returns to its seat, 220 ms fade |
| Disabled | exactly when the CTA is: `!progressReady \|\| castStart.isCommitting()` |

Accessibility: the pad is `clearAndSetSemantics {}`; the CTA below carries the action.

## 2. Hyperspace (connecting screen)

| What | Value |
|---|---|
| Wash | radial from vanishing point: `#1F3AA8` → `#0B1640` at 45% → `#04070F`; radius 0.75 × the longer side |
| Vanishing point | centre x, 38% of the window height |
| Warp | poster (same 132 × 198 card) holds 280 ms, then scale 1 → 0.02 over 640 ms, ease-in cubic (0.32, 0, 0.67, 0); fades over the last 15% |
| Loop | one 1600 ms linear clock, read in the draw phase of one canvas; ramps in with the warp |
| Rings | 4 phone-shaped (height 2.05 × width, corner 0.16 × width), staggered ¼ period; size ∝ phase²; reach 1.3 × the window; stroke 1.5 → 3 dp; `rgba(111,160,255,.55)`, ring 2 is amber `#FFB61E` at 0.8 |
| Streaks | 12 radial, 30° apart ± fixed jitter; speed 1× or 2× the clock; 2 dp, born 18 dp out, up to 72 dp long; white / `#9FC0FF` / amber |
| Fade | every ring and streak: in over its first 12%, linear out to 0 at end of life (seamless wrap) |
| Copy | "On its way" (Bricolage 26 sp, white); "film · TV name" (`#A8B8DC`); stage line, slow-link card, Cancel, status pill unchanged |
| Reduce motion | static wash and copy only: no poster, no loop. A paused window stops the loop too |

The shared `CastPosterKey` seat is now a 32 × 18 dp empty box at the vanishing point, so the
remote's poster grows out of the warp when the first frame lands.

## 3. Landing (Connecting → NowPlaying only)

White over the whole window, above the shared-element overlay: alpha 0 → 0.85 in 120 ms
(linear), → 0 in 330 ms (0.4, 0, 1, 1) — 450 ms. `Confirm` haptic (moved here from the
connecting screen's `Active` arm, so it fires once). No flash under reduce motion; the haptic stays.
System bars: Connecting already declares a dark backdrop (`SenderShellPolicy.darkBackdrop`).

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
| Frame rate | the panel's peak through the hold and the warp; 30 Hz for the wait after it (`loopFrameRate`). The landing is under the shell's peak vote for the shared transition |

The shared `CastPosterKey` seat is now a 32 × 18 dp empty box at the vanishing point, so the
remote's poster grows out of the warp when the first frame lands.

## 3. Landing (Connecting → NowPlaying only)

White over the whole window, above the shared-element overlay: alpha 0 → 0.85 in 120 ms
(linear), → 0 in 330 ms (0.4, 0, 1, 1) — 450 ms. `Confirm` haptic — the only `Confirm`
of a cast: the release on the pad is `GestureEnd`, and the connecting screen's `Active` arm has none. No flash under reduce motion; the haptic stays.
System bars: Connecting already declares a dark backdrop (`SenderShellPolicy.darkBackdrop`).

## 4. Card flip (library tile ⇄ detail poster)

The tapped tile lifts, turns over in 3D as it travels to the pad's seat, and lands with the
poster on its back; the page fades up around it. Back is the same path on no-bounce springs,
and the card is handed back to its tile the moment it lands. Transform and opacity only: one
`sharedBounds` per end (`card-flip-<id>`, remeasured, in the overlay, no fades), and the seat's
side draws both faces while the tile's own copy is hidden. The faces swap edge-on (90°) and the
back is turned a further half-turn so it reads unmirrored.

Every track is a plain spring (damping, stiffness), so a transition turned round mid-flight
keeps every track on its own spring, turning from the pose and velocity it has. The delays are
a stagger applied only to a leg that starts from rest: until a track's delay has passed its
target is the pose it is already in. A leg that reverses a flight releases every track at once.
The travel has no delay, because the bounds carry its spring and a bounds transform cannot hold
one; back has no delays at all.

| Track | Open: delay, damping, stiffness | Back |
|---|---|---|
| Lift — scale 1 + 0.08 × lift × (1 − travel); back, × travel as well, so it is 0 at contact | 0 ms, 0.75, 900 | 0 ms, 1, 1000 |
| Tile's badges, running time and watched line — drawn on the card's front, fade with the travel | 1 − smoothstep over the first 30% of the travel | the same curve: back on over the last 30%, whole at contact |
| Slot ground and shadow fade (left in the grid) | 0 ms, 1, 2500 | follow the travel: whole from 6% of it left to contact |
| Travel to the seat (the bounds; also eases the corner 26 dp → 8% of the poster's width) | 0 ms, 0.86, 260 | 0 ms, 1, 520 |
| Flip — rotationY 180° × flip, roll −6° × sin(π × flip), camera distance 12 × density; back, flip is also × smoothstep over the last 15% of the travel, so both rotations are 0 at contact even on a reversed leg | 60 ms, 0.6, 190 | 0 ms, 1, 520 |
| Page — ground, backdrop and scrim opacity | 60 ms, 1, 360 | 0 ms, 1, 620 |
| Back disc / title / meta / chips (and attached subtitle) — rise 14 dp and fade in | 260 / 290 / 320 / 350 ms, 1, 500 | 0 ms, 1, 1400 |
| Glow and shadow under the seated card — grow from 0.7 × and fade in; also × smoothstep over the last 15% of the travel, so back they are gone by 15% of the way | 380 ms, 1, 300 | 0 ms, 1, 1100 |
| Chevrons — rise 14 dp | 460 ms, 1, 500 | 0 ms, 1, 1400 |
| Caption, note and notice card — rise 18 dp | 480 ms, 1, 500 | 0 ms, 1, 1400 |

- **Landing and hand-back.** Coming back, the card is down when the travel and the turn as drawn
  are both within 0.003 of home (a pixel or two, and half a degree); the turn home is as stiff
  as the travel so neither waits on the other, and the drawn turn is squared to the travel over
  its last 15%, so a flight reversed mid-way (whose turn lags its travel) still lands flat and at
  the tile's size rather than settling a few degrees on top of it. On that frame the seat stops drawing and the
  tile's own copy shows; neither waits for the route transition to end, which runs as long as
  the slowest page track. The seat is a card in flight only while a shared transition is
  active; otherwise it is the seated poster, fading with the page.
- **The slot's marks.** The tile's badges, running time and watched line (`TileMarkings`) ride on
  the card's front face while it flies, so they take its bounds, lift, turn and corner exactly:
  whole on the first lift frame, gone by 30% of the travel out, back on over the last 30% home
  and whole at contact. The tile's own copy is hidden with its face for the flight and shown
  with it at the hand-back, so nothing pops at either end. The tile hands its marks and its own
  palette to the page at the tap (`CardFlipHandoff`), so the card draws them in the tile's
  colours under the page's cinematic theme.
- **The light under the seat.** The glow and its tinted shadow are flat on the page and cannot
  follow the card, so they are gated by the travel as well as their own track: going home they
  are gone by 15% of the way instead of fading in place over the library.
- **Layering.** The page's ground, backdrop, scrim and glow sit under the card; the card is drawn
  in the shared-element overlay, so it also passes over the chrome while it travels. Library and
  detail are one entry to the route switch (`LibraryStack` in `FlickApp.kt`): the page is always
  drawn over a library that stays composed, so a flip reversed mid-way keeps the page on top
  while it fades, and closing the page only redraws the grid. The grid is drawn through an empty
  clip while the page is settled over it (left undrawn, its render nodes would drop their
  display lists and all be re-recorded in the close's first frame), hidden from TalkBack and focus, and takes no touches. Each end
  of the flip's bounds is switched off while it rests, so every leg matches from where the tile
  and the seat are now, not where the last leg left them. A stack left with its page up (for
  the cast, or the Connect tab) is a new entry if it comes back, and arrives without a flip.
- **Chrome.** The nav and dock wait below the window while the card comes back and are released
  when 12% of the travel is left, so their rise overlaps the card's last settle (at most 560 ms
  if no card is flying).
- **Faces.** Front: the tile's own art and scrim (`TileFace`), desaturated when the TV refused
  the file, asked for at the size the tile drew it so it is the tile's own decode in memory; the
  copy cached under the plain URL stands in if it is not. The art's ground is the tile's surface,
  never black. Back: the pad's poster with its hairline, over the small poster (w342) at 228 ×
  342 px, which the tap puts in memory. While a tile is on screen its small poster is in memory
  (at 114 × 171 px for a backdrop tile, at the tile's own size for a poster tile), and stands in
  for both until they land, so the back is never an empty ground. At the tap the full-size poster is fetched to disk only, and the seat decodes it at
  its own size. No warm is unsized, so none evicts the grid the card lands back on.
- **The seat is measured.** The page's first frame sizes the seat from the blocks it measured
  last time (identity, pad chrome and notice) rather than from an estimate, so the card flies to
  the seat it lands in.
- **No match** (the page opened from anywhere but the grid, or a grid that no longer holds the
  tile): the card stays seated, poster up, and fades with the page.
- **Taps and the throw.** A tile ignores a tap while its page is already opening and while any
  shared transition is active, so a tap during the flight home cannot open another page mid-air.
  The pad does not take a throw until the card has landed.
- **Reduce motion.** No flip and no travel: library and detail cross-fade.
- **Frame rate.** The flight is in the shell's overlay, so the shell's peak-rate vote while a
  shared transition is active covers it.
- **Tracing.** `CardFlipTrace` in `CardFlip.kt` logs each leg (fresh or reversed, its stagger)
  and each landing under the `FlickFlip` tag in debug builds.

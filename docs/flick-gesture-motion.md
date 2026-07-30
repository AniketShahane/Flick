# Flick gesture — motion spec

The looping animation on the phone's detail sheet ("Flick to TV"), directly above the
CTA. A thumb winds back against a small deck of cards, snaps through it, and the top card
leaves up-and-to-the-right trailing three amber speed bars.

It is the Flick mark in motion. The mark is a play triangle leaving three streaks; the
card that flies here **carries that triangle** and lays down **those same three bars at
those same weights**. Anything that reads as a second logo is wrong.

Implemented in `sender/src/main/java/com/flick/sender/ui/components/FlickGesture.kt`.
The numbers below and the constants in that file are one thing: change either and change
both.

---

## 1. Canvas and scale

The scene is drawn on a grid **48 units tall** and as wide as the strip it is given. The
strip ships at **48 dp tall × the sheet's content width** (≈ 320 dp on a 360 dp phone), so
at ship size **one unit is one dp**. The scene scales uniformly with the strip's height and
is never stretched: a wider sheet buys the card a longer flight, not a wider thumb.

Two anchors derive everything else. `W` is the strip's width in units.

| Anchor | Value |
|---|---|
| **S** — the seat, i.e. the resting card's centre | `x = max(0.28 × W, 44)`, `y = 21` |
| **F** — flight distance | `clamp(W − S.x − 23, 90, 200)` |

Nothing is clipped except by the strip's own bounds, which do cut the thumb (deliberately —
see §2.1) and swallow the card if it ever outlives its fade.

---

## 2. Silhouettes

### 2.1 The thumb

One capsule, and only the distal segment. The hand is never drawn: past the first joint it
would be a hand holding nothing, in a strip 48 dp tall.

- **Capsule**: length **58**, width **17** (fully rounded ends, radius 8.5). Its leading end
  is the *tip*; the capsule runs backwards from the tip along the thumb axis.
- **Rest tip**: `S + (−9, +12)` — just under the resting card's lower-left corner, touching
  it. Contact is the whole reason the pose reads.
- **Rest axis**: **−28°** (0° is horizontal-right; negative rotates counter-clockwise on
  screen, so the thumb points up and to the right).
- The capsule's base therefore falls at roughly `S + (−60, +39)`, which is **off the bottom
  edge of the strip**. That crop is the point: it is a hand entering frame.
- **Nail plate**: rounded rect **13 × 10.6**, radius 3, inset 3.2 from the capsule's long
  edges, its leading edge 3.2 units back from the tip. It is what stops the capsule reading
  as a sausage.
- Fill is translucent, not solid — the thumb crosses the card it is pushing, and a solid
  silhouette would black out the thing the gesture is about. Rim: a 1.4-unit stroke.

### 2.2 The card

- **30 × 20**, corner radius **4.5**, centred on its position.
- Carries the mark's play triangle, in card-local coordinates:
  `(−5.5, −6.5) → (6.5, 0) → (−5.5, +6.5)`, **filled, plus a 2-unit round-join stroke of
  the same colour** so the silhouette is rounded exactly the way the mark's triangle is.
- **Deck offset**: `(−4.5, +4.5)` behind the seat, at **42%** opacity.

Three cards exist at all times, in one of three roles — *flyer* (in the seat), *riser*
(behind it), *dealer* (fading in behind that). See §5.

### 2.3 The speed bars

Three horizontal capsules, stamped in the wake at the seat the card just left. The mark's
own arrangement: the outer pair sit nearer the card, the middle one reaches further back,
and the weights are the mark's **0.45 / 0.75 / 0.45**.

| Bar | Row (y, relative to S) | Length | Right end (x, relative to S) | Weight |
|---|---|---|---|---|
| Middle (fires first) | 0 | 19 | −1 | 0.75 |
| Top | −7 | 15 | +6 | 0.45 |
| Bottom | +7 | 15 | +6 | 0.45 |

Height **4**, fully rounded (radius 2). Each bar draws itself from its right end leftward,
then dissolves where it lies while drifting **+5 units** to the right — drafting behind the
card, never chasing it.

---

## 3. Paths

- **Thumb tip**: travels along its own **−28° axis**, and bows **up to 4 units above the
  straight line** between its two ends. The bow is a half-sine of the travel, so it is
  exactly zero at both the rest pose and full reach — the arc never displaces where the
  gesture starts or stops.
- **Wrist**: the capsule's rotation is separate from the path. It lays **back to −25°**
  while loading and **rolls through to −38°** at full reach. Rotation is linear in travel.
- **Card**: `x` covers **F** on the *Release* curve; `y` rises **8 units** on the *Lift*
  curve, which tops out early and flattens — a card skimmed away, not one thrown up. It
  tilts **0° → −14°** and shrinks **1 → 0.86** across the flight.

---

## 4. Timing

One cycle is **2400 ms**, driven by a single **linear** clock that repeats forever. Every
beat below is an absolute window on that clock, not a duration chained off the last one.

| ms | Beat | Easing | What moves |
|---|---|---|---|
| 0 – 300 | **Rest** | — | Nothing. |
| 300 – 540 | **Wind-up** | Wind | Thumb tip **−7** along the axis. Wrist −28° → **−25°**. Card compresses along the travel axis to **0.91 × 1.07**, tilts **+3°**, and is shoved **2.5 back**. |
| 540 – 580 | **Load hold** | — | Held at the wound pose. 40 ms of nothing is what makes the strike land. |
| 580 – 880 | **Strike** | Release | Thumb tip **−7 → +34**, bowing up to 4 above the chord. Wrist −25° → **−38°**. |
| 620 – 700 | **Snap** | Release | Card squash **0.91 × 1.07 → 1.09 × 0.93** — compression flips to stretch along the direction of travel in 80 ms. |
| 620 – 1040 | **Flight** | Release (x), Lift (y) | Card centre **+F** on x, **−8** on y, tilt **0° → −14°**, scale **1 → 0.86**. |
| 650 – 890 | **Wake — middle bar** | Release, then Fade | Length **0 → 19** over 110 ms; then shortens to **65%** and fades **1 → 0** over 130 ms, drifting +5. |
| 700 – 940 | **Wake — top bar** | Release, then Fade | As above, length 15. |
| 750 – 990 | **Wake — bottom bar** | Release, then Fade | As above, length 15. |
| 700 – 900 | **Unsquash** | Recover | Card scale **1.09 × 0.93 → 1 × 1**. |
| 860 – 1140 | **Riser** | Recover | The card behind the seat moves **(−4.5, +4.5) → (0, 0)** and goes **42% → 100%** opacity. |
| 880 – 1040 | **Exit** | Fade | Flying card **100% → 0%** opacity. |
| 880 – 1240 | **Recovery** | Recover | Thumb tip **+34 → 0**. Wrist −38° → **−28°**. |
| 1140 – 1340 | **Deal** | Recover | A new card fades **0% → 42%** in at the deck offset. |
| 1340 – 2400 | **Rest** | — | Nothing. A full second of stillness; the strip is under text the user is reading. |

### Easing curves

| Name | cubic-bezier | Used for |
|---|---|---|
| **Wind** | `0.20, 0.00, 0.10, 1.00` | Loading — gathers, then settles into the wound pose. |
| **Release** | `0.12, 0.62, 0.24, 1.00` | The flick. Breaks hard, glides long: ~32% of the flight is covered in the first 7% of it. |
| **Recover** | `0.40, 0.00, 0.20, 1.00` | The hand relaxing, the deck resettling. Unremarkable on purpose. |
| **Lift** | `0.05, 0.80, 0.30, 1.00` | The card's rise only. Tops out early and flattens. |
| **Fade** | `0.40, 0.00, 1.00, 1.00` | Things leaving. Accelerates out. |

Nothing here is a spring. Springs are for motion a finger is carrying; this is a loop with
nothing to retarget from.

### Entrance hold

The first cycle starts **620 ms after the screen appears**. The poster is still flying in
from the library over the top of this sheet when the route opens; two arrivals at once read
as neither.

---

## 5. The loop point

The cycle restarts by snapping the clock to zero, so the pose at 2400 ms **is** the pose at
0 ms — with the three cards' roles rotated one place:

| Role | At 0 ms | At 2400 ms |
|---|---|---|
| Flyer | in the seat, 100% | gone, 0% |
| Riser | at the deck offset, 42% | in the seat, 100% |
| Dealer | 0% | at the deck offset, 42% |

So the card that flies away this cycle is the one that faded in at the back of the deck
last cycle. There is no reset, no fade-back, no card that reappears where it was thrown
from. Everything else — thumb, wrist, squash, all three bars — is back at its rest value by
1340 ms and stays there.

### Rest state

The rest frame, which is also frame 0, is:

- Card seated at **S**, upright, full size, 100%.
- Second card at the deck offset, 42%.
- Thumb tip at `S + (−9, +12)`, axis −28°, touching the card's lower-left corner.
- No speed bars.

This frame is what renders **whenever the loop is not running**, which is: the system's
animator scale is off (reduce motion), or the window is not resumed. It is never nothing —
a strip that empties itself when a preference is set reads as a component that failed to
draw. A stopped loop parks *here* rather than wherever it was cut, so the first frame back
is the pose the gesture begins from instead of a frozen mid-flick.

---

## 6. Colour

Every colour is a **role**, never a hex. The app's dark palette is retuned independently of
this component, and the hexes in the last two columns are a snapshot of the roles as they
resolve today — **the role names are the contract**, so a palette change carries the
gesture with it automatically.

| Element | Role | Light | Dark |
|---|---|---|---|
| Card fill (all three) | `primary` | `#1240E8` | `#6E93FF` |
| Play triangle | `onPrimary` | `#FFFFFF` | `#0A1020` |
| Speed bars | `spark` | `#FFB61E` | `#FFB61E` |
| Thumb fill | `fillControl` | 11% `#0A1533` | 11% white |
| Thumb rim | `outlineSoft` | `#C3D0EE` | 24% `#E8EEFF` |
| Nail plate | `fillCard` | 8% `#0A1533` | 8% white |
| Backdrop | the sheet's own `surface` — the strip paints no background | `#F2F6FF` | `#0C0F18` |

Two things fall out of that and are worth stating plainly:

- **The action blue carries the object and the media amber carries the speed**, exactly as
  in the mark: a play triangle in the brand blue leaving amber streaks. If a palette swap
  makes the action role amber, the card becomes amber and the bars follow the media role
  wherever *it* went. The relationship is what is being specified, not the hues.
- **The thumb is translucent in both themes** because its two fills are ink-on-light and
  white-on-dark by definition. It reads as glass over the card it is pushing, which is
  correct: it is a gesture, not an object.

---

## 7. Constraints

- **Decoration only.** No label, no content description, no node in the accessibility tree,
  never focusable. It says nothing the CTA beneath it does not already say.
- **No TV glyph.** The button directly under this strip names the TV. Drawing one here says
  it twice.
- **Not mirrored in RTL.** The flying card carries the play triangle, which is a transport
  symbol and is never mirrored; flipping the scene would flip it with the card.
- **Not shown above a refusal.** When the receiver has already refused this file, the sheet
  prints a caution card instead of the direct-play promise, and a thumb cheerfully flicking
  the film away above that warning is the sheet arguing with itself.
- **Drawn, not an asset.** No image, no vector file, no animation library. Every frame is
  the shapes above under a transform stack, and the clock is read in the draw phase — the
  loop repaints one canvas and recomposes nothing.

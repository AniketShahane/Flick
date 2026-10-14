# Tunnel — the TV side of a cast

Chosen 2026-10-14 from the "Arrival" family. The phone's hyperspace carries on onto the TV as a
tunnel of TV-shaped rings flying toward the viewer, with a glowing window at its far end. At the
film's first frame the **real film starts playing inside that window**, the window draws nearer
for a beat, then flies down the tunnel to fill the screen. Replaces the Flick card's
rise / bob / expand in `HouseLights`.

Mockup (browser canvas, same numbers as below): `docs/design/tv-tunnel-mockup.html`, option B2.

## Goals

| Goal | Meaning |
|---|---|
| On time | The tunnel starts the instant the TV hears the cast (the control message, before any player or Activity work). No fixed holds the phone doesn't know about. |
| Smooth | A locked 60 fps on the Google TV Streamer (its output tops out at 4K 60 fps; there is no 120 Hz path). Zero frames over 16.7 ms through arrival, wait and fly-out, on a **release** build. |
| Real picture | The window shows the player's own video surface, not a copy: no second decode. |

## Timeline

`t0` = the TV hears the cast. `t1` = the film's first rendered frame (the phone's
Connecting → NowPlaying flash fires at this same moment). The wait `t1 − t0` is unbounded
(0.5 s to 8+ s for 4K Dolby Vision), so every waiting motion loops seamlessly.

| Beat | When | What |
|---|---|---|
| Lights down | t0 → +360 ms | Wash fades in over the room, ease-out cubic |
| Surge | t0 → ~+800 ms | Field speed × (1 + 3·e^(−s/280 ms)): it arrives fast and settles to cruise |
| Window appears | t0 + 250 → +650 ms | Ease-out cubic |
| Caption | t0 + 300 → +620 ms | "<device> is flicking <film> (year)" + detail line, at 74 % height |
| Cruise | until t1 | Rings + streaks loop; window breathes ±5 % over 900 ms |
| Film in window | t1 → +220 ms | Film fades in inside the window (over the window's white) |
| Nearer | t1 → +500 ms | Window grows 44 → 120 dp wide, ease-out cubic; caption fades over 300 ms |
| Hold | until t1 + 900 ms | Film plays in the window |
| Fly-out | t1 + 900 → +1460 ms | Window → full screen, ease-in cubic (accelerating at the viewer); centre moves from the vanishing point to screen centre; corner 3 dp → 0; field speed × (1 + 6·e) where e is fly-out progress; rings fade to 50 %; whole tunnel fades over the last 30 % |

## Geometry and colour (dp on the TV's 960 × 540 dp layout)

| What | Value |
|---|---|
| Vanishing point | centre x, 42 % height |
| Wash | radial from the vanishing point: `#1F3AA8` → `#0B1640` at 45 % → `#04070F`; radius 0.75 × width (phone's §2 wash) |
| Rings | 7, each a 16:9 rounded rect (corner 4 % of its width), staggered 1/7 of a life; life = 1700 ms / 0.55; size = phase² × 1.3 × screen width; stroke 1.5 → 3 dp; `rgba(111,160,255,.55)`, ring 2 amber `#FFB61E` at 0.8; fade in over the first 12 % of life, linear out to 0 (seamless wrap) |
| Streaks | 10, radial, evenly spaced ± fixed jitter (sin(i·7.3)·0.2 rad); speed 1× or 2× (every third); 2 dp round caps; born 18 dp out at phase², up to 72 dp long; y squashed × 0.7; white / `#9FC0FF` / amber; same fade as rings; alpha 0.8 |
| Window | 44 dp wide, 16:9, corner 3 dp, fill `#EAF0FF`; glow radial 2.2 × its width: `rgba(220,232,255,.9)` → `rgba(111,160,255,.45)` at 35 % → 0. With the film in it: 1.5 dp `rgba(234,240,255,.6)` hairline, fading as it flies |
| Fly-out wash | `rgba(255,255,255,.35)` flash over the first third of the fly-out |

## Implementation constraints (from the 2026-10-14 recording)

1. The 2026-10-14 debug cast showed the card 3.4 s after the flick, because the main thread spent
   2.5 s rebuilding the playback screen, and 1–3 s main-thread stalls during the card. The tunnel
   must not depend on that work: draw it from one clock in the draw phase (or a RenderThread-driven
   animation) on its own layer; build the player UI before or after, never inside the tunnel's frames.
2. Keep PR #19's fixes: `setEnableComposeSurfaceSyncWorkaround(false)` (the true setting froze the
   window for 1000 ms at the 2nd videoSize), the `WindowEntrance` wait for a summoned cast, and a
   first composition into the handshake still flying.
3. The film in the window is the player's SurfaceView, scaled and clipped (layer transform /
   clip bounds), not a TextureView copy or a second player. On API 34 a SurfaceView's position and
   clip follow the view hierarchy in the same frame; verify there's no tearing or stretch with
   `dumpsys SurfaceFlinger` while it flies. Any display-mode switch for the film's frame rate must
   land before the fly-out, not during it.
4. A reveal must never start into a mode-switch blank (`HouseLights`' resync hold stays, but it
   holds the window, not the whole tunnel).
5. Reduced motion: no tunnel; cut to the film as today.
6. Measure on `release` (R8 + baseline profile), not debug: `dumpsys gfxinfo framestats` and a
   Perfetto trace through t0 → t1 + 1.5 s. Regenerate the receiver baseline profile so the tunnel's
   code paths are AOT-compiled.

# Flick — implementation reference

What is actually built, why each piece exists, and what was measured on real
hardware. Companion docs: [`../README.md`](../README.md) (build & run),
[`../research/`](../research/) (the network research that drove the design),
[`../casting-app-plan.md`](../casting-app-plan.md) (original plan).

Verified end-to-end on a Samsung Galaxy S25 Ultra (sender) → Google TV Streamer
2024 (receiver), same home Wi-Fi (5 GHz, same AP), **no hotspot, no Ethernet**.

## The core thesis

For a user's **own local files**, never transcode and never screen-mirror. The
phone serves the original file bytes over HTTP with byte-range support; the TV
pulls exactly the slices it wants and decodes them in **hardware**. If the
network can move the bits, playback is glass-smooth — everything else in this
repo exists to (a) prove that, (b) keep the network path from silently
degrading, and (c) tell the user *precisely* what is wrong when it can't.

```
┌─────────────── phone (:sender) ───────────────┐        ┌────────────── TV (:receiver) ──────────────┐
│ Photo Picker → content:// URI                 │        │ Pre-flight probe (TCP + ranged GET)         │
│ Ktor CIO HTTP server 0.0.0.0:8080             │  LAN   │  └─ ok → ExoPlayer (Media3) direct-play     │
│  GET/HEAD /video  (byte-range, 206/416)       │ ─────► │     hardware decode only, 15s–180s buffer   │
│  GET /ping → "ok"                             │  http  │     bounded auto-recovery + retry policy    │
│ Foreground service + WifiLock + WakeLock      │        │ Live diagnostics overlay (the truth source) │
│ Transfer telemetry + band warning             │        │ Media-key seeking (±15 s / ±5 min)          │
└───────────────────────────────────────────────┘        └─────────────────────────────────────────────┘
```

## HTTP contract

The sender binds `0.0.0.0:8080` and both sides agree exactly on:

| Endpoint | Behavior |
|---|---|
| `GET /video` | Full byte-range support: `Accept-Ranges: bytes`; `Range: bytes=a-b` → `206` + `Content-Range`; unsatisfiable range → `416`; malformed range → `200` full body; correct `Content-Type`/`Content-Length`. |
| `HEAD /video` | Same headers, no body (ExoPlayer and probes use it). |
| `GET /ping` | `ok` — cheap liveness check for humans and probes. |

Serving streams slices straight out of a `ParcelFileDescriptor` via
`FileChannel.position(start)` — no copy of the file is ever made, so an 8 GB
file costs no storage and starts serving instantly.

## Sender (`:sender`, phone)

| Piece | What it does |
|---|---|
| `MainActivity` | Compose UI: Photo-Picker video selection, serving state, live transfer stats, Wi-Fi band readout + **2.4 GHz warning**, battery-exemption card. |
| `CastServerService` | Foreground service (`mediaPlayback` type) that owns the HTTP server for the whole session so serving survives screen-off and backgrounding. |
| `MediaHttpServer` | The Ktor CIO server implementing the contract above; instruments every served chunk. |
| `TransferTelemetry` | Thread-safe counters (atomics; zero allocation in the per-chunk hot path) → throughput EMA, total bytes, last-request age, in-flight request count, published as a `StateFlow` and rendered ~1 s in the UI. Answers the diagnostic question: *"did the TV stop asking, or did the phone stop serving?"* |
| `NetworkUtils` | Site-local IPv4 discovery for the displayed URL; Wi-Fi link sampling (frequency → band, link speed, RSSI — never the SSID, which is both a privacy leak and a location-permission tax). |

**Wireless hygiene (the #1 stall killer for phone-server casting apps):** while
serving, the service holds a `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF` — the
`LOW_LATENCY` mode is only honored while the screen is ON, and this server must
run full-rate with the screen OFF) plus a partial `WakeLock` (6 h safety
timeout). Lock acquisition and the server start run as one generation-guarded
critical section, so a Stop that races a Start can never leak a lock or
resurrect an orphaned server; every teardown path releases both locks. A
dismissible card offers the OS battery-optimization exemption
(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) because OEM task-killers
(Samsung especially) will otherwise kill even foreground services mid-movie.

Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
`FOREGROUND_SERVICE` (+`_MEDIA_PLAYBACK`), `POST_NOTIFICATIONS`, `WAKE_LOCK`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## Receiver (`:receiver`, Android TV)

### Player configuration (`PlayerController`)

Media3 ExoPlayer, built for LAN direct-play of large 4K files:

| Setting | Value | Why |
|---|---|---|
| Renderers | Hardware only — decoder fallback **off**, extension renderers **off** | A hardware-decode failure must surface as a visible error, never silently fall back to software (which would mask the exact failure this app exists to expose). |
| Min / max buffer | 15 s / **180 s** | A real-world ~70 s wireless outage drained the original 60 s cap and caused a 12 s stall; 180 s makes that entire class of event invisible. At typical 4K bitrates this is ~50–150 MB. |
| Playback / post-rebuffer threshold | 2.5 s / 5 s | Fast start and fast seek resume. |
| Byte target | 256 MB, `prioritizeTimeOverSizeThresholds(true)`, `largeHeap` | Time drives buffering on a fast LAN; the byte cap bounds memory for very-high-bitrate files. |
| Back-buffer | **30 s**, keyframe-aligned | Short backward seeks replay from memory with zero network. |
| HTTP source | connect 15 s, read 30 s, no cross-protocol redirects | A dead sender surfaces as an error instead of hanging. |

### Resilience (what makes plain Wi-Fi survivable)

1. **Generous retry policy** — a custom `LoadErrorHandlingPolicy` (base retry
   count 20, backoff `min(1000·(n+1), 5000)` ms ≈ 100 s of quiet retrying):
   every retry is a byte-range request, i.e. a perfect resume. It delegates to
   Media3's default classification first so genuinely-fatal errors
   (`ParserException` etc.) still fail fast, and 4xx responses (except 416)
   fail immediately so the diagnosis UI takes over.
2. **Bounded auto-recovery** — on a fatal-but-plausibly-transient error
   (network/IO/timeout error codes, or Media3's `StuckPlayerException` anywhere
   in the cause chain): silently `seekTo(last good position)` + `prepare()` on
   the same player after a backoff (2 s → 4 s → 8 s → 15 s, max 4 attempts;
   budget re-arms after 30 s of stable playback). Non-transient errors (4xx,
   decoder init) go straight to the error UI. Pending recoveries are cancelled
   on stop/release/backgrounding.
3. **Pre-flight probe** — before playback ever starts: raw TCP connect (2 s)
   then a real `Range: bytes=0-1023` GET (3 s). The result maps to a *specific*
   diagnosis card instead of a generic spinner-then-timeout: **unreachable**
   (router peer-block / AP isolation / wrong IP — with recovery advice),
   **not serving** (phone reached, app not running — including a TCP-connected
   server that won't answer HTTP), **server error N**, **bad URL** (including
   out-of-range ports, which `java.net.URL` accepts silently). Probe latency is
   surfaced in the overlay. The probe is lifecycle-gated so backgrounding
   mid-probe can never start playback into a stopped activity.

### Seeking

- Media transport keys are tunneled at the Compose root (they work regardless
  of which control holds D-pad focus): **FF/RW = ±15 s**, **next/previous =
  ±5 min**, **play/pause** toggles. Targets are clamped to `[0, duration−1 s]`.
- Seek-induced buffering is tracked as a **seek fill** (`DISCONTINUITY_REASON_SEEK`
  opens a window; reaching `STATE_READY` closes it; in-buffer seeks that never
  leave READY are closed after a 700 ms settle from the snapshot tick) and is
  **never counted as a rebuffer**, so the zero-stall pass metric stays honest.
- The overlay shows `Seeks / last fill` (count + how long the last seek took to
  render).

### Diagnostics overlay (the truth source)

~2 Hz snapshot of: state, resolution + 4K flag, frame rate, **rebuffers**
(post-start only, seek-aware) + cumulative rebuffer time (ticking live),
dropped frames, buffered-ahead, bandwidth estimate, decoder name (proof of
hardware decode), **TV's own Wi-Fi band / link speed / RSSI** (driver-invalid
values omitted; wired/unknown labeled as such), auto-recoveries, seeks/last
fill, probe latency, position/duration, source URL, and full error details.
Banner: **DIRECT-PLAY OK** ⇔ `rebuffers == 0 && dropped == 0 && buffer > 0`
while playing; anything else flips it to DEGRADED/ERROR so a stall is
impossible to miss.

Lifecycle: the decoder is released whenever the activity stops and rebuilt
(URL + position restored) on start, so the hardware decoder is never held in
the background; refresh-rate matching is applied when the content frame rate
becomes known.

## Measured results (real hardware, 2026-07-19)

Setup: 5.3 GB 4K Dolby Vision MKV, phone → TV over home Wi-Fi (both on the
same 5 GHz AP, TV link 780 Mb/s @ −53 dBm), no hotspot, no Ethernet.

| Measurement | Result |
|---|---|
| Pre-flight probe | **81 ms** |
| Playback | Hardware Dolby Vision decode (`c2.mtk.dvhe.sth.decoder`), **0 rebuffers, 0 dropped frames** |
| Buffer fill | ~54 s buffered within ~12 s of pressing Play; climbs to the 180 s target (refill observed at **>120 Mb/s**) |
| Cold seek **+5 min** (far outside buffer) | picture back in **1.9 s** |
| Seek **−5 min** | **0.73 s** (1 frame dropped at the seek boundary) |
| In-buffer skips (±15 s) | effectively instant (back-buffer / forward buffer) |
| Screen-off serving | Sustained — `WifiLock` + `WakeLock` doing their job |
| Absorbed real outage | A ~70 s wireless delivery gap drained the old 60 s buffer with only a 12 s stall and **zero errors** (retry policy healed it); motivated the 180 s buffer, under which the same event is invisible |

Network findings that shaped all of this live in [`../research/`](../research/):
notably, the home-LAN "client isolation" failure was re-diagnosed as a
**dynamic, pair-specific router-side peer block** (research 03), and the
Google TV Streamer **does not declare `android.hardware.wifi.direct`**
(feature check on the real unit), permanently ruling out an automatic
Wi-Fi-Direct bypass on this hardware.

## Known limitations / next steps

- **Seek UI:** the Streamer's minimal remote has no FF/RW keys — an on-screen
  scrub bar driven by the D-pad is the natural next receiver feature (the seek
  engine underneath is done and measured).
- **Discovery:** the phone's IP is typed manually on the TV. NSD/mDNS as a
  hint plus QR pairing would remove that step (never as the transport check —
  that's the probe's job).
- **Security:** cleartext HTTP, LAN-only, no auth token yet; plan is TLS with
  an ephemeral pinned cert + request token.
- **Android 16/17:** the Local Network Permission (`ACCESS_LOCAL_NETWORK`,
  mandatory at targetSdk 37) will gate the sender's inbound server — needs an
  explicit permission path distinct from the "unreachable" diagnosis.
- **The router experiment** (research 03 §"discriminating experiment") hasn't
  been run to completion: the dynamic peer block hasn't re-engaged since the
  hardening landed. If it does, the probe + telemetry are built to catch and
  classify it live.
- The strict pass banner counts a single dropped frame at a seek boundary as
  "degraded" — arguably the threshold should tolerate ~a frame per seek.

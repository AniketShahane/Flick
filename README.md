# Flick — zero-buffer local casting, phone → Android TV

A two-app Android project that casts your **own local 4K / 1080p videos**
(including HDR / Dolby Vision) from a phone to an Android TV over plain home
Wi-Fi, with **zero-stall direct play proven on real hardware** — plus a
hardening layer, instant seeking, and live diagnostics on both ends.

**Docs:** [`docs/implementation.md`](docs/implementation.md) — the complete
implementation reference (every component, tuning constant, and measured
result) · [`research/`](research/) — the network deep-dives behind the design.

## What Phase 0 proves

The thesis: for a user's own local file you never need to transcode and you never
need to screen-mirror. The phone serves the **original file bytes** over HTTP on
the LAN; the TV pulls them with HTTP byte-range requests and decodes them in
**hardware**. If the network can move the bits, playback is glass-smooth.

Phase 0 exists to demonstrate that empirically:

- **NEVER transcode** — the sender streams the raw file untouched.
- **NEVER screen-mirror** — the TV opens the URL and decodes locally.
- **Zero stall** — proven with a live on-screen measurement overlay (see
  [The Measurement Protocol](#the-measurement-protocol)).

### Project structure

Gradle multi-module project. Two Android **application** modules:

| Module      | Runs on        | Package                  | Role                                                                 |
|-------------|----------------|--------------------------|---------------------------------------------------------------------|
| `:sender`   | Phone          | `com.flick.sender`   | Pick a local video; run an embedded HTTP server (Ktor CIO) on `:8080`. |
| `:receiver` | Android TV     | `com.flick.receiver` | Enter the phone IP + token; play `http://<phone-ip>:8080/v/<token>` with ExoPlayer (Media3) + debug overlay. |

**HTTP contract** (both sides agree exactly): the sender binds its **LAN IP** on
`:8080` (not `0.0.0.0`) and serves `GET /v/{token}` with full byte-range support
(`Accept-Ranges: bytes`, `206 Partial Content` + `Content-Range` for `Range`
requests, `HEAD` supported) plus `GET /ping` → `ok`. `{token}` is a per-session
128-bit secret minted on the phone and shown in the cast URL, so only a client
that knows it can fetch the file; the handler also pins the `Host` header to the
LAN IP (anti-DNS-rebinding) and caps concurrent transfers. The receiver opens
`http://<phone-lan-ip>:8080/v/<token>`. See [`SECURITY.md`](SECURITY.md).

## Prerequisites

- **Android Studio** (2024.3 / Meerkat or newer — needed for AGP 8.9 + compileSdk 36).
- **JDK 17** (bundled with recent Android Studio).
- **`gradle-wrapper.jar` must be generated once.** This repo ships the wrapper
  scripts and `gradle-wrapper.properties` (Gradle 8.11.1) but not the binary
  `.jar`. Generate it once before building from the CLI:
  ```bash
  gradle wrapper   # requires a local Gradle install
  ```
  Or simply **open the project in Android Studio** — it materializes the wrapper
  automatically on first sync. After that, `./gradlew …` works.
- **Two devices on the SAME Wi-Fi:** an Android **phone** and an Android TV /
  Google TV **device or emulator**.
  - **Use 5 GHz Wi-Fi (ideally Wi-Fi 6).** 2.4 GHz Wi-Fi is the single most
    common cause of 4K stalls — it rarely sustains the ~40–100 Mbps a 4K file
    needs. If you can only test on 2.4 GHz, expect the overlay to show rebuffers.
  - Both devices must be on the **same subnet** (same router/SSID) so the TV can
    reach the phone's LAN IP. Guest networks and client-isolation modes block this.

## Build & install

From the repo root, after the wrapper exists:

```bash
# Sender -> phone   (list devices first with `adb devices`)
./gradlew :sender:installDebug

# Receiver -> TV
./gradlew :receiver:installDebug
```

With two devices attached, target them explicitly:

```bash
ANDROID_SERIAL=<phone-serial> ./gradlew :sender:installDebug
ANDROID_SERIAL=<tv-serial>    ./gradlew :receiver:installDebug
```

Or in Android Studio: select the `sender` run config → phone, and the
`receiver` run config → TV, and Run each.

## Run the spike end to end

1. **Phone:** open **Flick Sender**, grant media access, tap to **pick a
   local 4K/1080p video**. The app starts the LAN server and displays the exact
   URL, including the per-session token, e.g. `http://192.168.1.42:8080/v/Xk3q…`.
   It keeps serving via a foreground service, so the video keeps streaming while
   the screen is off.
2. **TV:** open **Flick Receiver**. The URL field is pre-filled with
   `http://:8080/v/`. **Type the phone's IP and the token** shown on the phone so
   the field matches the exact URL from step 1, e.g. `http://192.168.1.42:8080/v/Xk3q…`.
   (A QR/pairing flow that removes the typing is the planned next step.)
3. **TV:** press **Play**. The video decodes on the TV and the **debug overlay**
   appears.

Sanity check the link first by opening `http://<phone-ip>:8080/ping` in any
browser on the same network — it should return `ok`.

## The Measurement Protocol

This is the point of Phase 0. The receiver renders a live **debug overlay** with a
big **DIRECT-PLAY OK / PLAYBACK DEGRADED / PLAYBACK ERROR** banner and these
metrics, updated continuously:

| Metric              | What it means                                              | PASS reading                     |
|---------------------|------------------------------------------------------------|----------------------------------|
| **Rebuffers**       | Stall count *after* playback starts                        | **stays `0`** (and never "stalling…") |
| **Rebuffer time**   | Cumulative ms spent stalled                                | `0 ms`                           |
| **Dropped frames**  | Frames the decoder couldn't render in time                 | **`0` / ~0**                     |
| **Buffered ahead**  | Seconds of video buffered past the play head               | **stays `> 0`** while playing     |
| **Resolution**      | Decoded video size                                         | **`3840 x 2160`** for 4K UHD     |
| **4K UHD**          | Convenience flag (`width ≥ 3840 && height ≥ 2160`)         | `YES` for a 4K source            |
| **Frame rate / Decoder / Est. bandwidth** | Live fps, the active codec decoder, measured Mbps | Decoder is a HW codec; Mbps ≥ source bitrate |

### PASS condition (exact)

The banner reads **DIRECT-PLAY OK** only when, *while actually playing*:

```
rebufferCount == 0  AND  droppedFrames == 0  AND  bufferedAhead > 0
```

In words: after playback starts, **rebuffer count stays 0**, **dropped frames are
~0**, **buffered-ahead stays positive**, and for a 4K clip **resolution reads
3840 x 2160**. Hold that for the length of the clip and the direct-play thesis is
proven. Any stall flips the banner to **PLAYBACK DEGRADED** (yellow) so it's
impossible to miss.

### If it stalls (banner turns yellow/red)

- **Move to 5 GHz / Wi-Fi 6.** By far the most likely fix — 2.4 GHz cannot
  sustain 4K bitrates. Get both devices close to the AP.
- **Check the source bitrate** vs. the overlay's **Est. bandwidth**. If the file
  is, say, 80 Mbps but the link only measures 40 Mbps, the network — not the app
  — is the limit. That's physics, not a bug (see caveats).
- **Confirm the TV hardware-decodes the codec.** Check the **Decoder** line: it
  should name a hardware decoder (`c2.*`/`OMX.*`), not a software one
  (`c2.android.*` software fallback). If the TV lacks a HW decoder for the
  codec (**HEVC / VP9 / AV1**), dropped frames climb — test a clip in a codec the
  TV supports in hardware.
- Confirm phone and TV are on the **same subnet** and the phone screen going off
  didn't kill Wi-Fi (battery/Wi-Fi power-saving on the phone can throttle the AP link).

## Honest caveats

- **Bandwidth is physics.** This spike does not create bandwidth. If the LAN
  can't sustain the file's bitrate, it will stall — correctly reported by the
  overlay. The fix is a better link, not a code change (Phase 0 deliberately
  never transcodes to paper over it).
- **Scope is your OWN local files only.** Phase 0 is about serving files you
  already have on the phone. No streaming-service integration, no library, no
  cloud.
- **DRM content is out of scope by design.** Netflix, Disney+, etc. are
  encrypted and license-locked; you cannot serve their bytes over a plain HTTP
  server, and this spike does not try. It targets unencrypted, user-owned media.
- **Cleartext HTTP is LAN-only.** The **sender no longer permits cleartext at
  all** — it only *serves* over a raw socket, which `network-security-config`
  does not govern. The **receiver** still permits it because ExoPlayer fetches
  over plain HTTP on the LAN, and Android's NSC cannot scope cleartext to an IP
  range (`<domain>` matches literal hostnames only, and peers here are raw LAN
  IPs), so that allowance stays global for now. The per-session **request token
  is already implemented** (see [`SECURITY.md`](SECURITY.md)); **Phase 1** adds
  TLS with an ephemeral cert pinned to the negotiated peer, which removes the
  receiver's cleartext allowance too.

## Hardening layer

Phase 0 proved the physics; this layer keeps plain home Wi-Fi from *silently*
degrading it (see `research/03-plain-wifi-re-diagnosis.md`).

- **Phone (sender):** while serving it holds a `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`)
  and a partial `WakeLock` so screen-off Wi-Fi power-save and CPU sleep can't
  throttle the socket, and it offers an optional battery-optimization exemption
  prompt for OEM task-killers. The UI shows live transfer stats (throughput,
  last-request age) and warns when the phone is on a 2.4 GHz band.
- **TV (receiver):** the ExoPlayer `LoadErrorHandlingPolicy` retries byte-range
  requests generously and auto-recovers from transient outages within a bounded
  cap (a micro-outage becomes a silent resume, not a dead player). A pre-flight
  reachability probe (TCP connect + real byte-range GET) runs before playback and
  diagnoses the specific failure — **unreachable** (peer block / wrong IP) vs
  **not serving** (server down) vs **server error** — instead of a generic
  timeout. The debug overlay adds the TV's own Wi-Fi band and RSSI.

## Buffering & seeking

The receiver buffers up to **180 s ahead** (~50–150 MB at 4K bitrates; a
real-world ~70 s wireless outage motivated the size) and keeps a **30 s
back-buffer** so short rewinds replay from memory. Media transport keys work
from any remote or `adb`: **FF/RW = ±15 s**, **next/previous = ±5 min**,
**play/pause**. Seek-induced buffering is tracked as a separate "seek fill"
metric — never as a rebuffer — and measured on hardware a **+5 min cold jump
resumes in ~1.9 s**, a −5 min jump in ~0.7 s, and in-buffer skips are instant.

See [`docs/implementation.md`](docs/implementation.md) for the full reference.

---

*Do not run a Gradle/Android build without an Android SDK present. Build on a
real machine or in Android Studio.*

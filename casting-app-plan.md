# Best-in-class 4K/1080p Casting App — Architecture & Build Plan

> **Goal:** Cast 4K/1080p HD videos from an Android phone to an Android TV with best-in-class quality and, above all, **no buffering**.
>
> _Version facts grounded against the live Android docs (Jetpack Media3 **1.10.1**, released May 12 2026). Google Cast Connect specifics are from prior knowledge (the `developers.google.com` docs were unreachable when this plan was written) and are flagged with ⚠️ to re-verify against the current Cast documentation._

---

## 1. The one idea that actually kills buffering

Buffering is not a feature you polish — it's **physics**. A player stalls when its buffer drains to empty because bytes aren't arriving faster than they're consumed. So the entire app is organized around one thesis:

> **Direct-play the original file bytes over a fast local link, and let the TV do the decoding — never transcode in real time, and never mirror the screen.**

Everything else (discovery, UI, controls) is commodity. The two things that cause 4K stalls are:

1. **On-the-fly transcoding on the phone** — CPU/thermal-bound, can't sustain 4K, drains battery, throttles under heat.
2. **Insufficient or unstable Wi-Fi throughput** — 2.4 GHz, congestion, distance, walls.

Eliminate transcoding and guarantee network headroom, and stalls become essentially impossible. That's the whole game.

---

## 2. Being honest about "zero buffering"

*Literally* "never buffers under any condition" is not achievable — it's a network-physics guarantee no app can make (a microwave oven or a neighbor's Wi-Fi can wreck a 2.4 GHz link mid-playback). What **is** achievable, and what "best in class" actually means:

- **Direct play** so there's no transcode bottleneck — the phone just serves bytes.
- **Pre-flight network probing** so you *know* before pressing play whether the link can sustain the file's bitrate.
- **Aggressive pre-buffering** (tens of seconds on a fast LAN, because bandwidth vastly exceeds bitrate) so jitter is absorbed.
- **A graceful fallback ladder** that degrades quality *instead of stalling* if the link can't keep up.

On a decent 5 GHz / Wi-Fi 6 link, direct-play 4K genuinely never stalls, because you have 2–4× bandwidth headroom (see §6). The engineering is about *guaranteeing* you're in that regime and degrading gracefully when you're not.

---

## 3. Recommended architecture

Build a **companion pair**, not a single app:

| Component | Runs on | Role |
|---|---|---|
| **Sender app** | Android phone | Library browser (your videos), device discovery, playback controls, runs a local HTTP media server, decides direct-play vs. remux vs. transcode |
| **Native receiver app** | Android TV / Google TV | Receives the cast session via **Cast Connect**, pulls the file from the phone's local server, decodes in hardware with Media3/ExoPlayer under full buffer control |

**Data flow:** The phone hands the TV a URL like `http://<phone-ip>:<port>/media/<token>`. The **TV pulls the original bytes directly** and decodes them with its own hardware decoder. The phone is only a file server + remote control — its CPU never touches the video. That is why it can sustain 4K without heat or battery drain.

**Why a native receiver instead of the stock Chromecast receiver:** The built-in/web receiver (the "Default Media Receiver," a Chrome instance) is limited to what its `<video>` element exposes and gives little control over buffering. A native Media3 receiver launched via **Cast Connect** lets you:

- Use the TV's *full* hardware decode (HEVC Main10, VP9 Profile 2, AV1 where present, Dolby Vision/HDR10+).
- Set your own large `LoadControl` buffers.
- Render sidecar subtitles (SRT/ASS/PGS).
- Do multi-audio track selection.
- Match display refresh rate to the content.

That is the "best in class, no buffer" tier.

**Why Cast Connect (vs. rolling your own discovery):** You get Google's mature, battle-tested discovery, session management, volume/queue control, and the familiar Cast button UX users already know — *plus* your own native player. You don't reinvent mDNS pairing and remote-control protocols.

> ⚠️ **Verify against current Cast docs:** Cast Connect requires registering a receiver app ID in the Google Cast SDK Developer Console and using the Android TV Receiver library (`CastReceiverContext`, `MediaManager`). Confirm current setup/requirements.

---

## 4. Alternatives considered — and why they're rejected

- **Screen mirroring / Cast-your-screen / Miracast — rejected.** Real-time screen encoding is lossy, high-latency, and judders badly at 4K. The opposite of what you want. Never use it for video playback.
- **DLNA/UPnP (BubbleUPnP-style) — secondary at best.** Open and works with some TVs, but discovery/control is clunky, renderer codec support is inconsistent, and the UX is dated. Worth adding *only* as a fallback for non-Cast TVs.
- **WebRTC — rejected for this use case.** Its congestion control is designed to *sacrifice quality to protect latency* — the wrong trade-off for buffered VOD. Pull-based HTTP with a big buffer gives higher quality and simpler code.
- **Server-transcode model (Plex/Jellyfin) — overkill for local.** Introduces the transcode you're trying to avoid. But borrow their **direct-play-vs-transcode decision logic** — it's the best prior art for exactly this problem.

---

## 5. The zero-buffer engine (the core subsystem)

This is where the product wins or loses. Four parts:

**1. Codec/bitrate probe (phone).** On selecting a file, read container, video codec, profile/level, bit depth, HDR type, resolution, frame rate, and *peak* bitrate (not just average — peaks cause stalls). `MediaExtractor`/`MediaFormat` covers most; keep a bundled parser for edge containers (MKV/PGS).

**2. Receiver capability report (TV → phone).** The receiver enumerates its real decoders via `MediaCodecList` at runtime and sends a capability profile back over the Cast message channel. This matters because "Android TV" spans thousands of devices (a 2020 Chromecast, a Google TV Streamer, an Nvidia Shield, a TCL panel) with very different decoders — never hardcode; detect.

**3. Playback decision.** Match (1) against (2):
- **Direct play** if the TV can decode the source natively → serve original bytes. *(Target this ~90%+ of the time.)*
- **Remux only** if just the container is unsupported (e.g., MKV → fMP4) → cheap, lossless, no re-encode. Prefer this over transcoding always.
- **Transcode** only as last resort, and transcode *down* to a comfortably sustainable target (e.g., 1080p high-bitrate) using **hardware** encode — never a real-time 4K→4K software transcode.

**4. Network sustainability gate + buffer strategy.**
- Run a quick **throughput probe** to the TV before playback; compare sustained Mbps against the file's peak bitrate with margin.
- On the receiver's ExoPlayer, use a custom `DefaultLoadControl` with a large buffer (tens of seconds is fine on a LAN where bandwidth ≫ bitrate) and tuned `bufferForPlaybackMs` / `bufferForPlaybackAfterRebufferMs`.
- Use Media3's **preload manager / pre-caching** (added in 1.10.x) to pre-buffer the next queue item so transitions are instant.
- If the gate fails, **degrade, don't stall**: offer 5 GHz guidance, or step to remux/transcode-down, or Wi-Fi Direct turbo mode — but keep playing.

**Wi-Fi Direct as "turbo mode" (advanced):** A direct phone↔TV P2P link bypasses a congested router. Useful as an opt-in for hostile networks, but it can disrupt the phone's internet and complicates Cast discovery, so it's an advanced toggle, not the default. (Wi-Fi Aware/NAN is even better on paper but TV support is too spotty in 2026 to rely on.)

---

## 6. Codec & bandwidth reality (concrete numbers)

The thesis holds because the numbers are comfortable:

| Content | Typical bitrate | | Link | Real-world throughput |
|---|---|---|---|---|
| 1080p | 8–25 Mbps | | Wi-Fi 5 (5 GHz) | 200–400 Mbps |
| 4K streaming-grade (HEVC) | 15–25 Mbps | | Wi-Fi 6/6E | 500 Mbps–1 Gbps+ |
| 4K high-quality (HEVC) | 40–80 Mbps | | 2.4 GHz | 20–60 Mbps ⚠️ |
| 4K Blu-ray remux (HEVC) | up to ~100–128 Mbps peaks | | | |

Even a ~100 Mbps 4K remux has 2–4× headroom on a good 5 GHz link → direct play never stalls. The **only** danger zone is 2.4 GHz / weak signal, which is exactly what the pre-flight probe catches and warns about. **The single most impactful thing your app can do for "no buffering" is detect and push users onto 5 GHz.**

**Decoder support to detect (don't assume):** HEVC Main10 (HDR10/10+/Dolby Vision) is near-universal on 4K Google TV devices; VP9 Profile 2 common; **AV1 hardware decode is present on the Google TV Streamer (2024) but *not* reliably on the 2020 Chromecast with Google TV or Nvidia Shield** — so AV1 files often need the capability check to route to remux/transcode.

---

## 7. Tech stack (current as of mid-2026)

- **Language/UI:** Kotlin; Jetpack Compose (phone); **Compose for TV** (`androidx.tv`) on the receiver.
- **Player (both sides):** **Jetpack Media3 1.10.1** (ExoPlayer) — *confirmed current, released May 12 2026*.
- **Cast (sender):** Google Cast SDK (`play-services-cast-framework`) + **`androidx.media3:media3-cast`** using the new **`RemoteCastPlayer`** (1.10.x adds `setTrackSelector` for audio/subtitle track selection).
- **Cast (receiver):** Android TV Receiver library + `CastReceiverContext`/`MediaManager` (Cast Connect). ⚠️ re-verify current API.
- **Local media server (phone):** Embedded **Ktor** or NanoHTTPD, streaming from `ContentResolver` with **HTTP byte-range** support; bind to LAN only; per-session **token in the URL**; auto-shutdown when the session ends (security — otherwise any LAN device could pull your files).
- **Remux/transcode fallback:** **`androidx.media3:media3-transformer`** (hardware-accelerated via MediaCodec) — the modern replacement for old ffmpeg-mobile approaches.
- **Storage access:** MediaStore + Android Photo Picker / SAF; `READ_MEDIA_VIDEO` granular permission (Android 13+).
- **Best-in-class touches on the receiver:** `MATCH_CONTENT_FRAME_RATE` / `Display.Mode` for **refresh-rate matching** (kills 24p-on-60Hz judder), correct HDR color-space handoff, gapless/resume, sidecar subtitle rendering.

### Gradle sketch (sender)

```kotlin
dependencies {
    val media3 = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3") // remux/transcode fallback
    implementation("com.google.android.gms:play-services-cast-framework:<latest>")
    // Embedded local HTTP server, e.g. Ktor or NanoHTTPD
}
```

---

## 8. Phased roadmap (de-risked)

**Phase 0 — Spike (1–2 wks).** Prove the physics on *your* hardware: sender serves a local 4K HEVC file over HTTP byte-range; a plain Media3 receiver pulls and plays it; measure real throughput and confirm zero stalls on 5 GHz. Validates the whole thesis before you build UI.

**Phase 1 — Ship a sender-only MVP (no TV install needed).** Phone app: library browser, Cast discovery, `RemoteCastPlayer`, local HTTP server, point the **stock/default receiver** at it. Works immediately for codecs the built-in receiver supports. Gets a usable product out fast.

**Phase 2 — Native receiver + Cast Connect (the "best in class" tier).** Add the Android TV receiver app: full hardware decode, custom large buffers, capability reporting, refresh-rate matching, subtitle/track selection, AV1/Dolby Vision. This is where "no buffering at 4K" becomes real.

**Phase 3 — Robustness & polish.** Pre-flight network probe + 5 GHz guidance, remux/transcode-down fallback via Transformer, Wi-Fi Direct turbo mode, queue pre-caching, resume/continue-watching, optional DLNA target for non-Cast TVs.

---

## 9. Top risks & how to retire them

- **Cast Connect registration/review overhead and possibly-changed APIs** → verify against live Cast docs and register the receiver app ID early (Phase 0).
- **AV1 / exotic-codec files** → the capability-detection + remux/transcode path is mandatory, not optional.
- **The 2.4 GHz user** → pre-flight probe + explicit in-app guidance; this is your #1 support issue, design for it.
- **Two apps to distribute** → the native receiver must be installed on the TV (Play Store on Android TV or sideload); Phase 1's stock-receiver path is your fallback so users aren't blocked.
- **DRM-protected content (Netflix etc.) won't work** — and shouldn't; this app is for the user's *own* local videos. Good, that's your scope.

---

## 10. Recommended next step

Start with the **Phase 0 spike** — a runnable sender local-HTTP-server + a minimal Media3 receiver that proves zero-stall 4K on your actual hardware/network. It validates the entire "no buffering" thesis before any UI investment, and everything else builds on it.

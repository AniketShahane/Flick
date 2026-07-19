# Research: reliable local 4K casting phone → Android TV

Two deep-research efforts (each: 5 Sonnet 5 research agents + 5 adversarial verifiers + 1 synthesis, live web search) run **2026-07-18** to answer how to make this app stream/deliver a user's own local 4K HDR / Dolby Vision files to an Android TV **reliably, over a normal home Wi-Fi, with minimal setup.**

- [`01-streaming-over-home-wifi.md`](./01-streaming-over-home-wifi.md) — can *live streaming* just work over home Wi-Fi (beat client isolation + band/VBR limits) without a hotspot?
- [`02-fast-transfer-to-tv.md`](./02-fast-transfer-to-tv.md) — what's the *absolute fastest* way to copy an ≤8 GB file to the TV's storage and play it locally (32 GB, watch-then-delete)?

## Combined verdict (the decision-relevant bottom line)

**Client isolation is a router-level L2 wall that no app can cross in software.** Every major casting stack (Google Cast, AirPlay, DLNA/UPnP, Plex, Jellyfin) documents the same single fix — "disable AP/client isolation on your router" — and none ships a software workaround. Google Cast's own local-file path is architecturally identical to ours (phone HTTP server + TV pulls over LAN) and hits the same wall.

Crucially, **the "transfer instead of stream" idea does *not* escape isolation either** — a LAN copy is blocked exactly like a LAN stream, because isolation is a *binary connectivity block, not a slowdown* (bulk-transfer's tolerance for slow links can't rescue zero throughput).

Only four paths cross client isolation, for **both** streaming and transfer:

| Path | Crosses isolation? | Notes |
|---|---|---|
| **Wired Ethernet on the TV** | ✅ (usually) | One-time, permanent; also removes the 2.4/5 GHz VBR problem. Not universal (some mesh/enterprise "Net Isolation" blocks wired↔wireless too). |
| **USB exFAT drive** | ✅ always | Only transport *verified on real Google TV Streamer hardware*; ~3 min/8 GB (USB2-class ~46 MB/s, corrected down from an initial USB3 overclaim). |
| **Wi-Fi Direct** | ✅ architecturally | **Unverified on this TV's chipset** — the single biggest open question. Spec sheet silent; MediaTek driver hints lean "no." |
| **Cloud round-trip** | ✅ | Slow (45+ min just to upload 8 GB); privacy/quota cost. Last resort only. |

## Recommendation

**Stream-first, transfer as fallback. And for this user's isolating router, the cleanest fix is to wire the TV via Ethernet** — it defeats both failure modes at once, is permanent, and keeps the simple live-streaming already proven to work (0 rebuffers, 45 s buffer, Dolby Vision hardware decode).

Decision logic the app should implement:

1. **Pre-flight probe** (real TCP connect + byte-range GET — *not* mDNS "found it", *not* `InetAddress.isReachable()`) classifies **OK / isolated / weak-band / permission-denied** in < 2 s.
2. **OK** → live stream (default; simplest, instant, seekable).
3. **Weak-band** → widen Media3 buffers (sized for *peak* not average bitrate) + warn; solvable fully in-app.
4. **Isolated** → ranked recovery: (a) "wire the TV via Ethernet" tip, (b) one-tap app-orchestrated hotspot / Direct Link (QR pairing), (c) router-settings guide, (d) fallback to **transfer** (USB exFAT, or Wi-Fi Direct if the on-device feature check passes, or cloud).
5. **permission-denied** → distinct Android 16/17 Local-Network-Permission path (must not be misdiagnosed as isolation).

## Cross-cutting facts to bank

- **Local playback uses the identical decoder as streaming** → Dolby Vision/HDR success (`c2.mtk.dvhe`) carries over unchanged; that part is de-risked by the architecture. But local playback fixes *network* problems, not *decoder* bugs.
- **MediaTek decoder gotchas:** DV Profile 7 (dual-layer) is mishandled → prefer Profile 8.1; black-screen bug when HDR10+ and DV coexist in one HEVC MKV → prefer MP4 remux where we control the source.
- **Audio:** decodes Dolby Digital / DD+ (Atmos via E-AC3 JOC) internally; does **not** passthrough lossless TrueHD/DTS-HD/DTS:X. Use `AudioTrack.isDirectPlaybackSupported()` at runtime.
- **Android 16/17 Local Network Protection** (`NEARBY_WIFI_DEVICES` → mandatory `ACCESS_LOCAL_NETWORK` at targetSdk 37) gates the phone's inbound server + mDNS. Handle now.
- **VBR stalls are fully solvable in-app** via `DefaultLoadControl` tuning (`setPrioritizeTimeOverSizeThresholds(true)`, larger target buffer bytes/time).

## The one test that gates the architecture

Run on the **actual Google TV Streamer**: `adb shell pm list features | grep -i wifi` (and a `WifiP2pManager.discoverPeers()/createGroup()` spike). This resolves whether an *automatic wireless* isolation bypass (Wi-Fi Direct) is buildable at all, before committing engineering to it.

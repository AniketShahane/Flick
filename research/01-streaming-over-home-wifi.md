# Deep research: stall-free live streaming over home Wi-Fi

**Date:** 2026-07-18 · **Method:** 5 Sonnet 5 research agents (client-isolation, google-cast, direct-link-no-hotspot, zero-buffer-engineering, landscape-2026), each adversarially fact-checked by a Sonnet 5 verifier, then a Sonnet 5 synthesis. Live web search. 11 agents, ~712K tokens.

## Question

How can an Android phone → Android TV app reliably stream the user's local 4K (incl. HDR / Dolby Vision, high & variable bitrate) files over a **normal, unmodified home Wi-Fi** with **zero stalls** and **minimal setup** — specifically defeating or gracefully handling (1) router **client isolation** and (2) **band / VBR throughput** — without a manual hotspot?

Context: direct-play architecture (phone Ktor HTTP byte-range server → TV Media3/ExoPlayer hardware decode). Proven zero-stall on 5 GHz. Two live failure modes hit on real hardware: client isolation (phone↔TV 100% packet loss on the same AP) and 2.4 GHz VBR-peak stalls.

## Verdicts

**Client isolation cannot be defeated in software.** It's an L2 frame filter at the router/AP, architecturally upstream of NAT hairpinning, multicast/mDNS, and every app-layer protocol tested. The only things that work avoid the isolated hop entirely: (a) a device-to-device link that bypasses the AP (Wi-Fi Direct or an app-orchestrated hotspot — same trick as the manual hotspot, automated), (b) removing one device from the isolated wireless segment (wired Ethernet on the TV — plausible on typical consumer routers, not universal), or (c) a cloud relay that never touches the isolated LAN (bandwidth-incompatible with true 4K). There is no fourth option.

**No genuinely zero-setup robust path exists today.** The only truly automatic candidate — an invisible Wi-Fi Direct fallback modeled on Quick Share — is unverified and skews unlikely to work on the Google TV Streamer's single-radio MediaTek MT7663 (Wi-Fi 5): no confirmed Android TV Wi-Fi Direct support anywhere, no AOSP TV settings surface, and AOSP's own STA/STA-concurrency guidance suggests single-radio chips struggle to hold the home Wi-Fi link during P2P. Realistic best target: **zero setup for the majority whose routers don't isolate, and a one-tap recovery for the minority who do.**

## Ranked strategies

| # | Strategy | Setup | Robust | Under isolation | Note |
|---|---|---|---|---|---|
| 1 | **Pre-flight isolation/weak-band detection** (TCP connect + real byte-range GET; `WifiInfo` band/link-speed) | none | high | yes | Not a bypass — the mandatory diagnostic foundation everything else depends on. |
| 2 | **Adaptive buffer tuning** (`DefaultLoadControl`: `setPrioritizeTimeOverSizeThresholds(true)`, larger target bytes/time) + band warning | none | high | no | Solves VBR stalls entirely in-app. Ship unconditionally. |
| 3 | **Disable AP/client isolation** in router admin | medium | high | yes | The universal documented fix, but needs router access the user may lack. Secondary (guided link). |
| 4 | **Wired Ethernet on the TV** (built-in port) | low | medium | partial | Often defeats isolation *and* the VBR risk; not a universal guarantee (mesh "Net Isolation"). "Try this first." |
| 5 | **App-orchestrated hotspot / "Create Direct Link"** (`startLocalOnlyHotspot()` + QR to TV, 5 GHz via `SoftApConfiguration.setChannels()`) | low | high | yes | Same trick as the manual hotspot, automated to one button + a TV-side network select. Both devices lose internet while active — must disclose. Best practical fallback today. |
| 6 | **Automated Wi-Fi Direct** (`WifiP2pManager`, Quick-Share-style) | none | low | yes | Best theoretical UX, high risk: no confirmed Wi-Fi Direct to any Chromecast/Google-TV device; single-radio MT7663 likely can't hold home Wi-Fi during P2P. Gate on an on-device spike before building. |
| 7 | **Cloud TURN-style relay** (Plex-Relay pattern) | none | low | yes | Only path that bypasses isolation via infra outside the LAN, but Plex's own 1–2 Mbps cap proves it doesn't scale to 4K. Last-resort "reduced quality" only. |
| 8 | **Wi-Fi Aware (NAN)** | none | low | unknown | No evidence any Android TV device declares `FEATURE_WIFI_AWARE`. Not worth pursuing. |
| 9 | **Adopt Google Cast SDK / Cast Connect** | high | low | no | Cast's local-file path == our HTTP-server architecture; same LAN, same isolation vulnerability. Adds Play-Store/registration friction for zero robustness gain. Not recommended. |

## Recommended architecture (defense-in-depth)

Keep the existing direct-play core (Ktor byte-range server + Media3 hardware decode). Wrap in layers:

- **L0 — hygiene:** Ktor server in a foreground Service (Doze/App-Standby exemption) for the whole session; hold `WifiLock(WIFI_MODE_FULL_HIGH_PERF)`; handle Android 16/17 Local Network Permission explicitly.
- **L1 — diagnose before playing:** mandatory pre-flight probe (real TCP connect + byte-range GET; never mDNS-found-it, never `isReachable()`) → classify OK / isolated / weak-band / permission-denied in < 2 s.
- **L2 — fix VBR in software:** tune `DefaultLoadControl` for peak (not average) bitrate; surface a live band/link-speed warning. Zero user setup; ship unconditionally.
- **L3 — isolation recovery, ranked by friction:** Ethernet tip → one-tap Direct Link (5 GHz forced, QR pairing, discloses internet loss) → router-settings guide.
- **L4 — proactive stall recovery:** Media3 1.9+ `StuckPlayerException` drives recovery UX mid-playback instead of a passive spinner.

## Key risks / open items

- Concurrent STA + Wi-Fi Direct on the Streamer's single-radio MT7663 is unconfirmed and likely breaks/time-shares the home link.
- No quantified data on real-world isolation prevalence → add consented telemetry on pre-flight outcomes to size the roadmap.
- "Ethernet bypasses isolation" is plausible but unconfirmed as universal — validate on real routers before shipping confident UI copy.
- Android 16/17 Local Network Protection gates our exact sockets — permission-denial must be distinguished from isolation.
- Any hotspot/Direct-link drops both devices' normal internet during playback — disclose.
- DV Profile 7 mishandling and Atmos-passthrough-vs-PCM are decoder-level issues independent of the network strategy.

## Phase-1 actions

1. Mandatory pre-flight reachability probe (raw `Socket().connect()` ~1.5–2 s + small byte-range GET) with OK/isolated/permission-denied/weak-band classification.
2. Specific, correctly-named isolation diagnosis UI with ranked one-tap recoveries (Ethernet, Create Direct Link).
3. Explicit `DefaultLoadControl` tuning (`setPrioritizeTimeOverSizeThresholds(true)`, `setTargetBufferBytes` ~300 MB, 30 s/90–120 s window).
4. Phone-side `WifiInfo` band/link-speed check pre-playback → widen buffers + warn on 2.4 GHz.
5. Ktor server in foreground Service + `WifiLock` for the session.
6. On-device spike: `hasSystemFeature(FEATURE_WIFI_DIRECT/FEATURE_WIFI_AWARE)` + `discoverPeers()/createGroup()` on the actual Streamer — gates all Wi-Fi Direct work.
7. Empirically test the Ethernet-under-isolation hypothesis on 2–3 router brands.
8. Implement Android 16/17 Local Network Permission handling with a distinct error path.
9. Adopt `StuckPlayerException` for proactive mid-playback stall recovery.
10. Consented telemetry on pre-flight-probe outcomes to size fallback investment.

## Sources

- developer.android.com — [Wi-Fi Direct / WifiP2pManager](https://developer.android.com/develop/connectivity/wifi/wifip2p), [Local Network Permission](https://developer.android.com/privacy-and-security/local-network-permission)
- source.android.com — AOSP CDD (Wi-Fi Aware conditional reqs), Wi-Fi STA/STA concurrency
- support.google.com — [Google Cast / Chromecast AP-isolation troubleshooting](https://support.google.com/chromecast/answer/3222253)
- github.com/androidx/media — DefaultLoadControl source; Media3 1.8/1.9 `StuckPlayerException`
- github.com/jellyfin/jellyfin-android — issues #4364, #1259 (Android TV buffering)
- support.plex.tv — Plex Relay bandwidth cap (~1–2 Mbps)
- NDSS 2026 "Demystifying and Breaking Client Isolation in Wi-Fi Networks" (AirSnitch) — flagged for follow-up
- Router-vendor client-isolation docs (Netgear, TP-Link Deco per-device isolation, Ubiquiti, OpenWrt, Meraki)

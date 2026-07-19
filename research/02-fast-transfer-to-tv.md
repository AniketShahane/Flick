# Deep research: fastest transfer phone → Android TV + local HDR/DV playback

**Date:** 2026-07-18 · **Method:** 5 Sonnet 5 research agents (transports-and-isolation, androidtv-storage, local-playback-hdr-dv-audio, transfer-engineering, lifecycle-and-precedent), each adversarially fact-checked by a Sonnet 5 verifier, then a Sonnet 5 synthesis. Live web search (note: session web-search budget was partly exhausted; some findings lean on page-fetch + model knowledge). 11 agents, ~499K tokens.

## Question

What is the **absolute fastest, most robust, least-setup** way to get an up-to-8 GB 4K HDR / Dolby Vision file from an Android phone to an Android TV's **local storage** (Google TV Streamer, ~32 GB, watch-then-delete), then **play it locally** with full 4K HDR / DV / Dolby-audio fidelity — given the LAN may be client-isolated?

Rationale: playing from local storage removes the network from the *playback* path (immune to mid-playback stalls). But copying the file still has to cross the phone→TV gap.

## Verdicts

**Fastest path:** in the absence of isolation, plain LAN transfer over 5 GHz (~1.5–3.5 min/8 GB) — but moot here (this user's router isolates). Given isolation, the fastest *verified* path is **USB-C exFAT drive** (~3 min/8 GB at the corrected ~46 MB/s USB2-class speed; the *only* transport confirmed on the actual Streamer). Wi-Fi Direct could be more convenient (no physical drive, ~4.5–9 min) but its TV-side support is unverified.

**Under client isolation, exactly three transport families survive** (they never route through the AP): (1) **USB-C physical media** — guaranteed, verified, ~3 min; (2) **Wi-Fi Direct / Wi-Fi Aware** — architecturally correct but Wi-Fi Direct's TV chipset support is *unverified* (Aware effectively ruled out on TV SoCs); (3) **cloud round-trip** — guaranteed but slow (45–110+ min, upload-bound). Everything through the AP (LAN HTTP/SMB/FTP/WebDAV, LAN-based Quick Share) is a **binary block** — bulk-transfer tolerance cannot rescue it. Consumer Quick Share/Nearby Share has **no confirmed Android TV video receiver** at all.

## Ranked strategies

| # | Strategy | ~8 GB time | Setup | Under isolation | Robust | Note |
|---|---|---|---|---|---|---|
| 1 | **USB-C exFAT drive** on the TV | ~3 min (~46 MB/s) | medium | yes | high | Only transport verified on real Streamer hardware. Zero network exposure. Must be exFAT (NTFS unsupported; FAT32's 4 GB cap fails 8 GB files). Sidesteps the 32 GB budget if played off the drive. |
| 2 | **Plain LAN transfer** (phone HTTP/SMB/WebDAV) | ~1.5–3.5 min | none | **no** | low | Fastest when it works, but a binary all-or-nothing transport — zero bytes under isolation. Only behind a pre-flight isolation probe. |
| 3 | **Wi-Fi Direct** (`WifiP2pManager` or Nearby Connections `P2P_POINT_TO_POINT`) | ~4.5–9 min* | low | partial | low | Structurally correct wireless bypass, but TV chipset support **unverified**; may silently degrade to Bluetooth (1–3 MB/s → 44–133 min). Spike-test before committing. |
| 4 | **Cloud round-trip** (upload → TV downloads) | 45–110+ min | medium | yes | high | Universally works (two client↔WAN legs isolation can't block) but upload-bound and slow; quota + privacy cost. Background/overnight last resort. Auto-delete cloud copy after download. |
| 5 | **Bluetooth-only** (degraded Nearby Connections path) | 44–133 min | none | yes | low | Only relevant as an unintended worst-case fallback. Don't design around it. |

\* if real throughput matches unverified phone-to-phone anecdotes; no phone→Android-TV benchmark exists.

## Storage strategy (32 GB, watch-then-delete)

- Treat "32 GB" as an upper bound only — real free space after OS/apps is **undocumented**; measure live via `StatFs` / `StorageManager.getAllocatableBytes()`.
- Stage the working copy in a **persistent app-specific dir** — `getExternalFilesDir(DIRECTORY_MOVIES)` (fallback `getFilesDir()`) — **never a cache dir** (OS can silently evict under pressure).
- Reserve space with `StorageManager.allocateBytes()` (API 26+) before/ during transfer so the OS can evict other apps' reclaimable cache; treat `IOException` as the ENOSPC signal and prompt "delete an old file."
- Prefer app-specific storage over MediaStore for the working copy → `File.delete()` with **no** user-confirmation dialog (MediaStore requires `createDeleteRequest()`).
- Build an in-app **local library UI** (file list, size, watched status, one-tap delete, free-space meter) — neither Android TV Settings nor Netflix/Disney+/Plex TV apps provide adequate budgeting tooling.
- USB exFAT (unadopted, user-visible, cross-app) is preferable to Android "adoptable storage" (merged/encrypted into the private pool) for a drop-file/watch/delete UX.

## Playback fidelity (DV / HDR / Dolby audio)

- **Local file uses the identical decode pipeline as HTTP streaming** — Media3 abstracts the DataSource below the extractor/MediaCodec layer. The DV/HDR success already verified via streaming (`c2.mtk.dvhe`) carries over unchanged. Local playback fixes network problems, **not** decoder bugs.
- **MediaTek (MT8696-class) decoder bugs to guard against:** (1) incorrect DV **Profile 7** (dual-layer BL+EL) → prefer **Profile 8.1**; (2) black-screen/freeze when **HDR10+ metadata coexists with DV 8/8.1 in one HEVC MKV** (confirmed on same-silicon Firestick 4K Max via an open Google-assigned androidx/media issue; Streamer instance Kodi-forum-sourced).
- **Prefer MP4 remux over MKV** where the source pipeline is controlled — Media3's DV extraction is best-maintained for MP4 (DV Profile 10 + VVC added in Media3 1.10); `MatroskaExtractor` has an open `dvhe.07.06` supplemental-data bug.
- **Audio:** decodes Dolby Digital / DD+ (E-AC3, incl. Atmos/JOC) internally; does **not** passthrough lossless TrueHD / DTS-HD / DTS:X to an external AVR. Use `AudioTrack.isDirectPlaybackSupported()` (API 29+) to detect the sink and prefer E-AC3 JOC > E-AC3 > AC3 > PCM. Don't market lossless passthrough.
- Cosmetic: display can get briefly stuck in DV output mode (SDR flash) after ExoPlayer playback ends on Chromecast/GTV-class Android 14.

## Recommended architecture

Tiered, auto-selecting transfer + a **two-phase (transfer-then-play, not concurrent hybrid)** local model:

1. Pre-flight LAN socket probe → if isolation absent, **plain LAN HTTP transfer** (fastest, zero setup).
2. If isolated → recommend **USB-C exFAT** as the primary guaranteed option (verified; sidesteps 32 GB budget), with clear setup instructions.
3. Offer **Wi-Fi Direct (Nearby Connections)** as an optional "try wireless" path, labeled experimental until the on-device feature check + throughput spike passes.
4. **Cloud round-trip** (auto-delete after download) as the universal slow last resort.
5. Do **not** build on Wi-Fi Aware or Quick Share (no video/TV support).

Playback/storage: Media3 `DownloadService`/`DownloadManager` + `SimpleCache`/`NoOpCacheEvictor`, but implemented as write-temp → verify → atomic-rename → `player.prepare()` from a local `FileDataSource`. Files watched once and deleted, so the full-copy wait is an acceptable tradeoff vs. hybrid read/write-cache risk.

## Key risks / phase-1 actions

**Risks:** Streamer Wi-Fi Direct group-owner support unverified (could silently degrade to Bluetooth); USB port is USB2-class (~46 MB/s, not USB3); real free storage unknown; MediaTek DV7 + HDR10+/DV MKV bugs; no lossless-audio passthrough; isolation is binary (bulk tolerance doesn't help LAN transports).

**Phase-1:** (1) `hasSystemFeature(FEATURE_WIFI_DIRECT/FEATURE_WIFI_AWARE)` on the real unit; (2) benchmark a real exFAT USB drive with a timed 8 GB copy; (3) measure real free storage (`adb shell df` / `StatFs`); (4) build the isolation pre-flight probe; (5) prototype Nearby Connections `P2P_POINT_TO_POINT` on the actual isolated router + observe the TV D-pad confirmation UX; (6) test a real DV file in MP4 (8.1) vs MKV (dvhe.07/08 + HDR10+) to confirm/deny the black-screen bug.

## Sources

- aftvnews.com — Google TV Streamer USB-C OTG / exFAT mount / adoptable storage
- developer.android.com — [app-specific storage](https://developer.android.com/training/data-storage/app-specific), `StorageManager` allocate/getAllocatableBytes, [Media3 downloading](https://developer.android.com/media/media3/exoplayer/downloading-media), Media3 1.10 DV Profile 10 + VVC, [Nearby Connections](https://developers.google.com/nearby/connections/overview), `WifiP2pManager` / AOSP Wi-Fi Aware HAL
- github.com/androidx/media — issue #1895 (Firestick HDR10+/DV MKV freeze), #2480 (`MatroskaExtractor` dvhe.07.06), #2595 (GTV DV output-mode stuck-state)
- LocalSend docs (AP-isolation requirement); Send Anywhere (Wi-Fi Direct via Nearby); Samsung Quick Share for TV (images only)
- 9to5Google / Android Police / Android Central / TechRadar — Google TV Streamer specs (32 GB, 4 GB RAM, Wi-Fi 5, HDMI 2.1, USB-C OTG)
- Netflix Smart Downloads, Disney+ / Plex offline-download limitations on TV

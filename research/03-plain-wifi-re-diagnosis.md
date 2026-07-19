# Re-diagnosis: the home-Wi-Fi block is dynamic, not static client isolation

**Date:** 2026-07-18 (evening) · **Method:** forensic re-read of the Phase-0 session evidence (logcat, ping matrices, Wi-Fi state dumps captured live during the failure), cross-checked against research 01/02. No new web research (session search budget exhausted); precedent claims below are model-knowledge and marked as such.

## Why re-open this

Research 01 concluded "client isolation cannot be defeated in software" and recommended Ethernet/hotspot fallbacks. The user's goal, however, is **plain Wi-Fi with nothing else** — and apps in this exact architecture (phone HTTP server → TV player: VLC-to-Chromecast, Web Video Caster, LocalCast, AllCast) demonstrably work on ordinary home networks every day. Re-reading our own captured evidence shows the static-isolation verdict **does not fit this network**.

## The evidence timeline (all same afternoon, same home LAN, same device pair)

| # | Fact (captured live) | Implication |
|---|---|---|
| E1 | 1080p H.264 streamed phone→TV over the home LAN: 0 rebuffers, buffer grew 26 s → 47 s, hardware decode | Phone↔TV unicast TCP **worked** on this router |
| E2 | User had also cast successfully to this TV the previous day (third-party path) | Peer traffic is not permanently blocked |
| E3 | ~40 min later, 4K attempt: `SocketTimeoutException: failed to connect … after 15000ms` — a **TCP connect** failure, before any media bytes | The pair got severed at connectivity level, not throughput level |
| E4 | Ping matrix at failure: TV→phone 100 % loss; phone→TV 100 % loss (+errors ⇒ ARP failing); TV→Mac 0 % loss; phone→Mac 0 % loss; Mac→phone HTTP 200 in 0.09 s with 15–29 MB/s byte-range reads | Block is **bidirectional and pair-specific**; each device is individually healthy; the phone server is fast and reachable |
| E5 | Wi-Fi state at failure: phone **and** TV both associated at **5260 MHz (5 GHz)**, phone 11ax @ 1080 Mbps RSSI −60, TV 11ac @ 780 Mbps RSSI −53 | Same band, same radio, strong links — not a band split, not weak signal, not phone power-save |

## Why this falsifies "static client isolation"

Classic AP/client isolation is a configuration: an L2 filter that blocks **all** wireless↔wireless forwarding, **all** the time. Our network violates that on both axes:

- **Time:** the same pair streamed for minutes (E1), then lost even ARP (E3/E4) with no one touching the router.
- **Scope:** wireless↔wireless forwarding worked for Mac↔phone and Mac↔TV *during* the failure (E4). A blanket isolation filter would block those too.

So the router **can and does** forward peer frames — something *selectively and dynamically* severed this one pair. Research 01's conclusion ("no software defeats a router that refuses to forward peer frames") remains true in general; it just isn't what this router is doing.

## Ranked hypotheses for a dynamic, pair-specific peer block

1. **Router/ISP "advanced security" feature quarantining the flow.** Xfinity xFi Advanced Security, TP-Link HomeShield, Eero Secure, ASUS AiProtection, Plume/ISP-mesh "Guard" all do device-to-device flow blocking, and all have long support-forum histories (model knowledge — verify) of breaking Chromecast/Plex/Sonos *mid-session*: works, then the pair dies, other devices fine. A sustained high-rate HTTP flow phone→TV looks exactly like the "unusual local traffic" these features flag. **Fix: whitelist both devices / disable the feature — one toggle, permanent, pure Wi-Fi.**
2. **Stale proxy-ARP / client-steering state in the AP.** Many APs proxy ARP for associated stations; a stale entry after a band-steer or rejoin can blackhole one pair while others work. The `+errors` on phone→TV ping (destination unreachable ⇒ ARP resolution failing both ways) fits. **Fix/test: reboot the router; if phone↔TV returns, it was state, not config.**
3. **Per-band or per-device isolation setting.** Some routers isolate only one band/SSID profile or expose per-device isolation toggles (e.g. TP-Link Deco). If the phone was on 2.4 GHz during E1 and steered to 5 GHz before E3, a 5 GHz-only isolation would fit E1 vs E3 — though it strains under E4 (Mac↔TV wireless↔wireless worked). **Fix: audit band/SSID/device settings.**

All three are router-side, checkable in minutes, and none require Ethernet or a hotspot.

## The discriminating experiment (~10 min, no code changes)

On home Wi-Fi (TV back off the hotspot; Mac can then adb to the TV again):

1. Identify the router (brand/ISP, mesh?, companion app with a "security"/"protection" tab?).
2. Start the sender, play the **1080p** file on the TV → expect success (baseline, matches E1).
3. Switch to the **4K** file and let it run. If/when it dies, immediately capture the ping matrix + both devices' Wi-Fi state (we have the exact commands from Phase 0).
4. Check the router app for security events / blocked-device notifications at that timestamp.
5. Reboot the router, retry 4K. Restored → hypothesis 2 (state). Router app shows a block → hypothesis 1 (whitelist it). Neither → audit per-band/per-device isolation (hypothesis 3).
6. While the TV is adb-reachable: `adb shell pm list features | grep -i wifi` — resolves the Wi-Fi Direct question from research 01/02 for free.

## How the apps that "just work" actually do it

None of them defeat a router that refuses to forward peer frames. They work because (a) most home networks forward peer traffic fine — as this one demonstrably can — and (b) they ship a hygiene/resilience layer that keeps the fragile parts (phone radio sleep, transient Wi-Fi outages, discovery flakiness) from ever surfacing. That layer, mapped against our current code:

| Mechanism (used by phone-server casting apps) | Purpose | Flick today |
|---|---|---|
| `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) held while serving | Stops screen-off Wi-Fi power-save from throttling the server | ❌ missing |
| Partial `WakeLock` in the server service | Keeps CPU serving sockets with screen off | ❌ missing |
| Foreground service, `mediaPlayback` type | Survives backgrounding / app standby | ✅ have |
| Battery-optimization exemption prompt (OEM killers, esp. Samsung) | Prevents the OEM killing the server mid-movie | ❌ missing |
| Large, time-prioritized player buffers | Rides through roams/scans/micro-outages invisibly | ✅ have (15/60 s, 256 MB) |
| Generous `LoadErrorHandlingPolicy` + resume-at-position + `StuckPlayerException` (Media3 1.9+) | A transient outage becomes a silent byte-range resume, not a dead player | ❌ default 3-retries |
| Server + player telemetry (throughput, last-request age, both ends' band/RSSI) | Turns any stall into a 5-second diagnosis: which side, which layer | ◐ player only |
| Discovery decoupled from transport (manual IP / QR / subnet scan — mDNS as hint only) | Multicast filtering breaks discovery far more often than unicast | ✅ manual IP |
| Pre-flight probe (TCP connect + real byte-range GET) classifying OK / blocked / weak-band / permission-denied | Never start a doomed session; tell the user *which* problem they have | ❌ missing |

## Software-only candidates that cross even a peer block (ranked, honest odds)

1. **Relay via a third LAN device.** The block is pair-specific: during the failure the Mac could reach **both** ends at full speed. A dumb TCP relay on any always-on box (Mac, Pi) — TV pulls from relay, relay pulls from phone — would have streamed 4K *through the block, that day, zero router changes*. Not a consumer-product primary path (needs a third device), but a proven-viable stopgap and a powerful diagnostic.
2. **UPnP IGD port-mapping hairpin.** Phone requests a router port mapping (UPnP IGD); TV fetches via the router's own address, so traffic traverses the NAT path instead of the peer-forwarding path — a documented (model knowledge — verify) isolation workaround that works on some routers. Cheap spike: jupnp/Cling `AddPortMapping` + TV fetch via gateway IP.
3. **Wi-Fi Direct.** Still architecturally correct and still gated on the unresolved `FEATURE_WIFI_DIRECT` check on the Streamer (step 6 above).

## Bottom line

**Plain-Wi-Fi 4K casting is very likely achievable on this network with zero extra hardware.** The router already forwards phone↔TV traffic at 4K-capable rates (proved by E1 + E5); something dynamic — most plausibly an ISP/router "security" feature or stale AP state — severed the pair once. Path: (1) run the 10-minute discriminating experiment and fix the router-side cause; (2) ship the hygiene/resilience layer above regardless, because it's what separates "worked in a demo" from "never stalls in real life" for every app in this category; (3) keep relay/UPnP/Wi-Fi Direct spikes as insurance if the router feature turns out to be unfixable (e.g. ISP-locked).

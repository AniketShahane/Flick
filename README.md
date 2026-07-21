# Flick — direct-play local casting for Android TV

Flick is a two-app Android project for casting your own local 4K/1080p video from an Android phone to Android TV. The phone serves the original bytes over the home LAN and the TV hardware-decodes them. Flick never transcodes and never screen-mirrors.

Control protocol v2 is implemented in both apps at `versionCode=3` / `versionName=0.2.1`. Install the sender and receiver together; mixed builds are unsupported. The normative protocol is [docs/design/control-channel.md](docs/design/control-channel.md).

## Project structure

| Module | Device | Role |
|---|---|---|
| `:sender` | Android phone | Reads the local video library, runs the cast-correlated Ktor byte-range HTTP server on port 8080, and owns pairing/control plus the remote UI. |
| `:receiver` | Android/Google TV | Hosts the LAN-bound WebSocket control server and Media3 hardware playback. |

The media path is deliberately narrow. The sender binds one phone-owned RFC1918 address, never `0.0.0.0`, and serves `GET`/`HEAD /v/{token}` with Host pinning, a fresh 128-bit token, byte-range support, and at most four concurrent GET bodies. The receiver accepts only the canonical authenticated-peer URL and follows no redirects.

## Prerequisites

- Android Studio Meerkat or newer, with Android SDK 36 available.
- JDK 21 for the Gradle launcher. The modules compile to Java/JVM 17 bytecode.
- `local.properties` containing the local Android SDK path.
- An Android phone and Android/Google TV on the same home LAN. Use 5 GHz where possible; 2.4 GHz commonly cannot sustain 4K VBR peaks.

Build both modules together from the repository root:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:assembleDebug :receiver:assembleDebug
```

List attached devices, then install the already-built APKs with explicit serials:

```bash
adb devices
adb -s <phone-serial> install -r sender/build/outputs/apk/debug/sender-debug.apk
adb -s <tv-serial> install -r receiver/build/outputs/apk/debug/receiver-debug.apk
```

Never commit serials, private addresses, SSID/BSSID values, pairing material, media titles, tokens, or screenshots containing them. This repository is public.

## Pair and cast

1. Open Flick on the TV. Its pairing surface shows a QR plus a numeric host, a durable control port (`47654` by default), and a four-digit code.
2. Scan the QR with the phone's system camera. The QR is `flick://pair?v=3&h=<tv-lan-ip>&p=<port>` — a non-secret endpoint, nothing more. It opens Flick with host and port filled in and the code cell focused; it never connects on its own.
3. Type the four-digit code shown on the TV and choose **Pair**. The code is never in the QR: it stays the one thing you have to read off the screen, so a scan alone authorizes nothing. Opening Connect manually uses the same form when camera launch or mDNS is unavailable.
4. Grant the phone video-library permission, choose a local item, and tap **Flick to TV**.
5. Keep watching Connecting. The TV keeps the real player surface attached behind an opaque Preparing overlay, then Flick changes to Now Playing only after the TV validates the canonical source, completes a real byte-range probe, starts Media3, and reports the matching cast's first rendered frame.

Pairing proves control authorization; it does not prove the TV can reach the phone's media server. Later connections discover candidate endpoints through NSD/mDNS, then authenticate the TV with mutual HMAC before updating the saved endpoint. Discovery alone is never trusted.

There is no in-app camera scanner, QR-carried pairing code or key, media-URL entry on the TV, or one-scan authorization.

If something goes wrong, both apps keep a memory-only diagnostics log: TV **Settings › Diagnostics**, phone **Diagnostics**. Over adb they are `adb logcat -s FlickTV:V` and `adb logcat -s FlickPhone:V`.

## Pairing behavior

- The TV's visible code stays stable for five minutes, is valid only while the pairing surface is open, and is consumed by one successful phone.
- Leaving or hiding the pairing surface immediately invalidates its code. A later submission is generically denied rather than paired in the background.
- Four wrong attempts retain the code. The fifth starts a 30-second global lockout; repeated rounds double up to eight minutes and survive app restart. A host that makes three wrong attempts is throttled for 10 seconds independently.
- Success shows **Phone paired** for 1.5 seconds, then leaves the code screen without revealing a replacement code.
- A stored v2 pairing uses a persistent non-secret TV ID plus a 256-bit key. Resume sends nonce-bound HMAC proofs, never the key itself.
- Old host-key records are never automatically replayed. Re-pair with the current values displayed by the TV; a successful same-host visible pairing retires the matching legacy record.
- TV Settings shows the paired-phone count. **Forget all phones** requires a second confirmation, stops/revokes the live controller, durably clears all TV-side pairing credentials, and reopens first-run pairing.

## Troubleshooting

**The scan opened an empty form.** That is expected. Copy the host, port, and current four-digit code from the TV. The custom-scheme QR is launch-only by design.

**Update required.** Confirm both apps are 0.2.1. A v2 sender will not fall back to optimistic v1 control, and it will not send a pairing code until the receiver answers the harmless v2 negotiation.

**The TV is not listed.** mDNS is best-effort and some routers suppress it. Open Connect, choose manual entry, and copy all three values from the TV. An unpaired discovery result is advisory and cannot prefill the target.

**The code is rejected or pairing is locked.** Use the currently visible code. Codes expire after five minutes and disappear when the TV pairing screen closes. Wait for the TV's lockout surface to clear rather than repeatedly submitting.

**The TV cannot reach the phone.** Put both devices on the same non-guest LAN, disable a route-changing VPN, wake/open Flick on the TV, and check router client/peer isolation. A dynamic pair-specific router block can affect two otherwise healthy devices on the same radio; Flick reports this as reachability, not codec failure.

**The phone is reachable but not serving.** Keep the sender foreground service running, allow its notification, consider the battery-optimization exemption, and confirm port 8080 is available. Flick's Wi-Fi and wake locks keep an active source alive through phone screen-off, but OEM task killers can still require the exemption.

**Playback reports an unsupported format or decoder failure.** Flick deliberately selects hardware video decoders only and has no software fallback. Try a container/codec/HDR profile the TV exposes in hardware; Dolby Vision Profile 8.1 in MP4 is the established target on the Google TV Streamer.

**4K buffers or stalls.** Move both devices to 5 GHz, reduce distance to the access point, and compare source bitrate with the metrics overlay. Flick does not lower quality to hide insufficient LAN throughput.

## Metrics and direct-play acceptance

The TV Settings screen can enable the live metrics overlay. It reports playback state, buffer, rebuffer/drop counters, resolution/HDR class, decoder, estimated bandwidth, Wi-Fi band/link quality, probe latency, and bounded recovery activity. The established direct-play pass target is:

```text
rebufferCount == 0 AND droppedFrames == 0 AND bufferedAhead > 0
```

For 4K, also confirm 3840×2160 output and a hardware decoder name rather than a platform software codec. This is an acceptance procedure, not a claim that the current v2 APK pair has passed it.

## Current validation status

As of 2026-07-20, all 29 sender and 38 receiver JVM unit tests pass, and the synchronized build produces both debug APKs. These are focused pure/helper seam tests for parsing and proof fixtures, attempt/candidate/result policy, ownership generations, player-surface mode, terminal frames, range/body handling, redirect policy, retry policy, and decoder filtering.

No Android instrumentation or production-boundary test has exercised the real `SharedPreferences` transactions, Ktor client/server sockets, Activity intent/lifecycle integration, or Media3 hardware decoder on devices. The v2 APK pair has **not** completed real phone/TV installation or the two-device acceptance matrix. Cold/warm system-camera ingress, actual pair/resume, first-frame acknowledgement, lifecycle/LAN loss, and sustained 4K/Dolby Vision playback therefore remain hardware-unverified for this release. Earlier direct-play hardware measurements establish the transport baseline only; they do not validate v2 pairing/control.

See [docs/implementation.md](docs/implementation.md) for component details, [SECURITY.md](SECURITY.md) for the threat boundary, and [docs/design/control-v2-fixtures.md](docs/design/control-v2-fixtures.md) for byte-for-byte fixtures.

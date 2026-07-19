# Security

Flick direct-plays a user's **own** local video from their phone to their TV over
their **home Wi-Fi**. The phone runs a small HTTP server that serves the original
file bytes; the TV pulls them with byte-range requests. This document describes the
security model, what is hardened today, and what is planned.

## Threat model

The phone's owner is not the adversary — the picked file is intentionally shared to
the TV. The model covers:

- **Other devices on the same Wi-Fi** (a guest device, a compromised IoT gadget, a
  neighbor who has the Wi-Fi password).
- **A malicious app co-resident on the phone** with only the ordinary `INTERNET`
  permission, trying to abuse the local server or intents.
- **A network attacker** crafting HTTP requests to the server.
- **The TV being pointed at a malicious/spoofed server.**
- **Accidental exposure beyond the LAN** (bind address, DNS rebinding, redirects).

Out of scope: DRM, physical device theft, a rooted device, and OS-level compromise.

## What is hardened today

**Media endpoint requires a per-session capability token.** The video is served only
at `GET`/`HEAD` `/v/{token}`, where `{token}` is a fresh **128-bit `SecureRandom`**
value minted per cast session (it rotates whenever a new video is picked) and shown
in the cast URL on the phone. The server compares it in **constant time** and answers
**any** miss — no session, wrong token, or absent token — with an **identical `404`**,
so a probe learns nothing. This closes the "any device/app can pull the file"
exposure, including the sharpest vector: a co-resident app hitting `127.0.0.1:8080`.

**The socket binds the phone's LAN IP, not `0.0.0.0`.** The server listens only on the
interface actually carrying the cast, which removes the loopback (`127.0.0.1`) path a
co-resident app could use and avoids exposure on unrelated interfaces (hotspot/tether).

**`Host`-header pinning (anti-DNS-rebinding).** The video handler requires the request
`Host` to equal the bound LAN IP literal; a rebinding page carries a DNS name and is
rejected with `403`. This is independent of, and additional to, the token.

**Denial-of-service caps.** Concurrent GET body transfers are bounded by a fair
semaphore (excess → `503`); `HEAD` and error responses are not gated. The engine reaps
idle connections, and the blocking file-copy runs off the HTTP engine's worker threads
so a stalled socket cannot pin them.

**The sender permits no cleartext at all.** The sender makes no outbound HTTP (it only
*serves*, over a raw socket that Android's `network-security-config` does not govern),
so its NSC is set to `cleartextTrafficPermitted="false"`.

**Receiver hardening.** The player uses **hardware decoders only** (no silent software
fallback), **disallows cross-protocol redirects**, and runs a **pre-flight probe** that
diagnoses a bad peer before playback. No secrets are logged, and the repository holds no
credentials, real network identifiers, or device serials.

## Known, accepted limitations

- **The receiver still uses cleartext HTTP on the LAN** (ExoPlayer fetches over plain
  HTTP). Android's NSC cannot scope cleartext to an IP range, so the allowance is global
  until TLS lands. On a WPA2/WPA3 home network this is the same posture as Chromecast,
  DLNA/UPnP, and video AirPlay.
- **Manual token entry** is awkward on a TV remote; it is an interim until QR/pairing.
- The HTTP server logs its own bound LAN IP to logcat at startup (a framework default).
  The token is never logged, and logcat needs adb/physical access — which is out of scope.

## Roadmap (Phase 1)

- **TLS** with an ephemeral certificate pinned to the negotiated peer, exchanged
  out-of-band with the token — authenticates the sender to the TV, encrypts the stream,
  and lets the receiver's cleartext allowance be removed.
- **QR / pairing-code delivery** of the token so it never has to be typed.
- **Receiver-IP pinning** as a second gate once the TV's address is known.
- **Android 16/17 Local Network Permission** (`ACCESS_LOCAL_NETWORK`) path for the
  sender's inbound server at `targetSdk` 37.

## Reporting

This is a personal project; open an issue for anything security-relevant (please avoid
posting a working exploit against the current build in a public issue).

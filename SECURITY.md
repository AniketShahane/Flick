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
- **A LAN device probing or trying to pair with the TV's control server** (brute-forcing the
  pairing code, or issuing playback commands without pairing).
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

**The TV control channel (new — for the synchronized scrub).** The phone can now drive the TV
(play/pause/seek/volume) and the TV streams its confirmed playhead back, powering the synchronized
scrub. This runs over a **separate control server on the TV** (a Ktor WebSocket), discovered via
NSD/mDNS. It is deliberately small and bounded: it **binds the TV's LAN IP** (never `0.0.0.0`),
**pins the request `Host`** to that IP (anti-rebinding), is **pairing-gated** (a 4-digit code shown
on the TV must be entered on the phone — with a failed-attempt cap, escalating lockout, code
rotation, an authentication deadline, and a cap on unauthenticated connections), and exposes **only
playback verbs — no filesystem, no arbitrary fetch, no shell**. Its one media-loading verb
(`loadMedia`) is validated to `http://<paired-phone>:<port>/v/<token>` (scheme, paired host, and an
exact `/v/<url-safe-token>` path) so it can never be pointed elsewhere. There are now **two
servers** — the phone's media server and the TV's control server — each LAN-only and each gated.

**The sender permits no cleartext HTTP.** The sender *serves* media over a raw socket (which
Android's `network-security-config` does not govern) and now also opens an **outbound `ws://`
control client** to the paired TV. Ktor's CIO engine uses raw NIO sockets that likewise do not
consult the platform cleartext policy, so the NSC stays `cleartextTrafficPermitted="false"` — the
control connection works regardless, and the "no accidental platform-stack plaintext" guarantee is
preserved. Media and control are cleartext-by-design on the LAN until TLS (Phase 1).

**Receiver hardening.** The player uses **hardware decoders only** (no silent software
fallback), **disallows cross-protocol redirects**, and runs a **pre-flight probe** that
diagnoses a bad peer before playback. No secrets are logged, and the repository holds no
credentials, real network identifiers, or device serials.

## Known, accepted limitations

- **The receiver still uses cleartext HTTP on the LAN** (ExoPlayer fetches over plain
  HTTP). Android's NSC cannot scope cleartext to an IP range, so the allowance is global
  until TLS lands. On a WPA2/WPA3 home network this is the same posture as Chromecast,
  DLNA/UPnP, and video AirPlay.
- **Pairing** is now discovery-first (NSD) with a TV-displayed 4-digit code / QR and a manual
  address fallback; the phone no longer needs the raw token typed in. **Camera QR *scanning* on the
  phone is not yet implemented** (the discovery + code + manual paths cover pairing); the TV only
  *displays* the QR.
- **Media playback still follows same-protocol (`http→http`) redirects** (Media3's default data
  source). Cross-protocol redirects are disabled and the URL is host-pinned to the paired phone, so
  a redirect can only stay within the trusted, paired peer; tightening this further is Phase 1.
- The HTTP server logs its own bound LAN IP to logcat at startup (a framework default).
  The token is never logged, and logcat needs adb/physical access — which is out of scope.

## Roadmap (Phase 1)

- **TLS** with an ephemeral certificate pinned to the negotiated peer, exchanged
  out-of-band with the token — authenticates the sender to the TV, encrypts the stream,
  and lets the receiver's cleartext allowance be removed.
- **Camera QR scanning** on the phone (CameraX + ML Kit) to finish the pairing story; discovery +
  the TV-displayed code + manual entry already cover it today.
- **Receiver-IP pinning** as a second gate once the TV's address is known.
- **Android 16/17 Local Network Permission** (`ACCESS_LOCAL_NETWORK`) path for the
  sender's inbound server at `targetSdk` 37.

## Reporting

This is a personal project; open an issue for anything security-relevant (please avoid
posting a working exploit against the current build in a public issue).

# Flick security model

Flick direct-plays a user's own local media: the phone exposes one temporary byte-range resource and the TV pulls it over the LAN. A separate, small TV WebSocket server authenticates pairing and playback control. The binding v2 wire contract is [docs/design/control-channel.md](docs/design/control-channel.md).

## Threat boundary

Flick protects against other LAN clients, a co-resident phone app with ordinary network permission, malicious HTTP/control requests, spoofed discovery records, and accidental exposure on another interface. It does not claim to protect a rooted/OS-compromised device, physical theft, DRM, or a fully on-path attacker.

Media and control remain cleartext on the home LAN. HMAC authenticates pairing/resume identity but does not encrypt frames or add per-frame integrity against an on-path attacker. Product copy must not describe the connection as encrypted or cryptographically secure.

## Media server

- One immutable `(content URI, token)` publication prevents a retarget race from authorizing new media with an old token. The fresh 128-bit per-cast token gates `GET`/`HEAD /v/{token}`; comparison is constant time and every missing/wrong token returns the same `404`.
- The socket binds the exact phone-owned RFC1918 address observed by the authenticated TV control connection, never `0.0.0.0`, and pins `Host` to that literal address. This removes loopback/unrelated-interface serving and rejects DNS rebinding.
- The server exposes no arbitrary path. It streams ranges directly from the content descriptor, caps GET bodies at four, and reaps idle connections after 30 seconds.
- The TV accepts only canonical `http://<authenticated-peer-ip>:8080/v/<22-character-token>` input: exact peer/port/path, no DNS, user-info, query, fragment, percent-encoding, or path variant.
- Preflight disables redirects and requires one coherent HTTP 206 body plus EOF under an absolute six-second deadline. Playback gives each Media3 `DataSpec` one `HttpURLConnection` with redirects disabled and rejects every 3xx before any follow-up request.

## Launch-only pairing

The accepted QR URI is `flick://pair?v=3&h=<tv-lan-ip>&p=<port>` (the legacy bare `flick://pair?v=2` still parses as a launch-only envelope). It carries a **non-secret endpoint** and nothing else: no identity, code, key, nonce, proof, or capability. **The four-digit code is never in the QR.** The phone camera launches Flick, which synchronously clears the incoming intent data before publishing an ephemeral event, then prefills host and port and focuses the code cell — it never auto-connects. The typed code remains the sole authorization factor, so the phone still never accepts an endpoint the user did not authorize with a secret read off the TV. The QR-supplied endpoint, like an unpaired NSD result, is an untrusted hint until that code proves it.

Initial pairing connects only to that canonical user-entered RFC1918 endpoint. It sends a harmless `negotiate` first and sends the code only after an exact v2 `negotiated` response. A v1/unknown receiver therefore cannot consume a live code while version compatibility is still unknown.

The code is authorized only while its TV surface is visible. It stays stable for five minutes, is single-use, and disappears on TV background/close; a submission after the surface is hidden receives only generic denial. Four global failures retain the code; the fifth begins a persistent 30-second lockout, with repeated rounds doubling to an eight-minute maximum. Three failures from one host impose a separate in-memory 10-second throttle. A successful durable key write clears the successful host/global state before the TV shows a 1.5-second confirmation. Confirmed **Forget all phones** revokes the live controller, durably clears all TV-side keys, and returns to visible first-run pairing.

## Control v2 and resumed trust

Each WebSocket starts unauthenticated. The receiver permits at most four unauthenticated sockets and a six-second authentication window. Before and after authentication, application frames are single unfragmented UTF-8 JSON-object text messages capped at 16 KiB decoded size. Both ends reject duplicate or unknown keys, trailing data, wrong types, non-object/binary/fragmented frames, invalid bounds, and unknown frame types. Malformed pre-auth frames consume the three-frame budget; policy closure is generic on the wire and named only in the device-local log. Authorization failures that are safe to answer use `{"t":"denied","v":2,"reason":"<enum>"}` over exactly `code`, `expired`, `surface`, `locked`, `busy`, `storage`, `proof`, `unknown`. Only `code` and `expired` are code-derived, so the frame is not an enumeration oracle: it never distinguishes a known from an unknown key id and never reveals transcript detail.

The authenticated peer is taken from the accepted socket's non-resolving address, never from a reverse-DNS lookup or a forwarding header, so `peerIp` — which feeds per-host pairing throttling, the media-URL host check, and the resume HMAC transcript — is always an IP literal a resolver cannot influence.

Initial success returns one random 256-bit pairing key and independent 128-bit key ID. Resume never retransmits the key: both peers generate fresh 128-bit nonces and use HmacSHA256 over a versioned, role-separated, length-prefixed UTF-8 transcript binding `tvId`, `keyId`, both nonces, observed phone IP, connected TV host/port, TV label, and canonical capabilities. Proof comparison is constant time and a challenge is one-use. Device, TV, and title labels are canonical single-line values on the wire: senders normalize outbound labels and receivers reject noncanonical values instead of silently authorizing a different string. Fixed client/server vectors are in [docs/design/control-v2-fixtures.md](docs/design/control-v2-fixtures.md).

NSD/mDNS names, addresses, and `tvId` values are hints, not identity. The sender tries the last mutually verified endpoint before at most three deterministic candidates for the stored TV ID and commits a changed endpoint/name only after validating the server proof. A generic denial cannot delete key bytes. Legacy v1 keys are never transmitted or used for automatic proof; the user must visibly re-pair, and only successful pairing at the exact stored host retires that legacy record.

Every playback mutation is guarded by control-session and cast generations. A stale socket's lease-checked loss callback cannot clear its successor; lifecycle, LAN-loss, and endpoint-rebind paths instead use unconditional local teardown because the TV itself is withdrawing authority. While a cast is preparing or active, a second mutually authenticated phone receives `busy(active_cast)` and cannot displace the owner.

Accepted residual P2: v2 has `busy` but no positive `available` frame. After a valid `paired`/`resumed`, the sender waits a fixed 250 ms for an immediate busy disposition, then treats silence as available. A delayed busy can briefly start sender UI/foreground-service work before the ordinary terminal cleanup runs, but the receiver's ownership mutex never grants that phone the active cast. A future protocol revision may add an explicit `available|busy` disposition.

## Custom-scheme limitation

Custom URI schemes do not establish application identity. Another installed app can claim `flick` or invoke Flick with a forged URI. The launch-only envelope prevents URI endpoint/capability injection, but it cannot stop a user from entering visible TV values into a phishing app selected in the system chooser. A future one-scan authorization claim requires a verified HTTPS App Link or another reviewed relay-resistant authenticated design.

## Privacy, backup, and notifications

Never record pairing key/code, media token/full URL, HMAC nonce/proof, raw private IP, SSID/BSSID, device serial, or media title in logs, exceptions, notifications, analytics, backup, exported diagnostics, or committed evidence. The permitted transient surfaces are TV/phone pairing UI for host/port/code and library/active playback UI for title.

Legacy full-backup and Android 12+ cloud/device-transfer rules exclude sender `flick_pairings.xml` and receiver `flick_pairing.xml`; migration therefore requires re-pairing. The sender foreground notification is private, shows generic localized direct-play status only, and uses a unique immutable `castId`-correlated Stop intent without URL/token/title extras. Diagnostics may retain redacted timing, HTTP status, link-quality, decoder, and buffer/recovery measurements.

## Current assurance status

All 29 sender and 38 receiver JVM tests pass and the synchronized build produces both debug APKs. Those tests exercise focused pure/helper seams, not the production persistence/socket/Activity/hardware boundaries. Android instrumentation, real-device installation, and adversarial phone/TV acceptance have not run, so camera-handler behavior, live pairing/resume, lifecycle/network transitions, and the cleartext LAN boundary remain hardware-unverified.

## Reporting

Please report security issues privately and do not publish working exploit details, real pairing material, network identifiers, or media URLs in a public issue.

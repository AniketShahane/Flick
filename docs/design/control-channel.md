# Flick control channel v2 — normative contract

Status: **implemented and JVM/build validated; real-device acceptance pending**. This document is the binding contract for the v2 sender and receiver. The fixtures in [control-v2-fixtures.md](control-v2-fixtures.md) are byte-for-byte test inputs. Production code must not invent variants.

Both modules are `versionCode=2` / `versionName=0.2.0`. All 29 sender and 38 receiver JVM tests pass, and the synchronized build produces both debug APKs as of 2026-07-20. Cold/warm system-camera ingress, actual pair/resume, first-frame delivery, lifecycle/LAN transitions, and sustained media playback have not yet run on a real phone/TV pair.

Flick direct-plays original phone bytes. The phone serves an authenticated HTTP byte-range resource on `:8080`; the TV pulls and hardware-decodes it. The TV also owns a small WebSocket **control** server. There is no transcoding, screen mirroring, file browser, arbitrary fetch, or one-scan authorization.

## 1. Versioning and compatibility

There are two independent version domains:

- `flick://pair?v=3` is an app-launch envelope carrying a **non-secret endpoint prefill**. `flick://pair?v=2` remains a valid launch-only envelope with no prefill, so an un-updated TV still opens the app.
- WebSocket control protocol `v=2` is the pairing, resume, and cast protocol. NSD TXT `v` is `2`; TXT `id` is the non-secret stable `tvId`.

This is a synchronized pre-1.0 release: both APKs advance from `versionCode=1`/`versionName=0.1.0` to `versionCode=2`/`versionName=0.2.0` and are installed together. A v2 sender never falls back to v1's optimistic cast or bearer-key resume. If NSD advertises `<2`, show **Update Flick on your TV** and send no cast command. When NSD is absent (including manual first pairing), send the v2-only `negotiate` frame before a code. A missing/non-exact v2 response within the six-second authentication deadline is the sender-local `update_required` result; no code is then transmitted. A v2 receiver may generically deny v1; it must not accept an unversioned command as v2.

Rollback installs the previous implementation on **both** apps with a newly higher `versionCode`; do not reuse version code 1. Debug downgrade is only an explicit, signature-matching development operation. Uninstall/reinstall loses pairing data.

## 2. Endpoint-prefill QR and initial pairing

The canonical QR URI is:

```text
flick://pair?v=3&h=<tv-lan-ip>&p=<port>
```

It carries a **non-secret endpoint** — the TV's site-local IPv4 and its bound control port — and nothing else. It contains no TV identity, code, nonce, key, proof, or capability. **The four-digit code is never in the QR**; it stays the sole authorization factor, read off the TV screen and typed by the user, so scanning alone still authorizes nothing and the phone never accepts an endpoint the user did not authorize with a secret read off the TV.

The TV renders the QR plus a separately visible, private pairing surface with its numeric host, port, and four-digit code. The QR is emitted only while a real binding exists (host non-blank and port in `1..65535`); an `h=`/`p=` placeholder is never emitted. The phone camera may launch Flick, which **prefills host and port and focuses the code cell — it never auto-connects**. No in-app scanner is part of v2.

The parser accepts only a hierarchical URI with case-insensitive scheme `flick`, exact authority/host `pair`, empty path, no user-info, no explicit authority port, and no fragment. The query is either exactly `{v}` with value `2` (legacy launch-only) or exactly `{v,h,p}` with `v=3`, in any order, with no duplicate or missing key; `h` must be a canonical dotted-decimal RFC1918 IPv4 and `p` a canonical decimal `1..65535`. Any other key, duplicate, malformed URI, non-canonical `h`/`p`, or legacy `v=1` is invalid/unsupported. The parser returns only `Valid`, `Invalid`, or `UnsupportedVersion`: it neither preserves nor echoes the raw URI. A `v=3` prefill is an **untrusted hint** until the typed code proves the endpoint.

`MainActivity` handles cold `onCreate` and warm `onNewIntent` through the same ingress. Before Compose collection or suspension it copies `Intent.data` locally, calls `setIntent()` with a copy whose `data` is null, also sanitizes the incoming intent, parses only the local copy, and publishes an in-memory event with a process-local monotonically increasing ID. Raw URI/event data is never saved, logged, backed up, or replayed after process death. An ordinary launcher intent changes no pairing state.

A valid `v=2` event opens empty host, port, and code fields. A valid `v=3` event prefills host and port from the QR and focuses the code cell. Neither opens a socket. Pair is enabled only when the effective values are:

- a canonical dotted-decimal RFC1918 IPv4 address (four decimal octets `0..255`; no DNS, whitespace, sign, shorthand, integer, octal, hexadecimal, IPv6, loopback, link-local, multicast, or unspecified form);
- canonical ASCII decimal port `1..65535`, no sign or leading zero; and
- exactly four ASCII digits, retaining a leading zero.

The sender connects only to the endpoint shown in that form, and only when the user submits a code. A QR-supplied endpoint is a prefill hint, never a target on its own; an unpaired NSD candidate may also prefill but is never dialed blind. `47654` is offered as the default port in the manual sheet and never overrides a QR- or NSD-supplied port. The sender retains host/port but clears code after a transport failure; it clears code and gives guidance appropriate to the `denied` reason after `denied`; it never automatically retries pairing.

## 3. Transport and universal frame rules

- Endpoint: `ws://<TV-host>:<control-port>/control`; TV is server. Each WebSocket starts unauthenticated.
- Every application frame is one unfragmented WebSocket **text** message containing exactly one strict UTF-8 JSON object. Maximum decoded message size is 16 KiB before and after authentication.
- Reject binary, fragmented, invalid UTF-8, oversized, duplicate-key, non-object, unknown-field, missing-field, wrong-type, non-finite-number, and out-of-range input. Never use partial/defaulting JSON accessors.
- Unknown frame types are rejected, not ignored. Oversized and fragmented/binary messages close with 1009 and 1003 respectively. A malformed pre-auth message counts toward the three-frame unauthenticated limit; policy closure uses 1008 and a generic reason. A malformed authenticated frame sends at most one `commandRejected(malformed)` only when safe `t` and `castId` were extracted, then closes 1008. Close reasons contain no supplied data.
- The receiver permits at most four concurrent unauthenticated sockets. Authentication/challenge work has one six-second deadline.
- Every schema below forbids fields beyond those shown. `v` is exactly JSON integer `2` in every v2 frame.

String/number limits are exact:

| Field | Constraint |
|---|---|
| `t`, `phase`, `code`, `reason`, `command` | ASCII, max 32 characters |
| `tvId`, `keyId`, `clientNonce`, `serverNonce`, `castId`, media token, ping `id` | exactly 22 URL-safe unpadded Base64 characters (128 bits) |
| pairing key and HMAC `proof` | exactly 43 URL-safe unpadded Base64 characters (256 bits) |
| `device`, `tv` | non-empty after display normalization, max 80 Unicode code points |
| `title` | non-empty after display normalization, max 200 Unicode code points |
| `url` | ASCII, max 256 characters; canonical grammar in §8 |
| `cap` | at most 16 unique ASCII entries, each max 32 characters |
| `code` in `pair` | exactly four ASCII digits; all other wire codes use the ASCII 32-character limit above |
| `serverPort` | JSON integer `1..65535` |
| duration/position values | integer `0..604800000` ms (seven days) |
| `seq` | integer `0..Long.MAX_VALUE`, strictly increasing per authenticated session |
| `volume` / `level` | finite JSON number `0.0..1.0` |
| startup/probe timings | integer `0..60000` ms |
| HTTP status | integer `100..599`, only when HTTP was observed |

Display labels are canonical wire values: the sender normalizes them to one line, removes Unicode control/format characters, collapses whitespace, and caps by Unicode code point before sending. The receiver requires the received value to already equal that canonical form; it rejects rather than silently normalizing a different wire value. Authorization values are rejected, never truncated.

`minV`/`maxV` in `negotiate` are JSON integers exactly equal to 2. `retryable` and `state.playing` are JSON booleans, never strings/numbers. `state.bufferedMs`, `durationMs`, `startMs`, `posMs`, `probeLatencyMs`, `startupMs`, and `seq` use the integer limits above; fractional values and numeric strings are rejected.

The required capability list is exactly, and in this canonical order:

```text
cast-ack,first-frame-ready,structured-errors,resume-hmac
```

## 4. Pre-auth negotiation and pairing

Initial pairing is only allowed on the user-entered TV endpoint. The sender makes one fresh 128-bit `clientNonce` and sends:

```json
{"t":"negotiate","v":2,"minV":2,"maxV":2,"clientNonce":"<22-char-base64url>"}
```

The receiver's exact successful response is:

```json
{"t":"negotiated","v":2,"clientNonce":"<echo>","serverNonce":"<22-char-base64url>","tvId":"<22-char-base64url>","cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
```

Only after validating exact version, echoed nonce, fresh server nonce, valid `tvId`, and required canonical capabilities does the sender send its typed code, on the same connection:

```json
{"t":"pair","v":2,"clientNonce":"<same>","serverNonce":"<same>","code":"0007","device":"Demo Phone"}
```

`pair` is valid only for one live outstanding negotiation whose two nonces exactly match; it consumes that negotiation. Missing/mismatched/replayed nonce, second pair, or pair-before-negotiate is generically denied without charging the visible code's bad-attempt counter. A syntactically valid, correctly negotiated wrong four-digit code does charge it.

Under one receiver synchronization boundary, success verifies the visible current code/generation and lockout, compares it in constant time, consumes it, creates a random 256-bit key plus independent random 128-bit `keyId`, durably persists the key/ID/label, publishes pairing success, and returns:

```json
{"t":"paired","v":2,"key":"<43-char-base64url>","keyId":"<22-char-base64url>","tv":"Demo TV","tvId":"<22-char-base64url>","peerIp":"<phone-rfc1918-ipv4>","serverHost":"<tv-rfc1918-ipv4>","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
```

`peerIp` is the normalized IPv4 observed for the authenticated socket. `serverHost`/`serverPort` are the receiver's actual bound endpoint. Before storing a pair the sender requires that endpoint to equal the connected endpoint and `peerIp` to be currently owned by an up, non-loopback phone interface. These fields are protocol-only: do not log or display them.

Every authorization denial that is safe to answer has exactly this wire response, then closes:

```json
{"t":"denied","v":2,"reason":"code"}
```

`reason` is exactly one of `code`, `expired`, `surface`, `locked`, `busy`, `storage`, `proof`, `unknown`. Only `code` and `expired` are derived from what the user typed, so the frame is not an enumeration oracle: it never distinguishes a known from an unknown key id, and never reveals transcript or version detail. `update_required` is not a wire oracle. Senders must also accept the legacy two-key `{"t":"denied","v":2}` form emitted by an un-updated receiver.

Receiver pairing visibility is not authorization. `PairingManager`, rather than `forcePairing`, Compose-owned gates, or polling, owns `Standby`, `Open(code,generation,expiry)`, `Locked(generation,retryAt)`, and 1.5-second `Success(label,generation)` states. A code is valid only while `Open` is visibly rendered; Back, background/stop, cast adoption, or any other surface close invalidates it, and a late submission is generically denied. Four global wrong-code attempts retain it; the fifth consumes/rotates it and begins a 30-second cooldown. Later global rounds double to a maximum of eight minutes. A five-minute expiry rotates without a failed-attempt increment. The global round/wall-clock deadline is persisted and clamped on load; monotonic time is used only in process. A bounded per-host table throttles one host for 10 seconds after three wrong codes without blocking another host. Reopening cannot bypass active lockout. Confirmed **Forget all phones** stops/revokes the live controller, durably clears every TV-side key before changing UI state, and reopens first-run pairing.

## 5. Authenticated resume and endpoint commit

Pairing records are keyed by a persistent random non-secret `tvId`; they hold `keyId`, key, label, and last mutually verified endpoint. NSD is only a candidate hint. A pairing key is returned once in `paired` and is never transmitted again.

1. Sender sends `resumeInit` with a fresh client nonce:

   ```json
   {"t":"resumeInit","v":2,"tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<22-char-base64url>"}
   ```

2. Receiver looks up exactly `(tvId,keyId)`, creates a fresh server nonce, and responds:

   ```json
   {"t":"resumeChallenge","v":2,"tv":"Demo TV","tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<echo>","serverNonce":"<22-char-base64url>","peerIp":"<phone-rfc1918-ipv4>","serverHost":"<tv-rfc1918-ipv4>","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
   ```

3. Sender verifies every echoed/expected value, connected endpoint, current owned `peerIp`, and canonical capabilities before computing `clientProof`:

   ```json
   {"t":"resumeProof","v":2,"tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<clientNonce>","serverNonce":"<serverNonce>","proof":"<43-char-base64url>"}
   ```

4. Receiver constant-time verifies and consumes the one-connection challenge, then returns:

   ```json
   {"t":"resumed","v":2,"tv":"Demo TV","tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<clientNonce>","serverNonce":"<serverNonce>","peerIp":"<phone-rfc1918-ipv4>","serverHost":"<tv-rfc1918-ipv4>","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"],"proof":"<43-char-base64url>"}
   ```

5. Sender constant-time verifies the server proof before authenticating, refreshing host/port, or deduplicating an NSD candidate. A failed proof/nonce/replay/malformed field/extra frame expires or consumes the challenge, returns generic `denied`, and closes. Challenge deadline is six seconds and it accepts exactly one proof.

HMAC is `HmacSHA256` over UTF-8. The transcript is unambiguous: concatenate each field's four-byte unsigned big-endian byte length, then its UTF-8 bytes. Client fields, in order, are:

```text
Flick-Control-Resume-V2
client
2
tvId
keyId
clientNonce
serverNonce
peerIp
serverHost
serverPort
tv
cast-ack,first-frame-ready,structured-errors,resume-hmac
```

The server proof changes only `client` to `server`. Numbers are canonical unsigned ASCII decimal; `tv` is the exact validated challenge value. Base64url is unpadded. The fixed independently computed vector is in the fixtures.

An unauthenticated denial never deletes a key or changes its endpoint. After all bounded candidates fail, mark only that record `needsRepair` and route to visible-code pairing; transport failure retains it for retry. A sender tries last verified endpoint then at most three deterministic NSD candidates for the stored `tvId`; raw key bytes are never sent. Commit an endpoint/name only after server proof. Explicit Forget or a successful visible replacement tombstones the superseded `(tvId,keyId)` and its legacy fallback atomically; tombstones are never retried.

Legacy host-key records are conservative re-pair hints only. The sender may read a canonical stored host and locally derive `legacyKeyId = Base64url(first 16 bytes of SHA-256(lengthPrefix("Flick-KeyId-V2"), lengthPrefix(keyBytes))))`, but it sends neither the legacy key nor a legacy proof. The v2 receiver has no safe legacy TV-ID lookup, so there is no automatic challenge migration. The user must perform visible-code v2 pairing. If that succeeds at the exact stored host, the sender atomically writes the v2 record/migration marker and retires that legacy record; a changed host also requires visible pairing and never receives legacy proof material.

## 6. Cast identity, lifecycle, and timing

Every cast transaction gets a random 128-bit Base64url `castId`; it is correlation metadata, not the media bearer token. Every cast start/result/state/error/cancel/stop frame carries it (except `busy` which is session-level). Non-current cast results are ignored.

Sender startup is one process-scoped, generation-guarded transaction:

1. Authenticate v2 control and verify current `peerIp` ownership.
2. Validate readable `content:` media with safe metadata and positive known length.
3. Start the source service for this exact `castId`, binding explicitly to `peerIp`; wait for matching RUNNING.
4. Send `loadMedia`; wait for matching `loadAccepted`, then matching `loadReady`.
5. Enter Now Playing only on `loadReady`, meaning the current TV has rendered its first video frame.

Timeouts: authentication 6 s; source start 9 s; `loadAccepted` 2 s; receiver adoption-to-first-frame 18 s; sender total from send to first frame 20 s; remote stop grace ≤1 s. Retry creates a new `castId` and token; it is never automatic. Pre-ready paths invalidate generation first, best-effort `cancelLoad`, cancel waiters, stop only the matching service, clear URI/token/server state and locks, and leave a truthful phone surface. After `loadAccepted`, the TV may show receiver failure; no device claims a message was shown where control was unavailable.

Duplicate `loadMedia` for current ID returns its retained accepted/ready/failed status without restart. New B invalidates/cancels A, and delayed A callbacks cannot mutate B. `cancelLoad` is the best-effort pre-ready cancellation command. `stop` is the canonical terminal command for the current Checking/Preparing or Active cast: it clears ownership/player state and emits cast-correlated `stopped`; duplicate stop replays the retained `stopped`. Stale commands return `commandRejected(stale_cast)`. A socket-close callback invalidates only the cast whose control-lease generation it still owns, while TV lifecycle/LAN/rebind uses unconditional local teardown before closing control. No background auto-resume exists in v2.

Only one controller owns a preparing/active cast. A second phone first finishes visible-code pairing or mutual-proof resume, then under the same ownership mutex either becomes an idle controller or receives `busy(active_cast)` and closes; it never displaces the active controller. Resume sends proof-bearing `resumed` before `busy`. Initial pairing sends `paired` first, and the sender preserves that issued key/endpoint internally as `PairedBusy` before showing the busy failure. Explicit active takeover is outside v2.

Accepted residual P2: v2 defines `busy` but no positive `available` frame. The sender waits 250 ms after validated `paired`/`resumed` for an immediate disposition and treats silence as available. A delayed `busy` can therefore cause transient sender UI/foreground-service churn before normal cleanup, but receiver ownership is decided under the mutex and the second phone cannot take control. A future protocol revision may add an explicit `available|busy` frame; changing this requires a new contract version.

## 7. Exact v2 schemas

The canonical serialized fixtures enumerate every frame. This section defines semantic rules in addition to §3's strict common rules.

### Phone → TV, authenticated

```json
{"t":"loadMedia","v":2,"castId":"<id>","url":"http://<phone-rfc1918-ipv4>:8080/v/<token>","title":"Demo clip","durationMs":123000,"startMs":0}
{"t":"play","v":2,"castId":"<id>"}
{"t":"pause","v":2,"castId":"<id>"}
{"t":"seek","v":2,"castId":"<id>","posMs":45000}
{"t":"skip","v":2,"castId":"<id>","deltaMs":10000}
{"t":"setVolume","v":2,"castId":"<id>","level":0.75}
{"t":"cancelLoad","v":2,"castId":"<id>"}
{"t":"stop","v":2,"castId":"<id>"}
{"t":"ping","v":2,"id":"<22-char-base64url>"}
```

`durationMs` may be zero only when unknown; otherwise `startMs` and seek position cannot exceed it. With unknown duration, positions remain within the seven-day cap. `skip.deltaMs` is exactly `-10000` or `10000`. All commands except `loadMedia` and `ping` require current `castId`.

### TV → phone, authenticated

```json
{"t":"loadAccepted","v":2,"castId":"<id>"}
{"t":"loadReady","v":2,"castId":"<id>","probeLatencyMs":42,"startupMs":1380}
{"t":"loadFailed","v":2,"castId":"<id>","code":"http_rejected","retryable":true,"httpStatus":503}
{"t":"state","v":2,"castId":"<id>","posMs":0,"durationMs":123000,"playing":true,"bufferedMs":9000,"phase":"playing","volume":1.0,"seq":8}
{"t":"error","v":2,"castId":"<id>","code":"decoder_init","retryable":false}
{"t":"stopped","v":2,"castId":"<id>"}
{"t":"commandRejected","v":2,"castId":"<id>","command":"seek","code":"stale_cast"}
{"t":"pong","v":2,"id":"<echo>"}
{"t":"busy","v":2,"reason":"active_cast"}
```

`httpStatus` is optional and allowed only for HTTP-observed `loadFailed`/`error`; omit it otherwise. `phase` is exactly `buffering`, `playing`, `paused`, or `ended`. `loadAccepted` means command parsed, canonical URL passed, generation adopted, and probe started. The same attached player surface remains behind an opaque Preparing overlay until `loadReady`; `loadReady` is the sole success boundary because preflight succeeded and the current first rendered frame callback fired. `loadFailed` occurs exactly once before readiness; `error` is terminal after readiness; `state` is sent on transition and about 10 Hz while active; `stopped` is the cast-correlated response to canonical stop for a current pre-ready or active cast, is replayable for duplicate stop, and never blocks local cleanup.

## 8. Media URL, preflight, and failure codes

`loadMedia.url` must be exactly `http://<authenticated-peerIp>:8080/v/<22-char-base64url-token>`: `http` only, explicit port 8080, numeric RFC1918 IPv4 equal to the authenticated control peer, no user-info/query/fragment, raw path exactly `/v/<token>`, and no encoding, dot segment, repeated slash, decoded equivalent, or trailing slash. Do not substitute same subnet, DNS, arbitrary LAN host, or a different port. The receiver follows **no redirects** during preflight or playback.

After validation, emit `loadAccepted` and preflight under one absolute monotonic six-second deadline: raw TCP connect ≤2 s; a `Range: bytes=0-1023` GET uses blocking-operation timeouts capped at 3 s while a scheduled disconnect enforces the absolute end. HTTP must be 206; `Content-Range` must be canonical `bytes 0-<end>/<total>` with positive total and `end=min(1023,total-1)`; `Content-Length=end+1`; read exactly that body then one EOF read. HTTP 200, any 3xx, malformed/incoherent range, zero/early/extra/drip-fed/stalled body are rejected. Playback creates one non-redirecting HTTP connection per Media3 request and rejects every 3xx before following `Location`. Before first frame, at most two short transient IO retries (250 ms, then 500 ms) fit the 18-second receiver deadline; decoder/format/parser errors fail immediately. After first frame, preserve the steady-state buffer/recovery policy.

Wire `code` values are stable lowercase snake case. They are not raw errors:

| Code | Use |
|---|---|
| `update_required` | sender-local old/no v2 negotiation |
| `control_unreachable` | cannot connect/authenticate control |
| `source_unavailable` | phone content grant/file unavailable |
| `no_compatible_lan`, `media_bind_failed`, `host_mismatch` | local address/bind/control-peer mismatch |
| `media_unreachable`, `sender_not_serving`, `http_rejected` | preflight network/refused-or-stalled/HTTP-or-range failure |
| `tv_backgrounded` | adopted cast lost to TV lifecycle |
| `malformed_media`, `unsupported_container`, `unsupported_video_format`, `unsupported_video_codec`, `unsupported_hdr_profile`, `decoder_init` | evidence-conservative media/decode classification |
| `startup_timeout`, `control_disconnected`, `active_cast_busy`, `protocol_error`, `unknown` | terminal timing/session/protocol/fallback categories |

Only emit precise codec/HDR categories with explicit track plus decoder-support evidence. Filenames, MIME alone, test clip, or generic Media3 error are insufficient; use `unsupported_video_format` or `decoder_init` conservatively. `retryable` is always present on terminal `loadFailed`/`error`, but retry is user initiated. No raw exception text or free-form wire message exists.

## 9. Privacy and security boundaries

TV QR/code/manual endpoint and phone pending endpoint exist only on intentional visible pairing UI. Media title exists only in phone library/active remote and TV active playback UI. Pairing keys/codes, HMAC proofs/nonces, full URL/token, raw private IP, title in diagnostics, SSID/BSSID, device serial, and stable phone identity must never enter logs, exceptions, notifications, analytics, backups, exported diagnostics, or committed evidence. Both legacy full-backup and Android 12+ cloud/device-transfer rules exclude sender `flick_pairings.xml` and receiver `flick_pairing.xml`; migration therefore requires re-pairing.

The source server binds the exact `peerIp`, never `0.0.0.0`; its existing Host/token/range/concurrency hardening remains unchanged. Sender notification is private and says only localized direct-play status (and safe paired TV name when authenticated); its stop action is unique and `castId`-correlated, with no URL/token/title extras. Diagnostics may retain redacted timing/status/decoder/link measurements but not forbidden values.

Custom schemes do not prove app identity. A different installed app can claim `flick` or invoke it with a forged URI; v2 avoids endpoint/capability injection because it accepts only the launch envelope, but a user can still be phished into manually entering TV values into another app. Product copy must not call this encrypted, secure, or one-scan authorization. TLS and verified HTTPS App Links/another reviewed relay-resistant design are future work.

## 10. Validation and release gate

All 29 sender and 38 receiver JVM tests pass and the synchronized build produces both debug APKs. The tests cover focused pure/helper seams such as proof/schema parsing, attempt/candidate/result policy, ownership generations, surface/terminal state, exact range/body behavior, redirect policy, startup retry policy, and decoder filtering. They do not execute the production persistence stores, Ktor sockets, Activity lifecycle/intent path, or Media3 hardware decoder. Android instrumentation and the two-device matrix remain pending; no hardware success is implied by build/JVM validation.

No implementation may change a frame name, field, timeout, compatibility fallback, or success boundary without a new approved contract revision.

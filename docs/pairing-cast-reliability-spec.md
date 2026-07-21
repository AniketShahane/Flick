# Flick pairing and cast reliability specification

Status: **implemented; JVM/build validation green; real-device acceptance pending**
Audience: maintainers, adversarial reviewer, integrator, and device-test owner
Scope: `:sender`, `:receiver`, their shared control protocol, tests, and public documentation
Implementation state: **v2 production/test changes and adversarial fixes are present in both modules**
Automated validation: **all 29 sender and 38 receiver JVM tests pass and the synchronized build produces both debug APKs as of 2026-07-20**
Hardware validation: **the v2 APK pair has not been installed or exercised on a real phone/TV; Section 16 remains pending**

## 1. Executive decision

This release repairs the first-run pair-to-play journey as one versioned, observable transaction rather than as independent UI patches.

The implementation must make these product truths match the code:

1. Scanning the QR shown by the TV opens Flick on the phone, on both cold and warm launches, but imports no endpoint or authorization data. The user independently types the host, port, and four-digit code visibly shown on the TV before the phone sends any request.
2. A pairing code is single-use. After success, the TV shows a brief success confirmation and leaves the pairing surface; it never exposes the replacement code as though pairing failed.
3. Pairing proves control authorization only. It does not prove that the TV can reach the phone's media server.
4. Pressing **Flick to TV** remains in a truthful Connecting state until the TV has validated the source, fetched a byte range, initialized hardware playback, and rendered the first frame.
5. Every terminal startup failure is correlated to the attempted cast and cleans up the phone's foreground media server. A failure is shown on both devices only after the TV adopted the cast and the control channel is still usable; pre-control and phone-local failures are necessarily phone-only.
6. Direct-play remains unchanged: the phone serves the original bytes over the LAN; the TV pulls ranges and hardware-decodes them. There is no transcoding and no screen mirroring.

The selected architecture is:

- Keep `flick://pair?v=2` only as an **app-launch envelope**. It carries no endpoint, identity, code, nonce, or capability. Register the sender deep link and require the user to independently type host, port, and code shown on the TV. Do not add CameraX/ML Kit in this release.
- Sanitize the launch intent synchronously, before publishing it to Compose, and open no socket until the user submits the typed code.
- Replace receiver polling and `forcePairing` with an event-driven `PairingSnapshot` owned by `PairingManager`.
- Introduce control protocol v2 for cast startup, with a random `castId` and explicit `loadAccepted`, `loadReady`, and `loadFailed` frames.
- Define `loadReady` as **first frame rendered**, not merely “command queued” or “preflight passed.”
- Preserve receiver source-host pinning. The receiver reports the phone IP observed on the authenticated control socket; the sender must bind its HTTP server to that same owned address.
- Add a persistent, non-secret `tvId` so pairings survive TV DHCP address changes. Resume uses nonce-based mutual HMAC proof; the long-lived pairing key is never transmitted after initial pairing, and an NSD endpoint is trusted only after receiver proof.
- Add structured, redacted diagnostics. Pairing QR/code and media title may appear only on their intentional, transient private product surfaces; keys, codes, complete media URLs, tokens, titles, private IPs, and device serials never enter logs, notifications, analytics, backups, exported diagnostics, or committed evidence.

## 2. Why the release is necessary

### 2.1 Pre-v2 confirmed QR break

The receiver emits `flick://pair?host=<tvIp>&port=<controlPort>&code=<fourDigits>&v=1`, but the sender declares only a launcher intent. The sender activity neither receives nor parses pairing URIs, and the in-app QR card opens manual entry rather than a scanner. The current QR is display-only even though the product copy says to scan it.

### 2.2 Pre-v2 confirmed pairing-surface break

Successful pairing intentionally consumes the code and rotates it. The receiver UI currently learns pairing state by polling, and the **Pair another phone** path sets `forcePairing = true`. Pairing does not change `MediaStage`, while the cleanup clears `forcePairing` only after `MediaStage` leaves `None`. A successful re-pair therefore remains on the code screen and displays the newly rotated code.

### 2.3 Pre-v2 confirmed false-success cast behavior

The sender starts its HTTP service, sends `loadMedia`, immediately navigates to Now Playing, and considers the startup committed. The receiver validates and probes afterward. Receiver failures become generic `error` frames; the sender reduces those only to `PlaybackPhase.ERROR`, discards their reason, retains the server, and continues rendering the remote surface.

Code inspection cannot determine which failure occurred in the reported hardware run because the diagnostic is discarded. Plausible triggers include a control/media source-IP mismatch, router peer blocking, a stale control endpoint, and an unsupported container/codec/HDR profile. This release must make the next failure self-identifying.

### 2.4 Remaining validation gap

The implemented v2 tree now has 29 passing sender and 38 passing receiver JVM tests plus a green synchronized two-APK debug build. The current suites cover focused pure/helper seams: the frozen proof vector and strict parser/schema helpers, pairing-attempt/candidate/result policy, generation ownership, player-surface/terminal state, exact preflight range/body handling, redirect policy, startup retry policy, and hardware-decoder filtering.

There is still no Android instrumentation result or real-device v2 run. Production `SharedPreferences` transactions, real Ktor client/server sockets, Activity intent/lifecycle integration, and the Media3 hardware-decoder path have not been executed by these tests. Cold/warm camera launch, live pair/resume, first-frame acknowledgement, lifecycle/LAN loss, and the full two-device matrix remain unverified. Earlier manual direct-play hardware results establish the transport/player-tuning baseline only; they do not prove v2 pairing/control reliability.

## 3. Goals, non-goals, and invariants

### 3.1 Required outcomes

- System-camera QR recognition opens the installed sender and presents empty host, port, and four-digit-code fields. The QR conveys no endpoint, identity, or authorization data.
- Cold launch and `onNewIntent` warm launch use the same validated ingress.
- First-run and Pair-another success leave the TV pairing surface deterministically.
- Codes are valid only while the pairing surface is visibly open and not locked.
- The phone never enters Now Playing without a matching `loadReady` for the current `castId`.
- A delayed result from Cast A can never affect superseding Cast B.
- Startup failure removes the sender foreground notification, media token, HTTP listener, wake lock, and Wi-Fi lock.
- The exact safe failure category reaches every device that can truthfully receive it: phone-only before receiver adoption, and both devices after receiver adoption while control remains available.
- Existing byte-range, Host pinning, token, concurrency-cap, cleartext-LAN, and hardware-only-decoder constraints remain intact.
- Both modules gain deterministic automated coverage plus a two-device acceptance run.

### 3.2 Explicit non-goals

- In-app camera scanning, CameraX, or ML Kit.
- Verified HTTPS Android App Links. These remain the future route to safe one-scan authorization because they require a controlled domain and `assetlinks.json` signing workflow.
- TLS or endpoint certificates.
- Transcoding, remuxing, screen mirroring, cloud playback, Chromecast, AirPlay, or iOS.
- Making unsupported media decodable. The app must classify such failures honestly.
- Fixing router firmware or client isolation. Flick diagnoses it; it cannot override it.
- Android 16/17 local-network permission migration unless the target SDK changes during this work.
- Exportable analytics or persistent network histories.

### 3.3 Security and privacy invariants

- Pairing keys never enter QR payloads, logs, exceptions, analytics, notifications, saved instance state, or public storage.
- Pairing endpoint, identity, and code never enter custom-scheme URIs. Host, port, and code are rendered only on the TV pairing surface and independently typed into the phone pairing form.
- First pairing never targets an NSD-discovered or deep-link-supplied endpoint. The endpoint must be manually copied from the TV's visible pairing surface. NSD becomes identity-bearing only after a stored key completes mutual resume proof.
- Media bearer tokens and full media URLs never enter logs, notifications, screenshots committed to the repo, or protocol error messages.
- The receiver accepts playback URLs only from an authenticated control session and only in the canonical direct-play shape.
- The media server binds one explicit LAN address, never `0.0.0.0`.
- Pre-auth denial remains deliberately generic. Remote clients must not learn whether a code was wrong, expired, locked, or closed.
- Raw TV/phone model labels remain display metadata, never authorization identity.
- The TV pairing QR/code/manual LAN endpoint and the phone's manually entered pending endpoint are allowed only on the visible pairing flow. The selected media title is allowed only in the phone library/active remote and TV active-playback UI. None is allowed in notifications, diagnostics, analytics, backups, or committed screenshots.
- No test artifact committed to the public repo contains real LAN addresses, Wi-Fi names, device serials, video names, tokens, or keys.

## 4. Release and protocol versioning

### 4.1 Version domains

Two version domains must be documented separately:

- QR app-launch envelope `v=2`: carries only its version and no pairing data.
- WebSocket control protocol `v=2`: the new handshake metadata, stable TV identity, correlated cast startup, and structured errors.

NSD TXT attribute `v` advertises the WebSocket control version and changes from `1` to `2`.

### 4.2 Mixed-version policy

This is a synchronized pre-1.0 paired-APK release. Sender and receiver must be installed together.

Both implementation APKs increment from `versionCode=1` / `versionName=0.1.0` to `versionCode=2` / `versionName=0.2.0`. A rollback build must use a code greater than every distributed build rather than reusing 1.

- A v2 sender must not fall back to optimistic v1 casting.
- If NSD advertises a version lower than 2, the sender shows **Update Flick on your TV** and sends no cast command.
- Manual/QR pairing may reach an old receiver whose NSD record is unavailable. The sender first sends a harmless v2-only `negotiate` frame containing no code or key. It sends `pair` only after a valid `negotiated` result; therefore a v1 receiver cannot consume a live code before incompatibility is detected.
- A v2 receiver may reject v1 control authentication generically. It must never treat an unversioned command as v2.
- Rollback uses a new, higher-`versionCode` build containing the previous implementation, installed on both devices. Debug-only `adb install -r -d` is acceptable only with an explicitly matching signature; uninstall/reinstall is data-destructive and does not preserve pairings.

### 4.3 Pre-auth negotiation and initial pairing

Every WebSocket begins unauthenticated. Before a new pairing, the sender connects only to an endpoint independently typed from the TV's visible pairing surface, never to a deep-link or NSD candidate, and sends exactly:

```json
{"t":"negotiate","v":2,"minV":2,"maxV":2,"clientNonce":"<22-char-base64url>"}
```

The v2 receiver replies:

```json
{"t":"negotiated","v":2,"clientNonce":"<echo>","serverNonce":"<22-char-base64url>","tvId":"<22-char-base64url>","cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
```

Only after validating that response may the sender transmit the user-entered code:

```json
{"t":"pair","v":2,"clientNonce":"<same>","serverNonce":"<same>","code":"0007","device":"Pixel"}
```

The receiver atomically consumes the visible code, creates a 256-bit random pairing key and independent 128-bit random `keyId`, persists both, and returns:

```json
{"t":"paired","v":2,"key":"<43-char-base64url>","keyId":"<22-char-base64url>","tv":"Living Room TV","tvId":"<22-char-base64url>","peerIp":"192.168.1.42","serverHost":"192.168.1.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
```

`peerIp` is the normalized IPv4 address observed by the receiver for the authenticated control socket. `serverHost` and `serverPort` are the receiver's actual bound endpoint. The sender requires `serverHost:serverPort` to equal the endpoint it connected to and requires `peerIp` to be currently owned by the phone. These fields are protocol data only and must not be logged or displayed.

`pair` is accepted only on the same connection with one live outstanding negotiation whose two nonces match exactly. The negotiation is consumed by the first `pair` frame. Missing/mismatched/replayed nonces, a second `pair`, or `pair` before negotiation are denied without charging the visible code's invalid-attempt counter; a syntactically valid, correctly negotiated but wrong four-digit code does charge it.

A v1 receiver does not recognize `negotiate`; it may ignore or deny it but cannot consume a code because none has been sent. The sender closes and reports `UPDATE_REQUIRED` if it does not receive the exact v2 response within the authentication deadline.

Every failed pre-auth path that is safe to answer uses exactly `{"t":"denied","v":2}` and closes. `UPDATE_REQUIRED` is a sender-local classification for failed/old negotiation, not a detailed unauthenticated wire oracle.

### 4.4 Nonce-based mutual resume

NSD metadata and `tvId` are discovery hints, not authenticated identity. A stored key is never sent after initial `paired`.

Resume sequence:

1. Sender generates a fresh 128-bit `clientNonce` and sends:

   ```json
   {"t":"resumeInit","v":2,"tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<22-char-base64url>"}
   ```

2. Receiver finds the exact `(tvId, keyId)` record, generates a fresh 128-bit `serverNonce`, and replies:

   ```json
   {"t":"resumeChallenge","v":2,"tv":"Living Room TV","tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<echo>","serverNonce":"<22-char-base64url>","peerIp":"192.168.1.42","serverHost":"192.168.1.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"]}
   ```

3. Before proving, the sender requires exact nonce echoes, the expected `tvId`/`keyId`, the connected `serverHost:serverPort`, a `peerIp` currently owned by the phone, and all required capabilities in canonical order. It computes `clientProof` and sends:

   ```json
   {"t":"resumeProof","v":2,"tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<clientNonce>","serverNonce":"<serverNonce>","proof":"<43-char-base64url>"}
   ```

4. Receiver constant-time verifies `clientProof`, consumes the one-connection challenge, and returns a server proof:

   ```json
   {"t":"resumed","v":2,"tv":"Living Room TV","tvId":"<tvId>","keyId":"<keyId>","clientNonce":"<clientNonce>","serverNonce":"<serverNonce>","peerIp":"192.168.1.42","serverHost":"192.168.1.88","serverPort":42421,"cap":["cast-ack","first-frame-ready","structured-errors","resume-hmac"],"proof":"<43-char-base64url>"}
   ```

5. Only after constant-time verification of the server proof may the sender mark the socket authenticated, persist refreshed host/port, or deduplicate the NSD candidate into the trusted TV record.

HMAC is `HmacSHA256` over UTF-8 bytes. Input is an unambiguous, length-prefixed transcript: for each field append its 4-byte unsigned big-endian byte length followed by its UTF-8 bytes. The client transcript fields, in order, are:

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

The server transcript is identical except the role field is `server`. Numbers use canonical unsigned ASCII decimal. `tv` uses the exact validated UTF-8 value transmitted in `resumeChallenge`; capabilities use exactly the canonical comma-joined order shown above. Base64 is URL-safe without padding. Proofs/nonces are never logged. A challenge accepts exactly one proof and expires with the 6-second authentication deadline; another frame, replay, field mismatch, malformed proof, or failed proof yields the same generic `denied` and closes the socket.

This prevents an unauthenticated NSD impostor from learning the stored key or being committed as the TV endpoint. It does not encrypt the cleartext LAN channel or provide per-frame integrity against a fully on-path attacker; TLS/session framing remains a separately scoped protocol upgrade, and product copy must not call the connection encrypted or secure.

## 5. QR deep-link specification

### 5.1 Canonical app-launch URI

```text
flick://pair?v=2
```

The receiver constructs it with `Uri.Builder` and `appendQueryParameter`; it must not concatenate strings. Host, port, TV identity, code, and every other pairing value remain separate and visibly printed on the TV. The same surface shows canonical numeric host, port, and four-digit code under **Enter these in Flick**. This is intentional private UI and is not logged/exported.

The v2 launch parser accepts only:

- hierarchical URI;
- scheme equal to `flick`, case-insensitive;
- authority/host exactly `pair`;
- empty path only;
- no URI user-info, explicit URI authority port, or fragment;
- exactly one query key, `v`, exactly once;
- `v` exactly `2`;
- no `host`, `port`, `code`, `id`, nonce, capability, endpoint, or extra field.

The pure result is:

```kotlin
sealed interface PairLaunchParseResult {
    data object Valid : PairLaunchParseResult
    data object Invalid : PairLaunchParseResult
    data object UnsupportedVersion : PairLaunchParseResult
}
```

No parser result contains or echoes the raw URI. Any legacy `v=1` QR or URI with pairing data is reported as update-required and is never sent to the network.

### 5.2 Manifest and activity ingress

The sender `MainActivity` adds a browsable view intent for scheme `flick` and host `pair`, while preserving the existing launcher intent. Use `android:launchMode="singleTask"` for the sole activity.

The activity implements one `acceptPairIntent(Intent)` function used by:

1. `onCreate` for the initial intent;
2. `onNewIntent`, after `super.onNewIntent(intent)`.

Ingress order is security-critical:

1. copy `Intent.data` into a local value;
2. synchronously set the Activity's stored intent to a sanitized copy with `data = null` and sanitize the incoming intent as well;
3. parse only the local copy;
4. publish the parsed ephemeral event.

Sanitization happens before Compose collection or any suspension, eliminating the process-death/task-restoration replay window. `setIntent()` receives only the sanitized intent.

The activity owns one ephemeral `StateFlow<IncomingPairEvent?>`:

```kotlin
data class IncomingPairEvent(
    val eventId: Long,
    val result: PairLaunchParseResult,
)
```

- Each accepted external intent receives a monotonically increasing process-local event ID, even if its URI equals the last scan.
- Compose consumes an event with `LaunchedEffect(eventId)`, hands it to the process-scoped coordinator, and acknowledges only the in-memory event.
- Neither raw URI nor event is saved in `savedInstanceState`, `SavedStateHandle`, preferences, or logs.
- If process death occurs after sanitization but before collection, the launch is intentionally not replayed; the user scans again.
- An ordinary launcher intent has no effect on pairing state.

### 5.3 Independent endpoint/code entry and controller behavior

An external URI must never silently open a socket. The process-scoped coordinator owns:

```kotlin
data class PendingPairLaunch(val eventId: Long)

val pendingPairLaunch: StateFlow<PendingPairLaunch?>
fun acceptPairLaunch(event: IncomingPairEvent)
fun submitTvDisplayedPair(eventId: Long, host: String, port: String, code: String)
fun dismissPairLaunch(eventId: Long)
```

On a valid event:

- route to Connect;
- cancel/invalidate any older uncommitted pairing attempt;
- show empty host, port, and four-digit-code fields and say **Enter all three values shown on your TV**;
- show **Pair** and **Cancel**;
- import/pre-fill no value from the URI or NSD;
- do not open a socket, show the raw URI, or invent an unauthenticated TV name.

**Pair** remains disabled until all independently typed values validate:

- host is canonical dotted-decimal RFC 1918 IPv4 with four octets in `0..255`; reject DNS, whitespace, sign, shorthand, integer, octal, hexadecimal, IPv6, loopback, link-local, multicast, and unspecified forms;
- port is canonical ASCII decimal `1..65535` with no sign or leading zero;
- code is exactly four ASCII digits, preserving leading zero.

Submission connects to that typed endpoint, performs v2 `negotiate`, then sends `pair` only after exact negotiation success. The TV-provided name, `tvId`, `keyId`, and key are stored only after valid `paired` succeeds.

On invalid or unsupported input:

- open Connect;
- open no socket;
- show “This QR code cannot be used. Update Flick on both devices and scan the current TV code.”

On generic `denied`, clear the typed field and show “That code is no longer current. Check the TV and enter the current code.” Do not distinguish stale, wrong, expired, or locked.

On transport failure, retain the independently typed host/port but clear the typed code. Never retry pairing automatically.

The user must copy host and port from the TV's visible pairing surface before entering its code. Deep-link and discovered name/address data never prefill this form for an unpaired TV.

### 5.4 Custom-scheme threat boundary

Another installed app can claim the `flick` scheme or invoke Flick with a forged URI. Because the accepted launch URI contains only `v=2`, Flick receives no attacker-controlled pairing endpoint to trust. A competing handler can still impersonate/phish the app if the user selects it and manually gives it the TV values; custom schemes do not provide application identity. Product/security copy must state this limitation, and system chooser/app identity remains visible to the user.

A safe one-scan pairing flow requires a verified HTTPS App Link or a relay-resistant authenticated key agreement such as a reviewed PAKE. An explicit TV-side approval may provide an additional product confirmation but is not, by itself, cryptographic relay resistance. No implementation agent may put host, port, `tvId`, code, key, nonce proof, or bearer capability into the unverified custom URI.

## 6. Pairing-state specification

### 6.1 Receiver source of truth

`PairingManager` becomes the sole source of pairing-surface authorization and exposes a `StateFlow<PairingSnapshot>`.

```kotlin
data class PairingSnapshot(
    val surface: PairingSurface,
    val pairedCount: Int,
    val mostRecentDeviceLabel: String?,
)

sealed interface PairingSurface {
    data object Standby : PairingSurface
    data class Open(
        val code: String,
        val generation: Long,
        val expiresAtElapsedMs: Long,
    ) : PairingSurface
    data class Locked(
        val generation: Long,
        val retryAtElapsedMs: Long,
    ) : PairingSurface
    data class Success(
        val deviceLabel: String,
        val generation: Long,
    ) : PairingSurface
}
```

`ReceiverApp` collects this snapshot. Remove `forcePairing`, the 500 ms pairing-state polling, and the Compose-owned `acceptingPairings` gate.

Visibility and authorization are separate inside `PairingManager`. `PairingSurface` is only what the UI renders. `PairingManager` itself retains failure round/lockout deadline while the Activity is backgrounded, but invalidates and discards any open code/generation immediately. Rendering `Standby` therefore closes pairing without erasing a lockout; there is no separate production `PairingGate` whose isolated coverage should be mistaken for the real persistence/UI boundary.

Transitions:

- No stored keys on foreground start: `Standby -> Open`.
- **Pair another phone**: calls `requestOpen()`. It publishes `Open` only when the gate is eligible; during an active cooldown it publishes `Locked` with the retained deadline.
- Valid visible code: `Open -> Success -> Standby`.
- Five global invalid attempts: `Open -> Locked`; after cooldown, `Locked -> Open` with a new generation/code.
- Five-minute code expiry: `Open -> Open` with a new generation/code and no failed-attempt increment.
- TV background/stop: visible surface becomes `Standby`; any open code generation is invalidated, while an active lockout round/deadline remains in private `PairingManager` state.
- Cast adoption or any other pairing-surface close invalidates the open generation; a late submission is generically denied.
- Confirmed Forget all: stop/revoke active control, durably clear all keys, then `Standby -> Open` on the visible first-run surface. A failed durable clear leaves the prior pairing state intact.

`Success` is a 1.5-second visual confirmation. The old code is already consumed and the replacement code is not shown. After the confirmation, Idle displays the authenticated device label.

### 6.2 Atomic authorization

`attemptPair()` performs all of the following under one synchronization boundary:

1. verify the surface is `Open` and its generation is current;
2. check code expiry and global/per-host lockout;
3. validate the candidate in constant time;
4. consume the code generation;
5. durably persist the new key, `keyId`, and label;
6. publish `Success`;
7. return the key to `ControlServer` for the `paired` response.

The server no longer keeps an independently mutable `acceptingPairings` Boolean. A delayed `pair` cannot succeed after success, expiry, close, backgrounding, or lockout.

Detailed internal outcomes remain available for local UI/tests:

```kotlin
sealed interface PairAttemptResult {
    data class Success(val key: String, val keyId: String, val deviceLabel: String) : PairAttemptResult
    data object SurfaceClosed : PairAttemptResult
    data object Expired : PairAttemptResult
    data object InvalidCode : PairAttemptResult
    data class LockedOut(val retryAtElapsedMs: Long) : PairAttemptResult
    data object UnsupportedVersion : PairAttemptResult
}
```

All non-success pre-auth outcomes map to the same wire `denied` frame.

### 6.3 Brute-force rules

- Attempts 1–4 with an invalid code retain the visible code.
- Attempt 5 rotates the code and begins the existing 30-second global cooldown.
- Subsequent global rounds double up to the existing maximum.
- Per-host throttles remain independent and bounded.
- Reopening the pairing surface does not bypass active lockout.
- Persist the global lockout round and `lockoutUntilEpochMs`; lockout survives Activity recreation, process restart, and force-stop. On load, clamp any remaining cooldown to the configured maximum so wall-clock changes cannot create an unbounded lock.
- Inject both a monotonic clock for in-process expiry/deadlines and a wall clock for persistence/restart tests. Never compare persisted monotonic timestamps across boots.
- A successful host clears its own throttle and the global failed-attempt state; it does not erase unrelated active abuse records for every other host.
- The TV may show a local retry countdown. No retry timestamp or attempt count is sent over the unauthenticated wire.

### 6.4 Sender pairing concurrency

The sender owns exactly one pairing job/generation across QR/manual Connect launch + independently typed TV-screen host/port/code, and resume.

- An unpaired NSD result is advisory UI only. Tapping it opens instructions to scan the launch-only QR or open Connect and enter all values shown on that TV; it must not prefill or authorize a network pairing target.
- NSD candidates may be used automatically only for an already stored `(tvId,keyId)` and only through Section 4.4 mutual proof.

- A new attempt invalidates/cancels the previous one before opening a socket.
- Submit is disabled during the active attempt.
- Completion checks its generation before persisting, routing, or displaying an error.
- Cancellation invalidates the generation before closing the socket.
- `paired` without exact key/keyId/tvId lengths, endpoint binding, and v2 capability metadata is a protocol failure.
- A pre-auth resume denial is not authenticated and therefore never deletes/tombstones a key or changes the trusted endpoint. After all bounded candidates fail, mark the record `needsRepair` to suppress automatic retry and route to visible-code pairing; retain the bytes until explicit local Forget or successful replacement pairing.
- Resume transport failure retains the record for explicit retry.

### 6.5 Stable TV identity and persistence migration

The receiver creates one random 128-bit, URL-safe, non-secret `tvId` and persists it for the app-data lifetime.

- NSD TXT includes `id=<tvId>` and `v=2`.
- `paired` and mutually authenticated `resumed` include `tvId` and `keyId`.
- Pairing authorization still uses the secret pairing key, never `tvId`.

Sender pairing records become keyed by `tvId`, with `keyId`, name, last verified host/port, and key as fields. NSD may create an untrusted candidate endpoint, but host/port in the trusted record are refreshed only after the full server-proof step in Section 4.4.

Migration:

1. Continue reading legacy `key_<host>`, `name_<host>`, `port_<host>`, and `last_host` records only to recognize a canonical exact-host record during user-visible v2 pairing. Never send a legacy key or proof over the wire.
2. The sender deterministically derives a local legacy identifier as the first 16 bytes of `SHA-256(lengthPrefix("Flick-KeyId-V2"), lengthPrefix(keyBytes))`, Base64 URL-safe without padding. It is local metadata only; the v2 receiver has no safe legacy TV-ID/key-ID lookup.
3. There is no automatic legacy challenge-response migration. The user must enter the current TV-displayed host, port, and code. Changed-host legacy records also require visible pairing; discovery candidates never receive legacy proof material.
4. When visible v2 pairing succeeds at the exact canonical legacy host, synchronously write the v2 record plus `legacyHost -> (tvId,keyId)` marker and retire that legacy key/name/port in the same durable transaction before routing ready.
5. Retain other unmigrated legacy bytes only for the operational rollback window. After the bounded candidate set fails authentication/protocol verification, unauthenticated denials may set that v2 record `needsRepair` but cannot delete or tombstone key bytes; a transport-only failure preserves the record for explicit retry.
6. Explicit local Forget, or a successful user-visible replacement `paired` for the same `tvId`, writes a tombstone for the superseded `(tvId,keyId)` and disables/deletes its associated exact-host legacy fallback in the same durable transaction. A tombstoned key is never automatically retried.
7. Prefer a `tvId` NSD candidate for display/deduplication, but commit its new endpoint only after mutual proof.

Use a small persistence abstraction so tests inject in-memory storage. `SharedPreferences.commit()` on an IO dispatcher is acceptable for this bug-fix release; a DataStore migration is not required.

### 6.6 Multi-phone behavior

- Multiple pairing keys may remain stored on the TV.
- Each displayed code can authorize exactly one phone.
- A second phone requires the user to reopen pairing explicitly.
- One active control session remains the invariant. When the current controller owns a `Preparing` or `Active` cast, any newly paired or resumed second phone receives `{"t":"busy","v":2,"reason":"active_cast"}` and its socket closes; the first phone and playback remain untouched.
- After visible-code pairing or validating B's resume proof, the receiver makes the busy-versus-adopt decision under the same control-ownership mutex used for session adoption. A busy resume returns proof-bearing `resumed` followed by `busy` without replacing/closing A; a busy initial pair has already returned `paired`. If idle, it atomically installs B and only then closes the displaced idle socket. A state change after a busy decision may conservatively require B to retry.
- Initial pairing can issue a durable key immediately before the same busy decision. The sender preserves that credential/endpoint internally as `PairedBusy`, then presents the busy failure instead of discarding a successful pairing.
- Accepted residual P2: v2 has `busy` but no positive `available` frame. The sender waits 250 ms after validated `paired`/`resumed` and treats silence as available. A delayed busy may cause transient sender foreground-service/UI churn before cleanup, but the receiver mutex never grants the second phone ownership. A future protocol revision may add an explicit `available|busy` disposition.
- When no cast is preparing/active, a successfully authenticated second phone may supersede the idle controller. The displaced phone transitions to disconnected, never a live-looking remote.
- Explicit active-cast takeover is outside this release. It must not be approximated by ordinary resume.
- Settings shows paired count. **Forget all phones** requires confirmation, clears keys, closes the active control session, and returns to first-run pairing.
- Per-phone revoke is outside this release because the receiver currently stores a key set rather than full phone records.

## 7. Transactional cast protocol

### 7.1 Identity

Each Flick attempt generates a cryptographically random 128-bit URL-safe `castId`. It is an ephemeral correlation identifier, not a secret and not the media token.

Every cast-start, cast-state, cast-error, cancel, and stop frame carries `castId`. Results for a non-current ID are ignored.

### 7.2 Wire frames

All v2 application frames are single, unfragmented WebSocket text messages containing one strict UTF-8 JSON object. Maximum decoded message size is 16 KiB before authentication and after authentication. Binary, fragmented, invalid UTF-8, oversized, duplicate-key, non-object, unknown-field, missing-field, wrong-type, non-finite-number, and out-of-range frames are rejected; they are never partially defaulted with `opt*` accessors.

Authorization fields are rejected rather than truncated. Display labels are normalized to single-line text, have Unicode control/format characters removed, and are capped before display. Exact limits:

- `t`, `phase`, `code`, `reason`, `command`: 32 ASCII characters;
- `tvId`, `keyId`, `clientNonce`, `serverNonce`, `castId`, media token: exact 22-character Base64 URL-safe without padding;
- key/HMAC proof: exact 43-character Base64 URL-safe without padding;
- `device`, `tv`, and `title` must be nonempty after normalization; `device`/`tv` are at most 80 Unicode code points and `title` at most 200;
- `url`: 256 ASCII characters and the canonical grammar in Section 9.2;
- `cap`: at most 16 unique ASCII values, each at most 32 characters;
- all millisecond positions/durations: integer `0..604800000` (seven days);
- `seq`: integer `0..Long.MAX_VALUE` and strictly increasing per authenticated session;
- `volume`: finite JSON number `0.0..1.0`.

No frame permits fields beyond the listed schema. Section 4 defines the complete pre-auth frames. The authenticated phone-to-TV schemas are:

```json
{"t":"loadMedia","v":2,"castId":"<id>","url":"http://192.168.1.42:8080/v/<token>","title":"Movie","durationMs":123000,"startMs":0}
{"t":"play","v":2,"castId":"<id>"}
{"t":"pause","v":2,"castId":"<id>"}
{"t":"seek","v":2,"castId":"<id>","posMs":45000}
{"t":"skip","v":2,"castId":"<id>","deltaMs":10000}
{"t":"setVolume","v":2,"castId":"<id>","level":0.75}
{"t":"cancelLoad","v":2,"castId":"<id>"}
{"t":"stop","v":2,"castId":"<id>"}
{"t":"ping","v":2,"id":"<22-char-base64url>"}
```

`durationMs` may be zero only when metadata is unknown; positive `startMs` then remains allowed within the seven-day cap. Otherwise `startMs`/`seek.posMs` must not exceed the known duration. `skip.deltaMs` is exactly `-10000` or `10000`. Commands other than `loadMedia` and `ping` require the receiver's current `castId`.

The complete authenticated TV-to-phone schemas are:

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

`httpStatus` is optional and allowed only on HTTP-observed `loadFailed`/`error`, as an integer `100..599`; omit it otherwise. `phase` is exactly one of `buffering`, `playing`, `paused`, or `ended`. `probeLatencyMs` and `startupMs` are integer `0..60000`. `retryable` is required. Raw exception text and free-form wire messages are forbidden.

Malformed pre-auth frames count toward the existing three-frame unauthenticated limit; violation closes with WebSocket policy code 1008 and a generic reason. Any malformed authenticated frame sends at most one `commandRejected` with `code="malformed"` when `t`/`castId` can be safely extracted, then closes with 1008. Unknown frame types are not silently accepted in v2. Oversized/fragmented frames close immediately with 1009/1003 as applicable. Close reasons contain no supplied values.

Frame meanings:

- `loadAccepted`: authenticated command parsed, canonical URL validation passed, current generation adopted, and probing started. It is progress only.
- `loadReady`: preflight succeeded and the TV reported its first rendered video frame for this cast. This is the only success boundary that permits sender Now Playing.
- `loadFailed`: exactly one terminal startup result before readiness.
- `error`: terminal active-playback failure after readiness.
- `state`: immediate on phase transitions and approximately 10 Hz while active.
- `stopped`: exact cast-correlated, replayable confirmation of canonical stop for the current Checking/Preparing or Active cast; local cleanup never waits indefinitely for it.
- `commandRejected`: a command was well-formed but cannot target the current cast, or a safe generic malformed indication before closure. It never changes player state.

### 7.3 Idempotency and supersession

- Duplicate `loadMedia` for the same current `castId` returns the latest accepted/ready/failed status without restarting the probe/player. The receiver retains only that cast's latest status until a new cast is adopted or the socket closes.
- New Cast B invalidates Cast A, cancels A's probe/startup, and prevents every delayed A mutation or result.
- `cancelLoad(A)` is the sender's best-effort pre-ready cancellation for current A.
- `stop(A)` is the canonical terminal command for current A in Checking/Preparing or Active. It clears the player/cast lease, emits `stopped(A)`, and a duplicate stop replays that retained result.
- `play`, `pause`, `seek`, `skip`, and `setVolume` with a non-current ID emit `commandRejected(stale_cast)` and do not touch the player.
- Server stop, WebSocket close, TV backgrounding, LAN loss, and activity disposal invalidate the active control and cast generations before queued callbacks run.

## 8. Sender cast state machine

### 8.0 Ownership and UI reconciliation

The cast transaction is process-scoped, not Compose- or Activity-scoped. Add `FlickApplication` with an application `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` and one `CastCoordinator` (the renamed/split responsibility currently held by `FlickController`). The manifest registers the Application. `ControlClient`, `PlaybackSession`, discovery, pairing jobs, protocol waiters, `castJob`, and cast generation are owned by this coordinator.

`MainActivity` and Composables only collect coordinator state and forward user events. They never create the coordinator with `rememberCoroutineScope`, and composition disposal never closes control or stops an active server. Activity recreation sees the existing `StateFlow` values and renders the current Connect/Connecting/NowPlaying/Failure route without creating a second job or sending another command.

Lifecycle contract:

- Activity recreation/configuration change: reattach UI only; transaction is unchanged.
- Phone Activity background/screen-off during `Active`: keep control and foreground media service running.
- Phone Activity background before `loadReady`: keep the bounded startup transaction running; notification/returning UI remain truthful.
- Explicit user Cancel/Stop, control loss, or terminal receiver result: coordinator performs Section 8.4 cleanup.
- Process death: the non-sticky service dies with the process. On a later process start, if `ServerState` is not IDLE but no in-memory current cast exists, immediately send a matching stop/clear reconciliation and render no active remote.
- Unknown/null service restart intents remain terminal cleanup; they never reconstruct a cast from URI extras.

### 8.1 States

```text
Detail
  -> ConnectingControl
  -> StartingSource
  -> AwaitingLoadAccepted
  -> AwaitingFirstFrame
  -> NowPlaying

Any pre-ready state
  -> Cancelling
  -> Failure or prior safe route
```

Recommended model:

```kotlin
sealed interface CastStartState {
    data object Idle : CastStartState
    data class ConnectingControl(val castId: String) : CastStartState
    data class StartingSource(val castId: String) : CastStartState
    data class AwaitingAcceptance(val castId: String) : CastStartState
    data class AwaitingFirstFrame(val castId: String) : CastStartState
    data class Active(val castId: String) : CastStartState
    data class Failed(val castId: String, val failure: CastFailure) : CastStartState
}
```

One process-scoped coordinator-owned `castJob` serializes starts. A second Flick explicitly supersedes the first; it cannot run concurrently.

### 8.2 Ordered startup

1. Create `castId` and invalidate any older uncommitted transaction.
2. Ensure a v2 authenticated control session. Resume/reconnect before starting the media service.
3. Read `peerIp` from the authenticated response. Validate that it is RFC 1918 IPv4 and currently owned by an up, non-loopback phone interface.
4. Validate the selected media URI without copying bytes:
   - scheme `content`;
   - readable file descriptor;
   - resolvable safe MIME/name;
   - positive known length for byte-range direct-play.
5. Start `CastServerService` with `castId`, URI, name, size, and explicit `bindHost=peerIp`.
6. Await `ServerState.RUNNING` for this exact `castId`; stale global RUNNING state is never accepted.
7. Send `loadMedia` and remain on Connecting.
8. Await matching `loadAccepted` and then `loadReady`.
9. Only on `loadReady`, initialize the optimistic PlaybackSession state and navigate to Now Playing. Register all protocol/player observers before sending `loadMedia` so an immediate accepted/ready result cannot race past its waiter.

### 8.3 Timeouts

- Control connect/authentication: existing 6 seconds.
- Sender media-service start: existing 9 seconds, correlated by `castId`.
- `loadAccepted` after send: 2 seconds.
- Total receiver startup from send through first rendered frame: 20 seconds.
- Receiver enforces its own 18-second adoption-to-first-frame deadline so it can emit `loadFailed(startup_timeout)` before the sender's 20-second outer deadline.
- Remote cancellation/stopped grace: at most 1 second; local cleanup begins immediately.

Timeouts are terminal and explicit. Retry creates a new `castId` and a fresh media token. Do not automatically loop a failed startup.

### 8.4 Cleanup contract

Every path before `Active` uses `try/finally`. Unless the exact transaction reaches `loadReady`, it must:

- invalidate the cast generation first;
- best-effort send `cancelLoad` if authenticated;
- cancel pending protocol waiters;
- stop the matching foreground service;
- clear the media URI/token/server state;
- release Wi-Fi/wake locks;
- clear `castingItem`;
- route to a truthful failure or prior screen.

WebSocket loss while active sends the phone to a disconnected failure and stops serving unless a separately specified reconnect policy is later added. Pre-control/source-validation/bind failures are phone-only and leave the TV idle. After `loadAccepted`, the receiver displays its own terminal failure when possible; the phone never claims that an unreachable TV showed a message.

### 8.5 Service race hardening

`CastServerService` and `ServerStateHolder` carry `castId`.

- Each ACTION_START increments/records a latest-start generation; two rapid starts may not share an effective generation.
- Only the latest `(generation, castId)` may publish RUNNING, acquire locks, update the notification, or swap the served URI/token.
- Stop during address resolution or engine startup is final; delayed work cannot resurrect the server.
- Every coordinator-issued ACTION_STOP requires `castId` and stops only that exact matching transaction. A stale A cleanup can never stop B. Only service-internal `onDestroy`, unknown/null restart cleanup, and explicit app-wide emergency teardown may unconditionally tear down everything.

## 9. Receiver startup and playback state machine

### 9.1 States

```kotlin
sealed interface MediaStage {
    data object None : MediaStage
    data class Checking(val castId: String) : MediaStage
    data class Preparing(val castId: String) : MediaStage
    data class Active(val castId: String) : MediaStage
    data class Error(val castId: String?, val failure: CastFailure) : MediaStage
}
```

`SessionController` owns current `castId`, cast generation, probe job, first-frame deadline, and terminal-result emission.

### 9.2 Validation and preflight

Synchronous URL checks retain:

- `http` only;
- explicit port exactly `8080`;
- no user-info, query, or fragment;
- `rawPath` exactly `/v/<22-char-base64url-token>`; reject percent-encoding, dot segments, repeated slash, decoded-equivalent, trailing slash, and all noncanonical forms;
- numeric RFC 1918 IPv4;
- URL host equals the authenticated `peerIp` for the control session.

Do not weaken equality to “same subnet,” DNS resolution, or arbitrary LAN hosts. If the sender cannot bind `peerIp`, it fails locally and reconnects control; the receiver does not accept an alternate host.

After validation, emit `loadAccepted` and run preflight under one absolute monotonic 6-second total deadline:

1. raw TCP connect, maximum 2 seconds;
2. GET `Range: bytes=0-1023`, with each blocking phase capped at 3 seconds and a scheduled disconnect enforcing the absolute deadline;
3. disable redirects and require HTTP 206; HTTP 200 and every 3xx are `HTTP_REJECTED`;
4. require canonical `Content-Range: bytes 0-<end>/<total>`, where `total > 0`, `end = min(1023, total - 1)`, and `Content-Length = end + 1`;
5. under the same absolute deadline, read exactly `Content-Length` bytes (at most 1024), then require EOF on one additional read; reject zero bytes, early EOF, extra bytes, drip-feed extension, or a stalled body;
6. disconnect and record only redacted latency/status-class diagnostics.

Actual Media3 playback must also disable all redirects. If the selected data-source API cannot disable same-protocol redirects, add a narrow validating data-source wrapper that intercepts each 301/302/303/307/308 `Location` before any follow. The release policy is to reject all playback redirects; it must not rely only on `allowCrossProtocolRedirects=false`.

Every completion checks `(castGeneration, castId)` before mutating UI, player, or protocol state.

### 9.3 First-frame readiness

After successful preflight:

- stage becomes `Preparing(castId)`;
- the same movable `PlayerSurface` remains attached to Media3 behind the opaque Connecting overlay, then is revealed rather than recreated when the cast becomes Active;
- `PlayerController` receives the URL and a startup-mode flag;
- the cast-generation/first-frame listener is attached before the media item is set or `prepare()` is called;
- Media3 is prepared with autoplay;
- a Player/Analytics callback reports first rendered video frame to `SessionController`;
- only that callback for the current generation transitions to `Active` and emits `loadReady`.

`prepare()` or `STATE_READY` alone is insufficient if no frame has rendered.

### 9.4 Startup versus steady-state recovery

Before first frame:

- maximum two short retry decisions within the 20-second overall sender deadline;
- no four-cycle auto-recovery loop;
- decoder/format/parser errors fail immediately;
- network retry applies only to explicitly transient IO classes;
- terminal result is `loadFailed`.

After first frame:

- preserve the existing 180-second target buffer, 30-second back-buffer, retry policy, and bounded four-attempt auto-recovery;
- active terminal result is correlated `error(castId, ...)`.

This phase split prevents a bad initial 4K source from appearing black for roughly 100 seconds while retaining resilience mid-film.

### 9.5 Terminal player clearing

There is no background reconnect/resume policy in v2. WebSocket ownership loss is lease-guarded: `onControlLost(generation)` clears a cast only when that socket generation still owns the session, so a displaced stale socket cannot tear down its successor. Activity stop/background, LAN loss/change, and endpoint rebind use unconditional `forceLocalTeardown()` before stopping control because those local-authority events must clear whatever cast is current. Explicit stop and cast supersession likewise invalidate generations first and synchronously clear player state on main:

- `stop()` playback;
- `clearMediaItems()`;
- clear the stored/current URL, title, castId, startup listener, retry counter, and pending seek;
- publish `MediaStage.None` or the retained local error surface as appropriate;
- release the decoder when the lifecycle requires it.

`PlayerController.onStart()` must not re-prepare an old URL after any terminal event. Foreground restore shows Idle/Disconnected and requires a fresh authenticated cast. This deliberately trades automatic resume for consistency with the sender's rule that control loss stops serving.

## 10. Failure taxonomy and UX mapping

### 10.1 Shared safe enum

```kotlin
enum class CastFailureCode {
    UPDATE_REQUIRED,
    CONTROL_UNREACHABLE,
    SOURCE_UNAVAILABLE,
    NO_COMPATIBLE_LAN,
    MEDIA_BIND_FAILED,
    HOST_MISMATCH,
    MEDIA_UNREACHABLE,
    SENDER_NOT_SERVING,
    HTTP_REJECTED,
    TV_BACKGROUNDED,
    MALFORMED_MEDIA,
    UNSUPPORTED_CONTAINER,
    UNSUPPORTED_VIDEO_FORMAT,
    UNSUPPORTED_VIDEO_CODEC,
    UNSUPPORTED_HDR_PROFILE,
    DECODER_INIT,
    STARTUP_TIMEOUT,
    CONTROL_DISCONNECTED,
    ACTIVE_CAST_BUSY,
    PROTOCOL_ERROR,
    UNKNOWN,
}
```

Wire codes use stable lowercase snake case. Optional HTTP status may be included only for receiver-observed responses; raw exception strings are never sent.

`retryable` is part of the result, but the UI still requires an explicit user Retry.

### 10.2 Classification rules

Classification is evidence-conservative:

- socket timeout/no route before HTTP: `media_unreachable`;
- TCP refused or stalled body: `sender_not_serving`;
- HTTP 200, any 3xx, malformed range, 4xx, or 5xx: `http_rejected` with optional safe `httpStatus`;
- URL/control-peer inequality or noncanonical URL: `host_mismatch`;
- stopped TV lifecycle with an adopted cast: `tv_backgrounded`;
- parser/container exception with a recognized unsupported container cause: `unsupported_container`; otherwise `malformed_media`;
- known MIME/track codec plus Media3 decoder-support query proving no decoder: `unsupported_video_codec`;
- known codec profile/level/HDR metadata plus support evidence proving that profile unsupported: `unsupported_hdr_profile`;
- format-related failure without all evidence needed for either precise claim: `unsupported_video_format`;
- decoder creation/initialization exception: `decoder_init`;
- no first frame by deadline after transient budget: `startup_timeout`.

Format callbacks may arrive after a failure or not at all. Agents must never infer Dolby Vision/HDR/profile merely from filename, MIME type, generic Media3 error code, or the selected test clip. Tests assert precise categories only with the required evidence and assert the broad conservative fallback otherwise.

Full Media3 exceptions remain in in-memory diagnostics/logcat only when redacted. Production UI and wire use the enum. Pre-control authentication/version failures, unreadable content grants, no owned bind address, and service bind failures are phone-local; the TV remains Idle. Receiver-originated failures after `loadAccepted` are shown locally and sent to the phone if the authenticated socket is still usable. If it is not, neither side claims dual display.

### 10.3 Phone surfaces

Connecting shows truthful steps:

1. Connecting to TV.
2. Preparing the original video on this phone.
3. TV is checking direct-play.
4. Starting hardware playback.

Step 3 begins at `loadAccepted`; completion and navigation occur at `loadReady`. Back/Cancel is available throughout.

Failure UI must distinguish at least:

- update both apps;
- wake/open Flick on TV;
- source file unavailable;
- VPN/multiple-network identity mismatch;
- TV cannot reach phone (including router peer block);
- phone server rejected/not serving;
- unsupported media/codec/HDR profile;
- decoder startup failure;
- another paired phone is actively casting;
- generic timeout/disconnect.

No failure action sends the user back through pairing unless resume was specifically rejected.

### 10.4 TV surfaces

- Pair success is explicit and never shows a replacement code.
- Checking and Preparing are distinct.
- Startup failure uses compatibility language for codec/HDR issues and connectivity language for network issues.
- Settings/diagnostics may show Wi-Fi band, link speed, RSSI, decoder name, redacted probe phase, and timing. It never shows raw IP, URL, token, key, or title in exportable output.

## 11. Network discovery and lifecycle hardening

### 11.1 Sender route identity

The phone must not select the first private interface heuristically for a cast.

- Treat authenticated `peerIp` as the address the TV can route back to.
- Verify the address is currently assigned to an up non-loopback interface.
- Bind the media server explicitly to it.
- If it is no longer owned, close/reconnect the control session to obtain a current observation; do not guess another interface.
- A VPN or unsupported route produces `NO_COMPATIBLE_LAN`/`HOST_MISMATCH` guidance rather than a silent TV rejection.

### 11.2 NSD sender recovery

`NsdDiscovery` must reset its listener/state on start failure and allow retry.

- Handle `onStartDiscoveryFailed` by clearing the active listener under the lock and scheduling a bounded retry.
- Handle `onStopDiscoveryFailed` without leaving a false active state.
- Resolve failures continue pumping the queue.
- Treat each resolved `(tvId, host, port)` as an untrusted candidate. Do not let a duplicate `tvId` overwrite a trusted record or collapse away alternate endpoints before proof.
- For a stored TV, try the last mutually verified endpoint first, then at most three current candidates for the same `tvId` in deterministic host/port order. Each receives only `resumeInit` and transcript-bound proof, never the raw key. Commit/deduplicate/display the new endpoint as trusted only after server proof.
- NSD model/state/name remain advisory until authentication. A failed candidate cannot rename the stored TV or delete its pairing.
- Suggested retry backoff: 1, 2, 4, 8, then 30 seconds maximum with small jitter; stop cancels retry.

### 11.3 Receiver binding lifecycle

Replace the current null-insensitive address loop with a `LanBindingMonitor` backed by `ConnectivityManager.NetworkCallback`, plus defensive periodic reconciliation.

On LAN loss:

- invalidate active control and cast generations;
- stop/unbind the control server;
- unregister NSD;
- clear bound host/port;
- terminally clear the current media items/URL/cast state as specified in Section 9.5;
- publish disconnected/sleeping locally;
- leave player behavior truthful; active media failure is reported if possible before the socket closes.

On LAN restore, even with the same DHCP address:

- resolve current usable Wi-Fi/Ethernet IPv4;
- bind a fresh control server;
- advertise actual port, v2, state, model, and `tvId`;
- require the phone to reconnect/resume.

`ControlServer.stop()` increments/invalidates `activeGeneration` before clearing references so queued main-thread commands cannot mutate the player after stop.

## 12. Privacy and adjacent security corrections

These corrections are release-blocking because the same work touches manifests, persistence, notifications, and diagnostic paths.

### 12.1 Backup exclusion

Both apps currently permit backup while pairing keys live in SharedPreferences. Preserve app backup generally but exclude secret pairing preferences from both cloud backup and device transfer.

Add per-module:

- `res/xml/backup_rules.xml` for legacy full-backup exclusion;
- `res/xml/data_extraction_rules.xml` for Android 12+ cloud/device-transfer exclusion;
- manifest `android:fullBackupContent` and `android:dataExtractionRules` references.

Exclude sender `flick_pairings.xml` and receiver `flick_pairing.xml`. Tests/documentation must treat device migration as requiring re-pairing unless a future encrypted export exists.

### 12.2 Notification redaction

The sender foreground notification must never display the complete media URL/token or selected video title.

- Replace URL text with localized “Direct-play server active” / paired TV name only if authenticated and non-sensitive.
- Set notification visibility to private.
- Keep Stop action and embed only the current non-secret `castId` so it issues the required cast-correlated ACTION_STOP; use an immutable PendingIntent identity unique to that cast so an old notification cannot be retargeted to B, and do not use a global stop intent.
- Do not place URL/token in extras, content descriptions, or logs.

### 12.3 Diagnostic redaction

Allowed ephemeral measurements:

- preflight connect/range/body latency;
- startup time to first frame;
- HTTP status class/code;
- Wi-Fi band/link speed/RSSI;
- resolution/HDR class;
- decoder name;
- rebuffer/drop/recovery counters;
- ephemeral `castId` shortened/redacted for local correlation.

Forbidden:

- pairing key/code;
- media token or complete path;
- raw media URL;
- private IP;
- SSID/BSSID;
- file/video title in diagnostic export;
- device serial or stable phone identity.

## 13. Expected file changes

### 13.1 Sender production

- `sender/src/main/AndroidManifest.xml`
  - launch-only deep-link intent, singleTask, Application registration, backup rules.
- new `sender/src/main/java/com/flick/sender/FlickApplication.kt`
  - process scope and single `CastCoordinator` ownership.
- `sender/src/main/java/com/flick/sender/MainActivity.kt`
  - cold/warm intent ingress, synchronous intent-data sanitization, and UI attachment.
- new `sender/src/main/java/com/flick/sender/net/PairLaunch.kt`
  - pure launch-only QR parser with no endpoint model.
- `sender/src/main/java/com/flick/sender/net/PairingStore.kt`
  - v2 `tvId`/`keyId` records, `needsRepair`, tombstones, legacy migration markers, persistence seam.
- `sender/src/main/java/com/flick/sender/net/NsdDiscovery.kt`
  - v2/tvId parsing, retry/recovery, endpoint refresh.
- `sender/src/main/java/com/flick/sender/net/ControlClient.kt`
  - strict v2 negotiation, mutual HMAC resume, peerIp/endpoint/capabilities, frame caps, correlated delivery.
- new `sender/src/main/java/com/flick/sender/net/CastCoordinator.kt`; retire `FlickController.kt` after callers/tests migrate
  - process-scoped pairing job, independent TV-value entry, cast reducer/job, timeouts, UI state, cleanup/error routing.
- `sender/src/main/java/com/flick/sender/net/PlaybackSession.kt`
  - separate load UI initialization from command send; castId-filtered state/error handling.
- `sender/src/main/java/com/flick/sender/CastServerService.kt`
  - castId/bindHost/latest-start correlation; notification redaction.
- `sender/src/main/java/com/flick/sender/ServerState.kt`
  - castId-correlated state.
- `sender/src/main/java/com/flick/sender/NetworkUtils.kt`
  - explicit owned-address validation; remove arbitrary cast-path selection.
- `sender/src/main/java/com/flick/sender/MediaHttpServer.kt`
  - no contract change expected beyond regression seams/diagnostics.
- `sender/src/main/java/com/flick/sender/model/Models.kt`
  - expanded cast failure/UI models.
- `sender/src/main/java/com/flick/sender/ui/screens/ConnectScreen.kt`
  - empty TV-display host/port/code entry, unpaired-NSD advisory behavior, and truthful pair results.
- `sender/src/main/java/com/flick/sender/ui/screens/ConnectingScreen.kt`
  - four-step progress plus cancel.
- `sender/src/main/java/com/flick/sender/ui/screens/ErrorScreen.kt`
  - structured failure actions.
- `sender/src/main/java/com/flick/sender/ui/screens/NowPlayingScreen.kt`
  - explicit active error handling; no healthy UI for error phase.
- `sender/src/main/res/values/strings.xml`
  - all user-facing copy.
- new sender backup-rule XML files.

### 13.2 Receiver production

- `receiver/src/main/AndroidManifest.xml`
  - backup rules.
- `receiver/src/main/java/com/flick/receiver/net/PairingManager.kt`
  - PairingSnapshot, surface gate, expiry, stable tvId, persistence seam.
- `receiver/src/main/java/com/flick/receiver/net/ControlCommands.kt`
  - cast-aware commands and shared failure codes.
- `receiver/src/main/java/com/flick/receiver/net/ControlServer.kt`
  - strict frame caps/schema, v2 negotiation, mutual HMAC resume, busy policy, castId, generation invalidation, peerIp/endpoint transcript.
- `receiver/src/main/java/com/flick/receiver/net/PreflightProbe.kt`
  - bounded range/body verification and typed result.
- `receiver/src/main/java/com/flick/receiver/net/NsdAdvertiser.kt`
  - v2/tvId metadata and registration recovery.
- `receiver/src/main/java/com/flick/receiver/net/LanAddress.kt`
  - explicit network-aware address resolution.
- new `receiver/src/main/java/com/flick/receiver/net/LanBindingMonitor.kt`
  - connectivity callback and reconciliation.
- `receiver/src/main/java/com/flick/receiver/session/SessionController.kt`
  - cast generation, progress/terminal emission, first-frame boundary.
- `receiver/src/main/java/com/flick/receiver/player/PlayerController.kt`
  - first-frame callback ordering, redirect rejection/validation, startup/steady retry split, typed error extraction, terminal URL clearing.
- `receiver/src/main/java/com/flick/receiver/player/InstrumentationState.kt`
- `receiver/src/main/java/com/flick/receiver/player/DiagnosticsSnapshot.kt`
- `receiver/src/main/java/com/flick/receiver/player/PlaybackFrame.kt`
  - cast-aware, redacted startup/error observations.
- `receiver/src/main/java/com/flick/receiver/ReceiverApp.kt`
  - collect PairingSnapshot, remove force/polling, bind monitor, new stages.
- receiver Pair/Idle/Settings/Playback/Error screens and strings
  - success/lockout/startup/failure surfaces.
- new receiver backup-rule XML files.

### 13.3 Build, docs, and tests

- module Gradle files: synchronized 0.2.0/versionCode 2 bump, JUnit/coroutines-test, and required Android test dependencies only.
- `docs/design/control-channel.md`: normative QR grammar, v2 protocol, frames, state machines, compatibility.
- `docs/implementation.md`: actual pair/control/media flow, player startup policy, hardening.
- `README.md`: replace obsolete manual TV URL instructions with install/pair/cast/troubleshooting.
- `SECURITY.md`: deep-link risk, source-IP pinning, backup exclusion, notification/log redaction.
- remove or update stale handoff claims after hardware validation.

## 14. Agent work plan and conflict boundaries

The protocol is frozen before production edits. Agents do not improvise frame names, timeouts, success boundaries, or version behavior.

Recorded status (2026-07-20): Phase 0 contract freeze, module implementation, source-level adversarial fix loop, JVM tests, and the synchronized debug build are complete. Android instrumentation on devices and Phase 4 hardware acceptance have not run.

**Model-role override for this handoff:** the user's current instruction explicitly requests GPT-5.6 Terra-high implementation agents and a GPT-5.6 Sol-xhigh adversarial reviewer. That direct instruction supersedes the older Opus/Fable role names in `AGENTS.md` for this implementation only. The repository's file partitioning, public-repo privacy, adversarial fix loop, and single-Gradle-runner rules remain binding.

### Phase 0: contract freeze

Owner: root orchestrator or one docs-only Terra-high agent.

- Land the normative v2 additions in `docs/design/control-channel.md`.
- Add byte-for-byte JSON fixtures for every Section 4/7 frame, the length-prefixed HMAC transcript vectors (including one fixed key/proof), failure-code table, size/field limits, timing values, and compatibility policy.
- No sender/receiver implementation begins until both module owners acknowledge the frozen contract.
- A Sol-xhigh reviewer must sign off specifically on the launch-only/no-pairing-data QR policy, resume transcript, v1 non-consumption negotiation, and endpoint commit boundary before Phase 1 starts.

### Phase 1: parallel module implementation

Sender owner: one Terra-high implementation agent.

- Owns every sender production file, sender test file, sender resources, and sender Gradle edits.
- Implements QR ingress, pairing concurrency/store migration, control v2 client, correlated service/cast reducer, NSD recovery, UI/errors, privacy fixes.
- Does not edit receiver files or run Gradle.

Receiver owner: one Terra-high implementation agent.

- Owns every receiver production file, receiver test file, receiver resources, and receiver Gradle edits.
- Implements PairingSnapshot, v2 server, preflight/first-frame transaction, error classifier, bind lifecycle, tvId, UI, privacy fixes.
- Does not edit sender files or run Gradle.

Docs owner: one Terra-high agent.

- Owns only README, SECURITY, implementation/design docs, and the release checklist.
- Reconciles docs to actual landed symbols after module owners finish.
- Does not run Gradle.

No two implementers edit the same file. Tests remain owned by the corresponding module owner so production/test changes do not conflict.

### Phase 2: static integration and adversarial review

Integrator:

- reviews the combined diff and protocol symmetry;
- runs `git diff --check`;
- does not change behavior silently; routes fixes back to the relevant module owner.

Adversarial Sol-xhigh reviewer, read-only:

- checks cold/warm deep-link replay;
- verifies no code/capability enters the custom URI and intent data is sanitized before publication;
- verifies HMAC domain separation, nonce single-use, endpoint/peer binding, constant-time proof comparison, and no post-pair key transmission;
- pairing gate/expiry/lockout races;
- cast A/B supersession and delayed callbacks;
- service resurrection/leaked locks;
- v1/v2 mixed behavior;
- SSRF/Host/token regressions;
- error truthfulness and cleanup completeness;
- secret/URL/IP leakage;
- first-frame and player retry semantics;
- missing tests and doc drift.

Confirmed findings are fixed by the owning Terra-high module agent. The Sol-xhigh reviewer rechecks the fix diff. No implementation is release-ready while a P0/P1 remains unresolved.

### Phase 3: one build runner

Exactly one designated agent runs Gradle; concurrent builds are forbidden.

Order:

1. sender unit tests;
2. receiver unit tests;
3. sender instrumentation compile/test where a device is available;
4. receiver instrumentation compile/test where a device is available;
5. repository-required two-module build:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:assembleDebug :receiver:assembleDebug
```

Any blocker is reported with exact output. No agent claims device verification from a build alone.

### Phase 4: hardware owner

One agent/operator installs both APKs using explicit serials from `adb devices`, performs the matrix in Section 16, and records only redacted evidence outside committed source unless a safe summary is added to docs.

## 15. Automated test specification

### 15.1 Test infrastructure

Current validation note: 29 sender and 38 receiver JVM tests pass. They exercise focused pure/helper protocol, parser, attempt/candidate/result, ownership, surface/terminal, range/body, redirect, retry, and decoder-selection seams. Misleading coverage for an unused standalone `PairingGate` is not claimed. Production persistence/socket/Activity/hardware boundaries and every instrumentation/device case below remain acceptance requirements; a green JVM/build run must not be reported as those cases passing.

Add the smallest necessary dependencies:

- JUnit 4.13.2 or the repo-standard equivalent;
- `kotlinx-coroutines-test` matching the production coroutines version;
- AndroidX test core/runner and Compose UI test only for the required activity/screen instrumentation;
- no mocking framework unless a test seam cannot reasonably be expressed with fakes.

Extract clocks, random sources, persistence, transport, server control, and player callbacks behind narrow interfaces. Avoid sleeps in unit tests; use virtual time.

### 15.2 Sender tests

Suggested files:

- `PairLaunchParserTest`
- `MainActivityDeepLinkTest` (instrumented)
- `PairingCoordinatorTest`
- `PairingStoreMigrationTest`
- `CastTransactionTest`
- `CastServerGenerationTest`
- `ControlClientV2Test`
- `NsdDiscoveryRecoveryTest`
- `OwnedLanAddressTest`

Required cases:

1. Canonical launch QR succeeds and contains exactly `v=2`; any host, port, code, ID, nonce, capability, duplicate, or extra field is rejected.
2. Missing/malformed/bad-version/path/fragment/user-info/authority-port inputs fail without a socket. Independently typed host/port/code validation covers every invalid form in Section 5.3.
3. Package manager confirms sender MainActivity can resolve the valid QR. The test does not claim custom-scheme exclusivity.
4. Cold launch creates exactly one empty host/port/code-entry surface; warm `onNewIntent` reuses the process coordinator/activity and imports no pairing values.
5. Intent data is null synchronously before event collection. Kill/recreate before collection and recreate after acknowledgement both prove the URI does not replay.
6. Cancel, non-four-digit code, non-RFC1918/ambiguous host, or noncanonical/out-of-range port creates no connection or persistence.
7. Two pairing events/submit attempts yield at most one committing result; stale completion is ignored.
8. Successful pair persists valid v2 record before route change.
9. Tapping an unpaired NSD result never opens a socket or prefills its endpoint; only fully user-entered TV-screen host/port/code can begin initial negotiation, whether Connect was opened by QR or launcher.
10. Resume never serializes the long-lived key. Fixed-vector client/server HMAC proofs verify; changed role, nonce, tvId, keyId, peerIp, host, port, version, order, or length prefix fails.
11. A fake NSD endpoint with a cloned tvId cannot authenticate, rename, replace, or persist over the trusted endpoint. Host/port changes only after valid server proof.
12. Unauthenticated resume denial changes no key/endpoint and, after all candidates fail, marks only that record `needsRepair`; transport failure preserves it for explicit retry. Explicit Forget or successful replacement pairing tombstones/disables only the superseded key and legacy fallback.
13. Legacy same-host record derives the expected local keyId but sends no legacy proof; successful visible v2 pairing at that exact host retires it, while changed-host legacy also requires code pairing from a TV-displayed endpoint.
14. Tombstone/process restart cannot resurrect a revoked legacy pairing.
15. Now Playing is reachable only from matching `loadReady`.
16. Wrong/late castId results do nothing; every outgoing play/pause/seek/skip/volume/stop carries the current castId.
17. Service RUNNING for A cannot satisfy B.
18. Failure, timeout, cancel, disconnect, and supersession stop the matching service exactly once and leave no waiter/job/lock state.
19. Rapid service A/B where A finishes last leaves only B served; ACTION_STOP(A) cannot stop B and a coordinator stop without castId is rejected.
20. Stop-during-start cannot resurrect engine, notification, wake lock, or Wi-Fi lock.
21. Activity destroy/recreate and Compose disposal retain exactly one active coordinator/job/socket; a recreated UI observes current progress without re-sending load.
22. Simulated process reconciliation with orphaned non-IDLE service state stops/clears it and never reconstructs a cast.
23. NSD start failure clears state and retries; stop cancels retry.
24. Notification text/state contains no URL, token, IP, or title.
25. Oversized, fragmented, duplicate-key, extra-field, wrong-type, and malformed authenticated frames close/reject exactly as Section 7.2 specifies.

### 15.3 Receiver tests

Suggested files:

- `PairingManagerTest`
- `ControlServerV2Test`
- `MediaUrlValidatorTest`
- `PreflightProbeTest`
- `SessionControllerCastGenerationTest`
- `PlaybackFailureClassifierTest`
- `StartupRecoveryPolicyTest`
- `LanBindingMonitorTest`
- `ReceiverPairingUiTest` (instrumented as needed)

Required cases:

1. First-run and Pair-another valid v2 `pair` consume one code, durably persist one key/keyId, publish Success, then Standby; a replacement code is never rendered.
2. Two simultaneous correct `pair` frames yield exactly one success.
3. Four invalid attempts retain code; fifth locks/rotates; cooldown escalation is bounded and survives process restart using injected monotonic/wall clocks.
4. Expiry rotates without charging failure budget; old generation cannot pair.
5. Background/close invalidates visible code.
6. Reopening cannot bypass lockout.
7. All non-success pre-auth outcomes produce indistinguishable `denied`.
8. `negotiate` is harmless on a v1-style server fixture; sender never sends `pair`/code without exact v2 `negotiated`. Unsupported version never authenticates or consumes a code.
9. Resume survives process restart; revoked keyId denies generically; nonce replay and modified transcripts deny; no response contains a key.
10. URL validation rejects wrong host, any port except 8080, scheme, user-info, query, fragment, percent-encoded/noncanonical raw path/token, and unsafe IP.
11. Preflight distinguishes timeout, refusal, HTTP 200, every 3xx, 4xx/5xx, malformed/incoherent range or length, stalled/early-EOF body, and valid 206 ranged body.
12. Playback data source rejects 301/302/303/307/308 for same-host path changes, cross-host, cross-port, and cross-protocol locations without making a second request.
13. `loadAccepted` follows validation/adoption, not readiness.
14. First-frame listener is attached before prepare; `loadReady` occurs exactly once only after the current first rendered frame.
15. A/B load supersession allows only B to mutate player or emit a terminal result.
16. play/pause/seek/skip/volume with A after B are rejected and never mutate B.
17. cancel/stop/background/network loss invalidate delayed probe/player callbacks and clear media items/current URL so foreground cannot re-prepare a zombie source.
18. Server stop invalidates queued main-thread commands.
19. Startup errors fail within budget; steady-state retry behavior remains unchanged after first frame.
20. Precise codec/HDR classifications require explicit evidence; ambiguous format errors map conservatively to `unsupported_video_format`/`decoder_init`.
21. A second authenticated phone receives `busy(active_cast)` during Preparing/Active and cannot disconnect or starve the first phone.
22. Loss and restoration of the same IP causes fresh bind/advertisement.
23. Backup and forbidden diagnostic/notification surfaces contain no key/code/token/raw URL/IP/title; intentional pairing/playback UI still renders its allowed QR/code/title.
24. Pre-auth and authenticated 16-KiB caps, string caps, exact schemas, unknown fields, binary/fragmented frames, and close behavior are enforced.

### 15.4 Protocol symmetry tests

One normative fixture document plus mirrored executable fixture sets must assert exact JSON field names/types, required and forbidden fields, bounds, and malformed behavior for:

- negotiate/negotiated/pair/paired;
- resumeInit/resumeChallenge/resumeProof/resumed, including fixed HMAC transcript vectors;
- denied/update-required behavior;
- loadMedia/loadAccepted/loadReady/loadFailed;
- cancelLoad/stop/stopped;
- play/pause/seek/skip/setVolume with castId;
- state/error/commandRejected/busy with castId where defined;
- ping/pong;
- maximum-size and one-byte-oversize frames.

Both module owners sign off on the same fixtures before build integration.

## 16. Real-device acceptance matrix

Status: **not run for the v2 APK pair**. Every item in this section remains pending and must be recorded only with redacted evidence.

### 16.1 Pairing

- Fresh install both apps; TV shows first-run pair.
- Scan with phone system camera while sender is not running: Flick opens with empty host/port/code fields; independently type all values visible on TV, pair, and observe TV Success then Idle.
- Repeat with sender foregrounded on Library and Detail: one activity/process coordinator, one empty entry surface, and no values imported from the URI.
- Pair another phone flow exits the code screen after success.
- Host/port/code independently copied from the TV pairing surface remains functional; tapping an unpaired NSD result only instructs the user to open/scan and manually enter all values.
- Disable mDNS visibility where feasible; launch-only QR plus manually entered TV values still pairs.
- Re-scan the same launch QR and type the same TV endpoint plus consumed old code: generic current-code guidance, no key overwrite.
- Five bad attempts: TV lockout/countdown, no remote detail leakage, recovery after cooldown.
- Restart TV app: sender discovers current endpoint and resumes without code.
- Force a DHCP host/port change: same `tvId` record resumes through mutual proof and updates endpoint only after server proof.
- Advertise a cloned `tvId` from a test endpoint: it receives no key, fails proof, and cannot replace the trusted endpoint.
- While Phone A is actively casting, resume Phone B: B sees active-cast busy; A and playback remain uninterrupted.

### 16.2 Successful 4K direct-play

On the verified Google TV Streamer and a compatible Android sender:

- same 5 GHz network;
- known-good 4K Dolby Vision Profile 8.1 MP4, plus an SDR/HDR10 control clip;
- phone Connecting remains visible through validation/startup;
- TV receives `Range: bytes=0-1023` and reads body before playback;
- sender reaches Now Playing only after first frame;
- decoder name confirms hardware path (`c2.mtk.dvhe.sth.decoder` for the known DV clip);
- zero rebuffer and zero dropped frames for at least 15 minutes including a known bitrate peak, or the full clip if shorter;
- buffer grows toward the configured target;
- screen-off phone continues serving;
- Stop releases port 8080, notification, locks, player, and active cast state.

Record redacted:

- probe latency;
- time to first frame;
- decoder name;
- resolution/HDR class;
- buffer/rebuffer/drop counters;
- transfer throughput summary;
- no addresses, token, title, serial, SSID, or QR.

### 16.3 Deliberate failures

- Stop sender server before/during preflight: precise not-serving failure; phone never Now Playing.
- Block TV-to-phone route: media-unreachable/router guidance.
- Enable a route-changing VPN/multi-interface case: local no-compatible-LAN or host-mismatch guidance; receiver validation remains strict.
- Background/close TV before load: TV-backgrounded guidance.
- Unsupported container/codec/DV profile: evidence-supported compatibility/decoder guidance (broad video-format fallback is acceptable) within startup deadline, not “phone not serving.”
- Invalid media token: safe HTTP rejection; no token displayed.
- Return every HTTP redirect class from the canonical endpoint: TV follows none and reports HTTP rejection.
- Cast A followed immediately by B: only B renders; A cannot later fail or become ready.
- Cancel during every Connecting substage: sender service/notification/locks disappear promptly.
- Disconnect/reconnect TV Wi-Fi with the same IP: control rebinds and re-advertises; stale commands do nothing; subsequent resume/cast works.

### 16.4 Lifecycle soak

Run five complete pair/resume, cast, seek, background/foreground, stop cycles. After every cycle verify:

- no listener remains on port 8080;
- no stale foreground notification;
- no held wake/Wi-Fi lock;
- no zombie WebSocket or NSD registration;
- no decoder/player retained after terminal stop;
- next cast uses a new castId and token.

## 17. Documentation acceptance

Current documentation status: README, SECURITY, implementation reference, normative control contract, and fixtures describe the implemented v2 behavior, including the accepted 250 ms busy-disposition residual. All 29 sender and 38 receiver JVM tests pass and both debug APKs build; production-boundary instrumentation and device/hardware claims remain explicitly pending.

Before release:

- README describes the actual QR, code, manual fallback, paired resume, Flick action, and troubleshooting.
- `docs/design/control-channel.md` is normative and matches every implemented frame/field/version.
- `docs/implementation.md` no longer describes typing a media URL on the TV or QR as future work.
- SECURITY documents the launch-only custom-scheme boundary/phishing limitation, HMAC resume, generic denial, source-IP pinning, backup exclusion, notification redaction, and diagnostic rules.
- Product copy does not claim an in-app scanner or one-scan authorization; it accurately says to use the phone camera to open Flick, then enter the TV host, port, and code.
- The old handoff “not installed” warning is removed or replaced with dated, redacted validation status.

## 18. Definition of done

The release is complete only when all are true:

Current status: source implementation, 29 sender/38 receiver JVM tests, and the two-APK debug build are complete; this definition is **not yet met** because production-boundary Android instrumentation and the Section 16 real-device matrix remain outstanding.

- Valid launch-only TV QR opens sender on cold and warm launch, contains only `v=2`, and reaches empty host/port/code entry.
- Initial pairing can target only an endpoint independently typed from the visible TV surface; deep links and unpaired NSD results can never supply or prefill it.
- Invalid QR opens no network socket and cannot create/overwrite a pairing.
- Successful first-run and Pair-another pairing show one success transition and no replacement code.
- Pairing code/visibility/lockout behavior passes production-boundary concurrency and persistence tests.
- Pairing resumes after TV process restart and endpoint change using stable tvId, nonce-based mutual HMAC, and endpoint commit only after receiver proof; the stored key is never retransmitted.
- Phone Now Playing is reachable only after matching TV first-frame `loadReady`.
- Every startup failure is structured, correlated, visible wherever communication makes that truthful, and leaves no sender service/locks/token.
- Host/token/URL/SSRF hardening and byte-range behavior pass regression tests.
- Unsupported 4K/DV fails as the most precise compatibility category supported by actual format/decoder evidence, never an invented profile diagnosis.
- Successful known-good 4K/DV direct-plays through the hardware decoder with the established zero-stall evidence.
- Privacy review confirms no secret URL/token/key/code/IP/title leakage and pairing preferences are backup-excluded.
- Tests pass, `git diff --check` passes, both modules build with the required JDK, and both APKs are installed/tested together.
- Sol-xhigh adversarial review has no unresolved P0/P1 finding; confirmed lower-severity findings are either fixed or explicitly accepted in this spec.

## 19. Rollback

- Preserve the last known source/commit and signing setup before installing the v2 pair.
- Roll back both modules together using a newly built higher-`versionCode` APK pair containing the prior implementation; mixed v1/v2 operation is unsupported.
- Debug-only downgrade may use `adb install -r -d` only with matching signatures and an explicit warning. Uninstall/reinstall clears app data and pairings, so it is not the preservation path.
- Migration retains non-revoked legacy host records only during the declared rollback window. Revocation/tombstones take precedence over rollback convenience and must prevent stale-key resurrection.
- Do not roll back Host pinning, token validation, LAN-only bind, generic pre-auth denial, or hardware-only decoding independently.
- If first-frame acknowledgement regresses startup on hardware, stop release and correct the v2 state machine. Do not re-enable false optimistic Now Playing as a production fallback.

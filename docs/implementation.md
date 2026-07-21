# Flick — implementation reference

This document describes the implemented v2 pair-to-play path. The binding schemas and timing rules are in [control-channel.md](design/control-channel.md), with executable reference bytes in [control-v2-fixtures.md](design/control-v2-fixtures.md). Direct-play remains the product invariant: Flick never transcodes or screen-mirrors.

## Validation status

Both modules are synchronized at `versionCode=2` / `versionName=0.2.0`. As of 2026-07-20, all 29 sender and 38 receiver JVM unit tests pass and the required synchronized build produces both debug APKs.

The JVM tests cover focused pure/helper seams; they do not execute the production `SharedPreferences` stores, Ktor client/server sockets, Activity intent/lifecycle integration, or a Media3 hardware decoder. No Android instrumentation result is claimed. No v2 APK pair has been installed and exercised on a real phone/TV for this release. System-camera cold/warm launch, actual pairing/resume, first-frame delivery, lifecycle/LAN transitions, and sustained 4K/Dolby Vision remain device-acceptance work. Earlier manual direct-play measurements are a transport/tuning baseline, not v2 validation.

## Architecture

```text
phone (:sender)                                           TV (:receiver)
content:// media ── CastServerService ── HTTP ranges ──> Media3 hardware decoder
       ^                   :8080 /v/<token>                    ^
       |                  exact owned peer IP                   |
       └──── application CastCoordinator <── WebSocket v2 ──────┘
                                                    control server
```

The sender's Ktor CIO media server reads directly from a `ParcelFileDescriptor`/`FileChannel`; it does not cache or copy the whole file. The TV's Ktor CIO WebSocket server carries pairing/control/state only and cannot browse files or request arbitrary URLs.

## Media HTTP contract

`CastServerService` creates a fresh 128-bit URL-safe token per start and binds port 8080 on the exact currently owned RFC1918 address observed by the authenticated TV. `MediaHttpServer` publishes URI and token as one immutable atomic session so retargeting cannot mix an old authorization token with a new source.

| Endpoint | Implemented behavior |
|---|---|
| `GET /v/{token}` | Bound-host `Host` check, constant-time token check, identical `404` for absent/wrong session, MIME/length headers, full or single-range streaming, `206`/`416`, malformed-range fallback to full `200`, and at most four concurrent response bodies (`503` beyond the cap). |
| `HEAD /v/{token}` | Same Host/token and range semantics, headers only, outside the body-transfer cap. |
| `GET /ping` | Unauthenticated `ok` liveness response; it exposes no media bytes. |

The service uses `WIFI_MODE_FULL_HIGH_PERF` and a six-hour-bounded partial wake lock while it owns the source. The foreground notification is private, contains generic direct-play status only, and carries a unique immutable Stop intent for its `castId`. Latest-start/resource ownership gates prevent delayed A startup/failure/stop from publishing, releasing, or stopping B. `START_NOT_STICKY` and unknown intents never reconstruct a cast.

## Launch and initial pairing

The TV QR payload is exactly `flick://pair?v=2`. The sender's `singleTask` activity routes both `onCreate` and `onNewIntent` through one ingress. It copies the URI locally, clears both incoming and stored intent data synchronously, parses only the copy, and publishes an unsaved process-local event. A valid event keys a new empty host/port/code form; it does not prefill or connect.

Host entry accepts canonical dotted-decimal RFC1918 IPv4 only; port is canonical decimal `1..65535`; code is exactly four ASCII digits. Manual Connect uses the same full form. Unpaired NSD results are advisory and cannot supply a target.

The sender opens the typed endpoint and sends `negotiate(v=2,minV=2,maxV=2)` with a fresh nonce. It sends `pair` only after the exact strict `negotiated` response, so an old receiver cannot consume the code during version detection. A valid `paired` result is durable before routing away.

`PairingManager` is the receiver's sole authorization/UI source. An Open code is stable for five minutes and valid only while displayed. Four global failures retain it; failure five begins a 30-second lockout, with rounds doubling to an eight-minute maximum. The global round/deadline survives process restart; per-host state is bounded to 32 records and throttles a host for 10 seconds after three failures. Pair success atomically commits key/keyId/label, consumes the code, shows 1.5 seconds of Success, then returns to Standby without exposing a replacement code.

The receiver caps unauthenticated WebSockets at four and applies a six-second auth deadline. Malformed pre-auth input has a three-frame budget. Both sides enforce the 16 KiB decoded frame cap, unfragmented UTF-8 text/object input, duplicate/trailing-data rejection, exact fields/types/ranges, and no unknown v2 message types. Device, TV, and title labels are normalized to canonical single-line values before sending; the receiver rejects noncanonical control/format/whitespace variants rather than silently changing an authorization transcript or command.

Closing/hiding the pairing surface invalidates its open generation immediately; a late pair attempt is generically denied. TV Settings implements confirmed **Forget all phones**: it stops/revokes the active controller, clears credentials only after the durable write succeeds, resets throttle/lockout state, and reopens visible first-run pairing.

## Resume, discovery, and persistence

The receiver persists a random non-secret `tvId` and advertises `_flick._tcp.` with actual port plus TXT `v=2`, `id`, model, and state. The sender treats all resolved values as candidates. It tries the last mutually verified endpoint first, then at most three same-`tvId` candidates in deterministic host/port order.

Resume uses fresh 128-bit client/server nonces and HmacSHA256 over the frozen versioned, role-separated, length-prefixed transcript. The receiver consumes one challenge and compares the client proof in constant time. The sender verifies the server proof before marking the socket authenticated or committing endpoint/name changes. The 256-bit pairing key is transmitted only by initial `paired`, never by resume.

An unauthenticated denial cannot erase key bytes. If all bounded candidates fail authentication/protocol verification, the record is marked `needsRepair` and automatic retry is suppressed; transport-only failure retains it for explicit retry. Replacing a v2 key tombstones the superseded key ID.

Legacy v1 host records are handled conservatively. The sender can identify a canonical stored host and derive the deterministic non-secret legacy key ID locally, but it sends no legacy key or proof and performs no automatic challenge migration because the receiver has no safe legacy TV-ID lookup. The user re-pairs with current TV-displayed values. Only a successful visible v2 pair at the exact stored host writes the migration marker and retires that legacy record; a changed host always requires visible re-pairing and leaves no proof spray across discovery candidates.

After either initial pairing or resume, the sender waits 250 ms for the receiver's immediate `busy(active_cast)` disposition. A busy result after a successful initial pair is represented internally as `PairedBusy` so the newly issued key/endpoint is durably preserved before the user sees the busy failure. Accepted residual P2: v2 has no positive `available` frame, so silence after 250 ms is treated as available. A late busy can cause brief sender UI/foreground-service churn, but receiver ownership is decided independently under its mutex and the second phone cannot take the cast. A future protocol revision may add an explicit `available|busy` result.

NSD failures clear listener state and schedule one bounded retry; stop cancels it. On TV, connectivity callbacks plus two-second address reconciliation tear down control/cast/NSD on loss or change, then bind and advertise a fresh endpoint while foregrounded—even when the restored DHCP address is unchanged.

## Cast ownership and sender startup

`FlickApplication` owns one main-immediate application scope and one `CastCoordinator`. Activities/Composables observe its flows and forward events; they do not own the cast/control socket. One pairing-attempt generation and one cast job prevent stale completions from committing.

Each Flick action creates a random 128-bit `castId` and:

1. Uses an authenticated v2 socket, resuming the saved pairing first if necessary.
2. Confirms receiver-observed `peerIp` is still owned by an up, non-loopback phone interface.
3. Requires a `content:` item with positive known size; starts the source service for that cast and waits up to nine seconds for matching RUNNING/ERROR.
4. Registers `loadAccepted`/`loadReady` waiters, sends `loadMedia`, waits up to two seconds for acceptance, then up to 18 more seconds for first-frame readiness.
5. Enters Now Playing only when the matching current-generation `loadReady` arrives.

Every non-ready path invalidates the cast generation, cancels waiters, best-effort sends `cancelLoad`, and sends a cast-correlated service Stop. The service clears its atomic media session and releases its socket/locks only when that generation still owns them. Retry is user initiated and creates a new cast ID/token.

## Receiver validation and playback

Before adopting media, `ControlServer` strictly validates `loadMedia` and the canonical URL: HTTP, port 8080, raw `/v/<22-character-token>`, authenticated numeric peer host, and no user-info/query/fragment/percent encoding. `loadAccepted` is the synchronous adoption boundary and duplicates replay the retained accepted/ready/failed result.

`PreflightProbe` has one absolute monotonic six-second deadline. It spends at most two seconds on a raw TCP connect, then at most three seconds per HTTP blocking phase while a scheduled disconnect enforces the absolute end. It sends `Range: bytes=0-1023`, disables redirects, requires 206 plus exact `Content-Range`/`Content-Length`, reads exactly the advertised bytes, and performs one EOF read. Drip-feed, early/extra body, 200, all 3xx, 4xx/5xx, and incoherent range data fail safely.

For playback, each Media3 `DataSpec` creates one `HttpURLConnection` with `instanceFollowRedirects=false`; every 3xx is rejected before any second request. The video codec selector filters software codecs (`OMX.google.*`, `c2.android.*`, known software names, or API 29+ non-hardware entries), disables decoder fallback, and disables extension renderers.

The current cast/generation first-frame callback is installed before media/prepare. A single movable `PlayerSurface` stays attached to Media3 throughout Checking/Preparing behind an opaque Connecting overlay, then the same surface is revealed for Active playback; this preserves the real video output needed for the first-frame callback. Only `Player.Listener.onRenderedFirstFrame` transitions `Preparing` to `Active` and emits `loadReady`; `STATE_READY` alone is insufficient. The receiver's adoption-to-first-frame deadline is 18 seconds. Startup permits only two short transient-network retries (250 ms, then 500 ms) within that deadline; format/parser/decoder errors fail without entering the four-attempt steady-state recovery policy.

After first frame, the existing player tuning remains: 15/180-second min/max forward buffer, 2.5-second initial threshold, five-second post-rebuffer threshold, 30-second back buffer, 256 MiB byte target, up to 20 load retries with five-second capped backoff, and four bounded fatal-transient recovery attempts at 2/4/8/15 seconds.

## Lifecycle and structured failure

Control connection, cast ID, and receiver cast generation guard every queued mutation. A new cast supersedes the old generation; stale callbacks/commands cannot mutate the new player. A WebSocket close calls the lease-guarded `onControlLost(generation)`, so a displaced/stale socket cannot tear down its successor. Activity background, LAN loss/change, and endpoint rebind use `forceLocalTeardown()` before server teardown because those local-authority events must clear the current cast regardless of socket lease. While preparing/active, a resumed phone receives proof-bearing `resumed` then `busy(active_cast)`; a newly paired phone receives `paired` then `busy`, preserving the key through the internal `PairedBusy` result. Neither can displace the owner.

`stop(castId)` is the canonical terminal command for the current Checking/Preparing or Active cast. The receiver clears player/session ownership, sends cast-correlated `stopped`, and replays that retained result for a duplicate stop. The sender reducer treats matching `stopped` as terminal, runs cast-correlated foreground-service cleanup, and returns to Library; local cleanup never waits indefinitely for the acknowledgement. `cancelLoad` remains the sender's best-effort pre-ready cancellation path; local TV Back uses the same stopped terminal path rather than silently clearing an active cast.

TV background, LAN loss/change, control stop/loss, cancellation, and terminal failure invalidate the session before stopping/clearing media items, URL, title, startup callback, retry state, and decoder ownership. NSD/control are not advertised while backgrounded. Foreground return requires a fresh authenticated cast; v2 has no background playback resume.

Wire failures are stable lowercase codes with required `retryable` and optional HTTP status only when observed. Raw exception text is never serialized. Classification is evidence-conservative: actual parser/decoder/network/status evidence is used, while ambiguous codec/HDR failures stay broad (`unsupported_video_format`, `decoder_init`, or `unknown`). Phone-local pre-control/source/bind failures remain phone-only; adopted receiver failure reaches both devices only while control is still usable.

## Privacy and diagnostics

Pairing preferences are excluded from legacy backup, cloud backup, and device transfer. The notification omits title, URL, token, and private address. Diagnostics may show redacted probe/startup time, HTTP status, Wi-Fi band/link speed/RSSI, decoder, resolution/HDR class, and buffer/rebuffer/drop/recovery counters; export/log/committed evidence must not include pairing material, full URL/token, raw private address, SSID/BSSID, serial, or title.

## Release and rollback

Sender and receiver 0.2.0 must ship together. A v2 sender neither authenticates unversioned control nor falls back to optimistic v1 playback. Rollback installs a matched prior implementation on both devices using a newly higher `versionCode`; uninstall/reinstall clears pairing data. Host/token validation, explicit LAN binding, generic auth denial, no-redirect playback, and hardware-only decoding are not independently rolled back.

# Flick — implementation reference

This document describes the implemented v2 pair-to-play path. The binding schemas and timing rules are in [control-channel.md](design/control-channel.md), with executable reference bytes in [control-v2-fixtures.md](design/control-v2-fixtures.md). Direct-play remains the product invariant: Flick never transcodes or screen-mirrors.

## Validation status

Both modules are synchronized at `versionCode=3` / `versionName=0.2.1`.

### Automated build, JVM, and lint gate

As of 2026-07-21, the final automated gate passed:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test :sender:assembleDebug :receiver:assembleDebug :sender:assembleDebugAndroidTest :receiver:assembleDebugAndroidTest :sender:lintDebug :receiver:lintDebug
```

Gradle completed 170 tasks (11 executed and 159 up-to-date). All 86 JVM tests passed—33
sender and 53 receiver—with zero failures or errors. Both app debug APKs and both
instrumentation-test APKs assembled. Sender lint reported 0 errors, 73 warnings, and 1 hint;
receiver lint reported 0 errors, 62 warnings, and 6 hints. The lint warnings/hints remain
non-blocking review inventory; a zero-error lint result is not a claim that every warning has
been remediated.

### Connected instrumentation and TV smoke

On a connected, awake Google TV Streamer running API 34 at 1920×1080, the receiver Compose
suite passed 10/10 and the sender component-level Compose suite passed 8/8. The TV Idle and
Settings visual/D-pad smoke verified the cyan focus treatment, Back/navigation behavior,
safe-area layout, and focus-driven Settings scrolling. That smoke exposed a Settings clipping
defect; the defect was fixed and the affected path was re-verified.

The sender instrumentation suite ran on the TV device, **not on an Android phone**. Its 8/8
result validates the exercised component semantics and behavior in that host environment; it
does not establish phone viewport, touch ergonomics, camera/deep-link integration, or phone
accessibility acceptance.

### Open hardware acceptance

No phone was connected for this validation pass. Actual phone↔TV pairing and resume,
camera/deep-link entry, first-frame direct-play, lifecycle and LAN transitions, and sustained
4K HDR/Dolby Vision playback remain open hardware acceptance gates. Successful JVM tests,
lint, APK assembly, and TV-hosted instrumentation must not be presented as evidence that those
cross-device paths passed.

### Material Expressive toolchain baseline

The Material Expressive redesign pins AGP `9.3.0`, Gradle `9.5.0`, the Compose
compiler plugin `2.3.21`, and `compileSdk=37`; `targetSdk` remains `36` during
the UI migration. AGP 9's built-in Kotlin replaces the external
`org.jetbrains.kotlin.android` plugin, while the Compose compiler plugin remains
explicit. The project continues to launch Gradle with the repository-required
JDK 21 command. AGP 9.3 supports API 37 and requires Gradle 9.5.0.

The sender pins `androidx.compose.material3:material3:1.5.0-alpha24`, the line
that exposes `MaterialExpressiveTheme` and `MotionScheme.expressive()`. Its
transitive metadata requires Compose `1.12.0-beta01`. The shared Compose BOM is
therefore pinned at `2026.06.01` for the stable receiver stack and common
artifacts, while the sender's direct alpha dependency is intentionally allowed to
select the newer Compose 1.12 family. This is a deliberate, isolated alpha risk;
the toolchain gate must be green before any sender theme code adopts those APIs.

The receiver remains TV-native: it uses `androidx.tv:tv-material:1.1.0` and the
stable BOM-selected phone Material3 only for its single text field. It must never
wrap its UI in the sender's `MaterialExpressiveTheme`.

The JVM tests cover focused pure/helper seams; they do not execute the production
`SharedPreferences` stores, Ktor client/server sockets, Activity intent/lifecycle integration,
or a Media3 hardware decoder. The connected Compose results above cover only their stated UI
scope. Earlier manual direct-play measurements remain a transport/tuning baseline, not current
cross-device acceptance evidence.

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

## Control port selection

The control port is durable, persisted, and has a fixed default. An ephemeral port (`0`) meant every restart bound a different number: the port printed on the pairing screen went stale before the user finished typing it, the phone's persisted `port_<host>` was dead on every resume, and cached mDNS SRV records named a closed port.

`DEFAULT_CONTROL_PORT = 47654` sits in the dynamic range and is deliberately clear of Cast's 8008/8009/8010 and of the sender's media port 8080. `ControlServer.start(host, ports)` walks the ladder in order:

1. the port persisted from the last **successful** bind;
2. `47654`;
3. `47655..47663`, on `BindException`;
4. `0` (ephemeral), as an absolute last resort so a hostile or unlucky collision on every fixed port can never make the TV undiscoverable.

The receiver persists, advertises and renders the port that **actually bound**, never the one requested, and logs which tier won (`[bind] started host=<tv-lan-ip> port=<port> tier=persisted|default|ladder|ephemeral`). A failed candidate is logged at `w` rather than swallowed — silently absorbing `BindException` is exactly what would make a fixed-port strategy fail invisibly.

`reuseAddress = true` is set on the CIO engine. It defaults to **false** in Ktor 3.1.3, so a same-port rebind throws `EADDRINUSE` while a prior peer socket lingers in `TIME_WAIT` — which a durable fixed port hits on every restart. The bind host remains the specific site-local IPv4 from `LanAddress.current()`, never `0.0.0.0`, and the anti-rebinding `Host` pin still compares the exact bound `host:port`.

The binding is one immutable `(engine, host, port)` tuple published only after the engine has started; a socket accepted inside that publication window waits up to two seconds for it rather than being rejected. Engine start/stop run on `Dispatchers.IO` via `startSuspend`/`stopSuspend`; the blocking `start(Boolean)`/`stop(Long, Long)` bridges would hold the caller for up to ~1.1 s.

Connectivity callbacks are re-sample triggers, not rebind events. `LanBindingMonitor` reports an **address**; `onCapabilitiesChanged` fires for RSSI, link speed, validation and `NOT_SUSPENDED` on a link that never changed address, and those updates carry no address at all. A single reconciler owns bind state, is woken by the distinct address flow with a slow 10 s safety-net tick, and resolves a capability burst on an unchanged address to "do nothing".

A visibility change (screensaver, Home, a system dialog) no longer tears anything down. `ON_STOP` releases the decoder, publishes an idle frame, sends the `tv_backgrounded` terminal and closes the pairing surface, then **re-advertises with TXT `state=sleeping` while keeping the socket bound and the service registered**. `ReceiverBindingGate` already refuses `loadMedia` while backgrounded, so the posture is unchanged: the socket stops accepting new casts instead of vanishing. `ON_START` re-advertises `state=ready` on the still-live port. NSD has no update primitive, so a state flip re-registers under the **same service name and the same port**; the sender must treat a same-name re-registration as an update, never as a loss.

## Launch and initial pairing

The TV QR payload is

```text
flick://pair?v=3&h=<tv-lan-ip>&p=<port>
```

with exactly the parameter set `{v,h,p}` in any order, no user-info, no URI port, no path and no fragment. **The four-digit code is never in the QR.** It stays a human-verified out-of-band factor read off the TV screen, so a scan alone still authorizes nothing. The QR is emitted only while a real binding exists (host non-blank and port in `1..65535`); there is never an `h=`/`p=` placeholder. The legacy bare `flick://pair?v=2` remains a valid launch-only envelope with no prefill, so an un-updated TV still opens the app.

The sender's `singleTask` activity routes both `onCreate` and `onNewIntent` through one ingress. It copies the URI locally, clears both incoming and stored intent data synchronously, parses only the copy, and publishes an unsaved process-local event. On `v=3` it validates `h` with `isCanonicalIpv4` and `p` with `isCanonicalPort`, treats any failure as an invalid QR, **prefills host and port and focuses the code cell — it does not auto-connect**. The QR-supplied endpoint stays untrusted until the user-typed code proves it.

Host entry accepts canonical dotted-decimal RFC1918 IPv4 only; port is canonical decimal `1..65535`; code is exactly four ASCII digits. Manual Connect uses the same full form, pre-filled with `47654` as a default only — it is never dialed blind and never overrides a QR- or NSD-supplied port. Unpaired NSD results are advisory and cannot supply a target.

The sender opens the typed endpoint and sends `negotiate(v=2,minV=2,maxV=2)` with a fresh nonce. It sends `pair` only after the exact strict `negotiated` response, so an old receiver cannot consume the code during version detection. A valid `paired` result is durable before routing away.

`PairingManager` is the receiver's sole authorization/UI source. An Open code is stable for five minutes and valid only while displayed. Four global failures retain it; failure five begins a 30-second lockout, with rounds doubling to an eight-minute maximum. The global round/deadline survives process restart; per-host state is bounded to 32 records and throttles a host for 10 seconds after three failures. Pair success atomically commits key/keyId/label, consumes the code, shows 1.5 seconds of Success, then returns to Standby without exposing a replacement code.

### Peer identity

The authenticated peer is read from `call.request.local.remoteAddress`, never `call.request.origin.remoteHost`.

`remoteHost` resolves `InetSocketAddress.getHostName()` — a **synchronous reverse-DNS (PTR) query** on the LAN. Most consumer and ISP routers run dnsmasq and answer PTR for DHCP clients, so on those networks it returns a hostname such as `some-phone.lan`. That value failed the private-IPv4 gate and closed every legitimate phone's socket before one protocol frame was read, with no log and no `denied` frame; it was also published as `peerIp` in `paired`/`resumeChallenge`/`resumed` and is field 8 of the resume HMAC transcript, which the sender validates as an IPv4 literal. `remoteAddress` uses `getHostString()` and never resolves. `local` is used rather than `origin` because `origin` is overridable by a `Forwarded`/`XForwardedHeaders` plugin, while `local` is always the CIO connection point derived from the accepted socket. When the socket address is null Ktor returns the literal `"unknown"`, which correctly fails the gate — fail-closed is intended.

Using a non-resolving, non-header-derived identity strengthens the posture: `remoteHost` was a resolver-controlled string feeding per-host pairing throttling and the HMAC transcript.

The receiver caps unauthenticated WebSockets at four and applies a six-second auth deadline. Malformed pre-auth input has a three-frame budget. Both sides enforce the 16 KiB decoded frame cap, unfragmented UTF-8 text/object input, duplicate/trailing-data rejection, exact fields/types/ranges, and no unknown v2 message types. Device, TV, and title labels are normalized to canonical single-line values before sending; the receiver rejects noncanonical control/format/whitespace variants rather than silently changing an authorization transcript or command.

A `denied` frame carries a coarse diagnostic reason: `{"t":"denied","v":2,"reason":"<enum>"}` over exactly `code`, `expired`, `surface`, `locked`, `busy`, `storage`, `proof`, `unknown`. Only `code` and `expired` are code-derived, so the frame stays non-enumerating — it is no oracle for guessing a key id, a device, or whether a TV has ever been paired. The sender accepts both the legacy two-key form (an un-updated receiver) and the three-key form, and maps each reason to distinct copy.

Closing/hiding the pairing surface invalidates its open generation immediately; a late pair attempt is denied with `reason=surface`. TV Settings implements confirmed **Forget all phones**: it stops/revokes the active controller, clears credentials only after the durable write succeeds, resets throttle/lockout state, and reopens visible first-run pairing.

## Resume, discovery, and persistence

The receiver persists a random non-secret `tvId` and advertises `_flick._tcp.` with actual port plus TXT `v=2`, `id`, model, and state. The sender treats all resolved values as candidates. It tries the last mutually verified endpoint first, then at most three same-`tvId` candidates in deterministic host/port order.

Resume uses fresh 128-bit client/server nonces and HmacSHA256 over the frozen versioned, role-separated, length-prefixed transcript. The receiver consumes one challenge and compares the client proof in constant time. The sender verifies the server proof before marking the socket authenticated or committing endpoint/name changes. The 256-bit pairing key is transmitted only by initial `paired`, never by resume.

An unauthenticated denial cannot erase key bytes. If all bounded candidates fail authentication/protocol verification, the record is marked `needsRepair` and automatic retry is suppressed; transport-only failure retains it for explicit retry. Replacing a v2 key tombstones the superseded key ID.

Legacy v1 host records are handled conservatively. The sender can identify a canonical stored host and derive the deterministic non-secret legacy key ID locally, but it sends no legacy key or proof and performs no automatic challenge migration because the receiver has no safe legacy TV-ID lookup. The user re-pairs with current TV-displayed values. Only a successful visible v2 pair at the exact stored host writes the migration marker and retires that legacy record; a changed host always requires visible re-pairing and leaves no proof spray across discovery candidates.

After either initial pairing or resume, the sender waits 250 ms for the receiver's immediate `busy(active_cast)` disposition. A busy result after a successful initial pair is represented internally as `PairedBusy` so the newly issued key/endpoint is durably preserved before the user sees the busy failure. Accepted residual P2: v2 has no positive `available` frame, so silence after 250 ms is treated as available. A late busy can cause brief sender UI/foreground-service churn, but receiver ownership is decided independently under its mutex and the second phone cannot take the cast. A future protocol revision may add an explicit `available|busy` result.

NSD failures clear listener state and schedule one bounded retry with the error code logged; stop cancels it. On TV, connectivity callbacks re-sample the address and the reconciler tears down control/cast/NSD only when the site-local IPv4 is actually **lost or different**, then binds and advertises a fresh endpoint while foregrounded. An unchanged address produces no rebind, whatever the callback said.

## Cast ownership and sender startup

`FlickApplication` owns one main-immediate application scope and one `CastCoordinator`. Activities/Composables observe its flows and forward events; they do not own the cast/control socket. One pairing-attempt generation and one cast job prevent stale completions from committing.

Each Flick action creates a random 128-bit `castId` and:

1. Uses an authenticated v2 socket, resuming the saved pairing first if necessary.
2. Confirms receiver-observed `peerIp` is still owned by an up, non-loopback phone interface.
3. Requires a `content:` item with positive known size; starts the source service for that cast and waits up to nine seconds for matching RUNNING/ERROR.
4. Registers `loadAccepted`/`loadReady` waiters, sends `loadMedia`, waits up to two seconds for acceptance, then up to 18 more seconds for first-frame readiness.
5. Enters Now Playing only when the matching current-generation `loadReady` arrives.

Every non-ready path invalidates the cast generation, cancels waiters, best-effort sends `cancelLoad`, and sends a cast-correlated service Stop. The service clears its atomic media session and releases its socket/locks only when that generation still owns them. Retry is user initiated and creates a new cast ID/token.

Once Active, phone navigation is independent from cast ownership. The explicit downward minimize action and system Back route Now Playing to Library without sending `stop`, cancelling the cast job, or releasing the source service. Library renders a cast-backed mini-player that restores controls only while the same current `castId` remains Active. Android partial video access exposes a persistent user-triggered **Add videos** action; full MediaStore access exposes **Refresh**. Media queries are cancellation- and generation-gated so a stale result cannot republish videos after reselection or revocation.

## Receiver validation and playback

Before adopting media, `ControlServer` strictly validates `loadMedia` and the canonical URL: HTTP, port 8080, raw `/v/<22-character-token>`, authenticated numeric peer host, and no user-info/query/fragment/percent encoding. `loadAccepted` is the synchronous adoption boundary and duplicates replay the retained accepted/ready/failed result.

`PreflightProbe` has one absolute monotonic six-second deadline. It spends at most two seconds on a raw TCP connect, then at most three seconds per HTTP blocking phase while a scheduled disconnect enforces the absolute end. It sends `Range: bytes=0-1023`, disables redirects, requires 206 plus exact `Content-Range`/`Content-Length`, reads exactly the advertised bytes, and performs one EOF read. Drip-feed, early/extra body, 200, all 3xx, 4xx/5xx, and incoherent range data fail safely.

For playback, each Media3 `DataSpec` creates one `HttpURLConnection` with `instanceFollowRedirects=false`; every 3xx is rejected before any second request. The video codec selector filters software codecs (`OMX.google.*`, `c2.android.*`, known software names, or API 29+ non-hardware entries), disables decoder fallback, and disables extension renderers.

The current cast/generation first-frame callback is installed before media/prepare. A single movable `PlayerSurface` stays attached to Media3 throughout Checking/Preparing behind an opaque Connecting overlay, then the same surface is revealed for Active playback; this preserves the real video output needed for the first-frame callback. Only `Player.Listener.onRenderedFirstFrame` transitions `Preparing` to `Active` and emits `loadReady`; `STATE_READY` alone is insufficient. The receiver's adoption-to-first-frame deadline is 18 seconds. Startup permits only two short transient-network retries (250 ms, then 500 ms) within that deadline; format/parser/decoder errors fail without entering the four-attempt steady-state recovery policy.

After first frame, the existing player tuning remains: 15/180-second min/max forward buffer, 2.5-second initial threshold, five-second post-rebuffer threshold, 30-second back buffer, 256 MiB byte target, up to 20 load retries with five-second capped backoff, and four bounded fatal-transient recovery attempts at 2/4/8/15 seconds.

Playback chrome is a compact bottom scrim: the media title is a single ellipsized 30sp line, timecode is 28sp tabular, transport targets remain 48dp/56dp with at least 32dp rounded Material seek glyphs, and the movie frame stays visible behind lighter pause/seek/buffering dimming. Media3 subtitles retain its viewport-derived/user-scaled baseline minus exactly 2sp, use white text with a 60%-opaque black background and drop shadow, and ignore embedded cue styling that would override this treatment. Caption font-scale changes are applied in place without recreating the Activity or tearing down the cast.

Android TV remote input is routed first at `Activity.dispatchKeyEvent`, independent of Compose focus. During Active playback with chrome hidden, DPAD center/Enter toggles play-pause once per press; left/right seek by ten seconds and repeated key-down events provide held scrubbing; up/down reveal and re-arm the chrome. The matching key-up is consumed so the newly revealed chrome cannot receive the tail of that gesture a second time. With chrome visible, DPAD falls through to the transport/volume focus graph; dedicated media play, pause, play-pause, rewind, and fast-forward keys remain global. Outside Active playback all keys fall through to normal Compose/system navigation.

## Lifecycle and structured failure

Control connection, cast ID, and receiver cast generation guard every queued mutation. A new cast supersedes the old generation; stale callbacks/commands cannot mutate the new player. A WebSocket close calls the lease-guarded `onControlLost(generation)`, so a displaced/stale socket cannot tear down its successor. Activity background, LAN loss/change, and endpoint rebind use `forceLocalTeardown()` before server teardown because those local-authority events must clear the current cast regardless of socket lease. While preparing/active, a resumed phone receives proof-bearing `resumed` then `busy(active_cast)`; a newly paired phone receives `paired` then `busy`, preserving the key through the internal `PairedBusy` result. Neither can displace the owner.

`stop(castId)` is the canonical terminal command for the current Checking/Preparing or Active cast. The receiver clears player/session ownership, sends cast-correlated `stopped`, and replays that retained result for a duplicate stop. The sender reducer treats matching `stopped` as terminal, runs cast-correlated foreground-service cleanup, and returns to Library; local cleanup never waits indefinitely for the acknowledgement. `cancelLoad` remains the sender's best-effort pre-ready cancellation path; local TV Back uses the same stopped terminal path rather than silently clearing an active cast.

TV background, LAN loss/change, control stop/loss, cancellation, and terminal failure invalidate the session before stopping/clearing media items, URL, title, startup callback, retry state, and decoder ownership. While backgrounded the TV stays bound and advertised as `state=sleeping`, and `ReceiverBindingGate` refuses `loadMedia`; the socket accepts no new cast. Foreground return requires a fresh authenticated cast; v2 has no background playback resume.

Wire failures are stable lowercase codes with required `retryable` and optional HTTP status only when observed. Raw exception text is never serialized. Classification is evidence-conservative: actual parser/decoder/network/status evidence is used, while ambiguous codec/HDR failures stay broad (`unsupported_video_format`, `decoder_init`, or `unknown`). Phone-local pre-control/source/bind failures remain phone-only; adopted receiver failure reaches both devices only while control is still usable.

## Privacy and diagnostics

Pairing preferences are excluded from legacy backup, cloud backup, and device transfer. The notification omits title, URL, token, and private address. Diagnostics may show redacted probe/startup time, HTTP status, Wi-Fi band/link speed/RSSI, decoder, resolution/HDR class, and buffer/rebuffer/drop/recovery counters; export/log/committed evidence must not include pairing material, full URL/token, raw private address, SSID/BSSID, serial, or title.

### Runtime logging

Both modules ship one small logger with an identical shape: `FlickLog` in `receiver/util` under tag **`FlickTV`**, and `FlickLog` in `sender/util` under tag **`FlickPhone`**. Line format is `[area] key=value key=value`.

The `area` vocabulary is shared and identical on both sides: `bind`, `lan`, `nsd`, `ws`, `auth`, `pair`, `cast`, `probe`, `player`, `http`.

`v`/`d` are gated on `BuildConfig.DEBUG` (both modules set `buildFeatures { buildConfig = true }`; AGP 8 defaults it to false and generates no class at all). `i`/`w`/`e` always emit. Every level appends to a 200-entry in-memory ring buffer exposed as a `StateFlow`, which is never persisted to disk or backed up. Helpers: `fp(value)` returns an 8-hex SHA-256 prefix; `endpoint(url)` returns `scheme://host:port` only, because the path is the media token.

**Redaction contract.** Safe at any level: bound host/port, peer IP, NSD name/model/state/version, `tvId`, `keyId`, device labels, enum and sealed-class simple names, wire result codes, HTTP status, counts/lengths/attempts/generations, latencies, decoder name, resolution, HDR type, Wi-Fi band/RSSI. Never, at any level: the four-digit pairing code, the pairing `key`, the HMAC `proof`, the media session token, any full `/v/{token}` URL, the raw deep-link URI, SSID/BSSID.

**Do not fingerprint the pairing code.** A SHA-256 of four digits has a 10,000-entry rainbow table, so a hash pasted into a bug report *is* the plaintext code. Log `codeLen=4` / `codePresent=true` instead. Nonces may be fingerprinted but never printed verbatim. There is deliberately no generic `log(frame)` helper — per-field call sites are what keeps secrets out.

Log message bodies are English literals in code. This is developer output, not user-facing copy, and is a recorded exception to the strings.xml rule; the diagnostics UI chrome around them is still a string resource.

Every pre-auth rejection names itself locally (`not_bound`, `port_unbound`, `host_pin`, `peer_not_private`, `preauth_limit`, `auth_timeout_or_denied`), and every lifecycle edge that touches the binding carries a named trigger (`on_start`, `on_stop`, `no_lan_address`, `addr_changed`, `dispose`). The wire bytes are unchanged: reasons are logged locally only.

**Capture recipe.**

```sh
# TV
adb connect <tv-lan-ip>:5555
adb logcat -c && adb logcat -s FlickTV:V

# Phone
adb logcat -s FlickPhone:V
```

`adb logcat` is unusable on some OEM builds, so both apps also render their ring buffer in-app: TV **Settings › Diagnostics**, phone **Diagnostics › Copy**.

## Release and rollback

Sender and receiver 0.2.1 must ship together. A v2 sender neither authenticates unversioned control nor falls back to optimistic v1 playback. Rollback installs a matched prior implementation on both devices using a newly higher `versionCode`; uninstall/reinstall clears pairing data. Host/token validation, explicit LAN binding, generic auth denial, no-redirect playback, and hardware-only decoding are not independently rolled back.

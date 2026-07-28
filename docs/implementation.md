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
Settings visual/D-pad smoke verified the then-current cyan focus treatment, Back/navigation
behavior, safe-area layout, and focus-driven Settings scrolling. That smoke exposed a Settings
clipping defect; the defect was fixed and the affected path was re-verified.

That smoke predates the Material Expressive receiver redesign. The receiver focus treatment is
now the amber ring described under [Receiver validation and playback](#receiver-validation-and-playback),
so the connected TV pass must be re-run against the new chrome before it counts as acceptance
evidence for it.

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

The receiver's Expressive redesign was gated on `:receiver:assembleDebug`,
`:receiver:testDebugUnitTest` (103 JVM tests, zero failures), `:receiver:assembleDebugAndroidTest`,
and `:sender:assembleDebug`, all green. That gate is compile-and-JVM only: it ran no lint task and
no connected instrumentation, so it is not device acceptance evidence for the new chrome.

The JVM tests cover focused pure/helper seams; they do not execute the production
`SharedPreferences` stores, Ktor client/server sockets, Activity intent/lifecycle integration,
or a Media3 hardware decoder. The connected Compose results above cover only their stated UI
scope. Earlier manual direct-play measurements remain a transport/tuning baseline, not current
cross-device acceptance evidence.

### Build types, R8, and baseline profiles

Both apps declare three authored build types plus two synthesized by the baseline-profile
plugin.

| Type | `isMinifyEnabled` | `isDebuggable` | Signing | Role |
| --- | --- | --- | --- | --- |
| `debug` | false | true | debug | development; interpreted/JIT, no profile |
| `release` | **true** (+ `isShrinkResources`) | false | debug keystore | shipped shape; packages the baseline profile |
| `benchmark` | false | false | debug keystore | macrobenchmark target: AOT-compiled but symbol-readable |
| `nonMinifiedRelease` | false | false | debug keystore | plugin-synthesized profile-capture target |
| `benchmarkRelease` | inherits `release` | false | debug keystore | plugin-synthesized comparison target |

The `release` signing config points at the **debug** keystore. That is a local-testing
identity so a release APK can be installed for performance measurement; it is not a
distribution identity, and no keystore file, password, or credential is stored in the
repository.

Everything the project previously exercised was a debug build, which ART never compiles
ahead of time. Both apps now depend on `androidx.profileinstaller` so a packaged profile is
actually handed to ART on first run, and `:baselineprofile:sender` / `:baselineprofile:receiver`
(`com.android.test` modules on `androidx.benchmark:benchmark-macro-junit4`) generate those
profiles. One test module can name exactly one `targetProjectPath`, which is why there are
two of them.

Declaring those plugins costs one online build. Gradle resolves a plugin marker and the
whole classpath behind it at configuration time even under `apply false`, so the
`androidx.baselineprofile` and `com.android.test` chains are fetched before any task is
selected — a cold cache therefore fails `:sender:assembleDebug` as surely as it fails a
profile run. The configuration-time set is
`androidx.baselineprofile:androidx.baselineprofile.gradle.plugin:1.5.0-alpha07`,
`androidx.benchmark:benchmark-baseline-profile-gradle-plugin:1.5.0-alpha07`,
`com.google.testing.platform:core-proto:0.0.8-alpha08` and
`com.android.test:com.android.test.gradle.plugin:9.3.0`; the marker's own
`com.android.tools.build:gradle:9.3.0` is already present because the application plugin
pulls it. Compiling `:baselineprofile:*` additionally needs `benchmark-macro-junit4`,
`benchmark-macro`, `benchmark-common` and `benchmark-traceprocessor` at `1.5.0-alpha07`,
`androidx.test.uiautomator:uiautomator:2.4.0`, `androidx.test:rules:1.5.0`, and
`com.squareup.wire:wire-runtime:6.4.0` — the one coordinate here that comes from Maven
Central rather than Google's Maven. All of it is current and resolvable; nothing is
yanked. After the first successful resolve the build is `--offline`-clean again.

The generators exercise the journeys that dominate first-run cost: on the phone, cold start,
the library grid fling, the film detail sheet, the Now Playing remote, and the metrics and
subtitles sheets; on the TV, cold start, the idle/pair focus graph, Settings, playback chrome
show/hide, and the subtitles and metrics panels. Every interaction is best-effort — the TV's
playback routes only exist while a phone is casting, and a generator run cannot arrange that,
so those steps are attempted and skipped silently rather than failing the run.

Generation is off the assemble path (`automaticGenerationDuringBuild = false`), so
`assembleDebug` and `assembleRelease` never require a connected device; they package whatever
profile is committed under `src/release/generated/baselineProfiles/`. Refreshing a profile is
an explicit, device-attached task:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:generateReleaseBaselineProfile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :receiver:generateReleaseBaselineProfile
```

`dexLayoutOptimization = true` is set on both consumers so R8 lays startup classes
contiguously. The sibling `baselineProfileRulesRewrite` flag is deliberately left unset:
it writes the `android.experimental.art-profile-r8-rewriting` module property, which AGP
9.3.0 no longer defines.

Turning R8 on made two name-based dependencies load-bearing. `PlaybackFailureClassifier`
compares `cause.javaClass.name` against the literal
`androidx.media3.exoplayer.source.UnrecognizedInputFormatException`, so the receiver's
`proguard-rules.pro` pins that name; renaming it would silently reclassify every container
rejection as `MALFORMED_MEDIA`. Both modules also keep all `Throwable` names, because
`FlickLog` records `e.javaClass.simpleName` and the phone's Diagnostics sheet shows those
lines to the user. The remaining rules keep Ktor, kotlinx-coroutines, the slf4j-simple
service provider, ML Kit's bundled barcode runtime, and ZXing, and silence the JVM-only
classpath references that Guava, CameraX, Coil/OkHttp and ML Kit drag in.

## Architecture

```text
phone (:sender)                                           TV (:receiver)
content:// media ── CastServerService ── HTTP ranges ──> Media3 hardware decoder
       ^                   :8080 /v/<token>                    ^
       |             (+ :8080 /s/<token> subtitle)             |
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
| `GET/HEAD /s/{token}` | The sideloaded external subtitle, under a token independent of the video's. Same bound-host `Host` check, same constant-time compare, same identical `404`. Whole-file `200` only — no `Range` support and no range parser. |
| `GET /ping` | Unauthenticated `ok` liveness response; it exposes no media bytes. |

### External subtitles

Android has no way to enumerate sidecar `.srt` files: `READ_MEDIA_VIDEO` does not cover them, MediaStore does not index them, and `MANAGE_EXTERNAL_STORAGE` is Play-policy restricted and is not requested. Subtitles therefore arrive only from a user grant — a one-shot `ACTION_OPEN_DOCUMENT` pick, or a persisted `ACTION_OPEN_DOCUMENT_TREE` folder grant that the app then matches by filename.

The subtitle `(uri, token)` pair lives **inside** the same atomically published immutable `ServedSession` as the video's, so a retarget can never be observed half-applied and a subtitle can never be observed paired with a different video. `MediaHttpServer.setSubtitle` mutates that session under the same lock as `start`/`stop` and lands as one atomic publish, so attaching, swapping or revoking a subtitle never interrupts the video stream. The subtitle token is minted by the same `SecureRandom` 128-bit URL-safe generator as the video token; a new selection mints a new one and revoking or tearing down the socket retires it, so a leaked `/s/{token}` stops resolving.

`/s/{token}` is capped at 5 MiB and refuses anything larger — and anything whose size the provider will not report — with the same byte-identical `404`, so the route cannot be turned into a bulk file-exfiltration path. The ceiling is re-checked against the bytes actually produced, not just the declared size. Content type is keyed off the file's own extension (`.srt` → `application/x-subrip`, `.vtt`/`.webvtt` → `text/vtt`, everything else `text/plain`) because DocumentsProviders routinely declare `application/octet-stream` for both and Media3 selects its parser from what the sender sends.

`loadMedia` gains three optional fields, present **only** when the user actually selected an external subtitle:

```json
{"t":"loadMedia","v":2,"castId":"<id>","url":"http://<phone-ip>:8080/v/<token>",
 "title":"<title>","durationMs":0,"startMs":0,
 "subUrl":"http://<phone-ip>:8080/s/<token>","subLabel":"<label>","subLang":"<bcp-47>"}
```

`subUrl` is always the same host and port as `url` — the sender checks the shared origin before emitting it and omits it otherwise. `subLabel` goes through the same `normalizedLabel(…, 200)` canonicalization as every other wire label. `subLang` is omitted entirely unless the tag is well-formed BCP-47; the sender never guesses one. With no subtitle selected the frame is **byte-identical** to what it has always been, which is what keeps an un-updated receiver playing ordinary media and keeps `v=2` honest. If the optional fields would push the frame past the frozen 16 KiB cap, the sender drops the fields, never the frame.

The receiver validates `subUrl` through the same `MediaUrlValidator` rules as the media URL (same host, same port, no redirect, no scheme change) and fails the cast on a bad one rather than silently fetching or dropping it. It attaches the file as a real Media3 `SubtitleConfiguration`, so it surfaces as an ordinary text track that the existing `subtitleTracksFrom` mapping and the TV subtitles panel already handle. A subtitle that fails to load degrades to no subtitles and never fails the video.

Selecting or clearing a subtitle mid-cast re-arms the capability and re-issues `loadMedia` for the **same** `castId` at the position the TV last confirmed. Control ownership classifies a repeat of the live cast as a duplicate, which is otherwise answered from the retained result; the receiver therefore compares the frame's validated subtitle triple against what the running session was prepared with and, only when it differs, re-prepares with the new (or removed) `SubtitleConfiguration` instead of replaying. An identical repeat still replays, so an ordinary retransmit never costs a re-buffer, and a reload is only ever taken from the lease that already owns the cast. Only a generation that still owns the socket may repoint the capability, so a late intent from a superseded cast cannot re-arm a revoked one.

**A reload of a cast that is already Active is not a load transaction.** `SessionController.onReloadMedia` holds the stage at `Active` and calls `PlayerController.reloadInPlace`, which re-prepares the **same** ExoPlayer instance — `setMediaItem(item, resetPosition = false)`, seek to the player's own current position, `prepare()` — and restores `playWhenReady`. The instance, the surface it is presenting to, the `MediaSession` that owns platform media buttons and the track-selection parameters all survive, so the swap costs a re-buffer and nothing else. The one selection state it does overrule is a suppressed text renderer: a sideloaded track arrives under `SELECTION_FLAG_DEFAULT`, which the panel's Off row and any earlier per-group override both outrank, so attaching a subtitle clears text overrides and re-enables the text track type — a fresh player used to discard those along with everything else, and reusing one must not turn a just-attached subtitle into a track that draws nothing.

Routing that through `beginLoad` instead is what made attaching *or removing* a subtitle kill the cast, reproduced on a Google TV Streamer. `beginLoad` arms the 18 s startup deadline and sets the stage to `Checking`, which `playerSurfaceMode` maps to `CoveredConnecting` — so the UI rebuilt the `PlayerView`'s `SurfaceView` (four surface handles inside 250 ms) at the same moment `playStartup` released the player that was presenting. The replacement's video renderer landed on a surface that never presented, `onRenderedFirstFrame` never fired at all, and since that frame is the deadline's **only** disarm path, a healthy cast died 18 s later reporting `startup_timeout`. Staying `Active` is therefore the fix rather than an optimisation: the surface mode never leaves `VisiblePlayback` and no deadline is armed. It also fixes the taxonomy — a reload installs no startup callback, so a reload that genuinely fails reaches the ordinary steady-state error path (bounded auto-recovery, then a classified `error` frame with `beforeReady=false`) and a cast that has already started can never report a startup code. The full load stays reserved for a genuinely new cast, and for the contradictory case of a reload arriving with no live player at all, where the startup transaction is the correct one.

A sideloaded subtitle belongs to the title it was picked for. A live cast owns the file it is serving, so browsing the library mid-cast cannot clear the selection; the sender instead re-checks that ownership when the next cast starts and drops a selection made for a different item, rather than attaching one film's cues to another under `SELECTION_FLAG_DEFAULT`.

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

`PairingManager` is the receiver's sole authorization/UI source. An Open code is stable for five minutes, and it is never left valid across a surface that does not render it: closing or hiding the pairing surface drops the code in the same call, and a path that opens one — including a per-phone forget that empties the store — obliges its caller to route the screen to Pair. Four global failures retain it; failure five begins a 30-second lockout, with rounds doubling to an eight-minute maximum. The global round/deadline survives process restart; per-host state is bounded to 32 records and throttles a host for 10 seconds after three failures. Pair success atomically commits key/keyId/date/label plus the last-paired key id, consumes the code, shows 1.5 seconds of Success, then returns to Standby without exposing a replacement code.

**Record encoding.** Credentials live in one `SharedPreferences` StringSet, `pairing_records_v2`. A record is `v3|keyId|key|pairedAtMs|label` — label last, so it may still contain `|`, which label normalization does not strip. The leading `v3` is a version sentinel rather than a field count, because a count cannot tell the shapes apart: the legal v2 label `12345|home` splits into four fields whose third parses as a number, and a migration trusting the count would read a phone called "home" paired in 1970. A keyId is always 22 base64url characters, so no v2 record can begin `v3|`. Legacy `keyId|key|label` records are read in place with an **unknown** date — never a guessed one — and the UI says so rather than inventing one; they are never rewritten, so an existing pairing survives the upgrade without re-pairing. A v3 record whose date is corrupt keeps its credential and loses only the date. The set has no order, so the Settings list sorts dated records newest-first, then undated ones by label, with the key id as a final tiebreak, giving a total order that is identical on every read.

### Peer identity

The authenticated peer is read from `call.request.local.remoteAddress`, never `call.request.origin.remoteHost`.

`remoteHost` resolves `InetSocketAddress.getHostName()` — a **synchronous reverse-DNS (PTR) query** on the LAN. Most consumer and ISP routers run dnsmasq and answer PTR for DHCP clients, so on those networks it returns a hostname such as `some-phone.lan`. That value failed the private-IPv4 gate and closed every legitimate phone's socket before one protocol frame was read, with no log and no `denied` frame; it was also published as `peerIp` in `paired`/`resumeChallenge`/`resumed` and is field 8 of the resume HMAC transcript, which the sender validates as an IPv4 literal. `remoteAddress` uses `getHostString()` and never resolves. `local` is used rather than `origin` because `origin` is overridable by a `Forwarded`/`XForwardedHeaders` plugin, while `local` is always the CIO connection point derived from the accepted socket. When the socket address is null Ktor returns the literal `"unknown"`, which correctly fails the gate — fail-closed is intended.

Using a non-resolving, non-header-derived identity strengthens the posture: `remoteHost` was a resolver-controlled string feeding per-host pairing throttling and the HMAC transcript.

The receiver caps unauthenticated WebSockets at four and applies a six-second auth deadline. Malformed pre-auth input has a three-frame budget. Both sides enforce the 16 KiB decoded frame cap, unfragmented UTF-8 text/object input, duplicate/trailing-data rejection, exact fields/types/ranges, and no unknown v2 message types. Device, TV, and title labels are normalized to canonical single-line values before sending; the receiver rejects noncanonical control/format/whitespace variants rather than silently changing an authorization transcript or command.

A `denied` frame carries a coarse diagnostic reason: `{"t":"denied","v":2,"reason":"<enum>"}` over exactly `code`, `expired`, `surface`, `locked`, `busy`, `storage`, `proof`, `unknown`. Only `code` and `expired` are code-derived, so the frame stays non-enumerating — it is no oracle for guessing a key id, a device, or whether a TV has ever been paired. The sender accepts both the legacy two-key form (an un-updated receiver) and the three-key form, and maps each reason to distinct copy.

Closing/hiding the pairing surface invalidates its open generation immediately; a late pair attempt is denied with `reason=surface`. TV Settings implements confirmed **Forget all phones**: it stops/revokes the active controller, clears credentials only after the durable write succeeds, resets throttle/lockout state, and reopens visible first-run pairing.

TV Settings also lists each paired phone by name and pairing date and offers a per-phone **Forget**, likewise two-press confirmed — armed per key id, and disarmed the moment the D-pad leaves that row. `PairingManager.forget(keyId)` removes exactly one record on the same durable-write-first discipline and returns false when nothing was removed, so the UI can never report a forget that did not happen. Removing one phone deliberately does **not** open a pairing code: a code is an authorization surface, and removing one of several phones is not a request to admit a new one. Reaching zero records is the exception and takes the Forget-all path exactly, lockout reset included, so a TV cannot be left with no phones and no way to re-pair while a lockout still runs — and because that path opens a code, `ReceiverApp` closes Settings when the last phone goes, exactly as Forget all does. It has to: the surface router gives Settings priority over Pair, so leaving it open would leave a code that is valid, rotating and accepting attempts while nothing on screen renders it. With no phones there is no list left to show either, and the router then falls to `pairedCount == 0 → Pair`, which renders the code. `last_device` — what Idle renders as "Paired with …" — is recomputed whenever the forgotten phone was the one it named, promoting the newest remaining phone or clearing the name outright; a store written before v3 has no last-paired key id and falls back to matching the bare label, which clears a name when two phones share one rather than showing a wrong one. The UI is handed `PairedPhone` (keyId, label, date) and **never** the pairing key. `findKey` reads the same set, so a forgotten phone's next `resumeInit` is denied `unknown_key` — but a resume already past that point is not, which is what the challenge sweep below exists for.

Forgetting the focused row also has to say where the D-pad goes, because a `LazyColumn` item that is disposed takes focus with it. The landing is the row below, the row above when it was the last, and **Device name** when there was no other phone at all — one paired phone is the common state, not an edge case, and a landing of "nowhere" leaves the remote steering a screen it cannot move on, recoverable only with Back. It is expressed as a state (`SettingsFocusReturn.Phone` / `DeviceName`) rather than as a nullable key id precisely so that fallback is reachable instead of collapsing into "nothing owed". The request is driven off the ARRIVAL of the new list rather than off the press, and repeated across frames (`landTvFocus`), because the replacement row is not attached and placed in the frame that removed the old one.

**Forgetting the connected phone.** `ControlServer.forget(keyId)` also ends that phone's live session, but only that phone's: `Connection` carries the key id it authenticated with, and the revoke is gated on it, so forgetting phone B never drops phone A off a running film. The identity test and the lease clear happen under one hold of `serverLock` and compare the connection **object**, not the key id — `active` is `@Volatile` and an idle replacement can install a different socket between any test and any separate mutation, so a single early read followed by an unconditional clear would still drop that replacement. A live cast is stopped through the player before the socket closes, because the revoke invalidates the lease and a film left decoding past that point has no owner left to stop it and no socket to report `stopped` on; a phone that is merely connected has no cast and needs only the revoke.

The order is deliberately the **inverse** of Forget-all: the durable write goes first, then the socket work. Forget-all is all-or-nothing and closes the screen, so revoking first costs nothing; per-phone forget returns a value that drives a row which stays on screen, and revoking first would let a rejected write report "nothing happened" to a user whose casting phone had in fact just been cut off. Writing first makes `false` mean nothing happened. The cost is the microseconds between the write and the revoke, in which an already authenticated socket stays authenticated — it gains no capability it did not already hold, and a cast started in that window is still caught because the lease is read after the write.

**Removing the record is not by itself enough to stop re-authentication.** `authenticate` reads the credential once, at `resumeInit`, and validates `resumeProof` against that cached copy; the store is never read again, and nothing re-checks a live connection's key id afterwards. A phone whose `resumeInit` landed before the write therefore completes its proof afterwards against a record the TV no longer has, and installs itself as the controller — for the whole `resumeInit → resumeProof` round trip, up to the six-second auth deadline, and deterministically for a hostile paired phone, which may keep spare half-finished handshakes open within the four-connection pre-auth cap and complete one after each Forget. So each forget marks the handshakes instead: a `resumeInit` registers a `@Volatile` ticket for the key id it claims **before** `findKey` is called, `ControlServer.forget` sweeps that registry **after** the durable write and **before** it reads the lease, and the lease install reads the ticket under `serverLock`. The four orderings are exhaustive — a handshake already registered is marked; one registered after the sweep reads a store the write has already changed; one that installs in between is found by the lease read; and the `pair` path carries no ticket at all, so a record `attemptPair` has just committed still installs. Forget-all runs the same sweep after its clear, and re-reads the lease behind it, because a durable commit is not instantaneous.

The sweep touches no lock, and that is a requirement rather than an optimization: `ControlServer.forget` acquires the `PairingManager` monitor and then `serverLock`, so re-reading `PairingManager` from inside the install block — the obvious fix — would acquire `serverLock` → manager monitor and give the control server an AB-BA deadlock. A refused install answers `denied reason=proof`, which is what the phone's next connection would get from the missing record anyway, so the frame enumerates nothing new; `busy` is deliberately not reused, because `busy` is an invitation to retry.

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

Refresh-rate matching is **derived state, never a latch**. `preferredWindowRefreshRate(presentingVideo, contentFrameRate)` is the single decision: while the player surface is actually presenting a film and the decoder reports a real cadence, that cadence is applied to both `WindowManager.LayoutParams.preferredRefreshRate` and `Surface.setFrameRate` (`FIXED_SOURCE`, `CHANGE_FRAME_RATE_ALWAYS`), which is what removes 3:2 judder on 23.976/24/25 fps material. In every other state — pairing, idle, settings, an error, and any frame rate that is 0/NaN/infinite — both hints are released by re-applying the platform's `0` sentinel, and the release also runs when the receiver composable leaves composition. The one state that neither pins nor releases at once is the **handshake**: a cast arriving over a running film — the next episode — passes through Checking/Preparing while the old film is still decoding under the connecting cover, so `refreshRateHintDelayMs` defers a release by a 2-second settle there. A subtitle change no longer reaches that settle at all: an in-place reload never leaves `Active`, so the hint is simply never released, which is the correct answer for a re-prepare of the very same film. Probe plus prepare on a LAN file lands far inside it, and the ordinary re-cast therefore costs zero mode switches instead of a release and an immediate re-pin at the same cadence — two visible HDMI resyncs for a hint that never changed. It is a settle rather than a hold because the adoption deadline is 18 seconds and a stalled handshake may not keep the panel pinned to a finished film. The previous one-way apply was guarded by `if (fps > 0)`, so when playback ended and the reported frame rate fell to 0 the branch was skipped and the film's hint survived it: the TV was measured sitting on the **pairing** screen with `preferredRefreshRate=24.000002` and a 41.67 ms vsync period, rendering every spring, fade and focus lift in the whole Compose UI in 24 discrete steps a second, for the rest of the process. The rate is deliberately **not** released for interactive chrome over a running film: a display-mode switch on the verified hardware costs a visible resync, and two of them per chrome reveal is worse than chrome animating at the film's own cadence.

Playback chrome is a glass transport panel anchored inside the 5% TV-safe inset, not a full-width bottom bar: the media title is a single ellipsized 34sp line, timecode is 20sp tabular mono, transport targets are 52dp/66dp with 26dp/35dp seek and play glyphs, and the movie frame stays visible behind lighter pause/seek/buffering dimming. The top and bottom scrims are gradients that fade in and out with the chrome rather than permanently overlaying the film. Focus is a detached amber ring drawn outside the element bounds, so focusing a control never reflows its row; the play key takes the white ring because amber on amber would vanish, and the scrub bar draws its ring around the knob rather than around a 700dp span. **The control row is traversed the way it is drawn**: left/right step through `subtitles → back-10 → play → forward-10 → volume → stream metrics`, up from any of them reaches the scrub bar, down from the scrub bar returns to the row, and up from the scrub bar reaches `END SESSION`. Revealing the chrome still lands focus on play; after a side panel closes it lands on the card that opened it. Because both handoffs compose the arriving surface in the same frame that removes the departing one, entry focus is requested across several frames (`landTvFocus`) rather than once — a `FocusRequester` whose node has not been placed yet throws, and there would be nothing else on screen to steer with.

Only **FINISHED** still draws a centred state chip. The paused chip was cut: a viewer who has just pressed pause is being told what they did themselves, over the frame they paused to look at. Paused instead auto-hides the chrome on the same 4-second countdown as playing, and leaves the amber play key resting at the foot of the frame. That key is a state signal, not a control — not focusable, no click action, and DPAD center/up/down restore the full chrome. Left and right stay the blind seek they are under any hidden chrome: the paused-rest state does not get its own key model, and a paused film is exactly when stepping through it without a panel in the way is wanted. Its **fill** carries the 50% and its ink stays solid, which is a measurement rather than a preference: behind one uniform 50% layer the amber and its `OnSpark` glyph composite to 2.72:1 over a dimmed white frame, under the 3:1 graphical floor, while the split reads 7.29:1 there (6.44:1 against the frame itself) and the fill still holds 3.41:1 against a black frame.

Two side panels **replace** the transport bar rather than stacking on it, and are the only chrome that suspends the 4-second auto-hide: **Subtitles** lists Media3's live text tracks with the format its sample MIME actually names, plus the Small/Medium/Large caption size; **Stream metrics** shows a 40-bar throughput histogram over the rolling peak and a nine-cell stat grid. Both read only measured fields — an unavailable value renders as an em-dash or is omitted, never as a plausible number, so file size and any synthesized countdown are absent by construction. The panel is a sibling of the transport in the layout, not a child of it, which is what lets it outlive the bar; opening one hides the bar, closing one brings it back with focus on the card that summoned it, and Back dismisses the panel before it can hide the chrome or end the cast. The countdown stays suspended while a panel is open precisely because the bar is gone — firing it would take away the surface the viewer expects to return to. The opt-in `Playback metrics overlay` dev HUD from Settings paints only while the chrome is hidden, because the redesigned chrome owns the corners it used to occupy.

Resolution is classified in exactly one place, `videoResolutionClass(width, height)`, shared by the transport's spec chip and the start-of-cast quality card. It reads the frame's **long edge**, not its height: a 2.39:1 feature is encoded 1920×804 with the letterbox baked out, so a height-only ladder found 804, cleared the 720 rung and labelled every scope film on the drive "720p SDR" while the phone, reading the same file, said 1080p. The short edge is kept as a second test so portrait and rotated capture — 720×1280 — is still read by the dimension that carries its scale.

Media3 **text** subtitles retain their viewport-derived/user-scaled baseline minus exactly 2sp, then multiply it by the panel's caption-size choice (0.85 / 1.0 / 1.25, floored at 1sp); the platform caption-manager scale still governs the baseline and the existing caption/layout/configuration listeners are unchanged. They use white text with a drop shadow, and ignore embedded cue styling/font sizes that would override this treatment. Because embedded styles are disabled, the cue typeface is set explicitly to the bundled Geist SemiBold `res/font` face rather than left null: a null typeface renders cues in the platform default (Roboto Regular 400), below the receiver's ten-foot weight floor and in a different family from the rest of the UI. The per-glyph background is fully transparent; a single 55%-opaque black Media3 cue window supplies the visibly translucent plate without overlapping glyph/run backgrounds becoming effectively opaque. Disabling embedded styles clears source cue-window overrides, so Media3 falls back to this `CaptionStyleCompat.windowColor`. Caption font-scale changes are applied in place without recreating the Activity or tearing down the cast. Bitmap subtitle cues (for example PGS/VobSub) have styling baked into their pixels, so Media3 renders them unchanged as the non-destructive fallback; the metrics overlay reports selected subtitle MIME plus `text`/`bitmap`/`mixed` cue shape without retaining cue text or bitmap payloads.

Android TV remote input is routed first at `Activity.dispatchKeyEvent`, independent of Compose focus. During Active playback with no side panel open, DPAD left/right are a **playback gesture in exactly two states** — the chrome is hidden, or the scrub bar itself holds focus — and are ordinary Compose focus navigation everywhere else. That is the corrected model: while horizontal keys were captured unconditionally, a transport row laid out horizontally could only be walked with up and down, which is what the product owner rejected on the device. A tap seeks exactly ten seconds; a held key emits every fourth repeat and progresses through capped 10/20/30-second pulses (1×/2×/3×). The Activity consumes the full gesture through key-up, and the captured gesture outranks both chrome visibility and scrub focus, so a chrome auto-hide or a focus move landing mid-hold cannot split one physical press into two meanings. It outranks the other DPAD keys too: while a gesture is held, a second direction — a thumb rocking the ring — is swallowed rather than allowed to take the capture, because the held key's key-up would then match nothing and the gesture would never end. The burst state is derived from the capture rather than credited to whichever key released one, so no path can leave it frozen over the film; and because only the holder's own key-up releases it, a capture that outlives its release (a window-focus loss swallows the key-up) is cleared by pressing that same direction again rather than by a different one. Dedicated media keys are excluded from all of this and keep falling through. A burst on the seeked half of the frame shows the gesture's accumulated signed delta and acceleration, holds for 700 ms after release, then fades and clears; it is shown only for a **blind** seek, because with the chrome up the scrub bar is already drawing the target, the ghost and both timecodes. A blind seek deliberately does **not** summon the chrome — quick-seek without bringing up UI is the TV-player convention, and revealing it would make the second tap of a double-tap a focus move rather than another ten seconds. With chrome hidden, DPAD center/Enter toggles play-pause once per press and up/down reveal the chrome; with chrome visible those non-horizontal keys fall through to the transport focus graph. An open side panel hands the whole DPAD to Compose: the receiver reports `playbackActive = false` to the policy, which then consumes nothing. There is no longer a volume-engagement latch at the Activity boundary — volume reads left/right through its own key handler while it holds focus, and horizontal keys can only bypass Compose when the scrub bar holds focus, so the two can no longer claim the same key. Dedicated play, pause, play-pause, rewind, and fast-forward buttons are never consumed by the Activity policy: Android delivers them to a Media3 `MediaSession`, whose ten-second seek increments match the on-screen transport. Media Stop and unsupported Next/Previous are consumed/rejected by the session callback because raw `Player.stop()` or playlist navigation would bypass cast ownership and terminal-state bookkeeping. The platform session switches to a replacement ExoPlayer before the prior instance is released, is released before decoder teardown or terminal cast stop, and is rebound before any later playback. Outside Active playback custom DPAD keys fall through to normal Compose/system navigation.

## Lifecycle and structured failure

Control connection, cast ID, and receiver cast generation guard every queued mutation. A new cast supersedes the old generation; stale callbacks/commands cannot mutate the new player. A WebSocket close calls the lease-guarded `onControlLost(generation)`, so a displaced/stale socket cannot tear down its successor. Activity background, LAN loss/change, and endpoint rebind use `forceLocalTeardown()` before server teardown because those local-authority events must clear the current cast regardless of socket lease. While preparing/active, a resumed phone receives proof-bearing `resumed` then `busy(active_cast)`; a newly paired phone receives `paired` then `busy`, preserving the key through the internal `PairedBusy` result. Neither can displace the owner.

`stop(castId)` is the canonical terminal command for the current Checking/Preparing or Active cast. The receiver clears player/session ownership, sends cast-correlated `stopped`, and replays that retained result for a duplicate stop. The sender reducer treats matching `stopped` as terminal, runs cast-correlated foreground-service cleanup, and returns to Library; local cleanup never waits indefinitely for the acknowledgement. `cancelLoad` remains the sender's best-effort pre-ready cancellation path; local TV Back uses the same stopped terminal path rather than silently clearing an active cast.

TV background, LAN loss/change, control stop/loss, cancellation, and terminal failure invalidate the session before stopping/clearing media items, URL, title, startup callback, retry state, and decoder ownership. While backgrounded the TV stays bound and advertised as `state=sleeping`, and `ReceiverBindingGate` refuses `loadMedia`; the socket accepts no new cast. Foreground return requires a fresh authenticated cast; v2 has no background playback resume.

### Control-socket transport failures

Ktor runs the WebSocket ping/pong watchdog as a coroutine of the session, not of the code that opened it. On timeout it does not merely close the socket: it closes the session's `incoming` channel **with** `IOException("Ping timeout")` as the channel's cause, so the phone's authenticated reader loop receives a throw rather than an end of iteration. That reader is a plain `applicationScope.launch`, and `SupervisorJob()` stops a child's failure from reaching its siblings but not from reaching the thread's default uncaught handler — so with no `CoroutineExceptionHandler` anywhere in that context the process died and the user was dropped to the launcher mid-film. `ControlClient.open`'s try/catch could never catch it: the watchdog is a sibling of the `action(socket)` call and had long since returned.

The reader now runs its loop inside `absorbingTransportFailure`, which classifies anything that escapes through the pure `ControlTransportFailure` and takes its logging and teardown as parameters so the disposition — not just the predicate — is exercised by a JVM test against a channel closed exactly the way the watchdog closes it. The reader also carries a `CoroutineExceptionHandler` **on its own `launch`** as the backstop for anything that escapes the shell itself — a handler is consulted only on the context of the coroutine whose failure reaches a root, and under a supervisor that root is the reader itself, so a handler installed on any scope that did not launch it would never run. `FlickApplication.applicationScope` deliberately stays handler-free: it also carries the library load, the subtitle job, frame collection and every pairing job, and a blanket handler there would hide genuine defects in all of them.

Classification is an allow-list of what the control socket's coroutines can actually observe — `IOException` and its subtypes, the closed-channel exceptions, `FrameTooBigException`, `ProtocolViolationException` — matched on the throwable itself and never through its cause chain or through a supertype: `ClosedSendChannelException` *is* an `IllegalStateException` and `ClosedReceiveChannelException` *is* a `NoSuchElementException`, so a supertype rule would swallow ordinary defects. Cancellation is re-raised so `reader?.cancel()` keeps meaning something, and everything else — including every `Error` — is re-raised unchanged and still crashes. An absorbed failure lands on `ConnectionStatus.DISCONNECTED` through the same identity-guarded teardown a close uses (`session === socket`, so a late failure from a prior socket cannot tear down its successor), which the reducer already reports as `control_disconnected`. The dial-time taxonomy is untouched: a transport failure after authentication is not an `Unreachable`/`TimedOut`/`RejectedByTv`/`ProtocolError` dial result.

The ping interval is 15 s, and it buys two different numbers that must not be confused. Ktor derives the pong deadline as exactly twice the interval and offers no separate setting, and its pinger spends a whole interval draining stale pongs *before* it puts the next ping on the wire: a stalled link is therefore tolerated for **2× = 30 s** of missing pong once a ping is out, while a TV that dies is noticed only **between 2× and 3×, i.e. 30–45 s** later — the phone reads CONNECTED over a dead cast for that whole window, so a hardware check of a pulled-power TV should expect the Failure route at up to 45 s, not 30. The tolerance floor is what the value is chosen for: the previous 5 s tolerated 10 seconds, which an ordinary home-Wi-Fi stall under a 4K VBR peak exceeds, and past that point the receiver's lease is released and the cast is gone; the 45-second detection ceiling is the price. 15 s also keeps a ping on the wire about every 15 seconds, inside Ktor CIO's 45-second server connection-idle timeout, so an authenticated but idle session is never reaped for silence. The receiver is unaffected in both directions: the server WebSockets plugin's `pingPeriod` defaults to zero and `ControlServer` never sets it, so the TV installs no pinger and cannot raise this failure at all, and the pong is answered by Ktor's own ponger rather than by `ControlServer`, so single-controller ownership never sees it. The TV is structurally protected as well — Ktor's `WebSocketUpgrade` runs the handler inside a `catch (Throwable)` that converts any escape into a scope cancellation, so a throw out of the receiver's read loop still runs its lease-releasing `finally` and can never reach an uncaught handler.

Wire failures are stable lowercase codes with required `retryable` and optional HTTP status only when observed. Raw exception text is never serialized. Classification is evidence-conservative: actual parser/decoder/network/status evidence is used, while ambiguous codec/HDR failures stay broad (`unsupported_video_format`, `decoder_init`, or `unknown`). Phone-local pre-control/source/bind failures remain phone-only; adopted receiver failure reaches both devices only while control is still usable.

## Privacy and diagnostics

Pairing preferences are excluded from legacy backup, cloud backup, and device transfer. The notification omits title, URL, token, and private address. Diagnostics may show redacted probe/startup time, HTTP status, Wi-Fi band/link speed/RSSI, decoder, resolution/HDR class, and buffer/rebuffer/drop/recovery counters; export/log/committed evidence must not include pairing material, full URL/token, raw private address, SSID/BSSID, serial, or title.

### Runtime logging

Both modules ship one small logger with an identical shape: `FlickLog` in `receiver/util` under tag **`FlickTV`**, and `FlickLog` in `sender/util` under tag **`FlickPhone`**. Line format is `[area] key=value key=value`.

The shared core `area` vocabulary is `bind`, `lan`, `nsd`, `ws`, `auth`, `pair`, `cast`, `probe`, `player`, and `http`; the receiver additionally uses `subtitle` for selection-only MIME diagnostics.

`v`/`d` are gated on `BuildConfig.DEBUG` (both modules set `buildFeatures { buildConfig = true }`; AGP 8 defaults it to false and generates no class at all). `i`/`w`/`e` always emit. Every level appends to a 200-entry in-memory ring buffer exposed as a `StateFlow`, which is never persisted to disk or backed up. Helpers: `fp(value)` returns an 8-hex SHA-256 prefix; `endpoint(url)` returns `scheme://host:port` only, because the path is the media token.

**Redaction contract.** Safe at any level: bound host/port, peer IP, NSD name/model/state/version, `tvId`, `keyId`, device labels, enum and sealed-class simple names, wire result codes, HTTP status, counts/lengths/attempts/generations, latencies, decoder name, resolution, HDR type, selected subtitle MIME/cue shape, Wi-Fi band/RSSI. Never, at any level: the four-digit pairing code, the pairing `key`, the HMAC `proof`, the media session token, any full `/v/{token}` URL, the raw deep-link URI, subtitle cue text/bitmap payload, SSID/BSSID.

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

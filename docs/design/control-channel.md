# Flick control channel — architecture contract

Binding wire contract for the **new** phone↔TV subsystems that the redesign introduces:
**discovery**, **pairing**, and the **playback control channel** that powers the synchronized
scrub (design Part 4). `:sender` implements the **client** half, `:receiver` the **server** half.
Both sides MUST match the frames/fields defined here exactly. Nothing here changes the existing
hardened **media** path (`GET/HEAD /v/{token}` byte-range direct-play on the phone) — that stays
byte-for-byte as shipped.

---

## 1. Topology & the decision

Today: **phone = media file server** (Ktor, `:8080`, per-session token), **TV = ExoPlayer client**
pulling bytes. The player (and thus the authoritative playback position) lives on the TV.

The redesign's UX (design S1/T1) has the **phone discover the TV**, pair, then **drive** it — and
the hero needs the TV's real position streamed back to the phone. That requires the TV to accept
inbound control. Decision:

> **Add a small, authenticated, LAN-bound control server on the TV** (Ktor CIO + WebSockets) for
> **playback control only** — discovered via **NSD/mDNS**. **Keep media direct-play on the phone**
> exactly as hardened. The phone is the control **client**; the TV is the control **server**.

Why this and not "everything on the phone": for the phone to drive the TV, the TV must listen. A
control listener that carries **no media and no file access — only playback verbs** is a small,
well-bounded surface, and it is the natural home for both discovery and the position feedback the
hero needs. This is within the roadmap's anticipated "small phone→TV control endpoint / WebSocket."

### Security posture of the new TV surface (MUST hold)
- Bind the **TV's LAN IP**, never `0.0.0.0`; reject if bound host ≠ request `Host` (anti-rebinding).
- **Pairing-gated:** every control connection presents a pairing key (below); constant-time compare;
  identical `404`/close on any miss. An unpaired LAN device gets nothing.
- The control server can **only** control local playback. It exposes **no** filesystem, no arbitrary
  URL fetch beyond the media host the user paired to, no shell. `loadMedia` accepts only an
  `http://<phoneIp>:<port>/v/<token>` URL whose host matches the paired phone.
- No secrets logged. LAN-only; not reachable off the subnet.
- Document this in `SECURITY.md` (there are now **two** servers; the TV's is control-only).

---

## 2. Discovery (NSD)

- **TV advertises** via `android.net.nsd.NsdManager`:
  - Service type: `_flick._tcp.`
  - Service name: the TV's friendly name (e.g. `Living Room TV`), user-visible.
  - Port: the control server's chosen port (ephemeral; publish the real bound port).
  - TXT attributes: `model` (e.g. `Google TV Streamer`), `v` (protocol version, `"1"`),
    `state` (`ready|sleeping`). Keep values short.
- **Phone browses** for `_flick._tcp.`, resolves entries, and renders the S1 device list
  ("ON HOMENET — N FOUND") with live status dots. Resolve gives host+port for pairing/control.
- Manual IP entry survives as an **escape-hatch link only** (S1 "enter address manually").
- Discovery is best-effort: if NSD yields nothing (some routers block mDNS), fall back to the
  manual-address path — never dead-end.

---

## 3. Pairing (trust establishment)

Goal: prove the phone's user can **see the TV screen**, so a silent LAN attacker can't pair.

- On first control connection from an unknown phone, the TV displays a **4-digit code** (design
  T1) and an equivalent **QR** encoding: `flick://pair?host=<tvIp>&port=<ctrlPort>&code=<4digit>&v=1`.
  The TV generates the QR bitmap with **ZXing core** (`com.google.zxing:core`) rendered to a Compose
  `Canvas` (no camera needed to *display*).
- The phone supplies the code by one of (design S1): NSD-discovered TV + user types the code shown
  on the TV; **or** manual entry; **or** (stretch, may defer) camera QR scan.
- Handshake over the control WebSocket:
  1. Phone → `{"t":"hello","v":1,"code":"7429","device":"Pixel 9"}`
  2. TV validates `code` (constant-time). On success it mints a **persistent pairing key**
     (128-bit `SecureRandom`, base64url) bound to that phone, returns
     `{"t":"paired","key":"<pairingKey>","tv":"Living Room TV"}`. On failure: `{"t":"denied"}`
     then close. The code is single-use and rotates.
  3. Both sides persist the pairing (phone: TV name+host+key; TV: key+phone label) so subsequent
     connects are **one-tap** — phone reconnects with `{"t":"resume","key":"<pairingKey>"}` and skips
     the code. Store keys in each app's private storage (not logged, not in the public repo).

---

## 4. Control WebSocket

- Endpoint: `ws://<tvIp>:<ctrlPort>/control` (TV = server). Phone connects after pairing/resume.
- Text frames, **compact JSON** (`org.json` on both sides — no serialization plugin/dep).
- Field `t` = frame type. Unknown types are ignored (forward-compatible). `v=1`.
- One control connection at a time per TV; a new one supersedes (the TV closes the old).

### Phone → TV (commands)
| `t` | fields | meaning |
|---|---|---|
| `hello` / `resume` | see §3 | open/authenticate |
| `loadMedia` | `url` (`http://<phoneIp>:<port>/v/<token>`), `title`, `durationMs`, `startMs` | begin/replace playback. TV validates host == paired phone. |
| `play` | — | resume |
| `pause` | — | pause |
| `seek` | `posMs` | **target** (optimistic) seek; may arrive at ~drag rate (throttle ≤ ~20/s) |
| `skip` | `deltaMs` (±10000) | relative |
| `setVolume` | `level` (0..1) | — |
| `stop` | — | end session (TV releases player per existing terminal-stop semantics) |
| `ping` | `id` | liveness; TV replies `pong` |

### TV → phone (state, the ghost feed)
| `t` | fields | meaning |
|---|---|---|
| `paired` / `denied` | see §3 | handshake result |
| `state` | `posMs` (**confirmed** playhead), `durationMs`, `playing` (bool), `bufferedMs`, `phase` (`idle|buffering|playing|paused|ended|error`), `volume`, `seq` | pushed at **~10 Hz** while active, and immediately on any TV-side change |
| `error` | `code`, `message` | preflight/decoder/network failure (map to design S12/T9) |
| `pong` | `id` | liveness reply |

### Optimistic / ghost reconciliation (the hero, Part 4)
- Phone keeps **two** values: `targetMs` (optimistic — moves instantly with the thumb / on a
  local command) and `confirmedMs` (= last `state.posMs` from the TV).
- **Solid** playhead renders `targetMs`; **ghost ○** renders `confirmedMs`. When the difference is
  small they superimpose (healthy). While dragging, the phone sends throttled `seek{posMs=targetMs}`;
  the TV applies and streams back `state.posMs`, which chases the target.
- On a hiccup (no fresh `state` within ~250ms of a `seek`), show the cyan **"SYNCING…"** shimmer;
  the ghost eases toward target (never jumps).
- On **release**, phone sends a final `seek`, then reconciles ghost→target with `syncSpring`
  (design Motion) and fires the shared Spark pulse when `|confirmedMs − targetMs|` collapses.
- **Cross-surface:** a TV-side D-pad seek/pause simply arrives as a `state` frame with a new
  `posMs`/`playing`; the phone animates its transport to match (Beat 6). Commands are **idempotent
  by absolute value** where possible (`seek posMs`, `setVolume level`) to survive reordering; `seq`
  lets the phone drop stale `state` frames.

---

## 5. Dependencies this introduces (each module edits its OWN build.gradle.kts only)

- **`:sender`** (control client + gallery): `io.ktor:ktor-client-core:3.1.3`,
  `io.ktor:ktor-client-cio:3.1.3`, `io.ktor:ktor-client-websockets:3.1.3`; image loading
  `io.coil-kt:coil-compose:2.7.0` + `io.coil-kt:coil-video:2.7.0`; downloadable fonts
  `androidx.compose.ui:ui-text-google-fonts`. NSD + frame preview
  (`android.media.MediaMetadataRetriever`) need no dep. Camera-QR scan (CameraX + ML Kit) is
  **stretch — may be deferred**; discovery + code entry + manual IP are the required pairing paths.
- **`:receiver`** (control server + QR gen): `io.ktor:ktor-server-core:3.1.3`,
  `io.ktor:ktor-server-cio:3.1.3`, `io.ktor:ktor-server-websockets:3.1.3`,
  `org.slf4j:slf4j-simple:2.0.16` (Ktor logging), `com.google.zxing:core:3.5.3` (QR bitmap),
  `androidx.compose.ui:ui-text-google-fonts`. NSD needs no dep.
- Add coordinates directly in each module's `build.gradle.kts` (as the existing files already do).
  **Do not** touch `settings.gradle.kts` or `gradle/libs.versions.toml` (shared — avoid clashes).
  Keep `compileSdk/minSdk/targetSdk` = 36/26/36, Kotlin 2.1.20, Compose BOM 2025.05.01, Media3 1.10.1.

---

## 6. Manifest / permissions

- `:sender` already holds `INTERNET`; add nothing for outbound WS. NSD browse needs no runtime
  permission but declare nothing extra beyond what NSD requires. Coil reads via MediaStore/SAF Uris
  the user already granted (Photo Picker) — no broad storage permission.
- `:receiver` control server needs `INTERNET`; NSD registration needs no runtime permission.
  Keep the receiver's cleartext allowance (media is plain HTTP until TLS). The TV control WS is `ws://`
  on the LAN — same posture, pairing-gated.
- Android 16/17 **Local Network Permission** (`targetSdk 37`) is a known Phase-1 follow-up; at
  `targetSdk 36` no new local-network gate applies.

---

## 7. What must NOT regress (guardrails for implementers)

- Existing media server: `GET/HEAD /v/{token}` byte-range 206/416/200, LAN-IP bind, `Host` pin,
  `Semaphore(4)` cap, idle-timeout, constant-time token, identical 404. **Do not alter its contract.**
- Receiver terminal-stop semantics (`PlayerController.stop()` clears state so a
  Stop→background→foreground can't silently re-prepare) — preserve; the WS `stop` maps onto it.
- No decoder/software-fallback change; hardware decoders only; no cross-protocol redirects.
- Memory: releasing the session must release the player, buffer pool, FDs, threads, **and** close
  the control WS + NSD registrations. No leaked coroutines/sockets across start/stop cycles.

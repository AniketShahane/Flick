# Flick — session handoff

Working doc for picking up after a context compaction. Kept public-repo-safe: no
device serials, real IPs, or personal paths (get devices from `adb devices`).

---

## ⚠️ Do this first (pending)

Both devices were **offline** when the redesign build finished, so nothing is installed yet.
When they're reachable:

```sh
adb devices                    # confirm the phone (USB) and TV (network :5555) show up
adb -s <phone-serial> install -r sender/build/outputs/apk/debug/sender-debug.apk
adb -s <tv-ip:5555>   install -r receiver/build/outputs/apk/debug/receiver-debug.apk
```

Install **both** — the redesign changes both apps and adds a phone↔TV control channel; an old
build on one side won't talk to the new build on the other. APKs are already built and green.

Then, to get the final adversarial pass, run **`/code-review ultra`** on the branch (see below) —
it's user-triggered and billed, so it has to be launched from the CLI, not by the agent.

---

## Where the project is

Flick casts a user's **own** local 4K/HDR/Dolby-Vision video from an Android phone to an Android TV
by **direct-play** (phone serves original file bytes; TV hardware-decodes). "Make it work / make it
robust / make it secure / make it beautiful" are all done. The **front-end redesign is implemented**
(both modules build green, adversarially reviewed, and hardened) on a branch, pending install +
the final review pass.

### Shipped this cycle — the redesign (branch, not yet pushed)
- **Full Flick design system** on both apps (`ui/theme/` in each module): the Spark/Link color
  split, dark+light (phone) / fixed cinematic dark (TV), Space Grotesk + Roboto Mono via
  downloadable Google Fonts (graceful fallback), the six motion easings (`flickSettle`,
  `playheadGlide`, `syncSpring`, `crossDissolve`, `chromeFade`, `focusPop`), the icon set, and the
  component kit. Source of truth: `docs/design/design-tokens.md`.
- **Phone (`:sender`)** — a real MediaStore-backed **video gallery** (Coil video-frame stills, DV/HDR
  badges), detail/"cast this", connecting, and the **remote**: the optimistic **solid** / ghost **○**
  synchronized scrub with on-device frame-preview decode and haptics; connect/pair (NSD discovery +
  code + manual), quality sheet, advisories, error states. Screens S1–S12.
- **TV (`:receiver`)** — Compose-for-TV cinematic playback with an on-screen scrub bar (target ● +
  ghost ○), transport, D-pad focus, first-run pair (QR + code), idle, buffering, quality, errors,
  and the diagnostics overlay **demoted to an opt-in settings toggle**. Screens T1–T10.
- **The hero, end to end** — a phone↔TV control channel: the TV runs a pairing-gated WebSocket
  control server (discovered via NSD); the phone is the client. Contract:
  `docs/design/control-channel.md`.

### Architecture decision (flagged)
The synchronized scrub required the TV to accept inbound control, so the redesign adds a **small,
pairing-authenticated, LAN-bound control server on the TV** (playback verbs only — no file access).
**Media direct-play stays exactly as hardened on the phone** (`/v/{token}`, byte-range, untouched).
There are now two servers; both are LAN-only and gated. See `SECURITY.md`.

### How it was built (per CLAUDE.md orchestration)
Design imported from Claude Design → 3 canonical contract docs → **Workflow A** (2 Opus-xhigh
implementers partitioned by module + a single Gradle runner) → **Workflow B** (Fable 6-lens
adversarial review with per-finding refutation + a Codex pass → Opus fixes). 75 verified findings;
sender fully fixed (incl. a media-server TOCTOU hardening, cleartext reverted to `false`, rotation
survival, transactional cast-startup); receiver hardened (pairing brute-force protection, strict
`loadMedia` validation, session-adoption race, dead-remote-on-chrome-hide, D-pad focus fixes).

---

## Deferred (intentional — not blockers)
- **Camera QR scanning** on the phone (CameraX + ML Kit). Discovery + TV-displayed code + manual
  entry already cover pairing; the TV only *displays* the QR.
- **Downloadable-font certs**: `font_certs.xml` ships an **empty** cert array on purpose (public
  repo — no third-party crypto committed); fonts fall back to the platform Sans/Mono until the real
  `com_google_android_gms_fonts_certs` array is dropped in locally.
- **Sender config-change retention**: rotation is survived via `configChanges` (the WS/session stay
  alive); a `ViewModel`/retained holder would be the more idiomatic long-term home.
- **Same-protocol `http→http` redirects** in Media3's data source (trusted-peer-gated; cross-protocol
  already disabled) — tighten in Phase 1 with TLS.
- **5× start/stop on-hardware memory-cycle test** — needs a live cast; teardown paths (player + WS
  + NSD + coroutines) are all wired.

## Backlog / Phase-1
- **TLS** with an ephemeral pinned cert (removes the LAN cleartext allowance on both servers).
- Camera QR scan; per-video Material You ambient tint from artwork.
- **Receiver-IP pinning** and exact media-port pinning as second gates.
- **Android 16/17 Local Network Permission** (`ACCESS_LOCAL_NETWORK`) at `targetSdk` 37.
- Router discriminating experiment if the dynamic peer-block recurs (research 03).

---

## Build / run / device recipes
- **Build (both modules, before committing):**
  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:assembleDebug :receiver:assembleDebug
  ```
  The `JAVA_HOME` prefix is required (the Gradle launcher needs it). SDK path is in
  `local.properties` (gitignored).
- **Install:** `adb -s <serial> install -r <module>/build/outputs/apk/debug/<module>-debug.apk`.
  Get serials from `adb devices` (phone is USB; TV is a network target on `:5555`).
- Screenshots → `$CLAUDE_JOB_DIR/tmp`.

---

## Reference docs
- `CLAUDE.md` — project overview, build quirks, **working agreements** (orchestration pattern;
  **PUBLIC repo — never commit secrets/real emails/SSIDs/MACs/serials/private IPs/personal paths**;
  strings in `strings.xml`).
- `SECURITY.md` — public security model + roadmap (now covers the TV control surface).
- `docs/design/design-tokens.md` — canonical color/type/shape/motion/icon tokens (both apps).
- `docs/design/control-channel.md` — NSD discovery + pairing + WS control protocol + optimistic/ghost
  sync + the two-server security posture.
- `docs/design/redesign-plan.md` — screen inventory (S1–S12 / T1–T10) → files + acceptance criteria.
- `docs/design/flick-design-system.html` — the imported Claude Design source (pixel/behavior ref).
- `docs/implementation.md` — implementation reference (player tuning, HTTP/WS contract, hardening).
- `docs/memory-audit.md`, `research/` — memory findings + network decision record.

# Flick — Claude project notes

Two-app Android system that casts a phone's local 4K HDR / Dolby Vision videos to an
Android TV by **direct-play**: `:sender` (phone — Kotlin, Compose, Ktor CIO HTTP
byte-range server in a foreground service, port 8080) serves the original file bytes;
`:receiver` (Android TV — Compose-for-TV, Media3 ExoPlayer, tuned `DefaultLoadControl`,
live diagnostics overlay) hardware-decodes them. **Never transcode, never screen-mirror** —
that is the entire anti-buffering thesis, proven zero-stall with 4K Dolby Vision on real
hardware (Phase 0).

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:assembleDebug :receiver:assembleDebug
```

- The `JAVA_HOME` prefix is required: the Gradle *launcher* needs it even when
  `org.gradle.java.home` is set (that property only governs the daemon). The system
  default JDK is too new for this AGP/Gradle pair.
- Android SDK location comes from `local.properties` (`sdk.dir=...`, gitignored) —
  create it on a fresh machine.
- Always build both modules before committing.
- **The first build on a machine must have network access**, even for plain
  `:sender:assembleDebug`. Gradle resolves plugin markers and their full classpath at
  configuration time under `apply false` too, so the baseline-profile and `com.android.test`
  chains are fetched before any task runs. None of the following is in a stock Gradle cache;
  all of it resolves from Google's Maven except where noted. Configuration time — a cold
  cache fails *every* invocation without these:
  `androidx.baselineprofile:androidx.baselineprofile.gradle.plugin:1.5.0-alpha07`,
  `androidx.benchmark:benchmark-baseline-profile-gradle-plugin:1.5.0-alpha07`,
  `com.google.testing.platform:core-proto:0.0.8-alpha08`,
  `com.android.test:com.android.test.gradle.plugin:9.3.0`. Only when `:baselineprofile:*`
  itself compiles: `androidx.benchmark:benchmark-macro-junit4` and its `benchmark-macro` /
  `benchmark-common` / `benchmark-traceprocessor` siblings at `1.5.0-alpha07`,
  `androidx.test.uiautomator:uiautomator:2.4.0`, `androidx.test:rules:1.5.0`, and
  `com.squareup.wire:wire-runtime:6.4.0` (Maven Central). Once fetched, `--offline` works
  again.

### Build types

| Type | Minified | Debuggable | Signed with | Purpose |
| --- | --- | --- | --- | --- |
| `debug` | no | yes | debug | day-to-day development; interpreted/JIT, no baseline profile |
| `release` | **yes** (R8 + resource shrinking) | no | debug keystore | the real shape of the app; carries the baseline profile |
| `benchmark` | no | no | debug keystore | macrobenchmark measurement target — AOT-compiled but symbol-readable |
| `nonMinifiedRelease`, `benchmarkRelease` | synthesized by the baseline-profile plugin | | | profile generation and comparison |

The `release` signing config is deliberately the **debug** keystore. That is a
local-testing identity so `installRelease` works on a developer machine; it is not a
distribution identity, and no keystore, password or credential belongs in this repo.

### Performance builds

Debug builds are interpreted/JIT-compiled with no profile, which is a large part of why
animation feels choppy on the TV's MediaTek CPU. Measure on `release`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:installRelease :receiver:installRelease
```

Regenerating the baseline profiles needs the matching device attached (a phone for
`:sender`, the TV for `:receiver`) and writes back into `src/release/generated/baselineProfiles/`:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :sender:generateReleaseBaselineProfile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :receiver:generateReleaseBaselineProfile
```

Generation is never part of `assemble*` (`automaticGenerationDuringBuild = false`), so a
build with no device attached still succeeds — it just packages the profile already
committed.

## Working agreements

- **Orchestration pattern (user-mandated):** substantial implementation work fans out as
  a multi-agent workflow — **Opus (xhigh) sub-agents implement, Fable verifies
  adversarially, Opus sub-agents implement fixes** for confirmed findings. Partition
  parallel implementers by module (`:sender` vs `:receiver`) so they never edit the same
  files, and let only ONE agent run Gradle (concurrent builds clash).
- **This repo is PUBLIC** (github.com/AniketShahane/Flick). Never commit secrets, real
  emails, Wi-Fi SSIDs/BSSIDs, device serials, private IPs, or machine-specific personal
  paths. Commits are authored with the GitHub noreply address.
- Comments state constraints the code can't show — nothing else. User-facing strings live
  in `res/values/strings.xml`.

## Key context

- `docs/implementation.md` is the implementation reference — keep it in sync when
  changing player tuning, the HTTP contract, or the hardening behaviors.
- `research/` holds the decision record: 01 (streaming over home Wi-Fi), 02 (fast
  transfer to TV), 03 (re-diagnosis: the home-LAN failure was a **dynamic, pair-specific
  router-side peer block** — not static AP isolation; both devices were healthy on the
  same 5 GHz radio while blocked).
- The hardening layer (Wi-Fi/wake locks + battery-exemption prompt + transfer telemetry +
  band warning on the phone; generous retry policy + bounded auto-recovery + pre-flight
  probe with specific diagnosis + TV-side Wi-Fi telemetry on the TV) exists so plain
  home-Wi-Fi streaming never silently degrades.
- Verified TV hardware: Google TV Streamer (Wi-Fi 5, MediaTek) — decoders `c2.mtk.avc.decoder`,
  `c2.mtk.dvhe.sth.decoder`. Prefer DV Profile 8.1 in MP4; 2.4 GHz cannot sustain 4K VBR peaks.

# Flick Material 3 Expressive redesign — implementation plan

This document is the implementation handoff for redesigning both Flick apps around
Material 3 Expressive. It supersedes the previous visual implementation plan. The
existing HTML design artifact remains historical reference only; implementers must use
the user-selected Expressive direction as the visual source of truth.

## Worktree and baseline contract

- **Main checkout:** `/Users/khooni-dracula/Workspace/android-casting`
- **Implementation worktree:** `/Users/khooni-dracula/Workspace/android-casting-material-expressive`
- **Implementation branch:** `codex/material-expressive-redesign`
- **Base branch:** local `main`
- **Verified base commit:** `d93982d` (`Complete control-channel hardening and diagnostics`)
- The base commit includes the prior control-channel, durable-port, lifecycle, pairing,
  runtime-diagnostics, and UI integration changes that had been present as an
  uncommitted diff on `main`.
- Before the base commit, `git diff --check`, all sender/receiver unit tests, and both
  debug assemblies passed. The baseline build command was:

  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test :sender:assembleDebug :receiver:assembleDebug
  ```

All implementation agents must run with the implementation worktree as their `cwd`.
No Material Expressive agent may edit, stage, commit, switch branches, or run formatting
from the main checkout. Before handoff, the coordinator verifies:

```sh
git -C /Users/khooni-dracula/Workspace/android-casting-material-expressive status --short --branch
git -C /Users/khooni-dracula/Workspace/android-casting-material-expressive merge-base --is-ancestor d93982d HEAD
```

The worktree must be clean and the ancestry check must succeed. If `main` advances after
handoff, only the integration owner may merge or rebase it into this branch, after all
module agents stop. Agents must never copy patches through the main checkout.

The local `main` branch is one commit ahead of `origin/main` at worktree creation. That
does not invalidate the worktree: it was intentionally based on the verified local
`main` commit. Publishing `main` is a separate operation and is not part of this plan.

## Platform decision

- **Phone (`:sender`):** use the literal `MaterialExpressiveTheme` and
  `MotionScheme.expressive()` from the selected, pinned Material3 alpha line.
- **TV (`:receiver`):** remain on `androidx.tv.material3.MaterialTheme`. Compose for TV
  has no TV-specific `MaterialExpressiveTheme`; forcing the phone theme into the TV tree
  would compromise focus, typography, and remote behavior.
- Share semantic design intent, not Compose theme/component types. Each module keeps its
  own theme and form-factor-specific primitives.
- Material expression is purposeful hierarchy through color, size, shape, motion, and
  containment. It is not a license to decorate every component or animate utility/error
  states aggressively.

## Phase 0 — select the visual source of truth

No UI implementation begins until exactly three image-based directions have been shown
and the user selects one. Each direction must cover phone Connect, Library, Detail, Now
Playing, seeking, quality, and error; plus TV Pair, Idle, playback chrome, seeking,
Settings, and error. At least one paired phone/TV synchronized-scrub frame is required.

The following product truths are fixed:

- direct-play on the LAN; no transcoding, mirroring, account, or cloud;
- phone as tactile remote and TV as cinematic canvas;
- optimistic target versus network-confirmed scrub position;
- honest pairing, connection, loading, buffering, and error states;
- local video frames are the content imagery.

The existing Spark/Link hex values, fonts, glass styling, gradients, shapes, and mark are
not automatically preserved. They must earn their place in the selected direction.

After selection, update `design-tokens.md` so it maps the approved visual system to
phone Material roles and TV Material roles. The selected reference frames and token file
replace the old HTML-wins rule.

## Phase 1 — toolchain and dependency gate

Literal phone Material 3 Expressive currently requires the Compose 1.12 family,
`compileSdk 37`, and AGP 9. Perform this as an isolated compatibility change before any
screen edits.

Candidate pinned baseline:

| Area | Candidate |
|---|---|
| AGP | `9.3.0` |
| Gradle | `9.5.0` |
| Kotlin and Compose plugin | matching stable pair, initially `2.3.21` |
| compileSdk | `37` |
| targetSdk | remain `36` during this migration |
| Compose BOM | alpha BOM mapping to Material3 `1.5.0-alpha24` |
| Phone Material3 | `1.5.0-alpha24` |
| TV Material | `androidx.tv:tv-material:1.1.0` |

One integration owner controls the wrapper, version catalog, root build configuration,
both module build files, dependency resolution, and all Gradle execution. Centralize the
Compose BOM instead of retaining module-local hard-coded versions. Pin every alpha
version exactly. Keep experimental opt-ins at theme/component boundaries.

Build both apps before UI work. If the dependency/toolchain gate fails, stop. A stable
Material3 1.4 redesign may be Expressive-inspired, but must not be described as a literal
Material Expressive implementation.

## Phase 2 — token and component foundations

Define one semantic vocabulary with two implementations:

- action, connection, focus, playback progress;
- base, raised, overlay, and media surfaces;
- success, warning, error, and premium-media roles;
- compact, standard, hero, and live shapes;
- hero, utility, focus, synchronization, and reduced-motion intents.

### Sender foundation

- Evolve `FlickTheme` into `MaterialExpressiveTheme`.
- Provide a complete color-role mapping, dynamic color on Android 12+, and brand fallback
  schemes for API 26–30.
- Fill the expanded Expressive typography and shape scales.
- Route custom animation through `MaterialTheme.motionScheme`; preserve linear live-clock
  tracking and provide a snap/fade path when animator scale is zero.
- Centralize edge-to-edge, system-bar, navigation-bar, IME, and adaptive-window insets.
- Replace generic hand-built clickables with Material buttons, icon buttons, chips, list
  rows, text fields, cards, sheets, and loading components where appropriate.
- Retain custom scrub, frame preview, play/pause morph, video tile, HDR/DV treatment, and
  cross-device handoff visuals, but make them semantic and theme-driven.

### Receiver foundation

- Keep `androidx.tv.material3.MaterialTheme` as the only receiver theme.
- Use TV-native buttons, cards, surfaces, and list patterns for generic controls.
- Express personality through ten-foot hierarchy, deliberately varied shapes, tonal
  containment, purposeful focus movement, and restrained media-derived ambience.
- Keep the fixed cinematic surface system and the five-percent safe-area contract.
- Standardize one focused target, focus restoration, scale/outline/glow, selected versus
  focused state, modal focus traps, and hidden-chrome exclusion.
- Retain custom scrub/ghost, transport, volume engagement, pairing QR, quality summary,
  and diagnostics where Flick has behavior stock components cannot express.

## Phase 3 — screen migration order

### Sender

1. Now Playing, paused, buffering, seeking, and remote controls.
2. Connect, discovery, code pairing, QR prefill, and manual fallback.
3. Detail and the direct-play cast action.
4. Library, permission, filter, and empty states.
5. Connecting and first-frame handoff.
6. Quality, advisories, diagnostics, and structured errors.

### Receiver

1. Pair, locked/no-LAN, enlarged-code modal.
2. Idle and paired-phone readiness.
3. Checking/preparing over the retained player surface.
4. Playing hidden chrome, revealed chrome, paused, buffering, seeking, quality.
5. Errors, Settings, pairing reset, debug toggle, and diagnostics.

## Preservation boundary

Presentation may consume existing state and callbacks; it must not rewrite casting
semantics. Do not weaken or relocate:

- application-scoped `CastCoordinator` ownership;
- pairing/QR trust boundaries, authentication, schemas, or control ownership;
- atomic media serving, foreground-service ownership, Stop behavior, or locks;
- preflight, exact URL/range validation, redirect policy, decoder policy, or retry policy;
- `loadAccepted`/rendered-first-frame `loadReady` sequencing;
- the retained receiver `AndroidView`/`PlayerView` surface;
- session generations, terminal frames, background sleeping posture, or LAN rebinding;
- diagnostics redaction.

The phone scrubber must keep pointer-rate reads at the leaf draw/layout boundary and must
end an active gesture safely on disposal. TV hidden chrome must have no focusable or
accessibility-visible descendants.

## Agent ownership and sequencing

All implementation agents use GPT-5.6 Terra with high reasoning unless the verifier role
requires the repository's configured adversarial model.

| Lane | Sole ownership | Starts when |
|---|---|---|
| Design direction | three image-based references; no repo edits | immediately |
| Integration/toolchain | wrapper, catalog, root build, both module build files | direction selected |
| Sender foundation | `sender/**/ui/theme/**`, then sender reusable components | toolchain green |
| Receiver foundation | `receiver/**/ui/theme/**`, then receiver reusable components | toolchain green |
| Sender screens | sender screens and sender strings | sender token API frozen |
| Receiver screens | receiver screens and receiver strings | receiver token API frozen |
| App-shell integration | `MainActivity`, `FlickApp`, `ReceiverApp`; presentation wiring only | screens ready |
| Module test agents | new module-local preview/UI/screenshot tests | renderers stable |
| Build integrator | all Gradle invocations; no product edits during builds | every gate |
| Adversarial verifier | focus, lifecycle, scrub, privacy, performance findings | integration green |
| Fix agents | Terra-high, split sender/receiver | confirmed findings only |

Foundation agents run in parallel by module. Screen agents run in parallel only after
their module's foundation API is frozen. They never edit the other module. Only one agent
runs Gradle, because concurrent builds collide. Fable performs the adversarial pass if it
is available; Terra-high module agents implement confirmed fixes.

## Verification matrix

### Phone

- API 26 fallback, API 31+ dynamic color, and API 37;
- light, dark, and three representative dynamic palettes;
- font scale 1.0, 1.3, and 2.0;
- animator scale 1x and 0x;
- portrait, landscape, split screen, expanded/foldable widths, and IME-open pairing;
- TalkBack traversal, semantics, adjustment actions, and 48dp minimum targets;
- pointer-rate scrub performance and gesture-disposal safety.

### TV

- Pair, enlarged code, Idle, preparing, all playback chrome states, seeking,
  buffering, errors, Settings, and diagnostics;
- 1080p and 4K, physical D-pad and automated key traversal;
- one actionable initial focus per state, modal focus restoration, Back unwind, and no
  focus behind overlays or hidden chrome;
- TalkBack, font scaling, five-percent safe area, and animator scale zero;
- contrast over bright, dark, saturated, and high-detail video frames.

### End to end

- discovery, QR-prefilled code pairing, and manual fallback;
- SDR and 4K HDR/Dolby Vision Profile 8.1 direct-play;
- play/pause from both devices, skip, ten seeks, volume, stop, and cancellation during
  every startup stage;
- screen-off serving, Home/screensaver, foreground return, LAN loss/rebind, control loss,
  and five start/stop cycles;
- no secret, pairing material, media token, full private URL, SSID/BSSID, serial, or title
  in committed evidence or diagnostics.

## Definition of done

- The selected visual direction—not the previous HTML—is implemented on both surfaces.
- Sender uses literal Material Expressive theme and motion APIs at the pinned version.
- Receiver remains TV-native while sharing the approved semantic design language.
- All generic controls use platform Material components where they fit; product-specific
  direct-play and synchronization controls retain their behavior.
- Reduced-motion, accessibility, dynamic-color, focus, and safe-area matrices pass.
- All existing and new tests pass, and the sole build runner successfully executes:

  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test :sender:assembleDebug :receiver:assembleDebug
  ```

- Real-hardware direct-play remains zero-stall for the verified 4K HDR/DV path.
- Final documentation records the visual source, exact versions, alpha risk, rollback,
  screenshots/video evidence, and any explicitly deferred checks.
- Final diff review confirms no regression in security, lifecycle, control, media serving,
  decoding, privacy, or diagnostics redaction.

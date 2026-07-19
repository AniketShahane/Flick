# Flick front-end redesign — implementation plan

Turns `docs/design/flick-design-system.html` into working Compose. Read alongside
`design-tokens.md` (visual contract) and `control-channel.md` (networking contract). The design
HTML is the pixel/behavior reference — implementers read their own screen sections from it by
`data-screen-label`.

## Orchestration (per CLAUDE.md)
Two Opus-xhigh implementers run **in parallel, partitioned by module so they never edit the same
files** — `:sender` vs `:receiver`. They coordinate **only** through the two contract docs above.
A **single** build agent (the sole Gradle runner) then compiles both and fixes compile errors.
Fable verifies adversarially; Opus implements fixes; codex gives an adversarial pass.

## Module ownership (no file overlap)
- **`:sender`** owns everything under `sender/` — its `build.gradle.kts` (adds its own deps),
  `ui/theme/*`, `ui/components/*`, `ui/screens/*`, nav, the WS **client** + `PlaybackSession`
  (optimistic/confirmed), NSD **discovery**, on-device frame preview, haptics, MediaStore gallery.
  Keeps `MediaHttpServer.kt` media contract intact (may add the WS client elsewhere; do not weaken it).
- **`:receiver`** owns everything under `receiver/` — its `build.gradle.kts`, `ui/theme/*`,
  `ui/components/*`, `ui/screens/*`, the WS **control server** + NSD **advertise**, QR generation,
  player command wiring + position feedback, the debug overlay demoted to an opt-in toggle.
- **Neither** touches `settings.gradle.kts`, `gradle/libs.versions.toml`, or the other module.

## Theme first, then screens, then wire the channel
Within each module, build in this order so later code compiles against a stable base:
1. `ui/theme/`: `Color.kt`, `Type.kt` (Space Grotesk/Roboto Mono downloadable + tabular), `Shape.kt`,
   `Motion.kt` (the six easing tokens as `CubicBezierEasing`/`AnimationSpec`), `FlickTheme` (dark+light
   for phone; fixed cinematic dark for TV), plus elevation/glass helpers and the icon set.
2. `ui/components/`: the §7 kit — scrub bar (phone + TV variants), transport cluster, status pills,
   signal chip, video tile, advisory card, pair card, TV focusable primitives (focus = scale 1.08 +
   cyan border + glow).
3. `ui/screens/`: the screens below.
4. Networking: discovery/pairing/control per `control-channel.md`; wire the hero end-to-end.

## Screen inventory

### `:sender` (phone) — Compose + Material 3, single column, thumb-first, light+dark
| ID | Screen | Notes |
|---|---|---|
| S1 | Connect & pair | NSD device list ("N FOUND", live dots) + code entry + QR affordance + "enter address manually". dark+light. |
| S2 | Empty state | warm, guiding; stacked poster cards; "Choose a folder"; connected-TV chip. |
| S3 | Library | **gallery not file browser** — MediaStore videos, 16:9 Coil stills, duration/size mono, DV/HDR badges, filter chips, "Connected · tap any film to flick it". |
| S4 | Cast this (detail) | poster-forward, honest tech badges, "Will direct-play at full quality" card, **Flick to <TV>** CTA (motion streaks), "play on this phone instead". |
| S5 | Connecting | traveling-light phone→TV, staged checklist (found · handshake · confirm direct-play), "usually under 2 s". |
| S6 | Now Playing (remote) | **the remote**: frame-preview scrub (phone variant), transport cluster, signal chip, poster ambient glow, Material You tint. dark+light. |
| S7 | Paused | play/pause morphed; dimmed. |
| S8 | Seeking — synchronized moment | solid(target)+ghost(confirmed), frame preview above thumb, detent ticks, SYNCING grace. |
| S9 | Buffering | honest, calm; not a spinner wall. |
| S10 | Signal & quality sheet | expands from the signal chip; throughput, band, decoder, resolution. |
| S11 | Advisories | 2.4 GHz / battery nudges as tinted actionable cards. |
| S12 | Errors | "reachable, not serving" and "unreachable" variants — specific + actionable. |

### `:receiver` (TV) — Compose for TV, 10-ft focus-driven, fixed cinematic dark
| ID | Screen | Notes |
|---|---|---|
| T1 | First-run pair | QR (ZXing→Canvas) + 4-digit code + friendly name; NSD advertising. |
| T2 | Idle — ready to cast | ambient, "ready", the paired phone hint. |
| T3 | Now playing — chrome revealed | cinematic scrub (5dp, buffered range, target ● + ghost ○), big type, poster glow, safe-area chrome. |
| T4 | Playing — chrome hidden | full-bleed video; chrome dissolved (chromeFade out 500ms). |
| T5 | Paused | "PAUSED — REMOTE" affordance; freeze frame. |
| T6 | Seeking — ghost & snap | blurred freeze-frame under drag, ghost ○ trailing target ●, syncSpring snap. |
| T7 | Buffering | calm, on-canvas. |
| T8 | Quality moment | DV/HDR premium badge flourish on start. |
| T9 | Errors | "phone not serving" and "phone unreachable" variants. |
| T10 | Debug toggle + overlay | the diagnostics overlay **demoted to an opt-in** settings toggle (default off); ON state shows the existing telemetry. |

## Acceptance criteria (Workflow A "done")
- Both modules **compile**: `:sender:assembleDebug` and `:receiver:assembleDebug` green.
- Theme tokens match `design-tokens.md` exactly (colors/type/motion), phone light+dark, TV dark.
- Library is a real MediaStore-backed gallery; picking → detail → cast starts a session.
- **Hero works end-to-end**: phone remote scrub drives the TV; TV streams confirmed position back;
  solid/ghost render per Part 4; release reconciles with syncSpring; cross-surface pause mirrors.
- Discovery lists the TV; pairing (code) establishes a session; manual IP still works as fallback.
- **No regression** to the media contract, terminal-stop semantics, hardware-only decode, or memory
  release (control WS + NSD are torn down with the session). See `control-channel.md` §7.
- Every user-facing string in `res/values/strings.xml`. Comments state constraints only. No secrets,
  real IPs/SSIDs/serials, or personal paths (public repo).

## Known deferrable (don't block the build on these)
- Camera **QR scan** on the phone (CameraX + ML Kit) — discovery + code + manual cover pairing.
- Full 5× start/stop on-hardware memory cycle test (needs a live cast).
- TLS for media (Phase 1). Downloadable fonts must **fall back** gracefully if provider absent.

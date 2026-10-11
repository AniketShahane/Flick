# Surviving a Wi-Fi blip — design

Status: in implementation. Branch `claude/project-thread-o6053o`.

## The fault

Home Wi-Fi briefly disconnects stations (band steering, mesh hand-off, AP inactivity kick,
re-keying). When a device's IPv4 address is removed, Android's netd destroys every TCP socket
bound to it (`SOCK_DESTROY` on `kAddressRemoved`). A one-second blip therefore kills both the
control WebSocket and the media HTTP connection **on the side that blipped**, while the other
side is left holding a half-open socket it will not notice for a long time (the TV runs no
pinger; the phone's watchdog takes 30–45 s).

Flick then loses the film even though the TV holds 25–40 s of buffer:

| Who blipped | What happens today |
| --- | --- |
| TV | `SessionController.onControlLost` stops the player at once. `ReceiverApp.reconcile` also tears down on the first `null` address sample (`release("no_lan_address")`), stops the control server and NSD. The phone learns 30–45 s later and re-casts only if bytes flowed in the last 10 s; usually it shows "link dropped". |
| Phone | The phone sees its own socket die immediately and re-casts. The TV still holds the half-open lease of the **same** phone, so the resume gets `busy(active_cast)` — "already casting" — until TCP gives up on the dead socket (minutes). |

## The fix: the cast outlives its connection

No new frame, field, capability or failure code. The capability list (inside the HMAC
transcript) is untouched, so pairing and resume keep working across mixed versions.

### Receiver

- **R1 Same-phone takeover.** A newly authenticated connection whose `keyId` equals the current
  controller's `keyId` replaces it even while a cast is active (displace the old socket, close
  it with `superseded`), instead of `busy`. A different `keyId` still gets `busy`. The displaced
  socket's close must not tear down the cast (lease/generation guards), and the active cast's
  ownership moves to the new connection.
- **R2 Orphan grace.** When the control socket of an **Active** cast is lost (not `stop`, not
  supersession by another cast, not TV lifecycle), the player keeps playing. The session enters
  an orphaned state for `ORPHAN_GRACE_MS` (45 s) bound to the owning `keyId`, the `castId` and
  the media URL. Within the grace, a connection from the same `keyId` that sends `loadMedia`
  with the same `castId` **and** the same URL re-adopts the running cast: answer
  `loadAccepted` then `loadReady` (startupMs/probeLatencyMs 0), resume the 10 Hz `state` feed,
  ignore `startMs`, do not touch the player. Any other `loadMedia` supersedes the orphan as a new
  cast. Grace expiry, TV Back/Home, stop, LAN address *change*, or a different phone ends it with
  today's teardown and today's `control_disconnected` face. While orphaned the TV shows a small
  non-blocking notice ("Reconnecting to <phone>…", strings.xml). Startup-phase losses keep
  today's behaviour.
- **R3 Address debounce.** A `null` LAN address no longer tears down at once. Re-sample for up to
  `LAN_BLIP_GRACE_MS` (20 s); if the same address returns, keep the binding (and the cast), and
  verify the control server is still listening — restart it on the same port if not. A
  *different* address, or still none after the grace, releases exactly as today.

### Phone

- **S1 Reattach instead of re-cast.** When the control socket of an Active cast is lost, keep the
  media server, token and foreground service alive and keep the Now Playing screen, with a
  non-blocking "Reconnecting to <TV> · the film keeps playing" banner. Re-dial via the normal
  resume path with backoff (≈0.5, 1, 2, 4, 8 s… capped) inside a 40 s window (under the TV's
  45 s grace). On authentication, send `loadMedia` with the **same** `castId`, URL and title,
  `startMs` = last reported position. `loadReady` returns to Active with no re-buffer. An old
  receiver treats this as a new cast at `startMs`, which is today's recovery with no re-buffer
  penalty beyond it. If the phone's own address changed (URL no longer valid), or the window
  ends, fall back to today's paths (recovery re-cast / block-wait / error face) unchanged.
  The 10 s media-serving gate no longer decides whether to *try* a reattach; it still gates the
  existing re-cast fallback.
- **S2 Media server survives the phone's own blip.** If the phone's address disappears and
  returns unchanged, make sure the media server is listening on it again (same port, same
  token) so the TV's ExoPlayer retry reconnects. A changed address ends the reattach.
- **S3 Honest Wi-Fi lock comment.** On API 34+ `WIFI_MODE_FULL_HIGH_PERF` is silently replaced by
  `WIFI_MODE_FULL_LOW_LATENCY`, which only holds while the app is foreground with the screen on.
  No public API keeps Wi-Fi out of power save with the screen off; correct the comment, keep the
  lock.

## Tests

Pure-JVM tests for: same-key admission (R1), orphan grace re-adopt / reject rules (R2), address
debounce decisions (R3), sender reattach policy and backoff (S1).

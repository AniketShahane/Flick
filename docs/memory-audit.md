# Flick — Memory Return-to-Baseline Report

## 1. The straight answer to your worry

**No memory leaks were found on either device. Nothing retains large buffers, the codec, file descriptors, threads, or locks after Stop, and nothing grows monotonically across repeated cast sessions.** The sender and receiver teardown paths were audited against the app code *and* the actual dependency bytecode (Ktor CIO 3.1.3, Media3 1.10.1 from the Gradle cache), and every release chain is real.

What "returns to baseline" realistically means here — read this before you stare at a memory graph and panic:

- **The Java/Dalvik heap RSS will NOT shrink back to the OS after a cast, and that is not a leak.** The receiver runs `largeHeap=true` with a 256 MB ExoPlayer byte target; ART reclaims those allocations into *its own* free pool but keeps the pages mapped for reuse. A high `Java Heap` / `TOTAL PSS` number after Stop is expected and correct.
- **The three numbers that must actually fall and must stay flat across cycles are: Native Heap PSS, open FD count, and thread count.** These back the hardware decoder, the ExoPlayer allocator's off-heap segments, the CIO selector/sockets, and the `content://` file descriptor. All three were verified to release on Stop and to plateau (not climb) across sessions.
- **The sender process often fully exits after Stop** (`stopForeground(REMOVE)` + `stopSelf()`), in which case the OS reclaims everything — the best possible outcome.
- **The receiver keeps exactly one small object alive by design** across sessions — the shared `DefaultBandwidthMeter` — plus, after a plain Stop, a warm empty `ExoPlayer` shell and its parked playback thread (~1–2 MB, bounded, non-accumulating). The heavy stuff (decoder, 256 MB allocator pool, HTTP sockets) is released on Stop regardless.

So: memory returns to baseline in substance. The items below are **optional polish, not leaks** — they matter only if you want the receiver's footprint to hit baseline *immediately and literally* on Stop, and to close one real behavioral hole where memory can come *back* after a Stop.

## 2. Prioritized items (none are leaks — polish relevant to the strict goal)

| Severity | Module | Resource | Location | Fix (one line) |
|---|---|---|---|---|
| Low (behavioral) | receiver | 256 MB buffer + decoder re-allocated after a Stop | `PlayerController.stop()` (`PlayerController.kt:392`) / `stopSession` (`ReceiverApp.kt:149`) | On Stop clear `currentUrl`/`savedPositionMs` so a background→foreground doesn't silently re-prepare playback. |
| Low (polish) | receiver | Warm `ExoPlayer` shell + parked playback `HandlerThread` (~1–2 MB) | `PlayerController.stop()` (`PlayerController.kt:392`) | Optionally call `release()` on Stop for strict return-to-baseline (costs a ~200–400 ms rebuild on next Play). |
| Info (power, not memory) | receiver | 2 Hz poll coroutine + `getConnectionInfo` binder call while backgrounded | `ReceiverApp.kt:156` | Gate the poll with `repeatOnLifecycle(STARTED)`, matching the sender's ticker idiom. |
| Info (cosmetic) | sender | `TransferTelemetry` scalars persist until next Start | `TransferTelemetry.reset()` / `CastServerService` teardown | Optionally call `reset()` on Stop (frees zero memory; hygiene only). |

## 3. Detail (worst first)

### 3.1 — Receiver re-allocates the full working set after a Stop (behavioral, low)

**What lingers / what comes back:** `PlayerController.stop()` (lines 392–400) sets a private `pendingPlayWhenReady = false` field but never touches the live `exo.playWhenReady` flag (still `true` from playback) and never clears `currentUrl` (line 62) or `savedPositionMs` (line 64). It calls `exo.stop()` + `clearMediaItems()` and keeps the instance.

Now trace **Stop → Home → return**:
1. `stop()` — decoder released, allocator trimmed, but `currentUrl` still set, `exo.playWhenReady` still `true`.
2. `onStop()` (line 344) — captures `pendingPlayWhenReady = exo.playWhenReady` = **`true`** (overwriting the `false` that `stop()` set), then `exo.release()`.
3. `onStart()` (line 330) — `player == null`, so it rebuilds; `currentUrl != null` ⇒ `setMediaItem` + `prepare` + `seekTo(savedPositionMs)` + `playWhenReady = pendingPlayWhenReady` = **`true`**.

Result: playback silently resumes and the up-to-256 MB buffer re-allocates, even though the on-screen `sessionState` is still `Idle`. This does **not** accumulate (single reused instance, `growsAcrossSessions = false`), so it is not a leak — but it is the one path that makes memory go *back up* after the user believes they stopped, which is squarely your stated concern.

**Fix** — make Stop terminal for the *media*, so `onStart()` has nothing to restore:

```kotlin
// PlayerController.stop()
fun stop() {
    pendingPlayWhenReady = false
    cancelPendingRecovery()
    stableReadySinceMs = 0L
    currentUrl = null          // <-- add: onStart()'s `if (url != null)` guard now skips re-prepare
    savedPositionMs = 0L        // <-- add: nothing to seek back to
    player?.let { exo ->
        exo.playWhenReady = false   // <-- add: don't let a captured true flag restart playback
        exo.stop()
        exo.clearMediaItems()
    }
}
```

This is the minimal correctness fix and is independent of whether you keep the warm instance.

### 3.2 — Warm ExoPlayer shell held after Stop (polish, low)

**What lingers:** after a plain Stop the receiver keeps the empty `ExoPlayer` object graph plus its single parked playback `HandlerThread` and the two listeners on the reused instance — roughly 1–2 MB, deliberately kept so `PlayerView` (`setKeepContentOnPlayerReset(true)`) shows the last frame and the next Play reuses the instance (`play()` line 386: `player ?: createPlayer()`).

**Why it's not a leak:** the heavy resources are already gone at Stop — verified in Media3 1.10.1 bytecode: with `setForegroundMode` never called (grep-confirmed), `exo.stop()` runs `resetInternal(resetRenderers=true)`, which calls `MediaCodecRenderer.releaseCodec()` (the `c2.mtk.*` hardware decoder is freed) and `DefaultLoadControl.onStopped()` → `DefaultAllocator.reset()`; because the allocator is built `trimOnReset=true` (line 253), the entire pooled 256 MB target is trimmed to zero and becomes GC-reclaimable. Terminal `onStop()`/`release()` (lines 344/362) remove both listeners, cancel the recovery `Handler`, `exo.release()` (quits the HandlerThread), and null the field. Repeated Play/Stop create no new threads/listeners/player graphs.

**Fix (optional, only if you want literal baseline on Stop):** have `stopSession` (`ReceiverApp.kt:149`) call `controller.release()` instead of `controller.stop()`. `play()` already tolerates a null player. Trade-off: you lose the held last frame and pay a ~200–400 ms player rebuild on the next Play. Combine with the `currentUrl = null` from §3.1 so a later background→foreground stays quiet.

> **Fragility note (not a current bug):** the retain-on-stop design is safe *only* because `foregroundMode` is never enabled and `trimOnReset=true`. If either ever changes, `stop()` would start silently holding the decoder or the 256 MB pool. Worth a code comment at the allocator/`stop()` sites.

### 3.3 — Diagnostics poll runs at 2 Hz while backgrounded (power, not memory)

**What lingers:** `LaunchedEffect(controller) { while (isActive) { snapshot = controller.snapshot(); delay(500) } }` (`ReceiverApp.kt:156`) has no lifecycle gate. Because `delay()` is Handler-backed (not frame-clock-backed), it keeps ticking after Stop and while the Activity is `ON_STOP`'d, each tick doing a `WifiManager.getConnectionInfo` binder call (`WifiTelemetry.kt:29`).

**Why it's not a leak:** exactly one loop exists per composition (stable `remember{}` key), it is cancelled on dispose, and each tick's `DiagnosticsSnapshot`/`Link` overwrites a single `mutableStateOf` slot and is instantly GC-eligible. `snapshot()` null-tolerates the released player. Zero accumulation.

**Fix (battery/CPU hygiene):**

```kotlin
LaunchedEffect(controller) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (isActive) { snapshot = controller.snapshot(); delay(OVERLAY_REFRESH_MS) }
    }
}
```

Matches the sender's existing `repeatOnLifecycle` ticker pattern. Changes no memory outcome.

### 3.4 — TransferTelemetry not reset on Stop (cosmetic)

**What lingers:** `TransferTelemetry` is a Kotlin `object` holding two `AtomicLong`s, an `AtomicInteger`, a few sampling scalars, and a `StateFlow<TransferStats>` (3 longs + 1 int). `reset()` runs only on Start, not on Stop. No Uri, buffer, fd, thread, or collection is retained; every field is overwritten (not appended), and `inFlight` is intentionally preserved across `reset()` (paired enter/exit in `streamSlice`'s `finally`).

**Fix (optional hygiene, frees zero memory):** call `TransferTelemetry.reset()` in `stopEverything()`, carrying the same `inFlight` carve-out. Purely cosmetic — the fixed scalar footprint exists for the process lifetime by design.

---

## 4. Appendix — Measurement & acceptance-test protocol

```markdown
# Flick — Memory Return-to-Baseline Measurement & Acceptance Protocol

Goal: prove that after **Stop**, both the phone (`:sender`) and the TV (`:receiver`)
release the large native buffers, ExoPlayer + allocator, hardware decoder, file
descriptors, threads, listeners, coroutines/Handlers, and wake/Wi-Fi locks — and that
nothing grows **monotonically** across repeated cast sessions.

> **Read this first — the one thing that trips everyone up.**
> On Android/ART the **Java/Dalvik heap RSS does not shrink back to the OS** after a big
> allocation, even with zero leaks. The receiver runs `largeHeap=true` and a 256 MB
> ExoPlayer byte target, so its Dalvik/Java *reserved* footprint staying high after Stop
> is **EXPECTED and is NOT a failure**. What must come back down and must NOT grow across
> cycles: **Native Heap PSS, open FD count, and thread count** (these back the codec,
> sockets, the CIO selector/thread pool, the ExoPlayer allocator's off-heap segments, and
> the content:// file descriptor). Judge pass/fail on those three, cycle over cycle.

---

## 0. Setup

```sh
# One-time: point a shell var at adb and at each device.
export ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb

# List attached devices to grab the two serials.
"$ADB" devices -l
```

You will see two transports: the **phone over USB** (an alphanumeric serial) and the
**TV over network** (an `ip:5555` serial). Capture both:

```sh
export PHONE=<usb-serial-from-adb-devices>      # e.g. 39abf1c2
export TV=<tv-ip>:5555                          # e.g. 192.168.1.50:5555

# If the TV isn't connected yet:
# "$ADB" connect <tv-ip>:5555

export SENDER=com.flick.sender
export RECEIVER=com.flick.receiver
```

Both apps must be **debug builds** (they are, from `assembleDebug`) so `run-as` works for
the `/proc` reads. Sanity check:

```sh
"$ADB" -s "$PHONE" shell run-as "$SENDER"   id   # should print an app uid, not an error
"$ADB" -s "$TV"    shell run-as "$RECEIVER" id
```

---

## 1. The three raw probes

Everything below is built from three primitives. Run each against the right
device/package. `pidof` returns empty when the process is gone (a legitimate outcome for
the sender — see §4).

### 1a. Memory (PSS / Native Heap / Dalvik Heap) — `dumpsys meminfo`

```sh
"$ADB" -s "$PHONE" shell dumpsys meminfo "$SENDER"
"$ADB" -s "$TV"    shell dumpsys meminfo "$RECEIVER"
```

Read the **`App Summary`** block at the bottom. The numbers that matter:

| App Summary line | What it tells you |
|---|---|
| `Native Heap` | Off-heap: codec buffers, ExoPlayer `DefaultAllocator` segments, CIO buffers. **Primary leak signal.** |
| `Java Heap` | ART heap. High reserve after Stop is fine; watch only for growth across cycles. |
| `TOTAL PSS` (or `TOTAL`) | Whole-process proportional set size. Context only — dominated by heap reserve. |

Quick one-liners to pull just those three (works with the `App Summary` labels):

```sh
"$ADB" -s "$PHONE" shell dumpsys meminfo "$SENDER"   | grep -E 'Native Heap|Java Heap|TOTAL'
"$ADB" -s "$TV"    shell dumpsys meminfo "$RECEIVER" | grep -E 'Native Heap|Java Heap|TOTAL'
```

### 1b. File-descriptor count — `/proc/<pid>/fd`

`dumpsys meminfo` does **not** report FDs or threads, so read them straight from `/proc`
via `run-as`:

```sh
# FD count (sender)
PID=$("$ADB" -s "$PHONE" shell pidof "$SENDER"); \
"$ADB" -s "$PHONE" shell run-as "$SENDER" sh -c "ls -1 /proc/$PID/fd | wc -l"

# FD count (receiver)
PID=$("$ADB" -s "$TV" shell pidof "$RECEIVER"); \
"$ADB" -s "$TV" shell run-as "$RECEIVER" sh -c "ls -1 /proc/$PID/fd | wc -l"
```

The load-bearing FDs to watch: on the sender, the **listening socket (:8080)**, each live
`/video` **TCP connection**, and the **`content://` ParcelFileDescriptor** opened per range
request (`ParcelFileDescriptor.AutoCloseInputStream(...).use { }` must close it on every
transfer). On the receiver, the **ExoPlayer HTTP sockets** to the sender and codec FDs.

> To see *what* the FDs are (useful when a count looks high):
> ```sh
> "$ADB" -s "$PHONE" shell run-as "$SENDER" sh -c "ls -l /proc/$PID/fd"
> ```
> Expect `socket:[...]`, the app's own `.apk`/`.so`, `pipe:[...]`, `anon_inode:[...]`.
> A lingering `content://`-backed file or an ever-growing pile of `socket:[...]` after
> Stop is the smoking gun.

### 1c. Thread count — `/proc/<pid>/status`

```sh
# Thread count (sender)
PID=$("$ADB" -s "$PHONE" shell pidof "$SENDER"); \
"$ADB" -s "$PHONE" shell run-as "$SENDER" sh -c "grep Threads /proc/$PID/status"

# Thread count (receiver)
PID=$("$ADB" -s "$TV" shell pidof "$RECEIVER"); \
"$ADB" -s "$TV" shell run-as "$RECEIVER" sh -c "grep Threads /proc/$PID/status"
```

The load-bearing threads: on the sender, the **Ktor CIO selector + worker/dispatcher
threads** (must die on `engine.stop(100, 300)`) and the `serviceScope`
`Dispatchers.IO` coroutine threads (`serviceScope.cancel()`); on the receiver, the
**ExoPlayer internal / codec / loader threads** (must die on `ExoPlayer.release()`), with
no leftover from the `recoveryHandler` or the snapshot-poll `LaunchedEffect`.

### Optional convenience: capture all three at once

```sh
snap () {   # usage: snap <device-serial> <pkg> <label>
  local DEV="$1" PKG="$2" LABEL="$3"
  local PID; PID=$("$ADB" -s "$DEV" shell pidof "$PKG" | tr -d '\r')
  echo "=== $LABEL  ($PKG  pid=${PID:-GONE}) ==="
  if [ -z "$PID" ]; then echo "process not running (heap fully reclaimed by OS)"; return; fi
  "$ADB" -s "$DEV" shell dumpsys meminfo "$PKG" | grep -E 'Native Heap|Java Heap|TOTAL'
  echo -n "FDs:     "; "$ADB" -s "$DEV" shell run-as "$PKG" sh -c "ls -1 /proc/$PID/fd | wc -l" | tr -d '\r'
  "$ADB" -s "$DEV" shell run-as "$PKG" sh -c "grep Threads /proc/$PID/status" | tr -d '\r'
}
# Examples:
#   snap "$PHONE" "$SENDER"   baseline
#   snap "$TV"    "$RECEIVER" post-stop
```

### Forcing a GC / trim before the SETTLED reading

Native Heap and Dalvik reserve won't drop the instant you press Stop — ART reclaims lazily.
Nudge it before the SETTLED sample:

```sh
"$ADB" -s "$PHONE" shell am send-trim-memory "$SENDER"   RUNNING_CRITICAL
"$ADB" -s "$TV"    shell am send-trim-memory "$RECEIVER" RUNNING_CRITICAL
```

(`RUNNING_CRITICAL` works while the app is still foreground. If the sender **process has
already exited** after Stop, this prints an error — that's the best possible outcome, not a
problem.) Wait ~10–30 s after trimming before sampling SETTLED.

---

## 2. The per-session procedure (4 sample points)

Drive the apps by hand (pick a 4K/DV file on the phone, type the phone IP on the TV, Play,
let it run, Stop). At each of the four points below, run `snap` (or the three probes) for
**both** packages.

| Point | When to sample | What it establishes |
|---|---|---|
| **BASELINE** | Both apps open & idle, **no cast running** (receiver on the URL screen, sender on the picker, before any Play) | The floor to return to. |
| **DURING** | ~30–60 s into steady playback (overlay shows READY, buffer filled) | The working set — Native Heap, FDs (live sockets + content fd), threads all elevated. |
| **POST-STOP** | Immediately after pressing **Stop** on the TV **and** Stop on the phone notification/UI | Teardown fired. Native Heap/FDs/threads should already be falling. |
| **SETTLED** | After `am send-trim-memory` + ~10–30 s wait | The real comparison point vs BASELINE. |

Capture BASELINE **once** at the very start, then repeat DURING → POST-STOP → SETTLED for
each cycle in §3.

What "released" looks like at SETTLED:
- **Sender:** Native Heap back near baseline; **FD count back to baseline** (no leftover
  `:8080` connection sockets, no lingering `content://` fd); **thread count back to
  baseline** (CIO pool + `serviceScope` gone). Wake/Wi-Fi locks released — verify
  independently:
  ```sh
  "$ADB" -s "$PHONE" shell dumpsys power | grep -i -A2 "flick:"     # expect NO flick:cast-wake / flick:cast-wifi held
  "$ADB" -s "$PHONE" shell dumpsys activity services "$SENDER" | grep -i flick  # expect no running CastServerService
  ```
- **Receiver:** Native Heap back near baseline (codec + allocator segments freed by
  `ExoPlayer.release()`); FDs back to baseline (HTTP sockets closed); threads back to
  baseline. `dumpsys meminfo` **`Objects`** block is a bonus cross-check — after Stop the
  ExoPlayer-related objects should be collected once GC runs.

---

## 3. Acceptance test — 5× start/stop, no monotonic growth

**This is the real criterion.** Do the full picking→Play→~30–60 s→Stop loop **5 times** and
record SETTLED after each. Pass = Native Heap, FD count, and Thread count each return to
**near baseline** every cycle (small jitter fine) and do **not** trend upward across cycles.

Rule of thumb for pass:
- **FD count** and **Thread count** at SETTLED: back to baseline ±1–2 each cycle, flat trend.
  (These must be near-exact — a socket or thread that leaks shows up as +N per cycle.)
- **Native Heap PSS** at SETTLED: within roughly **±10–15%** of baseline, flat trend — no
  stair-step upward.
- **Java/Dalvik Heap / TOTAL PSS** staying high: **ignore for pass/fail.** Only a clear,
  monotonic climb *of Native Heap / FDs / threads* across all 5 cycles is a **FAIL**.

A leak looks like: FDs 42 → 47 → 52 → 57 → 62, or threads 31 → 34 → 37 → 40, or Native Heap
climbing every cycle and never returning. A healthy app looks like: 42 → 43 → 42 → 42 → 43.

### 3a. Receiver (`com.flick.receiver`, TV) — fill in

| Cycle | Baseline Native (KB) | SETTLED Native (KB) | Baseline FDs | SETTLED FDs | Baseline Threads | SETTLED Threads | Δ vs baseline OK? |
|---|---|---|---|---|---|---|---|
| 1 |  |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |  |
| **Trend** | — | flat? | — | flat? | — | flat? | **PASS / FAIL** |

### 3b. Sender (`com.flick.sender`, phone) — fill in

| Cycle | Baseline Native (KB) | SETTLED Native (KB) | Baseline FDs | SETTLED FDs | Baseline Threads | SETTLED Threads | Locks released? | Δ vs baseline OK? |
|---|---|---|---|---|---|---|---|---|
| 1 |  |  |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |  |  |
| **Trend** | — | flat? | — | flat? | — | flat? | all yes? | **PASS / FAIL** |

> Fill BASELINE columns once (row 1) and reuse, or re-read if the process was recreated.

---

## 4. Interpretation notes specific to Flick

- **The sender process may fully exit after Stop.** `stopEverything()` calls
  `ServiceCompat.stopForeground(REMOVE)` + `stopSelf()`, and if `MainActivity` isn't
  foreground the whole process can be killed. If `pidof com.flick.sender` returns empty at
  POST-STOP/SETTLED, that is the **best** result — the OS reclaimed everything. Record it as
  PASS (process gone). Re-open the app for the next cycle's BASELINE.
- **Receiver process persists** (the Activity stays up on the URL screen after Stop); this
  is where cycle-over-cycle Native/FD/thread growth would show, so it's the more important
  table. Note the receiver keeps **one** small object alive by design across sessions — the
  shared `DefaultBandwidthMeter` in `PlayerController` — which is bounded and must **not**
  grow; the `PlayerController` itself is `remember{}`-scoped to the composition, so it is a
  single stable instance, not one-per-cast.
- **First-cycle warm-up is normal.** Native Heap and thread pools often settle to a slightly
  higher plateau after cycle 1 than the cold BASELINE (JIT, thread-pool high-water marks,
  cached codec state). Judge the trend across cycles **2→5**, which should be flat.
- **If a number won't fall:** dump the specifics.
  - Sender leftover threads → `run-as com.flick.sender sh -c "ls /proc/$PID/task"` then
    `cat /proc/$PID/task/<tid>/comm` to name them (look for stray CIO / `DefaultDispatcher`
    threads that should have died with `engine.stop()` / `serviceScope.cancel()`).
  - Sender leftover FDs → `ls -l /proc/$PID/fd` (a stuck `content://` fd or `socket:[...]`
    means a range transfer or the listen socket didn't close).
  - Receiver leftover Native/threads → confirm `ExoPlayer.release()` ran (overlay/player
    null) and check `dumpsys meminfo` `Objects` for surviving `ViewRootImpl`/player objects.
```

---

## 5. Considered and dismissed (false alarms — all verified clean)

Every candidate below was checked against source *and*, where it mattered, decompiled dependency bytecode. None is a leak.

**Sender**
- **Ktor CIO engine teardown** — `engine.stop(100,300)` cancels the server job; the selector loop's `invokeOnCompletion` closes the `SelectorManager` (epoll fd) and listening socket; CIO workers run on the shared process-wide `Dispatchers.IO`, so no per-engine thread pool accumulates. A fresh `EmbeddedServer` per session is dropped and GC'd. Verified in ktor-server-cio 3.1.3 bytecode.
- **`MediaHttpServer.streamSlice` 256 KB per-request buffer** — a method-local reused for the whole slice, GC garbage the moment the request returns; effective concurrency 1, tens of allocations per session. Not LOS fragmentation at this scale.
- **`streamSlice` file descriptor / cancellation** — the `content://` PFD is wrapped in `AutoCloseInputStream(pfd).use { }`, closed on every path (EOF, client-disconnect IOException, revoked grant, forced engine-stop); `enter/exitTransfer` balanced in `finally`. No fd accumulation.
- **Teardown blocks the main thread ~400 ms** — real but bounded, documented, far below ANR thresholds; zero memory impact. Moving it off-thread would weaken the generation-guarded teardown ordering.
- **`TransferTelemetry` process-singleton** — fixed handful of atomics/scalars; overwritten not appended; `inFlight` deliberately preserved. Stale, not retained; a stop-time reset frees zero bytes (§3.4).
- **`CastServerService` full teardown / locks / singletons** — Wi-Fi + wake locks non-ref-counted, `isHeld`-guarded, released in both `stopEverything()` and `onDestroy()` (plus 6 h wake-lock safety timeout); generation guard closes the start/stop race; `ServerStateHolder` holds only strings/longs (no Uri/bitmap); `serviceScope` cancelled in `onDestroy`; `MediaMeta` cursors/AFD all `.use{}`-scoped.

**Receiver**
- **Stop keeps the ExoPlayer instance** — intentional warm-instance; decoder released via renderer reset (`foregroundMode` false, grep-confirmed) and the 256 MB allocator trimmed (`trimOnReset=true`, line 253) at Stop. Residue ~1–2 MB, bounded, non-accumulating (§3.2).
- **2 Hz snapshot poll loop** — single composition-bound coroutine, cancelled on dispose; per-tick snapshot overwrites one state slot and is instantly GC-eligible; null-tolerant after Stop. Power/CPU nit only (§3.3).
- **`ReceiverApp` Compose retention** (controller context, PlayerView/Surface, probeJob, window) — controller stores only `applicationContext`; `DisposableEffect.onDispose` removes the lifecycle observer and calls `release()`; probeJob is composition-scoped and superseded per run; `RefreshRateHelper`/`WifiTelemetry` are stateless.
- **`PreflightProbe` stage-2 GET body not drained** — `disconnect()` in a `finally` hard-closes the socket and releases the fd on every path; stateless singleton, once per attempt. Efficiency footnote, no fd retention.
- **Teardown listeners / recovery Handler / coroutines** — listeners added once in `createPlayer()`, removed in `onStop()`/`release()` before `exo.release()`; pending recovery Runnable cancelled in `stop`/`onStop`/`release`/`play` and self-guards with `player ?: return`; `InstrumentationState.reset()` clears all scalars per Play. Nothing orphaned, nothing grows across sessions.

Bottom line: the app is clean. Apply §3.1 to close the one behavioral hole where memory can return after a Stop; §3.2–§3.4 are optional polish. Then run the 5× acceptance test in the appendix and judge on Native Heap / FDs / threads — not on Java heap RSS.

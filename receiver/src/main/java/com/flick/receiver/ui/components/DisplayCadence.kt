package com.flick.receiver.ui.components

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round

/** How long after an observed mode change the panel is treated as still resyncing. */
internal const val RESYNC_GRACE_MS = 500L

/** How long a pending switch is waited for before it is assumed not to be coming. */
internal const val SWITCH_EXPECT_WINDOW_MS = 500L

/** The most any stage seam ever holds its pixels, measured from the seam. */
internal const val STAGE_HOLD_CAP_MS = 2_500L

/**
 * How long a film reveal waits for the film's cadence, which the 2 Hz diagnostics arm
 * can publish up to one period after the first frame; the pin, and any blank, follow it.
 */
internal const val RATE_KNOWN_WAIT_MS = SWITCH_EXPECT_WINDOW_MS + 100L

/** Relative tolerance under which two rates are one cadence: 23.976 against 24 is not. */
private const val CADENCE_TOLERANCE = 0.0005f

/** How close a seamless alternative must sit to a candidate mode to be that mode. */
private const val SEAMLESS_MATCH_HZ = 0.01f

/** DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER, which is API 31. */
private const val MATCH_CONTENT_NEVER = 0

/** DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY, which is API 31. */
private const val MATCH_CONTENT_SEAMLESS_ONLY = 1

/**
 * What the display is doing about the HDMI mode switch the refresh-rate pin causes.
 *
 * Passive and event-only: it never writes a hint, never polls per frame, and only
 * reports what the platform already did.
 */
internal interface DisplayCadenceSource {
    /** The panel's actual mode rate, never the rate the window asked for. */
    val physicalHz: Float

    /** The rate the panel sits at when nothing is pinned. */
    val restHz: Float

    /** When the display last changed mode, on the same clock as `now`. */
    val lastChangeAtMs: Long?

    /** Each mode change, as it lands. */
    val changes: Flow<Long>

    /** Whether asking for [requestedHz] would make the panel blank to resync. */
    fun switchWouldBlank(requestedHz: Float): Boolean
}

/** Whether [displayHz] shows [contentHz] with every frame held for a whole number of refreshes. */
internal fun cadenceCompatible(displayHz: Float, contentHz: Float): Boolean {
    if (!displayHz.isFinite() || !contentHz.isFinite() || displayHz <= 0f || contentHz <= 0f) return false
    val ratio = displayHz / contentHz
    val k = round(ratio)
    return k >= 1f && abs(ratio - k) / k < CADENCE_TOLERANCE
}

internal fun sameCadence(a: Float, b: Float): Boolean = abs(a - b) / b < CADENCE_TOLERANCE

/**
 * Whether the platform would answer a [requestedHz] pin with a blanking mode switch.
 *
 * [preference] is the user's match-content setting (null below API 31, where it
 * cannot be read and the switch is assumed); [alternativeHz] are the current mode's
 * seamless alternatives.
 */
internal fun switchBlanks(
    alternativeHz: FloatArray,
    sameSizeModeHz: FloatArray,
    preference: Int?,
    requestedHz: Float,
): Boolean {
    if (preference == MATCH_CONTENT_NEVER) return false
    val candidates = sameSizeModeHz.filter { cadenceCompatible(it, requestedHz) }
    if (candidates.isEmpty()) return false
    if (candidates.any { c -> alternativeHz.any { abs(it - c) < SEAMLESS_MATCH_HZ } }) return false
    if (preference == MATCH_CONTENT_SEAMLESS_ONLY) return false
    return true
}

internal enum class HoldReason { NoSwitch, Settled, NoChangeSeen, Cap, KeyPressed }

internal data class StageHold(val waitMs: Long, val reason: HoldReason)

/**
 * How much longer a seam holds, [elapsedMs] after it began. [lastChangeElapsedMs] is
 * the observed mode change relative to the seam, which may be slightly negative when
 * the switch landed just before it.
 */
internal fun stageHold(
    elapsedMs: Long,
    pending: Boolean,
    lastChangeElapsedMs: Long?,
    capMs: Long = STAGE_HOLD_CAP_MS,
): StageHold = when {
    !pending -> StageHold(0L, HoldReason.NoSwitch)
    elapsedMs >= capMs -> StageHold(0L, HoldReason.Cap)
    lastChangeElapsedMs != null -> StageHold(
        (min(lastChangeElapsedMs + RESYNC_GRACE_MS, capMs) - elapsedMs).coerceAtLeast(0L),
        HoldReason.Settled,
    )
    else -> StageHold(
        (min(SWITCH_EXPECT_WINDOW_MS, capMs) - elapsedMs).coerceAtLeast(0L),
        HoldReason.NoChangeSeen,
    )
}

/**
 * Suspends until the seam may show pixels, and returns how long it waited and why.
 * Any key ends the wait at once; the key itself is dispatched as normal elsewhere.
 * [capMs] is what is left of [STAGE_HOLD_CAP_MS] when the seam has already waited.
 */
internal suspend fun awaitStageWindow(
    cadence: DisplayCadenceSource,
    now: () -> Long,
    keyPresses: Flow<Unit>,
    pending: Boolean,
    capMs: Long = STAGE_HOLD_CAP_MS,
): StageHold {
    val edge = now()
    while (true) {
        val hold = stageHold(
            elapsedMs = now() - edge,
            pending = pending,
            lastChangeElapsedMs = cadence.lastChangeAtMs?.minus(edge)?.takeIf { it >= -RESYNC_GRACE_MS },
            capMs = capMs,
        )
        if (hold.waitMs <= 0L) return StageHold(now() - edge, hold.reason)
        val signal = withTimeoutOrNull(hold.waitMs) {
            merge(cadence.changes.map { 0 }, keyPresses.map { 1 }).first()
        }
        if (signal == 1) return StageHold(now() - edge, HoldReason.KeyPressed)
    }
}

/**
 * Whether a hold that has already seen a switch settle still owes one: the panel sits
 * at a rate that cannot show [rateHz], and the pin to it would blank.
 */
internal fun switchStillOwed(physicalHz: Float, rateHz: Float, wouldBlank: () -> Boolean): Boolean =
    rateHz > 0f && !cadenceCompatible(physicalHz, rateHz) && wouldBlank()

/**
 * Waits up to [SWITCH_EXPECT_WINDOW_MS] for a switch newer than the one a hold has
 * already waited out, then out its resync grace, all within [capMs]. Any key ends
 * the wait at once.
 */
internal suspend fun awaitOwedSwitch(
    cadence: DisplayCadenceSource,
    now: () -> Long,
    keyPresses: Flow<Unit>,
    capMs: Long,
): StageHold {
    val start = now()
    if (capMs <= 0L) return StageHold(0L, HoldReason.Cap)
    val seen = cadence.lastChangeAtMs
    val first = withTimeoutOrNull(min(SWITCH_EXPECT_WINDOW_MS, capMs)) {
        merge(
            cadence.changes.map { 0 },
            keyPresses.map { 1 },
            // A switch reported between reading `seen` and subscribing.
            flow { if (cadence.lastChangeAtMs != seen) emit(0) },
        ).first()
    }
    when (first) {
        null -> return StageHold(now() - start, HoldReason.NoChangeSeen)
        1 -> return StageHold(now() - start, HoldReason.KeyPressed)
    }
    while (true) {
        val capAt = start + capMs
        val graceAt = (cadence.lastChangeAtMs ?: now()) + RESYNC_GRACE_MS
        val left = min(graceAt, capAt) - now()
        if (left <= 0L) {
            return StageHold(now() - start, if (graceAt >= capAt) HoldReason.Cap else HoldReason.Settled)
        }
        val signal = withTimeoutOrNull(left) {
            merge(cadence.changes.map { 0 }, keyPresses.map { 1 }).first()
        }
        if (signal == 1) return StageHold(now() - start, HoldReason.KeyPressed)
    }
}

/**
 * The window's display, observed for mode changes for as long as the caller is
 * composed. Passive: it never writes a hint and does no per-frame work.
 */
@Composable
internal fun rememberDisplayCadence(
    window: Window,
    now: () -> Long = SystemClock::uptimeMillis,
): DisplayCadenceSource {
    val context = LocalContext.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    val display = LocalView.current.display ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    val cadence = remember(display, window) { DisplayModeCadence(display, displayManager, window, now) }
    DisposableEffect(cadence) {
        displayManager.registerDisplayListener(cadence, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(cadence) }
    }
    return cadence
}

private class DisplayModeCadence(
    private val display: Display,
    private val displayManager: DisplayManager,
    private val window: Window,
    private val now: () -> Long,
) : DisplayCadenceSource, DisplayManager.DisplayListener {

    @Volatile private var lastModeId: Int = display.mode.modeId
    @Volatile private var rest: Float = display.mode.refreshRate
    @Volatile private var lastChange: Long? = null
    private val changeEvents = MutableSharedFlow<Long>(extraBufferCapacity = 8)

    // `Display.getRefreshRate()` reports the rate the app is being driven at, which
    // can be a divisor of the panel's; only the mode says what the panel is doing.
    override val physicalHz: Float get() = display.mode.refreshRate
    override val restHz: Float get() = rest
    override val lastChangeAtMs: Long? get() = lastChange
    override val changes: Flow<Long> = changeEvents.asSharedFlow()

    override fun switchWouldBlank(requestedHz: Float): Boolean {
        val mode = display.mode
        val sameSizeModeHz = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }
            .toFloatArray()
        val alternativeHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mode.alternativeRefreshRates
        } else {
            FloatArray(0)
        }
        val preference = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            displayManager.matchContentFrameRateUserPreference
        } else {
            null
        }
        return switchBlanks(alternativeHz, sameSizeModeHz, preference, requestedHz)
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != display.displayId) return
        val mode = display.mode
        if (mode.modeId == lastModeId) return
        lastModeId = mode.modeId
        val at = now()
        lastChange = at
        // Re-sampled only while nothing is pinned, so an app started while the panel
        // sat at a film cadence learns its real rest rate on the first release.
        if (window.attributes.preferredRefreshRate == 0f) rest = mode.refreshRate
        changeEvents.tryEmit(at)
    }
}

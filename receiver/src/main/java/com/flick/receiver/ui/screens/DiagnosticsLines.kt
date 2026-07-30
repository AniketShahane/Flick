package com.flick.receiver.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.flick.receiver.util.FlickLog

/** Newest diagnostics lines rendered on the TV; the ring buffer itself holds 200. */
private const val DIAGNOSTICS_VISIBLE = 14

/**
 * The newest diagnostics lines, with the subscription to `FlickLog.revision` held
 * HERE rather than at the root of the TV app.
 *
 * `ReceiverApp` used to collect that flow in its own body. The revision is bumped on
 * every recorded line at every level — only the logcat emission is `DEBUG`-gated —
 * so a 1400-line composable re-executed once per log line, in release, for a value
 * one collapsed panel inside Settings consumes. `ControlServer` logs once per
 * malformed pre-auth frame, which made that an amplifier as well as a cost: an
 * unauthenticated LAN peer could drive recomposition of the whole TV UI at will.
 *
 * Two things follow from where the call now sits. It is inside the standby
 * surface's own composable lambda, so an invalidation reaches that subtree instead
 * of the root; and [visible] is checked BEFORE the flow is collected, so with the
 * panel closed — the default, and the state a TV spends its life in — nothing is
 * subscribed at all and a log storm recomposes nothing.
 *
 * A fresh immutable list per revision is deliberate rather than a mutable snapshot
 * list mutated in place: `SettingsScreen` folds this value into the layout epoch
 * that drives its `bringIntoView` placement, and an identity that never changes
 * while its contents do would leave that epoch comparing equal to a stale one.
 */
@Composable
internal fun rememberDiagnosticsLines(visible: Boolean): List<FlickLog.Entry> {
    if (!visible) return emptyList()
    val revision by FlickLog.revision.collectAsState()
    return remember(revision) { FlickLog.recent().take(DIAGNOSTICS_VISIBLE) }
}

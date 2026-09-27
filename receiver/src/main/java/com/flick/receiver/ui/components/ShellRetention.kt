package com.flick.receiver.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * True while a standby or error face is retained under the lights-down curtain
 * after its stage has ended. A retained face is on its way out and is never
 * re-activated — a return to standby composes a fresh one — so it must:
 *
 * - freeze its loops without a phase jump: the idle drift holds the phase it last
 *   drew, and a pulsing live dot stops where its envelope is;
 * - issue no focus requests.
 */
val LocalShellRetained = staticCompositionLocalOf { false }

/**
 * A shell face, or one standby surface, that takes focus and semantics only while
 * [interactive].
 *
 * Revoke only: the outermost focusProperties write wins, so a write of true here
 * would override a descendant's false.
 */
@Composable
internal fun ShellGate(interactive: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .focusProperties { if (!interactive) canFocus = false }
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
        CompositionLocalProvider(LocalShellInteractive provides interactive, content = content)
    }
}

/** Whether the standby surface drawn as [rendered] is the live one: the current surface, under a live host. */
internal fun <S> standbySurfaceInteractive(rendered: S, current: S?, hostInteractive: Boolean): Boolean =
    rendered == current && hostInteractive

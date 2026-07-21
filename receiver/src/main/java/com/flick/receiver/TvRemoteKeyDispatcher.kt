package com.flick.receiver

import android.view.KeyEvent

/** Synchronous bridge from Activity.dispatchKeyEvent into the current Compose state. */
internal class TvRemoteKeyDispatcher {
    private var handler: ((KeyEvent) -> Boolean)? = null

    fun attach(handler: (KeyEvent) -> Boolean) {
        this.handler = handler
    }

    fun detach(handler: (KeyEvent) -> Boolean) {
        if (this.handler === handler) this.handler = null
    }

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}

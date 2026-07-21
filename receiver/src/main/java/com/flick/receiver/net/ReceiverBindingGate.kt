package com.flick.receiver.net

/** Keeps server reconciliation from reviving control while the decoder is stopped. */
class ReceiverBindingGate(initiallyForeground: Boolean) {
    private var foreground = initiallyForeground

    fun onForeground() { foreground = true }
    fun onBackground() { foreground = false }
    fun mayBindOrAdvertise(): Boolean = foreground
}

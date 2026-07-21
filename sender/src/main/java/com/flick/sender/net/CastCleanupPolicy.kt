package com.flick.sender.net

/** A dispatched load can race with local cancellation; its receiver must see stop. */
internal object CastCleanupPolicy {
    fun shouldSendStop(castId: String, loadSentCastId: String?) = castId == loadSentCastId
}

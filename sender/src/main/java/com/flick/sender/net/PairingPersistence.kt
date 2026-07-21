package com.flick.sender.net

/** Prevents UI/auth progression when a durable pairing write is rejected. */
internal object PairingPersistence {
    inline fun commit(write: () -> Boolean): Boolean = write()
}

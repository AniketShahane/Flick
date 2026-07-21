package com.flick.receiver.net

import java.util.concurrent.Semaphore

/** Small non-blocking cap for unauthenticated sockets. */
class ConnectionPermitGate(maxConnections: Int) {
    private val permits = Semaphore(maxConnections, true)
    fun tryAcquire() = permits.tryAcquire()
    fun release() = permits.release()
}

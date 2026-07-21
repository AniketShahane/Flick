package com.flick.sender

import java.util.concurrent.atomic.AtomicReference

/** Publishes immutable media source/token pairs as one value so request threads cannot observe a half-retarget. */
internal class AtomicMediaSession<T : Any> {
    private val reference = AtomicReference<T?>(null)
    fun publish(value: T) = reference.set(value)
    fun snapshot(): T? = reference.get()
    fun clear() = reference.set(null)
}

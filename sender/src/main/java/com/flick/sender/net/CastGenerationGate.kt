package com.flick.sender.net

/** Small pure gate used by asynchronous service/control callbacks. */
class CastGenerationGate {
    private var generation = 0L
    private var castId: String? = null
    fun begin(id: String): Long { generation += 1; castId = id; return generation }
    fun invalidate(id: String): Boolean = if (castId == id) { generation += 1; castId = null; true } else false
    fun isCurrent(id: String, token: Long): Boolean = castId == id && generation == token
    fun current(): String? = castId
}

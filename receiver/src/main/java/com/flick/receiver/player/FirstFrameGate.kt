package com.flick.receiver.player

/**
 * Arms exactly one Media3 timeline media ID. A renderer event without that ID is
 * ignored rather than being attributed to whichever cast happens to be current.
 */
class FirstFrameGate {
    private var expectedMediaId: String? = null

    fun arm(mediaId: String) { expectedMediaId = mediaId }
    fun clear() { expectedMediaId = null }
    fun consumeIfMatches(eventMediaId: String?): Boolean {
        if (eventMediaId == null || eventMediaId != expectedMediaId) return false
        expectedMediaId = null
        return true
    }
}

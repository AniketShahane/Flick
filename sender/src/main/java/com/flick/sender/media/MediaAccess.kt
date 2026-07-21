package com.flick.sender.media

/** The video-library access Android currently grants to Flick. */
enum class MediaAccess {
    NONE,
    PARTIAL,
    FULL;

    val canReselect: Boolean get() = this == PARTIAL

    companion object {
        fun fromGrants(fullGranted: Boolean, partialGranted: Boolean): MediaAccess = when {
            fullGranted -> FULL
            partialGranted -> PARTIAL
            else -> NONE
        }
    }
}

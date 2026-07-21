package com.flick.sender.media

/** Identifies one MediaStore query and the permission snapshot that authorized it. */
internal data class MediaLibraryLoad(val access: MediaAccess, val generation: Long)

/** Prevents a delayed MediaStore result from publishing after access changes or a newer refresh starts. */
internal class MediaLibraryLoadGate {
    private var generation = 0L
    private var latest: MediaLibraryLoad? = null

    @Synchronized
    fun begin(access: MediaAccess): MediaLibraryLoad =
        MediaLibraryLoad(access, ++generation).also { latest = it }

    @Synchronized
    fun runIfLatest(load: MediaLibraryLoad, action: () -> Unit): Boolean {
        if (latest != load) return false
        action()
        return true
    }
}

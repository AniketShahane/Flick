package com.flick.sender.media

internal enum class MediaLibraryAction { HIDDEN, SELECT_MORE, REFRESH }

/** Full access already includes every MediaStore video; partial access must reopen system selection. */
internal object MediaLibraryActionPolicy {
    fun forAccess(access: MediaAccess): MediaLibraryAction = when (access) {
        MediaAccess.NONE -> MediaLibraryAction.HIDDEN
        MediaAccess.PARTIAL -> MediaLibraryAction.SELECT_MORE
        MediaAccess.FULL -> MediaLibraryAction.REFRESH
    }
}

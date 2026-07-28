package com.flick.sender.media

/**
 * One MediaStore bucket that holds at least one video Flick can currently see.
 *
 * [videoCount] counts exactly those rows and nothing else: Flick never enumerates the
 * folder itself, so a film sitting in it that MediaStore has not indexed — or that a
 * partial grant is withholding — is not counted, because it is not there to be listed.
 */
data class LibraryFolder(val id: Long, val name: String, val videoCount: Int)

/**
 * The folder the user pointed Flick at, remembered by bucket id and by the name it
 * carried when they picked it. The name is stored alongside the id because a folder
 * that has gone can only be named from what it was called: the live row that would
 * name it is precisely the thing that is missing.
 */
data class LibraryFolderChoice(val id: Long, val name: String)

/** What the library is scoped to. */
sealed interface LibraryScope {
    /** No folder chosen — every video this phone has shared with Flick. */
    data object All : LibraryScope

    /** A folder that is present in the library as it stands right now. */
    data class Folder(val id: Long, val name: String) : LibraryScope

    /**
     * A folder that was chosen and is no longer among the ones the library can see.
     *
     * Under full access that is deleted, renamed (a bucket id is derived from the path, so
     * a rename produces a different folder), or on storage that has been removed — which
     * of those is not knowable from here, and the copy that states it says so. Under a
     * partial grant it is one thing and knowable: the videos the user selected include
     * nothing from that folder. This type carries the name and no cause, because the level
     * of access is not a fact about the folder, and the screen that has it in hand is the
     * one that picks between the two cards.
     */
    data class Missing(val name: String) : LibraryScope
}

/**
 * Folder scoping, kept pure so every rule is unit testable: `MediaItem` holds an
 * `android.net.Uri` and cannot be built on the JVM, so — as in `LibraryFilterPolicy` —
 * the rules read the columns they need through projections instead of whole items.
 *
 * Nothing here consults `Build.VERSION`. It does not have to: below API 29 no row ever
 * carries a bucket id, so [derive] returns nothing and [chooserOffered] hides the
 * control on exactly the releases where the platform has no answer to give.
 */
internal object LibraryFolders {

    /**
     * Every folder the library can currently offer, ordered by name.
     *
     * A bucket whose rows all withheld a display name is left out: a folder Flick
     * cannot name is a folder it cannot put in front of the user, and its files stay
     * listed under [LibraryScope.All] exactly as they were. Rows with no bucket at all
     * are counted towards no folder for the same reason — inventing one would claim a
     * place on the phone that nothing reported.
     */
    fun <T> derive(
        items: List<T>,
        bucketId: (T) -> Long?,
        bucketName: (T) -> String?,
    ): List<LibraryFolder> {
        val counts = LinkedHashMap<Long, Int>()
        val names = HashMap<Long, String>()
        items.forEach { item ->
            val id = bucketId(item) ?: return@forEach
            counts[id] = (counts[id] ?: 0) + 1
            val name = bucketName(item)?.trim()
            if (!name.isNullOrEmpty()) names.getOrPut(id) { name }
        }
        return counts.mapNotNull { (id, count) ->
            names[id]?.let { LibraryFolder(id, it, count) }
        }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    /**
     * The scope a stored [chosen] folder resolves to against the folders that exist.
     *
     * [resolved] is whether a MediaStore query has actually run to its end and published
     * these folders. "That folder is gone" is a claim about the phone's storage, and only
     * a query that completed can support it — before the first one lands the stored
     * choice is taken at its word, or every cold start would accuse the user's folder
     * of having vanished for as long as the library takes to read.
     *
     * A query that began and then failed partway is not one of those either: it returns
     * rows newest first, so the [folders] a broken walk never reached are absent for a
     * reason that has nothing to do with the phone. `MediaLibrary.Read` reports which kind
     * of read produced this list precisely so that difference survives the trip here.
     */
    fun scope(
        chosen: LibraryFolderChoice?,
        folders: List<LibraryFolder>,
        resolved: Boolean,
    ): LibraryScope {
        if (chosen == null) return LibraryScope.All
        // Named from the live folder, never from the stored record: they agree today,
        // and if MediaStore ever starts reporting a new name for the same bucket the
        // one on screen should be the one the phone is using.
        folders.firstOrNull { it.id == chosen.id }?.let { return LibraryScope.Folder(it.id, it.name) }
        return if (resolved) LibraryScope.Missing(chosen.name) else LibraryScope.Folder(chosen.id, chosen.name)
    }

    /**
     * The items the library may list under [scope]. A [LibraryScope.Missing] folder
     * lists nothing: falling back to the whole library would quietly widen a scope the
     * user never widened, and the surface that shows this empty result is the one that
     * names the missing folder rather than a bare empty grid.
     */
    fun <T> scoped(items: List<T>, scope: LibraryScope, bucketId: (T) -> Long?): List<T> = when (scope) {
        LibraryScope.All -> items
        is LibraryScope.Folder -> items.filter { bucketId(it) == scope.id }
        is LibraryScope.Missing -> emptyList()
    }

    /**
     * Whether the folder control has anything to offer. One folder is the whole library
     * by another name, so the chooser stays out of the way until there are two to choose
     * between — but a scope already in force is always escapable, including the missing
     * one, whose only repair is reached through this control or the card that names it.
     */
    fun chooserOffered(folders: List<LibraryFolder>, scope: LibraryScope): Boolean =
        folders.size > 1 || scope != LibraryScope.All
}

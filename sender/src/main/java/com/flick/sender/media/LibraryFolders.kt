package com.flick.sender.media

/**
 * One folder in the tree the library can currently offer, and the videos beneath it.
 *
 * [id] is the folder's normalised `RELATIVE_PATH` — `Movies/Marvel/Phase 4` — which is
 * also the only identity a folder here is guaranteed to have: a folder that holds nothing
 * but other folders has no MediaStore bucket of its own, so a bucket id cannot name it.
 * [name] is its last segment and [depth] its distance from the top of that path, which is
 * what the chooser indents by.
 *
 * [videoCount] is DESCENDANT-INCLUSIVE: it counts the rows in this folder and in every
 * folder under it, because that is the set choosing it lists. It counts exactly those rows
 * and nothing else — Flick never enumerates the folder itself, so a film sitting in it
 * that MediaStore has not indexed, or that a partial grant is withholding, is not counted,
 * because it is not there to be listed.
 *
 * [bucketId] is the bucket of the rows sitting DIRECTLY in this folder, when it has any.
 * Nothing on screen uses it; it exists so a choice stored before Flick had a tree can be
 * migrated to the folder it always meant. Null is "no row here reported one", never "this
 * folder holds nothing".
 */
data class LibraryFolder(
    val id: String,
    val name: String,
    val depth: Int,
    val videoCount: Int,
    val bucketId: Long?,
)

/**
 * One line of the chooser as it currently stands.
 *
 * [visible] is whether the line is on screen — false while any folder above it is closed.
 * There is a row for every folder either way, because a line has to keep describing itself
 * for the frames it spends animating out; a list of only the visible ones would have
 * nothing to draw during its own exit.
 *
 * [expandable] is a fact about the tree and [expanded] a fact about the user, so a leaf is
 * never drawn with a control that would do nothing. [holdsChoice] is true only for a
 * PROPER ancestor of the chosen folder — the row that would otherwise be the one place a
 * collapsed tree can hide the answer to "which folder am I on".
 */
data class LibraryFolderRow(
    val folder: LibraryFolder,
    val visible: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    val holdsChoice: Boolean,
)

/**
 * How a remembered folder names the place it points at.
 *
 * The two forms are not interchangeable and are not a preference: [Path] is what Flick
 * writes, and [Bucket] is only ever READ — a record left by the version whose chooser was
 * a flat list of MediaStore buckets. It is carried rather than discarded so a user who
 * picked a folder before this tree existed keeps it, and it is rewritten to a [Path] by
 * the first library that can say which folder that bucket is.
 */
sealed interface LibraryFolderId {
    /** A folder of the tree, named by its normalised relative path. */
    data class Path(val path: String) : LibraryFolderId

    /** A folder named only by the MediaStore bucket its files sit in. */
    data class Bucket(val bucketId: Long) : LibraryFolderId
}

/**
 * The folder the user pointed Flick at, remembered by [id] and by the name it carried
 * when they picked it. The name is stored alongside the id because a folder that has gone
 * can only be named from what it was called: the live row that would name it is precisely
 * the thing that is missing.
 */
data class LibraryFolderChoice(val id: LibraryFolderId, val name: String)

/** What the library is scoped to. */
sealed interface LibraryScope {
    /** No folder chosen — every video this phone has shared with Flick. */
    data object All : LibraryScope

    /**
     * A folder that is present in the library as it stands right now, and everything
     * nested under it.
     */
    data class Folder(val id: LibraryFolderId, val name: String) : LibraryScope

    /**
     * A folder that was chosen and is no longer among the ones the library can see.
     *
     * Under full access that is deleted, renamed, or on storage that has been removed —
     * which of those is not knowable from here, and the copy that states it says so. Under
     * a partial grant it is one thing and knowable: the videos the user selected include
     * nothing from that folder. This type carries the name and no cause, because the level
     * of access is not a fact about the folder, and the screen that has it in hand is the
     * one that picks between the two cards.
     */
    data class Missing(val name: String) : LibraryScope
}

/**
 * Folder scoping, kept pure so every rule is unit testable: `MediaItem` holds an
 * `android.net.Uri` and cannot be built on the JVM, so the rules read the columns they
 * need through projections instead of whole items.
 *
 * Nothing here consults `Build.VERSION`. It does not have to: below API 29 no row ever
 * carries a relative path, so [derive] returns nothing and [chooserOffered] hides the
 * control on exactly the releases where the platform has no answer to give.
 *
 * The tree is one volume's, because the query behind it names one: `MediaLibrary` reads
 * `EXTERNAL_CONTENT_URI`, so two folders sharing a relative path on two volumes is not a
 * collision that can arrive here.
 */
internal object LibraryFolders {

    /**
     * A `RELATIVE_PATH` reduced to the form the tree keys on, or null when the row named
     * no folder at all.
     *
     * MediaStore hands these out with a trailing separator and, on some providers, a
     * leading one; empty segments are dropped rather than becoming a nameless level.
     * A row that normalises to null is not lost — it belongs to no folder, and
     * [LibraryScope.All] still lists it.
     */
    fun normalized(path: String?): String? {
        if (path == null) return null
        val segments = path.split('/').mapNotNull { segment -> segment.trim().takeIf { it.isNotEmpty() } }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    /**
     * The folder tree the library can currently offer, in the order it is rendered:
     * depth-first, siblings by name without letting case decide.
     *
     * Every ancestor of a path is a folder in its own right, including one that holds no
     * files of its own — `Movies` is offered when the only videos on the phone are in
     * `Movies/Marvel`, because "everything under Movies" is a scope the user can mean.
     * A row whose path normalises to nothing joins no folder, exactly as a row with no
     * bucket did: inventing one would claim a place on the phone that nothing reported.
     */
    fun <T> derive(
        items: List<T>,
        relativePath: (T) -> String?,
        bucketId: (T) -> Long?,
    ): List<LibraryFolder> {
        val counts = HashMap<String, Int>()
        val buckets = HashMap<String, Long>()
        items.forEach { item ->
            val path = normalized(relativePath(item)) ?: return@forEach
            bucketId(item)?.let { bucket -> if (!buckets.containsKey(path)) buckets[path] = bucket }
            var node = ""
            path.split('/').forEach { segment ->
                node = if (node.isEmpty()) segment else "$node/$segment"
                counts[node] = (counts[node] ?: 0) + 1
            }
        }
        return counts.keys
            .map { it to it.split('/') }
            .sortedWith(compareBy(SegmentOrder) { it.second })
            .map { (path, segments) ->
                LibraryFolder(
                    id = path,
                    name = segments.last(),
                    depth = segments.size - 1,
                    videoCount = counts.getValue(path),
                    bucketId = buckets[path],
                )
            }
    }

    /**
     * Segment by segment, so a folder always sorts immediately before the subtree it
     * holds: a parent's segments are a prefix of every descendant's, and a prefix that
     * runs out compares first. Case is not allowed to decide the order — "camera" must
     * not sort past "Films" — but it does break a tie, so two folders differing only in
     * case still have one stable order.
     */
    private val SegmentOrder = Comparator<List<String>> { a, b ->
        var i = 0
        while (i < a.size && i < b.size) {
            val byName = a[i].compareTo(b[i], ignoreCase = true)
            if (byName != 0) return@Comparator byName
            val exact = a[i].compareTo(b[i])
            if (exact != 0) return@Comparator exact
            i++
        }
        a.size - b.size
    }

    /** Every proper ancestor of a path, outermost first: `a/b/c` → `a`, `a/b`. */
    fun ancestorsOf(id: String): List<String> {
        val segments = id.split('/')
        if (segments.size < 2) return emptyList()
        return (1 until segments.size).map { segments.take(it).joinToString("/") }
    }

    /**
     * The chooser's lines, given which folders the user has opened.
     *
     * A line is visible only when every ancestor of it is open, so a closed branch takes
     * its whole subtree with it however deep that goes — one row is never left behind
     * because its own parent happened to be open. [folders] arrives in depth-first order
     * and that order is preserved, so a child is always drawn directly under the folder
     * that holds it.
     *
     * [expanded] is not pruned when a branch closes: reopening it restores the shape the
     * user last left it in rather than starting flat again. That also means the set may
     * name folders no longer in the tree — a stale id simply never matches, which is what
     * makes it safe to hold one across a library reload.
     */
    fun rows(
        folders: List<LibraryFolder>,
        expanded: Set<String>,
        chosen: LibraryFolderId?,
    ): List<LibraryFolderRow> {
        val parents = folders.mapNotNullTo(HashSet()) {
            it.id.substringBeforeLast('/', "").takeIf(String::isNotEmpty)
        }
        val chosenPath = (chosen as? LibraryFolderId.Path)?.path
        return folders.map { folder ->
            LibraryFolderRow(
                folder = folder,
                visible = ancestorsOf(folder.id).all { it in expanded },
                expandable = folder.id in parents,
                expanded = folder.id in expanded,
                holdsChoice = chosenPath != null &&
                    folder.id != chosenPath &&
                    holds(folder.id, chosenPath),
            )
        }
    }

    /**
     * Which folders a freshly opened chooser starts with open.
     *
     * The ancestors of the chosen folder, so the row carrying the tick is on screen
     * without the user having to go looking for the choice they already made.
     *
     * And the single root, when there is one. A lone top-level folder is not a choice —
     * it is the library under another name — so collapsing it would open the sheet on one
     * row that hides every decision the sheet exists to offer. Two or more roots ARE a
     * choice, and those start closed, which is the whole point of the control.
     */
    fun initialExpansion(folders: List<LibraryFolder>, scope: LibraryScope): Set<String> {
        val open = HashSet<String>()
        val roots = folders.filter { it.depth == 0 }
        if (roots.size == 1) open += roots.first().id
        val chosen = (scope as? LibraryScope.Folder)?.id as? LibraryFolderId.Path
        chosen?.let { open += ancestorsOf(it.path) }
        return open
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
     *
     * A [LibraryFolderId.Bucket] choice resolves through the bucket a folder's own rows
     * reported, which is what turns an old record into a folder of this tree. Until a
     * library arrives that can do that the choice keeps scoping by bucket, so the window
     * between the two never widens the library the user narrowed.
     */
    fun scope(
        chosen: LibraryFolderChoice?,
        folders: List<LibraryFolder>,
        resolved: Boolean,
    ): LibraryScope {
        if (chosen == null) return LibraryScope.All
        val live = when (val id = chosen.id) {
            is LibraryFolderId.Path -> folders.firstOrNull { it.id == id.path }
            is LibraryFolderId.Bucket -> folders.firstOrNull { it.bucketId == id.bucketId }
        }
        // Named from the live folder, never from the stored record: they agree today,
        // and if MediaStore ever starts reporting a new name for the same folder the
        // one on screen should be the one the phone is using.
        live?.let { return LibraryScope.Folder(LibraryFolderId.Path(it.id), it.name) }
        return if (resolved) LibraryScope.Missing(chosen.name) else LibraryScope.Folder(chosen.id, chosen.name)
    }

    /**
     * The record a stored choice should be REWRITTEN to, or null when there is nothing to
     * rewrite. Only ever non-null for a bucket-keyed choice that [scope] has just located
     * in the tree: that is the one moment the phone can prove which folder the old record
     * meant, and letting it pass would leave the choice depending on a bucket for ever —
     * including in the folder it names, which a parent folder does not have.
     */
    fun migration(chosen: LibraryFolderChoice?, scope: LibraryScope): LibraryFolderChoice? {
        if (chosen == null || chosen.id !is LibraryFolderId.Bucket) return null
        val folder = scope as? LibraryScope.Folder ?: return null
        val path = folder.id as? LibraryFolderId.Path ?: return null
        return LibraryFolderChoice(path, folder.name)
    }

    /**
     * The items the library may list under [scope]. A folder holds a row when the row's
     * path IS the folder or lies under it, so choosing a parent lists everything nested
     * beneath it — the folder's own count says the same number.
     *
     * A [LibraryScope.Missing] folder lists nothing: falling back to the whole library
     * would quietly widen a scope the user never widened, and the surface that shows this
     * empty result is the one that names the missing folder rather than a bare empty grid.
     */
    fun <T> scoped(
        items: List<T>,
        scope: LibraryScope,
        relativePath: (T) -> String?,
        bucketId: (T) -> Long?,
    ): List<T> = when (scope) {
        LibraryScope.All -> items
        is LibraryScope.Folder -> when (val id = scope.id) {
            is LibraryFolderId.Path -> items.filter { holds(id.path, normalized(relativePath(it))) }
            is LibraryFolderId.Bucket -> items.filter { bucketId(it) == id.bucketId }
        }
        is LibraryScope.Missing -> emptyList()
    }

    /** Separator-aware on purpose: `Movies` must not swallow a sibling called `Movies HD`. */
    private fun holds(folder: String, path: String?): Boolean =
        path != null && (path == folder || path.startsWith("$folder/"))

    /**
     * Whether the folder control has anything to offer.
     *
     * A folder that already holds the whole library is the library by another name, and a
     * tree makes that easy to produce: videos that all live in `Movies/Marvel` put both
     * `Movies` and `Movies/Marvel` on offer, and picking either shows exactly what is on
     * screen. So the control appears when some folder would NARROW the library — which is
     * the same question the old flat chooser asked by counting buckets, asked of a shape
     * where counting rows is the only way to ask it.
     *
     * A scope already in force is always escapable, including the missing one, whose only
     * repair is reached through this control or the card that names it.
     */
    fun chooserOffered(folders: List<LibraryFolder>, scope: LibraryScope, itemCount: Int): Boolean =
        scope != LibraryScope.All || folders.any { it.videoCount < itemCount }
}

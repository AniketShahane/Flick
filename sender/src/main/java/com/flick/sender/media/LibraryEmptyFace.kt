package com.flick.sender.media

/**
 * Which empty library is on screen.
 *
 * Replaces the two-way `locked` boolean the empty state used to branch on. PARTIAL with
 * nothing selected and a read that never finished are a third and a fourth fact, and both
 * were rendering as "Nothing to flick yet" on phones full of films.
 */
enum class LibraryEmptyFace { NOTHING_HERE, NO_ACCESS, NOTHING_SELECTED, UNREADABLE }

/**
 * [complete] is `MediaLibrary.Read.complete` — whether the cursor was walked to its end —
 * and null where no read has landed yet. A read that stopped partway is missing exactly
 * the rows it never reached, so it cannot support any claim about what this phone does NOT
 * have; only a completed walk can. A read that has not answered yet supports even less:
 * FALSE is a finished read that fell short, and only that may raise [UNREADABLE]. Seeding
 * it false instead put "Flick couldn't read your gallery" on screen for the whole of every
 * healthy first read, which on a large library is a second or more of accusing Android of
 * a failure that was not happening.
 *
 * [itemCount] > 0 has no empty state at all, and the arm is here so the mapping is total
 * rather than depending on the caller having checked first.
 */
fun libraryEmptyFace(
    access: MediaAccess,
    itemCount: Int,
    complete: Boolean?,
): LibraryEmptyFace = when {
    access == MediaAccess.NONE -> LibraryEmptyFace.NO_ACCESS
    itemCount > 0 -> LibraryEmptyFace.NOTHING_HERE
    complete == false -> LibraryEmptyFace.UNREADABLE
    access == MediaAccess.PARTIAL -> LibraryEmptyFace.NOTHING_SELECTED
    else -> LibraryEmptyFace.NOTHING_HERE
}

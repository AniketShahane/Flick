package com.flick.sender.media

import android.content.Context

/**
 * The one library folder Flick remembers, in its own preference file.
 *
 * Deliberately not the store `SubtitleFolderStore` keeps: that one holds a SAF tree
 * grant for sidecar files, this one holds a MediaStore bucket id for what the library
 * lists, and a user may well have picked two different folders for the two jobs.
 *
 * `commit()` rather than `apply()`: this is one short record into a file of its own,
 * written from the tap that chose it, and it has to land before the process can be killed —
 * a scope that lost the tap that set it opens on the whole library at the next launch.
 * Unlike a pairing, whose write a cast start genuinely gates on, nothing here reads the
 * outcome: `chooseLibraryFolder` applies the choice either way, because a preference file
 * that refused the write is not a reason to ignore the tap the user just made.
 */
class LibraryFolderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_library", Context.MODE_PRIVATE)

    /**
     * The remembered choice, or null when there is none. A record carrying an id but no
     * name is discarded rather than half-honoured: a folder that later goes missing is
     * named from this record, and a scope that could only be reported as "that folder"
     * is worse than no scope at all.
     */
    fun choice(): LibraryFolderChoice? {
        if (!prefs.contains(FOLDER_ID)) return null
        val name = prefs.getString(FOLDER_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return LibraryFolderChoice(prefs.getLong(FOLDER_ID, 0L), name)
    }

    fun save(choice: LibraryFolderChoice): Boolean = prefs.edit()
        .putLong(FOLDER_ID, choice.id)
        .putString(FOLDER_NAME, choice.name)
        .commit()

    fun clear(): Boolean = prefs.edit().remove(FOLDER_ID).remove(FOLDER_NAME).commit()

    private companion object {
        const val FOLDER_ID = "library_bucket_id"
        const val FOLDER_NAME = "library_bucket_name"
    }
}

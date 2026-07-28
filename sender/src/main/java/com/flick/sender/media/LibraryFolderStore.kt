package com.flick.sender.media

import android.content.Context

/**
 * The one library folder Flick remembers, in its own preference file.
 *
 * Deliberately not the store `SubtitleFolderStore` keeps: that one holds a SAF tree
 * grant for sidecar files, this one holds the folder the library lists from, and a user
 * may well have picked two different folders for the two jobs.
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
     * The remembered choice, or null when there is none.
     *
     * A record carrying an identity but no name is discarded rather than half-honoured: a
     * folder that later goes missing is named from this record, and a scope that could
     * only be reported as "that folder" is worse than no scope at all.
     *
     * The path is read first because it is the identity Flick writes now, and a record
     * written before the chooser had a tree carries only a bucket id. That record is
     * returned as what it is rather than dropped — it names a folder the user picked, and
     * `LibraryFolders.migration` rewrites it as soon as a library can say which one.
     */
    fun choice(): LibraryFolderChoice? {
        val name = prefs.getString(FOLDER_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        prefs.getString(FOLDER_PATH, null)?.takeIf { it.isNotBlank() }?.let {
            return LibraryFolderChoice(LibraryFolderId.Path(it), name)
        }
        if (!prefs.contains(FOLDER_ID)) return null
        return LibraryFolderChoice(LibraryFolderId.Bucket(prefs.getLong(FOLDER_ID, 0L)), name)
    }

    /**
     * One identity per record, never both: a migrated choice that left its old bucket id
     * behind would be read back as a bucket the next time the path key was ever missing.
     */
    fun save(choice: LibraryFolderChoice): Boolean {
        val edit = prefs.edit().putString(FOLDER_NAME, choice.name)
        when (val id = choice.id) {
            is LibraryFolderId.Path -> edit.putString(FOLDER_PATH, id.path).remove(FOLDER_ID)
            is LibraryFolderId.Bucket -> edit.putLong(FOLDER_ID, id.bucketId).remove(FOLDER_PATH)
        }
        return edit.commit()
    }

    fun clear(): Boolean = prefs.edit().remove(FOLDER_ID).remove(FOLDER_PATH).remove(FOLDER_NAME).commit()

    private companion object {
        // The bucket key keeps its old name because an installed phone already has it
        // written under that name, and so does the name key beside it: a rename would
        // read as an empty file and lose the folder this store exists to keep.
        const val FOLDER_ID = "library_bucket_id"
        const val FOLDER_PATH = "library_folder_path"
        const val FOLDER_NAME = "library_bucket_name"
    }
}

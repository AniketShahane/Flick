package com.flick.sender.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.flick.sender.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real, on-device video gallery (design S3). Queries `MediaStore.Video` for
 * the user's own films — no mock data. Reads via the media collection the user
 * granted (READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below), and never opens
 * the byte stream just to list.
 */
object MediaLibrary {

    /**
     * One read of the collection: the rows it got, and whether it got all of them.
     *
     * [complete] is false when the provider declined to hand over a cursor at all, or when
     * the walk failed partway — a CursorWindow it could not allocate, a row too large to
     * deliver, a provider that died mid-iteration. The rows already in hand come back
     * regardless, because those files are on this phone and the user asked to see them.
     * What a partial read cannot do is support a claim about what is ABSENT: rows arrive
     * newest first, so the tail it drops is the oldest folder's, and `LibraryFolders.scope`
     * would read that absence as the folder having been deleted.
     */
    data class Read(val items: List<MediaItem>, val complete: Boolean)

    suspend fun query(context: Context): Read = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        // One gate for all three folder columns: they became public API for video in the
        // same release, and the library's folder feature is absent below it rather than
        // guessed at from a file path.
        val hasFolders = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val hasGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val mediaStoreVersion = if (hasGeneration) {
            runCatching { MediaStore.getVersion(context) }.getOrNull()
        } else {
            null
        }
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            // Read as well as sorted on: the grid can be re-dealt into other orders and
            // back, and the column the cursor was ordered by is the only honest source
            // for the one that says "recently added".
            add(MediaStore.Video.Media.DATE_ADDED)
            if (hasGeneration) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            if (hasFolders) {
                add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Video.Media.BUCKET_ID)
                add(MediaStore.Video.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaItem>()
        val complete = runCatching {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val modifiedCol = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val addedCol = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val generationCol = if (hasGeneration) {
                    c.getColumnIndex(MediaStore.MediaColumns.GENERATION_MODIFIED)
                } else {
                    -1
                }
                val wCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val bucketCol = if (hasFolders) {
                    c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                } else {
                    -1
                }
                val bucketIdCol = if (hasFolders) {
                    c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                } else {
                    -1
                }
                val pathCol = if (hasFolders) {
                    c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    -1
                }
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "Untitled",
                        // Absent, null and non-positive all collapse to the same zero.
                        // MediaStore reports a 0 duration and 0 pixels for files whose
                        // metadata it never managed to scan, so the UI can only withhold
                        // a claim it can recognise as missing in exactly one form.
                        durationMs = unsignedColumn(c, durCol),
                        sizeBytes = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else -1L,
                        dateModifiedSeconds = unsignedColumn(c, modifiedCol),
                        dateAddedSeconds = unsignedColumn(c, addedCol),
                        generationModified = generationColumn(c, generationCol),
                        mediaStoreVersion = mediaStoreVersion,
                        width = pixelColumn(c, wCol),
                        height = pixelColumn(c, hCol),
                        bucket = if (bucketCol >= 0) c.getString(bucketCol) else null,
                        bucketId = bucketColumn(c, bucketIdCol),
                        relativePath = if (pathCol >= 0) c.getString(pathCol) else null,
                    )
                }
                true
            }
            // A null cursor is the provider refusing to answer, which is not the same
            // answer as an empty gallery: only a cursor walked to its end can say what
            // this phone does not have.
            ?: false
        }.getOrDefault(false)
        Read(out, complete)
    }

    /**
     * A bucket id is an opaque hash of the folder's path, not a measurement, so unlike
     * the columns above it has no value that reads as "nothing was scanned" — only an
     * absent column or a null cell can say the row belongs to no folder Flick can group
     * it under. The column is public API for video from Q, which is the same gate the
     * display name is behind; below it the library simply has no folders to offer.
     */
    private fun bucketColumn(cursor: Cursor, index: Int): Long? =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null

    private fun generationColumn(cursor: Cursor, index: Int): Long? =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).takeIf { it >= 0L } else null

    private fun unsignedColumn(cursor: Cursor, index: Int): Long =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).coerceAtLeast(0L) else 0L

    /** Read as a Long first: a scanner that wrote garbage must not wrap into a claim. */
    private fun pixelColumn(cursor: Cursor, index: Int): Int =
        unsignedColumn(cursor, index).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

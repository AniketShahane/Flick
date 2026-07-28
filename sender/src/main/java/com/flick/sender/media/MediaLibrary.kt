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

    suspend fun query(context: Context): List<MediaItem> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val hasBucket = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            if (hasBucket) add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        }.toTypedArray()

        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaItem>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val wCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val bucketCol = if (hasBucket) {
                    c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
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
                        width = pixelColumn(c, wCol),
                        height = pixelColumn(c, hCol),
                        bucket = if (bucketCol >= 0) c.getString(bucketCol) else null,
                    )
                }
            }
        }
        out
    }

    private fun unsignedColumn(cursor: Cursor, index: Int): Long =
        if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index).coerceAtLeast(0L) else 0L

    /** Read as a Long first: a scanner that wrote garbage must not wrap into a claim. */
    private fun pixelColumn(cursor: Cursor, index: Int): Int =
        unsignedColumn(cursor, index).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

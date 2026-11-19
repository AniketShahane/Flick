package com.flick.sender.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.flick.sender.media.Still
import com.flick.sender.media.VideoStills
import com.flick.sender.model.MediaItem
import java.io.ByteArrayOutputStream

/**
 * Contract §6.4: the phone's own still for a file, as the TV's thumbnail. The same
 * [VideoStills] search and one-colour refusal the library tiles use, so the TV never shows a
 * frame the phone itself would not.
 */
internal object LibraryThumbs {

    /** A baseline JPEG of at most [LibraryFrames.THUMB_MAX_JPEG_BYTES], or null for a miss. Call off the main thread. */
    suspend fun jpeg(context: Context, item: MediaItem): ByteArray? {
        val still = VideoStills.still(
            context, item.uri, item.durationMs, LibraryThumbPolicy.SOURCE_BOX, LibraryThumbPolicy.SOURCE_BOX,
        ) as? Still.Picture ?: return null
        var source = still.bitmap
        var target: Bitmap? = null
        try {
            // A software canvas cannot read a hardware bitmap.
            if (source.config == Bitmap.Config.HARDWARE) {
                val copy = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                source.recycle()
                source = copy
            }
            val crop = LibraryThumbPolicy.crop(source.width, source.height)
            if (crop[2] <= 0 || crop[3] <= 0) return null
            val scaled = Bitmap.createBitmap(LibraryThumbPolicy.WIDTH, LibraryThumbPolicy.HEIGHT, Bitmap.Config.ARGB_8888)
            target = scaled
            Canvas(scaled).drawBitmap(
                source,
                Rect(crop[0], crop[1], crop[0] + crop[2], crop[1] + crop[3]),
                Rect(0, 0, LibraryThumbPolicy.WIDTH, LibraryThumbPolicy.HEIGHT),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            return LibraryThumbPolicy.encodeWithin(LibraryFrames.THUMB_MAX_JPEG_BYTES) { quality ->
                val stream = ByteArrayOutputStream()
                if (scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) stream.toByteArray() else null
            }
        } finally {
            source.recycle()
            target?.recycle()
        }
    }
}

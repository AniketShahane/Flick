package com.flick.sender

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Resolves display name and total byte size for a `content://` URI handed to us
 * by the Photo Picker. Sizes are needed for HTTP byte-range math; we NEVER read
 * the whole file to measure it (4K files can be tens of GB).
 */
object MediaMeta {

    data class Info(val displayName: String?, val size: Long)

    fun resolve(resolver: ContentResolver, uri: Uri): Info =
        Info(resolveName(resolver, uri), resolveSize(resolver, uri))

    fun resolveName(resolver: ContentResolver, uri: Uri): String? {
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) return c.getString(idx)
                }
            }
        } catch (_: Exception) {
            // Query can throw if the grant was revoked; fall through to null.
        }
        return uri.lastPathSegment
    }

    /**
     * Total size in bytes, or `-1` if it genuinely cannot be determined.
     * Tries OpenableColumns.SIZE first, then falls back to the descriptor length
     * / stat size.
     */
    fun resolveSize(resolver: ContentResolver, uri: Uri): Long {
        // 1) OpenableColumns.SIZE — the cheap, authoritative source for picker URIs.
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val size = c.getLong(idx)
                        if (size >= 0) return size
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2) Descriptor length, then the raw stat size of the backing fd.
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val declared = afd.length
                if (declared != AssetFileDescriptor.UNKNOWN_LENGTH && declared >= 0) return declared
                val stat = afd.parcelFileDescriptor.statSize
                if (stat >= 0) return stat
            }
        } catch (_: Exception) {
        }

        return -1L
    }
}

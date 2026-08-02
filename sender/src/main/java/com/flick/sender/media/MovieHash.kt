package com.flick.sender.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * The OpenSubtitles "moviehash": the 64-bit sum, with wraparound, of the file size and of
 * every little-endian 64-bit word of the first 64 KiB and the last 64 KiB of the file.
 *
 * It is what lets an online search name THIS release rather than the title, so a subtitle
 * the server flags as a hash match was timed against the very bytes being cast — no
 * guessing from a release name, and no re-syncing by hand. Flick has the local file, so
 * the hash costs two bounded reads.
 *
 * Those two 64 KiB windows are the whole of the I/O: nothing here walks the middle of a
 * 40 GB remux, and a descriptor that cannot be seeked is refused rather than streamed
 * through. Every function is pure except [of] with a [Context], which exists to keep the
 * arithmetic testable without a device.
 */
object MovieHash {

    /** The inseparable values OpenSubtitles uses to identify one exact byte stream. */
    data class Fingerprint(val hash: String, val sizeBytes: Long)

    /**
     * Below this the two windows would overlap and the hash is not defined. A file this
     * small — or one whose size nothing will state — has no hash, which the caller reads
     * as "run the text query" and never as "hash of zero".
     */
    const val MinBytes = 128L * 1024L

    private const val WindowBytes = 64 * 1024
    private const val WordBytes = 8

    /** 16 lowercase hex digits, zero-padded: the only form the API's `moviehash` accepts. */
    private val WellFormed = Regex("^[0-9a-f]{16}$")

    /**
     * The hash and exact [sizeBytes] of a file whose first and last windows are [head]
     * and [tail], or null when the inputs cannot produce one. A short window is rejected
     * rather than padded: a nearly-right hash is a wrong hash, and a wrong hash asks the
     * server about somebody else's file.
     */
    fun of(sizeBytes: Long, head: ByteArray, tail: ByteArray): Fingerprint? {
        if (sizeBytes < MinBytes) return null
        if (head.size != WindowBytes || tail.size != WindowBytes) return null
        // Long addition in Kotlin already wraps two's-complement, which is exactly the
        // 64-bit overflow the format is defined in terms of.
        val sum = sizeBytes + sumOfWords(head) + sumOfWords(tail)
        return Fingerprint(hash = format(sum), sizeBytes = sizeBytes)
    }

    /** True for a value this object could have produced; the guard before a request. */
    fun isWellFormed(hash: String): Boolean = WellFormed.matches(hash)

    /**
     * Reads the two windows through the same ContentResolver the media server reads the
     * file with. Null on anything at all — an unknown size, a file below [MinBytes], or a
     * provider handing back a non-seekable descriptor (a pipe, which no seek can rewind)
     * — because the caller's fallback is the text query and never a hash it made up.
     *
     * [sizeHint] is MediaStore's own size, used only when the descriptor will not state
     * one. The I/O runs on [Dispatchers.IO]: composition never waits on a disk read.
     */
    suspend fun of(context: Context, uri: Uri, sizeHint: Long = -1L): Fingerprint? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    val size = authoritativeSize(descriptor.statSize, sizeHint) ?: return@use null
                    if (size < MinBytes) return@use null
                    // Built from a descriptor Android does not let this stream own, so the
                    // ParcelFileDescriptor stays the single thing that closes the fd.
                    val channel = FileInputStream(descriptor.fileDescriptor).channel
                    val head = channel.window(0L)
                    val tail = channel.window(size - WindowBytes)
                    if (head == null || tail == null) null else of(size, head, tail)
                }
            }.getOrNull()
        }

    /** Descriptor truth wins; the MediaStore hint is used only when no size is stated. */
    internal fun authoritativeSize(statSize: Long, sizeHint: Long): Long? =
        statSize.takeIf { it > 0L } ?: sizeHint.takeIf { it > 0L }

    /** Little-endian 64-bit words, summed with the wraparound the format specifies. */
    private fun sumOfWords(window: ByteArray): Long {
        var sum = 0L
        var index = 0
        while (index + WordBytes <= window.size) {
            var word = 0L
            for (offset in 0 until WordBytes) {
                word = word or ((window[index + offset].toLong() and 0xFFL) shl (offset * 8))
            }
            sum += word
            index += WordBytes
        }
        return sum
    }

    /** `%x` renders a Long as unsigned two's-complement, which is the hash's own form. */
    private fun format(value: Long): String = String.format(Locale.ROOT, "%016x", value)

    /** Exactly one full window at [offset], or null when the descriptor will not give it. */
    private fun FileChannel.window(offset: Long): ByteArray? {
        val buffer = ByteBuffer.allocate(WindowBytes)
        position(offset)
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) return null
        }
        return buffer.array()
    }
}

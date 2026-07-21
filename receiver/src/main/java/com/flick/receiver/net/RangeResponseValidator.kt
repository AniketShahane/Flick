package com.flick.receiver.net

/** Pure HTTP contract validation used after redirects have been disabled. */
object RangeResponseValidator {
    fun expectedLength(status: Int, contentRange: String?, contentLength: Long): Long? {
        if (status != 206) return null
        val match = Regex("^bytes 0-([0-9]+)/(\\d+)$").matchEntire(contentRange ?: return null) ?: return null
        val end = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull() ?: return null
        val expected = end + 1
        return expected.takeIf { total > 0 && end == minOf(1023, total - 1) && contentLength == expected }
    }
}

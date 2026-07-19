package com.flick.sender.ui

import java.util.Locale

/** Formatting helpers shared across the phone UI. Timecodes are always tabular. */
object Format {

    /** ms → `H:MM:SS` (or `M:SS` under an hour). */
    fun timecode(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    /** ms → `2h 14m` style, for captions. */
    fun durationHuman(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0L)) / 60000L
        val h = totalMin / 60L
        val m = totalMin % 60L
        return if (h > 0L) "${h}h ${m}m" else "${m}m"
    }

    /** bytes → `8.4 GB`. */
    fun bytes(bytes: Long): String {
        if (bytes < 0L) return "—"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var i = 0
        while (value >= 1024.0 && i < units.size - 1) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    /** bits/s → `61.4 Mb/s`. */
    fun megabits(bitsPerSec: Long): String =
        String.format(Locale.US, "%.1f Mb/s", bitsPerSec / 1_000_000.0)
}

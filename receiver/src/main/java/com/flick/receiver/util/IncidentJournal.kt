package com.flick.receiver.util

import android.content.Context
import android.util.Log
import com.flick.receiver.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small on-disk record of what happened to casts and the link, one line per
 * event, that outlives the process — [FlickLog]'s ring does not, and a stall is
 * usually investigated after the app that saw it has been killed.
 *
 * Every line is built by [JournalLine], whose grammar rejects addresses, URLs and
 * free text. Even so, never pass an SSID, BSSID, hostname or device name, nor a
 * value derived from one, though some would fit the token grammar: the file is
 * meant to be pasted into a public bug report.
 *
 * Read with `adb shell dumpsys activity top` (MainActivity) or
 * `adb shell dumpsys activity service com.flick.receiver/.summon.CastReadyService`,
 * which works on a release build. Each line is also written to logcat as
 * [TAG], and the newest [REPLAY_LINES] are replayed there at [install].
 *
 * The file lives in `noBackupFilesDir`, so it is never backed up or transferred.
 */
object IncidentJournal {
    const val TAG = "FlickJournal"

    private const val FILE_NAME = "incident-journal.log"
    private const val KEEP_LINES = 600
    private const val REWRITE_ABOVE_LINES = 800
    private const val REPLAY_LINES = 100
    private const val DUMP_WAIT_MS = 2_000L

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "flick-journal").apply { isDaemon = true } }
    private val installed = AtomicBoolean(false)

    // Both touched only on [writer].
    private var file: File? = null
    private var lineCount = 0

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val dir = runCatching { context.applicationContext.noBackupFilesDir }.getOrNull()
        submit {
            val target = File(dir ?: return@submit, FILE_NAME)
            val existing = storedLines(target)
            file = target
            lineCount = existing.size
            for (line in existing.takeLast(REPLAY_LINES)) Log.i(TAG, "replay $line")
        }
        record("process_start", "version_code" to BuildConfig.VERSION_CODE)
    }

    /** Never blocks and never throws; a line recorded before [install] reaches logcat only. */
    fun record(event: String, vararg fields: Pair<String, Any?>) {
        runCatching {
            val line = JournalLine.format(System.currentTimeMillis(), event, fields.asList())
            Log.i(TAG, line)
            submit { append(line) }
        }
    }

    /** Every stored line, oldest first; called from a binder thread by `dumpsys`. */
    fun dump(out: PrintWriter) {
        val lines = runCatching {
            writer.submit(Callable { file?.let { storedLines(it) }.orEmpty() }).get(DUMP_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrElse { emptyList() }
        out.println("$TAG lines=${lines.size}")
        for (line in lines) out.println(line)
        out.flush()
    }

    private fun submit(work: () -> Unit) {
        runCatching { writer.execute { runCatching(work) } }
    }

    private fun append(line: String) {
        val target = file ?: return
        target.appendText(line + "\n")
        lineCount++
        if (lineCount <= REWRITE_ABOVE_LINES) return
        val kept = storedLines(target).takeLast(KEEP_LINES)
        val staging = File(target.parentFile, "$FILE_NAME.tmp")
        staging.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
        if (!staging.renameTo(target)) {
            staging.delete()
            return
        }
        lineCount = kept.size
    }

    private fun storedLines(target: File): List<String> =
        runCatching { if (target.exists()) target.readLines().filter { it.isNotEmpty() } else emptyList() }
            .getOrElse { emptyList() }
}

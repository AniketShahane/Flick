package com.flick.sender.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The incidents worth keeping past the process: one line per event, in a file under
 * `noBackupFilesDir`, so a cast that failed before a crash or a kill can still be read
 * back from a release build: `adb shell dumpsys activity com.flick.sender/.MainActivity`
 * while the activity exists, or `dumpsys activity service com.flick.sender/.CastServerService`
 * while a cast is served. Every line also goes to logcat under [TAG], and [install] replays
 * the newest stored ones there.
 *
 * [FlickLog]'s ring is the detailed channel and dies with the process; this is the sparse
 * one that does not. Lines are built by [IncidentLine], whose value shapes are the whole
 * redaction contract: never pass an SSID, a BSSID, a hostname, a device or TV name, or a
 * file name, even where one would fit.
 *
 * Recording returns at once and never throws: the file is written on one daemon thread,
 * and an IO failure loses the line, never the caller.
 */
object IncidentJournal {

    const val TAG = "FlickJournal"

    private const val FILE_NAME = "incident-journal.log"
    private const val KEEP_LINES = 600
    private const val TRIM_AT_LINES = 800
    private const val REPLAY_LINES = 100

    /** Lines recorded before [install] wait here, on the writer thread only. */
    private const val EARLY_LINES = 100
    private const val DUMP_WAIT_MS = 2_000L

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flick-journal").apply { isDaemon = true }
    }

    @Volatile private var installed = false

    // Writer thread only.
    private var file: File? = null
    private var lineCount = 0
    private val early = ArrayDeque<String>()

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val dir = runCatching { context.applicationContext.noBackupFilesDir }.getOrNull() ?: return
        submit {
            val target = File(dir, FILE_NAME)
            val stored = runCatching { if (target.exists()) target.readLines() else emptyList() }.getOrDefault(emptyList())
            for (line in stored.takeLast(REPLAY_LINES)) Log.i(TAG, "replay $line")
            file = target
            lineCount = stored.size
            while (early.isNotEmpty()) append(early.removeFirst())
        }
    }

    fun record(event: String, vararg fields: Pair<String, Any?>) {
        val line = runCatching { IncidentLine.format(System.currentTimeMillis(), event, fields) }.getOrNull() ?: return
        runCatching { Log.i(TAG, line) }
        submit {
            if (file == null) {
                early.addLast(line)
                while (early.size > EARLY_LINES) early.removeFirst()
            } else {
                append(line)
            }
        }
    }

    /** Every stored line, oldest first, after whatever is still queued has been written. */
    fun dump(out: PrintWriter) {
        val snapshot = AtomicReference<List<String>?>(null)
        val done = CountDownLatch(1)
        submit {
            try {
                val lines = ArrayList<String>()
                file?.let { target -> runCatching { if (target.exists()) lines.addAll(target.readLines()) } }
                lines.addAll(early)
                snapshot.set(lines)
            } finally {
                done.countDown()
            }
        }
        runCatching { done.await(DUMP_WAIT_MS, TimeUnit.MILLISECONDS) }
        val lines = snapshot.get()
        if (lines == null) {
            out.println("Flick incident journal unavailable")
            return
        }
        out.println("Flick incident journal (${lines.size} lines)")
        for (line in lines) out.println(line)
    }

    private fun append(line: String) {
        val target = file ?: return
        runCatching {
            target.appendText(line + "\n")
            lineCount += 1
            if (lineCount > TRIM_AT_LINES) {
                val kept = target.readLines().takeLast(KEEP_LINES)
                val scratch = File(target.parentFile, "$FILE_NAME.tmp")
                scratch.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
                if (!scratch.renameTo(target)) {
                    target.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
                    scratch.delete()
                }
                lineCount = kept.size
            }
        }
    }

    private fun submit(task: () -> Unit) {
        runCatching { writer.execute { runCatching(task) } }
    }
}

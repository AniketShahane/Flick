package com.flick.sender.media

import android.content.Context
import android.net.Uri
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.sender.net.ControlProtocolV2
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val SUBTITLE_MEMORY_DATASTORE_NAME = "flick_subtitle_memory"

/** Under `filesDir` and never `cacheDir` — see [SubtitleMemoryStore]. */
internal const val SUBTITLE_MEMORY_DIR = "subtitle_memory"

/**
 * Its own file, for the reasons `AudioDelayMemory`'s header gives, plus one that belongs
 * to this store alone: it is the only one whose records name something OUTSIDE themselves.
 * A record here asserts that a file exists in `subtitle_memory/`, so the corruption
 * handler's empty replacement does not merely cost a feature — it strands every copy the
 * store was accounting for. That is what the sweep in [SubtitleMemoryStore] is for, and
 * why it runs unconditionally at launch rather than as a repair somebody has to notice.
 */
private val Context.subtitleMemoryDataStore by preferencesDataStore(
    name = SUBTITLE_MEMORY_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)

/**
 * The subtitle one film was last watched with.
 *
 * There is deliberately no path here. The copy lives at one derivable location, so
 * [SubtitleMemoryPolicy.fileName] answers where it is from the film's own identity and
 * the record only says which of [SubtitleFiles.SubtitleExtensions] it was saved under. A
 * stored path would be the one field able to name a file this feature never wrote, and
 * `/s/{token}` serves whatever the selection points at — so a record that had acquired
 * this app's own prefs path would put the pairing keys on the LAN. Deriving makes that
 * unrepresentable instead of something a validator has to keep catching.
 *
 * [sizeBytes] is what the copy weighed when it landed, and it is load-bearing twice: the
 * byte budget is summed from it, and a recall compares it against the file on disk, so a
 * copy a process death truncated cannot be re-attached as though it were whole.
 */
internal data class SubtitleMemoryRecord(
    val extension: String,
    val displayName: String,
    val language: String?,
    val sizeBytes: Long,
    val updatedAtEpochMs: Long,
)

internal sealed interface SubtitleMemoryState {
    data object Loading : SubtitleMemoryState
    data class Ready(val subtitles: Map<String, SubtitleMemoryRecord>) : SubtitleMemoryState
}

internal object SubtitleMemoryPolicy {

    /**
     * The same hundred films the resume and nudge stores keep, for the same reason: a
     * store that grows without a bound is a file that gets slower to rewrite for the rest
     * of the install's life, and Preferences DataStore rewrites all of it every time.
     */
    const val MAX_RECORDS = 100

    /**
     * And a second bound, because a count alone does not bound THIS store.
     *
     * The other two keep numbers; this one keeps files, and `SubtitleFiles.MaxSubtitleBytes`
     * lets one weigh 5 MiB — so a hundred records has a ceiling of 500 MB of somebody's
     * phone spent on films they last opened a year ago. An ordinary subtitle for a feature
     * is 40–150 KB, so a hundred remembered films is nearer 10 MB and never reaches this
     * number at all; 24 MiB is a hundred records at 245 KB each, which is well above any
     * subtitle that is only cues. What it bounds is the pathological store — a library of
     * styled .ass files, or one 5 MiB subtitle — where the count stops binding first.
     */
    const val MAX_TOTAL_BYTES = 24L * 1024L * 1024L

    /** What a copy is called while it is still being written. */
    const val TEMP_SUFFIX = ".part"

    /**
     * The temp one attempt at [name] writes through, told apart by its own [ticket].
     *
     * Unique per attempt and not one name per film, because a copy runs OUTSIDE the
     * store's lock: two picks for one film can be reading at the same moment, and a
     * shared temp would have them writing into each other's bytes and renaming whichever
     * mixture finished last into place.
     */
    fun tempFileName(name: String, ticket: Long): String = "$name$TEMP_SUFFIX$ticket"

    /**
     * Where the copy for [fingerprint] lives, or null when that identity could not be a
     * file name.
     *
     * [PlaybackMediaFingerprint] is URL-safe Base64 without padding, whose whole alphabet
     * is `A-Za-z0-9-_`, so a fingerprint is already legal here. The guard is not doubt
     * about that: this name is also what an eviction DELETES, and the keys it is built
     * from are read back out of a file on disk. A key that had acquired a separator or a
     * `..` would otherwise aim a delete at a path of the store's own choosing.
     */
    fun fileName(fingerprint: String, extension: String): String? {
        if (extension !in SubtitleFiles.SubtitleExtensions) return null
        if (fingerprint.isEmpty() || fingerprint.length > MAX_FINGERPRINT_CHARS) return null
        if (!fingerprint.all(::fileNameSafe)) return null
        return "$fingerprint.$extension"
    }

    /**
     * Every name [fileName] could ever have answered for [fingerprint], and deliberately
     * none of the temps.
     *
     * This is the list a removal deletes. A temp now belongs to an attempt that is still
     * reading — its rename would find nothing left to move — so a removal that reached
     * one would fail a pick made AFTER it, which is the very ordering the ticket exists
     * to get right. A temp nobody finished is the launch sweep's to collect.
     */
    fun copyFileNames(fingerprint: String): List<String> =
        SubtitleFiles.SubtitleExtensions.mapNotNull { fileName(fingerprint, it) }

    /**
     * Whether a mutation issued as [ticket] may still land on a film whose last landed
     * mutation was [landed] — null when none has yet, this process.
     *
     * The copy runs outside the store's lock, so a mutation can reach the point of
     * committing long after a later one already did — an attach whose provider read took
     * a minute, arriving behind the detach the viewer made while waiting for it. Ordering
     * by when a gesture was MADE rather than by when its IO finished is what keeps the
     * store agreeing with the last thing the viewer actually asked for.
     */
    fun landable(ticket: Long, landed: Long?): Boolean = landed == null || ticket > landed

    /**
     * Whether [record] is one this app could have written.
     *
     * The label and the tag are compared against what the normalizer would produce rather
     * than merely checked for shape, because `selectSubtitle` stores the output of exactly
     * those two calls — so a value they would change is a value this app did not write.
     * Refused rather than repaired, following `AudioDelayMemoryPolicy.storable`: a record
     * that fails is absent, and re-attaching is one tap.
     */
    fun storable(record: SubtitleMemoryRecord): Boolean =
        record.extension in SubtitleFiles.SubtitleExtensions &&
            record.displayName == ControlProtocolV2.normalizedLabel(
                record.displayName,
                ControlProtocolV2.SUBTITLE_LABEL_MAX,
            ) &&
            record.language == ControlProtocolV2.languageTag(record.language) &&
            record.sizeBytes in 1L..SubtitleFiles.MaxSubtitleBytes &&
            record.updatedAtEpochMs >= 0L

    /**
     * Which stored films a record of [incomingBytes] displaces. [existing] is every OTHER
     * record — the caller's own key is excluded, so re-picking a subtitle for a film that
     * already has one spends nobody else's room.
     *
     * Oldest first by [SubtitleMemoryRecord.updatedAtEpochMs], and both bounds are applied
     * to the same list in one pass: dropping until the count fits and then separately
     * until the bytes fit would evict two different films for what is one shortage.
     */
    fun evicted(existing: List<Pair<String, SubtitleMemoryRecord>>, incomingBytes: Long): List<String> {
        var count = existing.size + 1
        var bytes = existing.sumOf { it.second.sizeBytes } + incomingBytes
        if (count <= MAX_RECORDS && bytes <= MAX_TOTAL_BYTES) return emptyList()
        val dropped = mutableListOf<String>()
        for ((key, record) in existing.sortedBy { it.second.updatedAtEpochMs }) {
            if (count <= MAX_RECORDS && bytes <= MAX_TOTAL_BYTES) break
            dropped += key
            count--
            bytes -= record.sizeBytes
        }
        return dropped
    }

    /**
     * A SHA-256 in this encoding is 43 characters. The bound is generous rather than
     * exact so that changing the digest cannot silently retire the feature, and narrow
     * enough that no name here approaches a filesystem's own limit.
     */
    private const val MAX_FINGERPRINT_CHARS = 64

    private fun fileNameSafe(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '_'
}

/**
 * Per-field percent-encoding rather than the `:`-joined pair the other two stores use.
 *
 * Those encode two integers. This one encodes a display name, which is whatever a
 * DocumentsProvider or OpenSubtitles called the file — a colon, a pipe, a newline that
 * survived normalization, any script at all — and a value that can contain the delimiter
 * cannot be joined on it. [URLEncoder] leaves only `A-Za-z0-9.-*_` alone and turns space
 * into `+`, so `|` is guaranteed to survive as `%7C` inside a field and to mean exactly
 * one boundary between them.
 *
 * Pure JVM on purpose: `Uri.encode` is an `android.net` stub in a JVM unit test and would
 * return null there, which is a codec that passes its tests by not running.
 */
internal object SubtitleMemoryCodec {
    fun encode(record: SubtitleMemoryRecord): String = listOf(
        record.extension,
        record.displayName,
        record.language.orEmpty(),
        record.sizeBytes.toString(),
        record.updatedAtEpochMs.toString(),
    ).joinToString(DELIMITER.toString()) { URLEncoder.encode(it, CHARSET) }

    /** Anything [SubtitleMemoryPolicy.storable] refuses reads as no memory at all. */
    fun decode(value: String): SubtitleMemoryRecord? {
        val parts = value.split(DELIMITER)
        if (parts.size != FIELDS) return null
        val fields = runCatching { parts.map { URLDecoder.decode(it, CHARSET) } }.getOrNull() ?: return null
        val record = SubtitleMemoryRecord(
            extension = fields[0],
            displayName = fields[1],
            // No tag and an empty tag are the same fact: `languageTag` refuses an empty
            // string, so nothing this app writes can collide with the absent case.
            language = fields[2].takeIf { it.isNotEmpty() },
            sizeBytes = fields[3].toLongOrNull() ?: return null,
            updatedAtEpochMs = fields[4].toLongOrNull() ?: return null,
        )
        return record.takeIf(SubtitleMemoryPolicy::storable)
    }

    private const val DELIMITER = '|'
    private const val FIELDS = 5
    private const val CHARSET = "UTF-8"
}

/**
 * The subtitle remembered for [fingerprint], or null when this film has none.
 *
 * Keyed by [PlaybackMediaFingerprint] like the resume checkpoint and the A/V nudge, and
 * that is the point rather than a convenience: one film has one identity across all three.
 * A re-encode or a re-download that costs a viewer their resume position costs them the
 * subtitle too, which is right — cues are timed against the mux that file had.
 */
internal fun rememberedSubtitle(
    state: SubtitleMemoryState,
    fingerprint: String,
): SubtitleMemoryRecord? = (state as? SubtitleMemoryState.Ready)?.subtitles?.get(fingerprint)

/**
 * One durable copy of the subtitle each film was last watched with, plus the records that
 * name them.
 *
 * The copy is what makes this feature possible at all, and neither source could be
 * remembered by reference:
 *
 * A downloaded subtitle lands in `cacheDir` under a name derived from the SERVER's file
 * name alone (`OpenSubtitlesClient.cacheFileName`), so every film whose subtitle is called
 * `English.srt` resolves to one path — and `pruneCache` keeps only the newest few files
 * anyway. A remembered pointer there is not merely impermanent, it is capable of attaching
 * a DIFFERENT film's cues, and no existence check can tell the two apart.
 *
 * A picked document is held by a persistable grant, which survives until the user moves,
 * renames or deletes their own file, or the provider retires the grant. Copying answers
 * both with one mechanism, and it is why the directory is under `filesDir`: `cacheDir` is
 * evictable by the platform at any moment, which is the exact failure being fixed.
 */
internal class SubtitleMemoryStore(
    context: Context,
    scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    private val dataStore = app.subtitleMemoryDataStore
    private val directory = File(app.filesDir, SUBTITLE_MEMORY_DIR)

    /**
     * One mutation at a time through the SHORT part — the record, the move into place,
     * and the cleanup after it. Never the copy.
     *
     * DataStore serialises its own edits, but a mutation here is a record AND a file, and
     * those two land at different moments; unserialised, a removal can run between an
     * attach's record and the copy it is about to move into place, and what the two leave
     * behind stops describing the other. What this lock deliberately does NOT cover is
     * the provider read: a SAF pick can be backed by a cloud DocumentsProvider whose read
     * blocks for minutes, and a lock held across that is every later mutation for every
     * film silently never landing — including a detach the viewer believes they made.
     *
     * Which leaves a copy free to outlive the gesture that started it, so the ORDER of
     * two gestures is kept by [tickets] rather than by waiting. Fair and FIFO all the
     * same, so the critical sections themselves run in the order they queued.
     */
    private val mutations = Mutex()

    /**
     * Issued in call order, before the copy that may outlast the next gesture.
     *
     * Read outside [mutations] on purpose: a ticket has to be taken at the moment the
     * viewer's gesture arrives, which is before there is anything worth locking for.
     */
    private val tickets = AtomicLong()

    /**
     * The ticket of the last mutation to land, per film. Under [mutations] only.
     *
     * Attaching a subtitle and immediately taking it back off is two gestures a second
     * apart, and the copy for the first can still be reading when the second commits.
     * Without this the removal would finish first and the attach would then write its
     * record back over it — a subtitle the viewer explicitly took off, returning the next
     * time they open the film.
     *
     * One entry per film whose memory changed in this process, which is a handful: the
     * gesture is a deliberate pick or detach, not something browsing produces.
     */
    private val landed = mutableMapOf<String, Long>()

    val state: StateFlow<SubtitleMemoryState> = dataStore.data
        .map<Preferences, SubtitleMemoryState> { preferences ->
            SubtitleMemoryState.Ready(preferences.records().toMap())
        }
        // The resume store's rule for the resume store's reason: `catch` would END this
        // flow, and `stateIn` never re-collects a completed source, so one transient read
        // error would retire the memory for the life of the process. Retry, and publish
        // the fail-open empty value on the first failure — `openDetail` reads this without
        // waiting, so a state stuck at Loading silently costs every recall of the launch.
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            if (attempt == 0L) emit(SubtitleMemoryState.Ready(emptyMap()))
            delay(readRetryDelayMs(attempt))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, SubtitleMemoryState.Loading)

    init {
        // Read before the first record can be written, so the sweep below can tell a file
        // this process is creating right now from one an earlier process abandoned.
        val sweepFrom = wallClockMs()
        scope.launch {
            val ready = state.first { it is SubtitleMemoryState.Ready } as SubtitleMemoryState.Ready
            withContext(Dispatchers.IO) { sweep(ready.subtitles, sweepFrom) }
        }
    }

    /**
     * Copy [source] into the directory and file it under [fingerprint], replacing whatever
     * that film was remembered with.
     *
     * False is a complete outcome and never a half-written one: the record is made durable
     * BEFORE the copy replaces the one the previous record named, and taken back out again
     * if that move then fails, so nothing here can leave a record pointing at a file that
     * is not on disk. A failed READ costs the film nothing at all — the previous memory is
     * still whole and still named — and only a failed MOVE costs it that memory, which is a
     * filesystem that stopped answering inside one directory, and one tap to re-attach.
     */
    suspend fun remember(
        fingerprint: String,
        source: Uri,
        displayName: String,
        language: String?,
        extension: String,
    ): Boolean {
        val ticket = tickets.incrementAndGet()
        return withContext(Dispatchers.IO) {
            val name = SubtitleMemoryPolicy.fileName(fingerprint, extension)
                ?: return@withContext false
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext false
            val temp = File(directory, SubtitleMemoryPolicy.tempFileName(name, ticket))
            val sizeBytes = copyBounded(source, temp) ?: return@withContext false
            val record =
                SubtitleMemoryRecord(extension, displayName, language, sizeBytes, wallClockMs())
            // Checked before the write and not only on read: a record the codec would
            // refuse to hand back is a copy holding budget for a memory that cannot resolve.
            if (!SubtitleMemoryPolicy.storable(record)) {
                temp.delete()
                return@withContext false
            }
            mutations.withLock {
                // A detach the viewer made while this copy was still reading outranks it.
                if (!SubtitleMemoryPolicy.landable(ticket, landed[fingerprint])) {
                    temp.delete()
                    return@withContext false
                }
                // The record first and the move second, because the move is what DESTROYS
                // the previous copy: one rename(2) replaces the file the current record
                // names, so a persist that then exhausted its attempts would leave that
                // record naming a file no longer on disk — the one state this store exists
                // to keep out of. Deleting the target first instead of renaming over it
                // would open the same window deliberately.
                if (!persist(fingerprint, record)) {
                    temp.delete()
                    return@withContext false
                }
                val target = File(directory, name)
                if (!temp.renameTo(target)) {
                    // Rolled back rather than left behind: the record is already live, and
                    // a record whose copy never landed is a recall that resolves to nothing
                    // and budget spent on a film with no memory at all.
                    edit { it.remove(stringPreferencesKey(KEY_PREFIX + fingerprint)) }
                    temp.delete()
                    return@withContext false
                }
                landed[fingerprint] = ticket
                // One film owns one copy. A subtitle re-picked in another format leaves the
                // old extension's file behind, and until it goes it is bytes the budget
                // cannot see.
                siblingsOf(fingerprint, keep = name).forEach { runCatching { it.delete() } }
            }
            true
        }
    }

    /**
     * Forget [fingerprint] entirely: the record first, then the copy.
     *
     * That order is the invariant this store is built on. An orphaned file is harmless and
     * the sweep collects it; a record naming a file that is gone is a recall that resolves
     * to nothing, so a failed removal deliberately leaves the copy where it is — and does
     * not count as the last mutation to land, because a removal that never happened must
     * not outrank the attach still copying behind it.
     */
    suspend fun forget(fingerprint: String) {
        val ticket = tickets.incrementAndGet()
        withContext(Dispatchers.IO) {
            mutations.withLock {
                if (!SubtitleMemoryPolicy.landable(ticket, landed[fingerprint])) {
                    return@withContext
                }
                if (!edit { it.remove(stringPreferencesKey(KEY_PREFIX + fingerprint)) }) {
                    return@withContext
                }
                landed[fingerprint] = ticket
                deleteCopies(fingerprint)
            }
        }
    }

    /** The copy [record] names, once it has been proven whole on disk. */
    suspend fun recall(fingerprint: String, record: SubtitleMemoryRecord): Uri? =
        withContext(Dispatchers.IO) {
            val name = SubtitleMemoryPolicy.fileName(fingerprint, record.extension)
                ?: return@withContext null
            val file = File(directory, name)
            if (!file.isFile || file.length() != record.sizeBytes) return@withContext null
            Uri.fromFile(file)
        }

    /**
     * Streams [source] into [temp] and answers what it weighed, or null.
     *
     * Into a temp and never straight at the target: the target is the copy the CURRENT
     * record still names, and writing over it would truncate a working memory the moment
     * a read failed halfway.
     *
     * Bounded in SIZE while reading rather than by asking the provider how big the file
     * is: a DocumentsProvider's SIZE column is a claim, and `/s/{token}` already refuses
     * to trust it for the same reason. Counting the bytes as they arrive is the only bound
     * a provider cannot under-report its way past.
     *
     * Bounded in TIME the way that route bounds its own read of the same pick, and with
     * that route's honest limit: a blocking read cannot be interrupted, so the clock is
     * only ever checked BETWEEN reads. A cloud provider that is merely slow is bounded by
     * it; one that answers nothing at all is bounded by nothing, and its thread is still
     * down there whatever this returns. Which is why the fix for a stalled provider is
     * that no lock is held out here, and not the clock.
     */
    private suspend fun copyBounded(source: Uri, temp: File): Long? {
        val written = withTimeoutOrNull(COPY_TIMEOUT_MS) { copyInto(source, temp) }
        // Zero bytes is not a subtitle — the online path refuses one for the same reason.
        if (written == null || written <= 0L) {
            // An unlink is enough even while a timed-out read is still writing into it:
            // the name goes now and the blocks go when that thread finally lets go.
            temp.delete()
            return null
        }
        return written
    }

    private suspend fun copyInto(source: Uri, temp: File): Long? {
        var written = 0L
        return try {
            app.contentResolver.openInputStream(source)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        // Between reads and never during one: a read already in flight is
                        // beyond reach, so this is the only point at which the timeout
                        // above can take effect at all.
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > SubtitleFiles.MaxSubtitleBytes) return null
                        output.write(buffer, 0, read)
                    }
                }
                written
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Revoked grant, deleted file, a provider that fails mid-stream: a memory is
            // never worth taking a pick that is otherwise working down with it.
            null
        }
    }

    private suspend fun persist(fingerprint: String, record: SubtitleMemoryRecord): Boolean {
        val evicted = mutableListOf<String>()
        val stored = edit { preferences ->
            evicted.clear()
            val others = preferences.records().filterNot { it.first == fingerprint }
            SubtitleMemoryPolicy.evicted(others, record.sizeBytes).forEach { dropped ->
                preferences.remove(stringPreferencesKey(KEY_PREFIX + dropped))
                evicted += dropped
            }
            preferences[stringPreferencesKey(KEY_PREFIX + fingerprint)] =
                SubtitleMemoryCodec.encode(record)
        }
        if (!stored) return false
        evicted.forEach(::deleteCopies)
        return true
    }

    /** The write idiom the other two stores share: a few tries, then an honest failure. */
    private suspend fun edit(block: (MutablePreferences) -> Unit): Boolean {
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                dataStore.edit(block)
                return true
            } catch (_: IOException) {
                if (attempt + 1 == MAX_WRITE_ATTEMPTS) return false
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    /**
     * Delete every file in the directory no live record names.
     *
     * This is what makes the directory self-healing: a delete that failed, a record the
     * corruption handler replaced with nothing, a copy whose record never landed. It is
     * cheap because on nearly every launch it lists a directory holding at most as many
     * files as there are records.
     *
     * It matches on what records NAME, so a temp is collected whatever ticket it carries:
     * an attempt whose provider read never returned leaves a file nobody else will ever
     * come back for, and no list of names written in advance could have found it.
     *
     * [sweepFrom] is when this process started reading. A file at least that new is one
     * this process is writing right now, whose record is still on its way — the sweep and
     * a pick made during it would otherwise race for the same file.
     */
    private fun sweep(live: Map<String, SubtitleMemoryRecord>, sweepFrom: Long) {
        val named = live.mapNotNull { (fingerprint, record) ->
            SubtitleMemoryPolicy.fileName(fingerprint, record.extension)
        }.toSet()
        directory.listFiles()?.forEach { file ->
            if (file.name in named || file.lastModified() >= sweepFrom) return@forEach
            runCatching { file.delete() }
        }
    }

    private fun deleteCopies(fingerprint: String) {
        SubtitleMemoryPolicy.copyFileNames(fingerprint).forEach {
            runCatching { File(directory, it).delete() }
        }
    }

    private fun siblingsOf(fingerprint: String, keep: String): List<File> =
        SubtitleMemoryPolicy.copyFileNames(fingerprint).filterNot { it == keep }
            .map { File(directory, it) }

    private fun Preferences.records(): List<Pair<String, SubtitleMemoryRecord>> =
        asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
            SubtitleMemoryCodec.decode(value)?.let { key.name.removePrefix(KEY_PREFIX) to it }
        }

    private companion object {
        const val KEY_PREFIX = "subtitle_"
        const val MAX_WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MS = 100L
        const val COPY_BUFFER_BYTES = 32 * 1024

        /**
         * The same 20 s `/s/{token}` allows for reading the same pick. A subtitle that
         * cannot be copied inside it could not have been served inside it either.
         */
        const val COPY_TIMEOUT_MS = 20_000L
    }
}

package com.flick.sender.media

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.sender.net.AudioDelayPolicy
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val AUDIO_DELAY_DATASTORE_NAME = "flick_audio_delay"

/**
 * Its own file rather than another key beside the resume checkpoints.
 *
 * Preferences DataStore rewrites the WHOLE file on every edit and serialises those edits,
 * so one store shared by two writers makes each of them wait behind the other. These two
 * write on unrelated beats — a checkpoint every five seconds for the length of a film, a
 * nudge whenever a viewer stops moving one — and neither is worth delaying for the other.
 * Separate files also mean a corrupt one costs a single feature: the corruption handler
 * below replaces the file with an empty one, and losing every remembered nudge is not a
 * reason to also lose every resume position.
 */
private val Context.audioDelayDataStore by preferencesDataStore(
    name = AUDIO_DELAY_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)

/**
 * The nudge a viewer settled on for one film, and when they settled on it. The timestamp
 * is shown nowhere and means nothing to the viewer: it exists so that a full store drops
 * the film nobody has touched in longest rather than an arbitrary one.
 */
internal data class AudioDelayRecord(val delayMs: Int, val updatedAtEpochMs: Long)

internal sealed interface AudioDelayMemoryState {
    data object Loading : AudioDelayMemoryState
    data class Ready(val delays: Map<String, AudioDelayRecord>) : AudioDelayMemoryState
}

internal sealed interface AudioDelayMutation {
    data class Save(val delayMs: Int) : AudioDelayMutation
    data object Clear : AudioDelayMutation
}

internal object AudioDelayMemoryPolicy {

    /**
     * How still the offset has to be before it is written down.
     *
     * Almost nothing a viewer's hand produces here is a value they chose to keep. A drag
     * reports every pointer sample; a move too large for one frame is walked in
     * [AudioDelayPolicy.MAX_JUMP_MS] hops one [AudioDelayPolicy.WALK_INTERVAL_MS] apart,
     * which is 25 values a second and up to 40 of them for a bound-to-bound slam. Writing
     * each would be 25 whole-file rewrites a second to record 39 offsets nobody asked for
     * and one they did.
     *
     * Every new value restarts this window, so it only has to outlast the GAP between two
     * of them to never elapse mid-move. Ten walk hops of margin, rather than one, because
     * the gaps a walk does not control are the ones that would break it: a main thread
     * busy with the film's own frames stretches a 40 ms hop, and a window that fired in
     * that stretch would write a value the walk was only passing through. What the margin
     * costs is how long a settled nudge stays unwritten, and the end of the cast flushes
     * that anyway — so the only loss it can cause is a process death inside 400 ms of the
     * viewer's last touch.
     */
    const val SETTLE_QUIET_MS = 400L

    /**
     * How many films are remembered — the same hundred the resume store keeps, and for
     * the same reason: a store that grows without a bound is a file that gets slower to
     * rewrite for the rest of the install's life, and Preferences DataStore rewrites all
     * of it every time. A viewer who has nudged a hundred different films has long since
     * stopped caring about the first.
     */
    const val MAX_RECORDS = 100

    /**
     * What the store owes for [delayMs].
     *
     * In-sync is recorded as the ABSENCE of a record and never as a zero. A film with no
     * nudge should leave no trace — the viewer who dialled one in and then took it back
     * out is telling us the film does not need one — and a store that saved zeros would
     * spend its hundred records on films whose only fact is that they are fine, evicting
     * the ones that are not.
     */
    fun mutation(delayMs: Int): AudioDelayMutation {
        val value = AudioDelayPolicy.clamp(delayMs)
        return if (value == AudioDelayPolicy.IN_SYNC_MS) {
            AudioDelayMutation.Clear
        } else {
            AudioDelayMutation.Save(value)
        }
    }

    /**
     * Whether [delayMs] is a value this app could have written: inside the wire range, on
     * the step grid, and not in-sync.
     *
     * Read back rather than clamped, deliberately. A stored value that fails this is not
     * a near-miss to be repaired into the nearest legal one — it is a record this app did
     * not write, and silently re-applying a made-up offset would put the picture somewhere
     * the viewer never put it, on a film they have no reason to suspect. Absent is the
     * honest reading, and re-nudging is one gesture.
     */
    fun storable(delayMs: Int): Boolean =
        delayMs != AudioDelayPolicy.IN_SYNC_MS &&
            delayMs in AudioDelayPolicy.MIN_MS..AudioDelayPolicy.MAX_MS &&
            delayMs % AudioDelayPolicy.STEP_MS == 0

    /**
     * Which stored key a fresh record displaces, or null while there is still room.
     * Oldest by [AudioDelayRecord.updatedAtEpochMs], which is the last time a viewer
     * settled on it rather than the last time they watched the film.
     */
    fun evicted(records: List<Pair<String, Long>>): String? {
        if (records.size < MAX_RECORDS) return null
        return records.minByOrNull { it.second }?.first
    }
}

internal object AudioDelayCodec {
    fun encode(record: AudioDelayRecord): String = "${record.delayMs}:${record.updatedAtEpochMs}"

    /** Anything [AudioDelayMemoryPolicy.storable] refuses reads as no memory at all. */
    fun decode(value: String): AudioDelayRecord? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val delay = parts[0].toIntOrNull()?.takeIf(AudioDelayMemoryPolicy::storable) ?: return null
        val updated = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return AudioDelayRecord(delay, updated)
    }
}

/** One store edit the recorder is waiting on the answer to. */
internal data class AudioDelayStoreWrite(
    val fingerprint: String,
    val mutation: AudioDelayMutation,
    override val complete: (Boolean) -> Unit,
) : StoreWrite

/** What the recorder decided the store owes, and which cast decided it. */
internal data class AudioDelayWrite(
    val generation: Long,
    val id: Long,
    val castId: String,
    val fingerprint: String,
    val mutation: AudioDelayMutation,
)

/**
 * The nudge remembered for [fingerprint], or null when this film has none.
 *
 * "None" covers both never-nudged and nudged-back-to-in-sync, which are the same fact
 * and are stored the same way — as nothing.
 *
 * The key is [PlaybackMediaFingerprint]'s, the identity the resume checkpoint is already
 * filed under, and that is the point rather than a convenience: one film has one identity
 * here. A re-encode or a re-download that costs a viewer their resume position costs them
 * the nudge too, which is right — the offset was dialled in against the mux that file had,
 * and the new one has its own.
 */
internal fun rememberedAudioDelayMs(state: AudioDelayMemoryState, fingerprint: String): Int? =
    (state as? AudioDelayMemoryState.Ready)?.delays?.get(fingerprint)?.delayMs

/**
 * Publish a value from [values] only once it has held still for [quietMs], and publish
 * nothing at all for the ones it passed through on the way.
 *
 * Kept out of the coordinator so the timing is testable on virtual time, exactly as
 * `SkipBurstTimer` is: this is the same shape of decision the ±10 s tap run makes, that
 * a run of values close together is one intent and deserves one write.
 */
internal suspend fun collectSettledAudioDelay(
    values: Flow<Int>,
    quietMs: Long = AudioDelayMemoryPolicy.SETTLE_QUIET_MS,
    onSettled: (Int) -> Unit,
) {
    // collectLatest cancels the wait the moment a newer value arrives, so a drag under a
    // finger and a walk in flight both restart the window rather than queueing a write
    // each. The value that outlasts it is the one the viewer stopped on.
    values.collectLatest { value ->
        delay(quietMs)
        onSettled(value)
    }
}

internal class AudioDelayMemoryStore(
    context: Context,
    scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = context.applicationContext.audioDelayDataStore

    private val writes = Channel<AudioDelayStoreWrite>(Channel.UNLIMITED)

    val state: StateFlow<AudioDelayMemoryState> = dataStore.data
        .map<Preferences, AudioDelayMemoryState> { preferences ->
            AudioDelayMemoryState.Ready(
                preferences.asMap().mapNotNull { (key, value) ->
                    val fingerprint = key.name.removePrefix(KEY_PREFIX).takeIf {
                        key.name.startsWith(KEY_PREFIX) && value is String
                    } ?: return@mapNotNull null
                    AudioDelayCodec.decode(value as String)?.let { fingerprint to it }
                }.toMap(),
            )
        }
        // The resume store's rule, for the resume store's reason: `catch` would END this
        // flow, and `stateIn` never re-collects a completed source, so a single transient
        // read error would retire the memory for the life of the process. Retry instead,
        // and publish the fail-open empty value on the first failure — a cast start reads
        // this without waiting, so a state stuck at Loading would silently cost the nudge
        // on every film for the rest of the launch.
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            if (attempt == 0L) emit(AudioDelayMemoryState.Ready(emptyMap()))
            delay(readRetryDelayMs(attempt))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, AudioDelayMemoryState.Loading)

    init {
        scope.launch {
            drainStoreWrites(writes) { persist(it.fingerprint, it.mutation) }
        }
    }

    fun enqueue(
        fingerprint: String,
        mutation: AudioDelayMutation,
        complete: (Boolean) -> Unit,
    ) {
        // A closed channel means the consumer is gone and nothing will ever answer.
        if (writes.trySend(AudioDelayStoreWrite(fingerprint, mutation, complete)).isFailure) {
            complete(false)
        }
    }

    private suspend fun persist(fingerprint: String, mutation: AudioDelayMutation): Boolean {
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                dataStore.edit { preferences ->
                    val key = stringPreferencesKey(KEY_PREFIX + fingerprint)
                    when (mutation) {
                        AudioDelayMutation.Clear -> preferences.remove(key)
                        is AudioDelayMutation.Save -> {
                            if (!preferences.contains(key)) pruneIfFull(preferences)
                            preferences[key] = AudioDelayCodec.encode(
                                AudioDelayRecord(mutation.delayMs, wallClockMs()),
                            )
                        }
                    }
                }
                return true
            } catch (_: IOException) {
                if (attempt + 1 == MAX_WRITE_ATTEMPTS) return false
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
        return false
    }

    private fun pruneIfFull(preferences: MutablePreferences) {
        val records = preferences.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
            AudioDelayCodec.decode(value)?.let { key.name to it.updatedAtEpochMs }
        }
        AudioDelayMemoryPolicy.evicted(records)?.let { preferences.remove(stringPreferencesKey(it)) }
    }

    private companion object {
        const val KEY_PREFIX = "delay_"
        const val MAX_WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MS = 100L
    }
}

/**
 * What the store owes for the cast being driven — and nothing about when, which is
 * [collectSettledAudioDelay]'s job and the cast's teardown's.
 *
 * It holds one write in flight at a time, like the checkpoint recorder: a nudge is a
 * whole-file rewrite, and a viewer who keeps moving the blade while the disk is busy must
 * queue nothing. What that costs is covered by [finish], which spends the value the cast
 * ended on whatever was outstanding when it did.
 */
internal class AudioDelayRecorder {
    private data class Active(
        val generation: Long,
        val castId: String,
        val fingerprint: String,
        var durableDelayMs: Int,
        var pendingWriteId: Long? = null,
    )

    private var active: Active? = null
    private var generation = 0L
    private var writeId = 0L

    /**
     * Begin remembering for [castId].
     *
     * [appliedDelayMs] is what the cast STARTS at — the remembered offset that was just
     * re-applied, or in-sync for a film that has none — and it is recorded as already
     * durable because it is: it is either what the store holds or the absence of a record,
     * which are the two things this recorder can write. Without that, re-applying a
     * memory would immediately rewrite it, every cast of every nudged film.
     */
    fun activate(castId: String, fingerprint: String, appliedDelayMs: Int) {
        active = Active(++generation, castId, fingerprint, AudioDelayPolicy.clamp(appliedDelayMs))
    }

    /**
     * One value the viewer has stopped on. Null when there is nothing to write — no cast
     * being driven, a write already in flight, or a value the store already holds.
     *
     * The cast is not named here because the caller cannot name it: this arrives from the
     * session's own value, which has no cast in it. The active cast is the authority, and
     * a value that arrives while none is active belongs to no film — the in-sync reset a
     * teardown publishes, for instance, which must never be mistaken for the viewer
     * cancelling the nudge on the film that just ended.
     */
    fun settled(delayMs: Int): AudioDelayWrite? {
        val current = active ?: return null
        val value = AudioDelayPolicy.clamp(delayMs)
        if (current.pendingWriteId != null) return null
        if (value == current.durableDelayMs) return null
        return offer(current, AudioDelayMemoryPolicy.mutation(value))
    }

    fun acknowledge(write: AudioDelayWrite, success: Boolean) {
        val current = active?.takeIf {
            it.generation == write.generation && it.castId == write.castId &&
                it.pendingWriteId == write.id
        } ?: return
        current.pendingWriteId = null
        // A failure leaves `durableDelayMs` where it was, so the next settle — or the
        // cast's end — offers the same value again rather than assuming it landed.
        if (!success) return
        current.durableDelayMs = when (val mutation = write.mutation) {
            AudioDelayMutation.Clear -> AudioDelayPolicy.IN_SYNC_MS
            is AudioDelayMutation.Save -> mutation.delayMs
        }
    }

    /**
     * The cast is over: write [delayMs] unless the store already holds it.
     *
     * This is what makes the settle window safe to be as long as it is, and it is the only
     * thing that catches a nudge the viewer made and then immediately walked away from —
     * stopping the cast is exactly the gesture that outruns a quiet window.
     *
     * The value is passed in rather than remembered from the last settle, because the last
     * settle is precisely what a hurried viewer did not wait for. It has to be what was
     * ASKED for and not what is on screen: a teardown that interrupts a walk finds the
     * session mid-run, at a hop nobody chose.
     */
    fun finish(castId: String, delayMs: Int): AudioDelayWrite? {
        val current = active?.takeIf { it.castId == castId } ?: return null
        active = null
        val value = AudioDelayPolicy.clamp(delayMs)
        if (value == current.durableDelayMs) return null
        return write(current, AudioDelayMemoryPolicy.mutation(value))
    }

    private fun offer(current: Active, mutation: AudioDelayMutation): AudioDelayWrite =
        write(current, mutation).also { current.pendingWriteId = it.id }

    private fun write(current: Active, mutation: AudioDelayMutation) = AudioDelayWrite(
        generation = current.generation,
        id = ++writeId,
        castId = current.castId,
        fingerprint = current.fingerprint,
        mutation = mutation,
    )
}

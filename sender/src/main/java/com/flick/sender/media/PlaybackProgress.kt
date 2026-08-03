package com.flick.sender.media

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.sender.model.MediaItem
import com.flick.sender.model.PlaybackPhase
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAYBACK_PROGRESS_DATASTORE_NAME = "flick_playback_progress"
private val Context.playbackProgressDataStore by preferencesDataStore(
    name = PLAYBACK_PROGRESS_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)

private const val READ_RETRY_BASE_MS = 1_000L
private const val READ_RETRY_MAX_MS = 60_000L

/** Doubling backoff so a permanently unreadable store settles at one read a minute. */
internal fun readRetryDelayMs(attempt: Long): Long =
    (READ_RETRY_BASE_MS shl attempt.coerceIn(0L, 6L).toInt()).coerceAtMost(READ_RETRY_MAX_MS)

internal data class PlaybackStoreWrite(
    val fingerprint: String,
    val mutation: PlaybackProgressMutation,
    val complete: (Boolean) -> Unit,
)

/**
 * Drains [writes], acknowledging every one exactly once. The recorder holds a
 * single-flight slot per cast until a write is acknowledged, so a [persist] that
 * throws must still report failure — dropping the acknowledgement would silently
 * stop every later checkpoint for that cast, and killing this loop would stop them
 * for the whole process. Only cancellation and Errors end the drain, and cancellation
 * still answers the write it was carrying.
 */
internal suspend fun drainPlaybackWrites(
    writes: ReceiveChannel<PlaybackStoreWrite>,
    persist: suspend (PlaybackStoreWrite) -> Boolean,
) {
    for (write in writes) {
        val stored = try {
            persist(write)
        } catch (cancellation: CancellationException) {
            write.complete(false)
            throw cancellation
        } catch (_: Exception) {
            false
        }
        write.complete(stored)
    }
}

data class PlaybackCheckpoint(val positionMs: Long, val updatedAtEpochMs: Long)

sealed interface PlaybackProgressState {
    data object Loading : PlaybackProgressState
    data class Ready(val checkpoints: Map<String, PlaybackCheckpoint>) : PlaybackProgressState
}

internal object PlaybackResumePolicy {
    const val MIN_RESUME_MS = 10_000L
    const val END_WINDOW_MS = 30_000L
    const val WRITE_INTERVAL_MS = 5_000L
    const val MAX_POSITION_MS = 604_800_000L

    fun eligiblePosition(positionMs: Long, durationMs: Long): Long? {
        val safe = positionMs.coerceIn(0L, MAX_POSITION_MS).let {
            if (durationMs > 0L) it.coerceAtMost(durationMs) else it
        }
        if (safe < MIN_RESUME_MS) return null
        if (durationMs > 0L && durationMs - safe <= END_WINDOW_MS) return null
        return safe
    }

    fun mutation(positionMs: Long, durationMs: Long, phase: PlaybackPhase): PlaybackProgressMutation? {
        if (phase == PlaybackPhase.ENDED) return PlaybackProgressMutation.Clear
        if (positionMs < MIN_RESUME_MS) return PlaybackProgressMutation.Clear
        if (durationMs > 0L && durationMs - positionMs.coerceAtMost(durationMs) <= END_WINDOW_MS) {
            return PlaybackProgressMutation.Clear
        }
        return eligiblePosition(positionMs, durationMs)?.let(PlaybackProgressMutation::Save)
    }
}

internal object PlaybackMediaFingerprint {
    fun of(item: MediaItem): String = of(
        uri = item.uriKey,
        sizeBytes = item.sizeBytes,
        dateModifiedSeconds = item.dateModifiedSeconds,
        durationMs = item.durationMs,
        generationModified = item.generationModified,
        mediaStoreVersion = item.mediaStoreVersion,
    )

    fun of(
        uri: String,
        sizeBytes: Long,
        dateModifiedSeconds: Long,
        durationMs: Long,
        generationModified: Long?,
        mediaStoreVersion: String?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            uri,
            sizeBytes.toString(),
            dateModifiedSeconds.toString(),
            durationMs.toString(),
            generationModified?.toString().orEmpty(),
            mediaStoreVersion.orEmpty(),
        ).forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }
}

internal sealed interface PlaybackProgressMutation {
    data class Save(val positionMs: Long) : PlaybackProgressMutation
    data object Clear : PlaybackProgressMutation
}

internal data class PlaybackProgressWrite(
    val generation: Long,
    val id: Long,
    val castId: String,
    val fingerprint: String,
    val mutation: PlaybackProgressMutation,
)

internal object PlaybackCheckpointCodec {
    fun encode(checkpoint: PlaybackCheckpoint): String =
        "${checkpoint.positionMs}:${checkpoint.updatedAtEpochMs}"

    fun decode(value: String): PlaybackCheckpoint? {
        val parts = value.split(':')
        if (parts.size != 2) return null
        val position = parts[0].toLongOrNull()?.takeIf { it in 0L..PlaybackResumePolicy.MAX_POSITION_MS }
            ?: return null
        val updated = parts[1].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return PlaybackCheckpoint(position, updated)
    }
}

internal class PlaybackProgressStore(
    context: Context,
    scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = context.applicationContext.playbackProgressDataStore

    private val writes = Channel<PlaybackStoreWrite>(Channel.UNLIMITED)

    val state: StateFlow<PlaybackProgressState> = dataStore.data
        .map<Preferences, PlaybackProgressState> { preferences ->
            PlaybackProgressState.Ready(
                preferences.asMap().mapNotNull { (key, value) ->
                    val fingerprint = key.name.removePrefix(KEY_PREFIX).takeIf {
                        key.name.startsWith(KEY_PREFIX) && value is String
                    } ?: return@mapNotNull null
                    PlaybackCheckpointCodec.decode(value as String)?.let { fingerprint to it }
                }.toMap(),
            )
        }
        // `catch` would END this flow: one transient read error would pin the StateFlow
        // at its fail-open value for the life of the process, because stateIn never
        // re-collects a completed source, and Detail would stop offering a resume until
        // the app restarted. Retry instead — but publish the fail-open value on the first
        // failure, since Detail keeps its actions disabled until this reads Ready.
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            if (attempt == 0L) emit(PlaybackProgressState.Ready(emptyMap()))
            delay(readRetryDelayMs(attempt))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, PlaybackProgressState.Loading)

    init {
        scope.launch {
            drainPlaybackWrites(writes) { persist(it.fingerprint, it.mutation) }
        }
    }

    fun enqueue(
        fingerprint: String,
        mutation: PlaybackProgressMutation,
        complete: (Boolean) -> Unit,
    ) {
        // A closed channel means the consumer is gone and nothing will ever answer.
        if (writes.trySend(PlaybackStoreWrite(fingerprint, mutation, complete)).isFailure) {
            complete(false)
        }
    }

    private suspend fun persist(fingerprint: String, mutation: PlaybackProgressMutation): Boolean {
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                dataStore.edit { preferences ->
                    val key = stringPreferencesKey(KEY_PREFIX + fingerprint)
                    when (mutation) {
                        PlaybackProgressMutation.Clear -> preferences.remove(key)
                        is PlaybackProgressMutation.Save -> {
                            if (!preferences.contains(key)) pruneIfFull(preferences)
                            preferences[key] = PlaybackCheckpointCodec.encode(
                                PlaybackCheckpoint(mutation.positionMs, wallClockMs()),
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

    private fun pruneIfFull(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        val records = preferences.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
            PlaybackCheckpointCodec.decode(value)?.let { key.name to it.updatedAtEpochMs }
        }
        if (records.size < MAX_RECORDS) return
        records.minByOrNull { it.second }?.let { preferences.remove(stringPreferencesKey(it.first)) }
    }

    private companion object {
        const val KEY_PREFIX = "media_"
        const val MAX_RECORDS = 100
        const val MAX_WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MS = 100L
    }
}

internal class PlaybackProgressRecorder(
    private val writeIntervalMs: Long = PlaybackResumePolicy.WRITE_INTERVAL_MS,
) {
    private data class Active(
        val generation: Long,
        val castId: String,
        val fingerprint: String,
        var lastPositionMs: Long? = null,
        var lastDurationMs: Long = 0L,
        var lastPhase: PlaybackPhase = PlaybackPhase.BUFFERING,
        var durablePositionMs: Long? = null,
        var durableClear: Boolean = false,
        var pendingWriteId: Long? = null,
        val finalFallback: PlaybackProgressMutation? = null,
    )

    private var active: Active? = null
    private var generation = 0L
    private var writeId = 0L

    fun activate(castId: String, fingerprint: String, startOver: Boolean): PlaybackProgressWrite? {
        val fallback = PlaybackProgressMutation.Clear.takeIf { startOver }
        val current = Active(++generation, castId, fingerprint, finalFallback = fallback)
        active = current
        return fallback?.let { offer(current, it) }
    }

    fun onConfirmed(
        castId: String,
        positionMs: Long,
        durationMs: Long,
        phase: PlaybackPhase,
    ): PlaybackProgressWrite? {
        val current = active?.takeIf { it.castId == castId } ?: return null
        current.lastPositionMs = positionMs
        current.lastDurationMs = durationMs
        current.lastPhase = phase
        if (current.pendingWriteId != null) return null
        val mutation = PlaybackResumePolicy.mutation(positionMs, durationMs, phase) ?: return null
        if (mutation == PlaybackProgressMutation.Clear) {
            if (current.durableClear) return null
            return offer(current, mutation)
        }
        val saved = mutation as PlaybackProgressMutation.Save
        val previous = current.durablePositionMs
        if (phase != PlaybackPhase.PAUSED && previous != null &&
            kotlin.math.abs(saved.positionMs - previous) < writeIntervalMs
        ) return null
        if (previous == saved.positionMs) return null
        return offer(current, saved)
    }

    fun acknowledge(write: PlaybackProgressWrite, success: Boolean) {
        val current = active?.takeIf {
            it.generation == write.generation && it.castId == write.castId &&
                it.pendingWriteId == write.id
        } ?: return
        current.pendingWriteId = null
        if (!success) return
        when (val mutation = write.mutation) {
            PlaybackProgressMutation.Clear -> {
                current.durableClear = true
                current.durablePositionMs = null
            }
            is PlaybackProgressMutation.Save -> {
                current.durableClear = false
                current.durablePositionMs = mutation.positionMs
            }
        }
    }

    fun finish(castId: String): PlaybackProgressWrite? {
        val current = active?.takeIf { it.castId == castId } ?: return null
        val mutation = current.lastPositionMs?.let {
            PlaybackResumePolicy.mutation(it, current.lastDurationMs, current.lastPhase)
        } ?: current.finalFallback
        active = null
        return mutation?.let { write(current, it) }
    }

    private fun offer(current: Active, mutation: PlaybackProgressMutation): PlaybackProgressWrite {
        return write(current, mutation).also { current.pendingWriteId = it.id }
    }

    private fun write(current: Active, mutation: PlaybackProgressMutation) = PlaybackProgressWrite(
        generation = current.generation,
        id = ++writeId,
        castId = current.castId,
        fingerprint = current.fingerprint,
        mutation = mutation,
    )
}

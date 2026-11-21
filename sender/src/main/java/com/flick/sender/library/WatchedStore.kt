package com.flick.sender.library

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flick.sender.media.readRetryDelayMs
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val WATCHED_DATASTORE_NAME = "flick_watched"
private val Context.watchedDataStore by preferencesDataStore(
    name = WATCHED_DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)

/**
 * The persisted ledger: fingerprint → when it was watched, epoch ms. Excluded from backup for
 * the progress store's reason — the fingerprint names a MediaStore revision on this phone only.
 */
internal class WatchedStore(
    context: Context,
    scope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    private val dataStore = context.applicationContext.watchedDataStore
    private val writes = Channel<Write>(Channel.UNLIMITED)

    /** Empty until read and on a read failure: a missing mark costs a badge, never a cast. */
    val state: StateFlow<Map<String, Long>> = dataStore.data
        .map { preferences -> records(preferences) }
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            delay(readRetryDelayMs(attempt))
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            for (write in writes) persist(write)
        }
    }

    fun mark(fingerprint: String) {
        writes.trySend(Write(fingerprint, backfillAtMs = null))
    }

    /** Records [fingerprint] as watched at [atMs] only if the ledger has no record of it yet. */
    fun backfill(fingerprint: String, atMs: Long) {
        writes.trySend(Write(fingerprint, backfillAtMs = atMs))
    }

    private suspend fun persist(write: Write) {
        val key = longPreferencesKey(KEY_PREFIX + write.fingerprint)
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            try {
                dataStore.edit { preferences ->
                    if (write.backfillAtMs != null && preferences.contains(key)) return@edit
                    preferences[key] = write.backfillAtMs ?: wallClockMs()
                    prune(preferences)
                }
                return
            } catch (_: IOException) {
                if (attempt + 1 == MAX_WRITE_ATTEMPTS) return
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
    }

    private fun prune(preferences: MutablePreferences) {
        WatchedLedgerPolicy.overflow(records(preferences)).forEach { fingerprint ->
            preferences.remove(longPreferencesKey(KEY_PREFIX + fingerprint))
        }
    }

    private fun records(preferences: Preferences): Map<String, Long> =
        preferences.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(KEY_PREFIX) || value !is Long) return@mapNotNull null
            key.name.removePrefix(KEY_PREFIX) to value
        }.toMap()

    private class Write(val fingerprint: String, val backfillAtMs: Long?)

    private companion object {
        const val KEY_PREFIX = "w_"
        const val MAX_WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MS = 100L
    }
}

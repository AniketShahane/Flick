package com.flick.receiver.net

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom

data class PairingSnapshot(
    val surface: PairingSurface,
    val pairedCount: Int,
    val mostRecentDeviceLabel: String?,
)

sealed interface PairingSurface {
    data object Standby : PairingSurface
    data class Open(val code: String, val generation: Long, val expiresAtElapsedMs: Long) : PairingSurface
    data class Locked(val generation: Long, val retryAtElapsedMs: Long) : PairingSurface
    data class Success(val deviceLabel: String, val generation: Long) : PairingSurface
}

sealed interface PairAttemptResult {
    data class Success(val key: String, val keyId: String, val deviceLabel: String) : PairAttemptResult
    data object SurfaceClosed : PairAttemptResult
    data object Expired : PairAttemptResult
    data object InvalidCode : PairAttemptResult
    data class LockedOut(val retryAtElapsedMs: Long) : PairAttemptResult
    /** Durable storage rejected the new key, so the visible code was not consumed. */
    data object PersistenceFailed : PairAttemptResult
}

/** The only pairing authorization gate. All code checks and key writes share this monitor. */
class PairingManager(
    context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var generation = 0L
    private var visible = false
    private var open: PairingSurface.Open? = null
    private var lockoutRound = prefs.getInt(KEY_LOCKOUT_ROUND, 0).coerceIn(0, MAX_LOCKOUT_ROUNDS)
    private var lockoutUntilWall = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
    private var lockoutUntilElapsed = restoreLockout()
    private var failures = 0
    private data class HostThrottle(var failures: Int, var retryAtElapsedMs: Long)
    private val hostThrottles = LinkedHashMap<String, HostThrottle>(MAX_HOST_THROTTLES, 0.75f, true)
    private val _snapshot = MutableStateFlow(snapshot(PairingSurface.Standby))
    val snapshot: StateFlow<PairingSnapshot> = _snapshot

    var tvName: String
        get() = prefs.getString(KEY_NAME, DEFAULT_TV_NAME)?.trim().orEmpty().ifBlank { DEFAULT_TV_NAME }
        set(value) { prefs.edit().putString(KEY_NAME, normalizeLabel(value, 80).ifBlank { DEFAULT_TV_NAME }).commit() }

    val tvId: String = prefs.getString(KEY_TV_ID, null) ?: randomId().also {
        prefs.edit().putString(KEY_TV_ID, it).commit()
    }

    @Synchronized fun requestOpen() {
        visible = true
        FlickLog.d("pair", "surface=open")
        publishEligible()
    }

    @Synchronized fun closeSurface() {
        visible = false
        open = null // a code is never valid when it is not visibly rendered.
        FlickLog.d("pair", "surface=closed")
        publish(PairingSurface.Standby)
    }

    @Synchronized fun onForeground() { if (storedRecords().isEmpty()) requestOpen() }
    @Synchronized fun onBackground() = closeSurface()

    @Synchronized fun tick() {
        val current = open
        // An Open code and a Locked retry deadline are distinct states. In
        // particular, a normal Open code must stay stable until its own expiry.
        if (current != null) {
            if (elapsed() >= current.expiresAtElapsedMs) openNewCode()
            return
        }
        if (_snapshot.value.surface is PairingSurface.Locked && elapsed() >= lockoutUntilElapsed) publishEligible()
    }

    @Synchronized fun attemptPair(candidate: String, device: String, peerHost: String = ""): PairAttemptResult {
        // Do not call tick here: an expired candidate must be compared against
        // the generation it arrived for, never against a freshly rotated code.
        if (!visible || open == null) return PairAttemptResult.SurfaceClosed
        if (elapsed() < lockoutUntilElapsed) return PairAttemptResult.LockedOut(lockoutUntilElapsed)
        val current = open ?: return PairAttemptResult.SurfaceClosed
        if (elapsed() >= current.expiresAtElapsedMs) { openNewCode(); return PairAttemptResult.Expired }
        val host = if (MediaUrlValidator.isPrivateIpv4(peerHost)) peerHost else "unknown"
        hostThrottles[host]?.takeIf { elapsed() < it.retryAtElapsedMs }?.let { return PairAttemptResult.LockedOut(it.retryAtElapsedMs) }
        if (!constantTimeEquals(candidate, current.code)) {
            chargeHost(host)
            failures++
            if (failures >= MAX_FAILURES) {
                failures = 0
                beginLockout()
            }
            return PairAttemptResult.InvalidCode
        }
        val label = normalizeLabel(device, 80).ifBlank { return PairAttemptResult.InvalidCode }
        val key = randomKey(); val keyId = randomId()
        val records = storedRecords().toMutableList().apply { add("$keyId|$key|$label") }
        // Key, keyId and label are one durable transaction before success is published.
        val committed = commitPairing(
            commit = {
                prefs.edit().putStringSet(KEY_RECORDS, records.toSet()).putString(KEY_LAST_DEVICE, label)
                    .putInt(KEY_LOCKOUT_ROUND, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).commit()
            },
            afterCommit = {
                failures = 0; lockoutRound = 0; lockoutUntilElapsed = 0L; lockoutUntilWall = 0L
                hostThrottles.remove(host)
                open = null
            },
        )
        if (!committed) return PairAttemptResult.PersistenceFailed
        val success = PairAttemptResult.Success(key, keyId, label)
        publish(PairingSurface.Success(label, current.generation))
        return success
    }

    @Synchronized fun finishSuccess() { if (_snapshot.value.surface is PairingSurface.Success) { visible = false; publish(PairingSurface.Standby) } }

    @Synchronized fun findKey(tvId: String, keyId: String): PairingRecord? =
        if (tvId != this.tvId) null else storedRecords().mapNotNull(PairingRecord::decode)
            .firstOrNull { it.keyId == keyId }

    /** Removes every credential only after the durable transaction succeeds. */
    @Synchronized fun forgetAllPairings(): Boolean {
        return commitForgetPairings(
            commit = {
                prefs.edit().remove(KEY_RECORDS).remove(KEY_LAST_DEVICE)
                    .putInt(KEY_LOCKOUT_ROUND, 0).putLong(KEY_LOCKOUT_UNTIL, 0L).commit()
            },
            afterCommit = {
                failures = 0
                lockoutRound = 0
                lockoutUntilElapsed = 0L
                lockoutUntilWall = 0L
                hostThrottles.clear()
                visible = true
                open = null
                publishEligible()
            },
        )
    }

    @Synchronized fun clearPairings() = forgetAllPairings()

    @Synchronized fun pairedCount(): Int = storedRecords().size
    @Synchronized fun pairedLabel(): String? = prefs.getString(KEY_LAST_DEVICE, null)

    /**
     * QR payload v3: a NON-SECRET endpoint so the phone can prefill host and port.
     * The 4-digit code is never in it — it stays the out-of-band factor the user
     * reads off the TV, so scanning alone still authorizes nothing. Returns null
     * rather than a placeholder endpoint when no real binding exists.
     */
    fun qrPayload(host: String, port: Int): String? {
        if (host.isBlank() || port !in 1..65535) return null
        return "flick://pair?v=3&h=$host&p=$port"
    }

    private fun publishEligible() {
        if (!visible) return publish(PairingSurface.Standby)
        if (elapsed() < lockoutUntilElapsed) publish(PairingSurface.Locked(++generation, lockoutUntilElapsed)) else openNewCode()
    }
    private fun openNewCode() {
        val item = PairingSurface.Open(randomCode(), ++generation, elapsed() + CODE_TTL_MS)
        open = item; publish(item)
        // NEVER the code value: a SHA-256 of four digits is a 10,000-entry lookup.
        FlickLog.d("pair", "code rotated gen=${item.generation} ttlMs=$CODE_TTL_MS codeLen=${item.code.length}")
    }
    private fun beginLockout() {
        open = null
        lockoutRound = (lockoutRound + 1).coerceAtMost(MAX_LOCKOUT_ROUNDS)
        val duration = (LOCKOUT_BASE_MS shl (lockoutRound - 1)).coerceAtMost(MAX_LOCKOUT_MS)
        lockoutUntilElapsed = elapsed() + duration; lockoutUntilWall = wall() + duration
        prefs.edit().putInt(KEY_LOCKOUT_ROUND, lockoutRound).putLong(KEY_LOCKOUT_UNTIL, lockoutUntilWall).commit()
        FlickLog.w("pair", "lockout round=$lockoutRound durationMs=$duration")
        publish(PairingSurface.Locked(++generation, lockoutUntilElapsed))
    }
    private fun chargeHost(host: String) {
        val throttle = hostThrottles[host] ?: HostThrottle(0, 0L).also {
            if (hostThrottles.size >= MAX_HOST_THROTTLES) hostThrottles.entries.iterator().let { iterator -> if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
            hostThrottles[host] = it
        }
        throttle.failures++
        if (throttle.failures >= HOST_FAILURES) {
            throttle.failures = 0
            throttle.retryAtElapsedMs = elapsed() + HOST_THROTTLE_MS
        }
    }
    private fun restoreLockout(): Long {
        val remaining = (lockoutUntilWall - wall()).coerceIn(0L, MAX_LOCKOUT_MS)
        return elapsed() + remaining
    }
    private fun publish(surface: PairingSurface) { _snapshot.value = snapshot(surface) }
    private fun snapshot(surface: PairingSurface) = PairingSnapshot(surface, storedRecords().size, pairedLabel())
    private fun storedRecords(): Set<String> = prefs.getStringSet(KEY_RECORDS, emptySet()) ?: emptySet()
    private fun randomCode() = random.nextInt(10_000).toString().padStart(4, '0')
    private fun randomId() = bytes(16)
    private fun randomKey() = bytes(32)
    private fun bytes(size: Int) = Base64.encodeToString(ByteArray(size).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        private const val PREFS = "flick_pairing"
        private const val KEY_NAME = "tv_name"
        private const val KEY_TV_ID = "tv_id"
        private const val KEY_RECORDS = "pairing_records_v2"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_LOCKOUT_ROUND = "lockout_round"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until_epoch_ms"
        const val DEFAULT_TV_NAME = "Flick TV"
        private const val CODE_TTL_MS = 5 * 60_000L
        private const val LOCKOUT_BASE_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 8 * 60_000L
        private const val MAX_LOCKOUT_ROUNDS = 5
        private const val MAX_FAILURES = 5
        private const val HOST_FAILURES = 3
        private const val HOST_THROTTLE_MS = 10_000L
        private const val MAX_HOST_THROTTLES = 32
    }
}

data class PairingRecord(val keyId: String, val key: String, val label: String) {
    companion object {
        fun decode(value: String): PairingRecord? {
            val parts = value.split('|', limit = 3)
            return parts.takeIf { it.size == 3 }?.let { PairingRecord(it[0], it[1], it[2]) }
        }
    }
}

fun normalizeLabel(value: String, max: Int): String {
    val normalized = StringBuilder()
    var whitespace = false
    value.codePoints().forEach { codePoint ->
        if (Character.getType(codePoint) == Character.FORMAT.toInt()) return@forEach
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            if (normalized.isNotEmpty()) whitespace = true
        } else if (Character.isISOControl(codePoint)) {
            return@forEach
        } else {
            if (whitespace) normalized.append(' ')
            whitespace = false
            normalized.appendCodePoint(codePoint)
        }
    }
    val result = normalized.toString()
    if (result.codePointCount(0, result.length) <= max) return result
    return result.substring(0, result.offsetByCodePoints(0, max))
}

/** Keeps authorization state unchanged when SharedPreferences rejects a write. */
internal fun commitPairing(commit: () -> Boolean, afterCommit: () -> Unit): Boolean {
    if (!commit()) return false
    afterCommit()
    return true
}

internal fun commitForgetPairings(commit: () -> Boolean, afterCommit: () -> Unit): Boolean {
    if (!commit()) return false
    afterCommit()
    return true
}

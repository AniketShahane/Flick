package com.flick.receiver.net

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.flick.receiver.util.FlickLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class PairingSnapshot(
    val surface: PairingSurface,
    val pairedCount: Int,
    val mostRecentDeviceLabel: String?,
    val devices: List<PairedPhone> = emptyList(),
)

/**
 * One paired phone as everything outside this file is allowed to see it.
 *
 * It deliberately has no `key` field. The 256-bit pairing secret is the whole
 * authorization story and it never leaves [PairingManager]: the UI layer reads
 * this type, so no screen, snapshot or log can reach the key even by mistake.
 *
 * [pairedAtMs] is null for a record written before this TV recorded dates —
 * unknown, never guessed.
 */
data class PairedPhone(val keyId: String, val label: String, val pairedAtMs: Long?)

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

    @Synchronized fun onForeground() { if (storedPhones().isEmpty()) requestOpen() }
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
        val records = storedRecords() + encodePairingRecord(keyId, key, wall(), label)
        // Key, keyId, date and label are one durable transaction before success is
        // published. KEY_LAST_DEVICE_ID rides along because the bare label alone
        // cannot say WHICH record the Idle screen is naming, and [forget] has to
        // know that to avoid leaving a name behind for a phone it just removed.
        val committed = commitPairing(
            commit = {
                prefs.edit().putStringSet(KEY_RECORDS, records)
                    .putString(KEY_LAST_DEVICE, label).putString(KEY_LAST_DEVICE_ID, keyId)
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
                prefs.edit().remove(KEY_RECORDS).remove(KEY_LAST_DEVICE).remove(KEY_LAST_DEVICE_ID)
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

    /**
     * Removes exactly one credential, durable write first, and returns whether it
     * is gone. False means this TV still admits that phone — no record carried
     * [keyId], or `SharedPreferences` rejected the write — so a caller can never
     * report a forget that did not happen.
     *
     * Unlike [forgetAllPairings] this does NOT reopen the pairing surface while
     * any phone remains: a code is an authorization surface, and removing one of
     * several phones is not a request to admit a new one. Reaching zero is the
     * exception, and it takes exactly the [forgetAllPairings] path — including the
     * lockout reset, so a TV cannot be left with no phones and no way to re-pair
     * for the eight minutes a lockout can still be running.
     *
     * That exception is a contract on the CALLER: reaching zero opens a code, and
     * this class cannot see which surface the screen is showing. A caller that
     * keeps a higher-priority surface up after this returns leaves a code that is
     * live, rotating and accepting attempts with nothing rendering it — which is
     * exactly what [closeSurface] refuses to allow. `ReceiverApp` closes Settings
     * on the emptied result for that reason.
     */
    @Synchronized fun forget(keyId: String): Boolean {
        val records = storedRecords()
        val remaining = recordsWithout(records, keyId) ?: return false
        val emptied = remaining.isEmpty()
        val nextLast = lastDeviceAfterForget(
            remaining = remaining,
            forgottenKeyId = keyId,
            forgottenLabel = records.mapNotNull(::decodePairingRecord).firstOrNull { it.keyId == keyId }?.label,
            storedKeyId = prefs.getString(KEY_LAST_DEVICE_ID, null),
            storedLabel = prefs.getString(KEY_LAST_DEVICE, null),
        )
        return commitForgetPairings(
            commit = {
                prefs.edit().apply {
                    if (emptied) remove(KEY_RECORDS) else putStringSet(KEY_RECORDS, remaining)
                    if (nextLast != null) {
                        if (nextLast.keyId == null) {
                            remove(KEY_LAST_DEVICE); remove(KEY_LAST_DEVICE_ID)
                        } else {
                            putString(KEY_LAST_DEVICE, nextLast.label)
                            putString(KEY_LAST_DEVICE_ID, nextLast.keyId)
                        }
                    }
                    if (emptied) { putInt(KEY_LOCKOUT_ROUND, 0); putLong(KEY_LOCKOUT_UNTIL, 0L) }
                }.commit()
            },
            afterCommit = {
                FlickLog.i("pair", "forgot keyIdFp=${FlickLog.fp(keyId)} remaining=${remaining.size}")
                if (emptied) {
                    failures = 0
                    lockoutRound = 0
                    lockoutUntilElapsed = 0L
                    lockoutUntilWall = 0L
                    hostThrottles.clear()
                    visible = true
                    open = null
                    publishEligible()
                } else {
                    publish(_snapshot.value.surface)
                }
            },
        )
    }

    @Synchronized fun clearPairings() = forgetAllPairings()

    /** Every paired phone, newest first. Never carries the pairing key. */
    @Synchronized fun pairedDevices(): List<PairedPhone> = storedPhones()

    @Synchronized fun pairedCount(): Int = storedPhones().size
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
    // Count and list come from the same decoded read. A record that cannot be
    // decoded can never satisfy [findKey] either, so counting it would claim a
    // phone is paired that this TV would refuse — and would leave the Settings
    // list one row short of its own heading.
    private fun snapshot(surface: PairingSurface): PairingSnapshot {
        val phones = storedPhones()
        return PairingSnapshot(surface, phones.size, pairedLabel(), phones)
    }
    private fun storedPhones(): List<PairedPhone> = pairedPhonesOf(storedRecords())
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
        // The key is still `_v2`: v3 changed the ENCODING of a record, not the
        // store it lives in, and both shapes coexist in this one set so an
        // existing pairing survives the upgrade without re-pairing.
        private const val KEY_RECORDS = "pairing_records_v2"
        private const val KEY_LAST_DEVICE = "last_device"
        private const val KEY_LAST_DEVICE_ID = "last_device_key_id"
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

/** [pairedAtMs] is null for a v2 record: the date is unknown, never invented. */
data class PairingRecord(
    val keyId: String,
    val key: String,
    val label: String,
    val pairedAtMs: Long? = null,
) {
    companion object {
        fun decode(value: String): PairingRecord? = decodePairingRecord(value)
    }
}

/**
 * The version sentinel that opens a v3 record — and the reason there is one.
 *
 * v2 is `keyId|key|label`, with the label LAST precisely so it may contain `|`,
 * which [normalizeLabel] does not strip. Adding a date field ahead of the label
 * makes the two shapes ambiguous by field count alone: the perfectly legal v2
 * label `12345|home` splits into four parts whose third parses as a number, and a
 * migration that trusted the count would silently read a phone called "home"
 * paired in 1970 — a wrong date shown to the user and half a label lost. A keyId
 * is always 22 base64url characters, so a leading `v3` is a token no v2 record
 * can produce, and the two are told apart with certainty rather than by guess.
 */
private const val RECORD_V3 = "v3"

internal fun encodePairingRecord(keyId: String, key: String, pairedAtMs: Long, label: String): String =
    "$RECORD_V3|$keyId|$key|$pairedAtMs|$label"

/**
 * Reads either shape. A v2 record migrates in place with an unknown date rather
 * than being dropped: it is a live credential, and the user must not have to
 * re-pair a phone because this TV started recording dates.
 */
internal fun decodePairingRecord(value: String): PairingRecord? {
    if (value.startsWith("$RECORD_V3|")) {
        val parts = value.split('|', limit = 5)
        // A corrupt date costs the date, never the credential.
        return parts.takeIf { it.size == 5 }
            ?.let { PairingRecord(it[1], it[2], it[4], it[3].toLongOrNull()) }
    }
    val parts = value.split('|', limit = 3)
    return parts.takeIf { it.size == 3 }?.let { PairingRecord(it[0], it[1], it[2], null) }
}

/**
 * The stored set with [keyId]'s record removed, or null when no record carries it
 * — a forget that removed nothing must say so rather than report success for a
 * phone this TV still admits.
 *
 * A record that cannot be decoded is left in place. It authorizes nothing (it can
 * never satisfy [PairingManager.findKey] either), and dropping it here would make
 * forgetting one phone quietly rewrite the whole store.
 */
internal fun recordsWithout(records: Collection<String>, keyId: String): Set<String>? {
    val doomed = records.firstOrNull { decodePairingRecord(it)?.keyId == keyId } ?: return null
    return records.filterNotTo(LinkedHashSet<String>()) { it == doomed }
}

/** Null in both fields means the TV names no phone at all. */
internal data class LastDevice(val label: String?, val keyId: String?)

/**
 * What `last_device` must hold once [forgottenKeyId] is gone, or null when it
 * still names a phone this TV admits and may be left exactly as it is.
 *
 * The Idle screen renders that value as "Paired with …", so it may never name a
 * phone that has just been removed. A store written before v3 recorded no
 * last-paired key id, and the bare label is then the only signal it left: that
 * costs a name when two phones share one, and shows "Ready" rather than a wrong
 * name, which is the right direction to be wrong in.
 *
 * The replacement is the newest phone still paired. Undated records sort last, so
 * an all-v2 remainder promotes a label alphabetically rather than by recency — it
 * is still a phone that IS paired, which is the honest half of the claim.
 */
internal fun lastDeviceAfterForget(
    remaining: Collection<String>,
    forgottenKeyId: String,
    forgottenLabel: String?,
    storedKeyId: String?,
    storedLabel: String?,
): LastDevice? {
    val stale = if (storedKeyId != null) {
        storedKeyId == forgottenKeyId
    } else {
        storedLabel != null && storedLabel == forgottenLabel
    }
    if (!stale) return null
    val next = pairedPhonesOf(remaining).firstOrNull() ?: return LastDevice(null, null)
    return LastDevice(next.label, next.keyId)
}

/**
 * Every decodable record as the UI sees it, in a TOTAL order. A `StringSet` has
 * no order of its own, so without one the Settings list would reshuffle between
 * process restarts for no reason the viewer could see. Dated records come first,
 * newest first; records migrated from v2 have no date at all and follow, by
 * label — with the key id as the final tiebreak so two identically named,
 * identically dated phones still land in the same order every read.
 */
internal fun pairedPhonesOf(records: Collection<String>): List<PairedPhone> =
    records.mapNotNull(::decodePairingRecord)
        .map { PairedPhone(it.keyId, it.label, it.pairedAtMs) }
        .sortedWith(
            compareBy<PairedPhone> { it.pairedAtMs == null }
                .thenByDescending { it.pairedAtMs ?: 0L }
                .thenBy { it.label.lowercase(Locale.ROOT) }
                .thenBy { it.keyId },
        )

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

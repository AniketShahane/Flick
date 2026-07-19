package com.flick.receiver.net

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Trust establishment for the control channel (control-channel.md §3).
 *
 * A rotating 4-digit code proves the phone's user can SEE the TV screen (a silent
 * LAN attacker can't). On success the TV mints a persistent 128-bit pairing key
 * bound to that phone; subsequent connects `resume` with the key and skip the
 * code. Keys live in app-private storage and are NEVER logged.
 *
 * All secret comparisons are constant-time.
 *
 * Brute-force resistance: the 10 000-value code space is tiny, so [attemptPair]
 * bundles validate + mint + rotate into ONE synchronized step and, after
 * [MAX_FAILED_ATTEMPTS] misses, ROTATES the code and imposes an escalating
 * lockout during which every attempt fails. Misses are counted both globally and
 * per remote host, so a single abusive peer is locked out on its own budget
 * (independent of the global rotate) while honest peers keep their fresh code.
 * Enumeration therefore never lands.
 */
class PairingManager(context: Context) {

    /** Outcome of a single [attemptPair] handshake. */
    sealed interface PairResult {
        data class Success(val key: String) : PairResult
        data object Denied : PairResult
        data object LockedOut : PairResult
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Volatile
    private var code: String = randomCode()

    // Brute-force throttle state (guarded by the monitor via @Synchronized).
    private var failedAttempts: Int = 0
    private var lockoutRound: Int = 0
    private var lockedUntilMs: Long = 0L

    /** Per-remote-host miss budget + escalating lockout, so one peer can't dilute
     *  the global counter and can be blocked independently. */
    private class HostThrottle {
        var failedAttempts: Int = 0
        var lockoutRound: Int = 0
        var lockedUntilMs: Long = 0L
    }

    // Bounded (a burst of distinct source hosts can't grow it without limit; real
    // LAN peers are few and completing a TCP handshake makes source spoofing hard).
    private val hostThrottles = HashMap<String, HostThrottle>()

    /** The current 4-digit code (single-use; rotates on success or a burst of misses). */
    fun currentCode(): String = code

    /** The user-visible TV name (editable in settings; advertised over NSD). */
    var tvName: String
        get() = prefs.getString(KEY_NAME, DEFAULT_TV_NAME) ?: DEFAULT_TV_NAME
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim().ifBlank { DEFAULT_TV_NAME }).apply()
        }

    /** The QR payload shown on the pairing screen (T1). */
    fun qrPayload(host: String, port: Int): String =
        "flick://pair?host=$host&port=$port&code=$code&v=1"

    /** True once at least one phone has paired (drives T1 vs T2). */
    fun isPaired(): Boolean = storedKeys().isNotEmpty()

    /** Label of the most-recently paired phone, or null. */
    fun pairedLabel(): String? = prefs.getString(KEY_LAST_DEVICE, null)

    /** How many phones are paired (for the settings summary). */
    fun pairedCount(): Int = storedKeys().size

    /**
     * Atomically validate an inbound `hello` code and, on success, mint + persist
     * the pairing key and rotate the code — all under one lock so a single-use
     * code can never be consumed twice by racing guesses. Failed guesses are
     * counted both globally and (when [remoteHost] is known) per host; a burst on
     * either budget rotates/locks with an escalating cooldown.
     */
    @Synchronized
    fun attemptPair(candidate: String, deviceLabel: String, remoteHost: String?): PairResult {
        val now = SystemClock.elapsedRealtime()
        // Global lockout: a recent burst from anywhere rotated the code.
        if (now < lockedUntilMs) return PairResult.LockedOut
        // Per-host lockout: this specific peer burned through its own budget.
        val throttle = remoteHost?.let { hostThrottleFor(it, now) }
        if (throttle != null && now < throttle.lockedUntilMs) return PairResult.LockedOut

        if (!constantTimeEquals(candidate, code)) {
            // Per-host budget: lock this peer independently on an escalating cooldown.
            if (throttle != null) {
                throttle.failedAttempts++
                if (throttle.failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    throttle.failedAttempts = 0
                    throttle.lockoutRound = (throttle.lockoutRound + 1).coerceAtMost(MAX_LOCKOUT_ROUNDS)
                    throttle.lockedUntilMs = now + (LOCKOUT_BASE_MS shl (throttle.lockoutRound - 1))
                }
            }
            // Global budget: rotate the guessed-at code and lock the pairing surface
            // with an escalating cooldown so the 10 000-code space can't be walked.
            failedAttempts++
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                code = randomCode()
                failedAttempts = 0
                lockoutRound = (lockoutRound + 1).coerceAtMost(MAX_LOCKOUT_ROUNDS)
                lockedUntilMs = now + (LOCKOUT_BASE_MS shl (lockoutRound - 1))
            }
            return PairResult.Denied
        }

        val key = mintKeyInternal(deviceLabel)
        code = randomCode()
        failedAttempts = 0
        lockoutRound = 0
        lockedUntilMs = 0L
        hostThrottles.clear()
        return PairResult.Success(key)
    }

    /**
     * The per-host throttle for [host], created on demand. Bounds the map: evicts
     * entries that are neither locked out nor mid-attack, and if all live, drops
     * the earliest-expiring so memory can't grow without limit. Callers hold the monitor.
     */
    private fun hostThrottleFor(host: String, now: Long): HostThrottle {
        if (hostThrottles.size >= MAX_TRACKED_HOSTS && !hostThrottles.containsKey(host)) {
            hostThrottles.entries.removeAll { (_, t) -> t.lockedUntilMs < now && t.failedAttempts == 0 }
            if (hostThrottles.size >= MAX_TRACKED_HOSTS) {
                hostThrottles.entries.minByOrNull { it.value.lockedUntilMs }
                    ?.let { hostThrottles.remove(it.key) }
            }
        }
        return hostThrottles.getOrPut(host) { HostThrottle() }
    }

    /** Constant-time membership check for an inbound `resume` key. */
    fun isKnownKey(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        // Compare against every stored key in constant time; short-circuit only
        // on a match (the set of keys is not itself a secret).
        var matched = false
        for (k in storedKeys()) {
            if (constantTimeEquals(candidate, k)) matched = true
        }
        return matched
    }

    /** Mint + persist a new pairing key bound to [deviceLabel]. Callers hold the monitor. */
    private fun mintKeyInternal(deviceLabel: String): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val keys = storedKeys().toMutableSet().apply { add(key) }
        prefs.edit()
            .putStringSet(KEY_KEYS, keys)
            .putString(KEY_LAST_DEVICE, deviceLabel)
            .apply()
        return key
    }

    /** Forget all paired phones (settings action). */
    fun clearPairings() {
        prefs.edit().remove(KEY_KEYS).remove(KEY_LAST_DEVICE).apply()
    }

    private fun storedKeys(): Set<String> = prefs.getStringSet(KEY_KEYS, emptySet()) ?: emptySet()

    private fun randomCode(): String = (random.nextInt(10_000)).toString().padStart(4, '0')

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        private const val PREFS = "flick_pairing"
        private const val KEY_NAME = "tv_name"
        private const val KEY_KEYS = "pairing_keys"
        private const val KEY_LAST_DEVICE = "last_device"
        const val DEFAULT_TV_NAME = "Flick TV"

        /** Wrong guesses tolerated before the code rotates and the lockout starts. */
        private const val MAX_FAILED_ATTEMPTS = 5

        /** First lockout (30s), doubling each round up to [MAX_LOCKOUT_ROUNDS] (~8 min). */
        private const val LOCKOUT_BASE_MS = 30_000L
        private const val MAX_LOCKOUT_ROUNDS = 5

        /** Upper bound on tracked per-host throttles (LAN peers are few). */
        private const val MAX_TRACKED_HOSTS = 64
    }
}

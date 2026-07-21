package com.flick.receiver.util

import android.util.Log
import com.flick.receiver.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

/**
 * TV-side diagnostics. `adb logcat -s FlickTV:V` is the primary channel; the same
 * lines are mirrored into a bounded in-memory ring buffer so the TV can render
 * them without a laptop attached.
 *
 * Line format is `[area] key=value key=value`, with a shared `area` vocabulary
 * kept identical to the phone's `FlickPhone`: `bind`, `lan`, `nsd`, `ws`, `auth`,
 * `pair`, `cast`, `probe`, `player`, `http`. Message bodies are English literals
 * in code on purpose — this is developer output, not user-facing copy, and is a
 * deliberate exception to the strings.xml rule. UI chrome around the diagnostics
 * view is still a string resource.
 *
 * REDACTION CONTRACT (identical on both modules):
 *
 * SAFE to log at any level:
 *  - bound host/port, peer IP
 *  - NSD service name/model/state/version, tvId, keyId, device labels
 *  - enum and sealed-class simple names, wire result codes, HTTP status
 *  - counts, lengths, attempts, generations, latencies
 *  - decoder name, resolution, HDR type, Wi-Fi band/RSSI
 *
 * NEVER, AT ANY LEVEL:
 *  - the 4-digit pairing code
 *  - the pairing `key`
 *  - the HMAC `proof`
 *  - the media session token, or any full `/v/{token}` URL
 *  - the raw deep-link URI
 *  - SSID / BSSID
 *
 * DO NOT FINGERPRINT THE PAIRING CODE. A SHA-256 of a 4-digit code has a
 * 10,000-entry rainbow table, so a hash pasted into a bug report IS the
 * plaintext code. Log `codeLen=4` / `codePresent=true` instead. Nonces MAY be
 * fingerprinted with [fp] but never printed verbatim.
 *
 * There is deliberately no generic `log(frame: JSONObject)` helper: per-field
 * call sites are what keeps secrets out of the buffer in the first place.
 *
 * The ring buffer is memory-only and is never persisted to disk or backed up.
 */
object FlickLog {

    const val TAG = "FlickTV"

    /** One captured line. [atMs] is wall-clock so it lines up with logcat stamps. */
    data class Entry(val atMs: Long, val level: Char, val area: String, val message: String)

    private const val CAPACITY = 200
    private val lock = Any()
    private val ring = ArrayDeque<Entry>()
    private val _revision = MutableStateFlow(0L)

    /** Bumped on every append so Compose can observe the buffer without polling. */
    val revision: StateFlow<Long> = _revision

    fun v(area: String, message: String) {
        record('V', area, message)
        if (BuildConfig.DEBUG) Log.v(TAG, line(area, message))
    }

    fun d(area: String, message: String) {
        record('D', area, message)
        if (BuildConfig.DEBUG) Log.d(TAG, line(area, message))
    }

    fun i(area: String, message: String) {
        record('I', area, message)
        Log.i(TAG, line(area, message))
    }

    fun w(area: String, message: String, error: Throwable? = null) {
        record('W', area, withError(message, error))
        Log.w(TAG, line(area, message), error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        record('E', area, withError(message, error))
        Log.e(TAG, line(area, message), error)
    }

    /** Newest-first snapshot of the ring buffer. */
    fun recent(): List<Entry> = synchronized(lock) { ring.toList() }.asReversed()

    fun clear() {
        synchronized(lock) { ring.clear() }
        _revision.value = _revision.value + 1
    }

    /**
     * 8-hex-character SHA-256 prefix — enough to correlate two devices' logs for a
     * nonce or key id, far too short to be a credential. Never call this on the
     * pairing code (see the class KDoc).
     */
    fun fp(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(8)
        for (index in 0 until 4) out.append(HEX[(digest[index].toInt() shr 4) and 0xf]).append(HEX[digest[index].toInt() and 0xf])
        return out.toString()
    }

    /**
     * `scheme://host:port` only. The path is dropped because the media path is
     * exactly the session token.
     */
    fun endpoint(url: String?): String {
        val value = url ?: return "none"
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return "malformed"
        val authorityStart = schemeEnd + 3
        val authorityEnd = value.indexOf('/', authorityStart).let { if (it < 0) value.length else it }
        val authority = value.substring(authorityStart, authorityEnd)
        // Strip any user-info; it is not an endpoint and may carry a secret.
        val hostPort = authority.substringAfterLast('@')
        return value.substring(0, schemeEnd) + "://" + hostPort
    }

    private fun withError(message: String, error: Throwable?): String =
        if (error == null) message else "$message err=${error.javaClass.simpleName}"

    private fun line(area: String, message: String) = "[$area] $message"

    private fun record(level: Char, area: String, message: String) {
        synchronized(lock) {
            ring.addLast(Entry(System.currentTimeMillis(), level, area, message))
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        _revision.value = _revision.value + 1
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

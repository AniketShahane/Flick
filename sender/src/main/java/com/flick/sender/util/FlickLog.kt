package com.flick.sender.util

import android.util.Log
import com.flick.sender.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * Phone-side diagnostics channel. Every line is written to logcat under [TAG] and
 * appended to a bounded in-memory ring buffer that the in-app diagnostics sheet
 * renders — the ring is the primary channel, because `adb logcat` is unusable on
 * some OEM builds and the user cannot be asked to attach a cable.
 *
 * Line format is `[area] key=value key=value`, with the `area` vocabulary shared
 * byte-for-byte with the receiver's FlickLog: `bind`, `lan`, `nsd`, `ws`, `auth`,
 * `pair`, `cast`, `probe`, `player`, `http`.
 *
 * REDACTION CONTRACT (identical on both modules):
 *  - SAFE: bound host/port, peer IP, NSD name/model/state/version, tvId, keyId,
 *    device labels, enum + sealed-class simple names, wire result codes, HTTP
 *    status, counts/lengths/attempts/generations, latencies, decoder name,
 *    resolution, HDR type, Wi-Fi band/RSSI.
 *  - NEVER, AT ANY LEVEL: the 4-digit pairing code, the pairing `key`, the HMAC
 *    `proof`, the media session token, any full `/v/{token}` URL, the raw
 *    deep-link URI, SSID/BSSID.
 *  - DO NOT FINGERPRINT THE PAIRING CODE — a SHA-256 of a 4-digit code has a
 *    10,000-entry rainbow table, so a hash in a pasted log IS the plaintext code.
 *    Log `codeLen=4` / `codePresent=true` instead. Nonces MAY be fingerprinted
 *    but never printed verbatim.
 *  - No generic `log(frame: JSONObject)` helper. Per-field call sites only — that
 *    is what keeps secrets out.
 *
 * Message bodies are English literals in code: this is developer output, not
 * user-facing copy, and is a deliberate recorded exception to the strings.xml
 * rule. The diagnostics UI chrome around it does live in strings.xml.
 *
 * The ring buffer is memory only and is never persisted to disk.
 */
object FlickLog {

    const val TAG = "FlickPhone"

    /** One captured line. [level] mirrors the logcat priority letter. */
    data class Entry(val timestampMs: Long, val level: Char, val area: String, val message: String)

    private const val CAPACITY = 200

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Snapshot of the ring, oldest first. Replaced wholesale on every write. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _revision = MutableStateFlow(0L)

    /** Monotonic write counter — cheap change signal for the diagnostics sheet. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun v(area: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.v(TAG, format(area, message))
        record('V', area, message)
    }

    fun d(area: String, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, format(area, message))
        record('D', area, message)
    }

    fun i(area: String, message: String) {
        Log.i(TAG, format(area, message))
        record('I', area, message)
    }

    fun w(area: String, message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, format(area, message)) else Log.w(TAG, format(area, message), error)
        record('W', area, message)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, format(area, message)) else Log.e(TAG, format(area, message), error)
        record('E', area, message)
    }

    /** Oldest-first snapshot of the ring. */
    fun recent(): List<Entry> = _entries.value

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _entries.value = emptyList()
            _revision.value = _revision.value + 1L
        }
    }

    /**
     * 8-hex-char SHA-256 prefix — correlates a value across lines without
     * disclosing it. Never call this on the 4-digit pairing code.
     */
    fun fp(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(8)
        for (index in 0 until 4) out.append(HEX[(digest[index].toInt() shr 4) and 0xF]).append(HEX[digest[index].toInt() and 0xF])
        return out.toString()
    }

    /** `scheme://host:port` only — the path of a media URL carries the session token. */
    fun endpoint(url: String?): String {
        if (url.isNullOrEmpty()) return "none"
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return "malformed"
        val scheme = parsed.scheme ?: return "malformed"
        val host = parsed.host ?: return "malformed"
        return if (parsed.port > 0) "$scheme://$host:${parsed.port}" else "$scheme://$host"
    }

    private fun format(area: String, message: String) = "[$area] $message"

    private fun record(level: Char, area: String, message: String) {
        synchronized(lock) {
            ring.addLast(Entry(System.currentTimeMillis(), level, area, message))
            while (ring.size > CAPACITY) ring.removeFirst()
            _entries.value = ring.toList()
            _revision.value = _revision.value + 1L
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

package com.flick.sender.net

import com.flick.sender.model.VideoRotation
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ControlProtocolV2 {
    const val VERSION = 2
    const val MAX_FRAME_BYTES = 16 * 1024

    /** A sideloaded-subtitle label is display text; it gets the media title's budget. */
    const val SUBTITLE_LABEL_MAX = 200

    /** The `setRotation` verb's two shapes, named by the field that tells them apart. */
    const val ROTATION_DEGREES_FIELD = "degrees"
    const val ROTATION_AUTO_FIELD = "auto"

    /** Matches the receiver's ceiling exactly; a longer value is not a tag it accepts. */
    private const val LANGUAGE_TAG_MAX = 20

    val capabilities =
        listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac", "audio-delay")
    private val idPattern = Regex("[A-Za-z0-9_-]{22}")
    private val proofPattern = Regex("[A-Za-z0-9_-]{43}")
    // Language, optional script, optional region — the receiver's `subLang` grammar
    // byte for byte. A sender that emitted a wider tag would fail the whole cast.
    private val languageTagPattern = Regex("[A-Za-z]{2,3}(-[A-Za-z]{4})?(-([A-Za-z]{2}|[0-9]{3}))?")

    fun id(value: String?) = value != null && idPattern.matches(value) && decodedLength(value) == 16
    fun key(value: String?) = value != null && proofPattern.matches(value) && decodedLength(value) == 32
    fun code(value: String?) = value != null && PairLaunch.isCode(value)
    fun canonicalCaps(value: List<String>?) = value == capabilities
    fun normalizedLabel(value: String?, maximum: Int): String? {
        if (value == null) return null
        val normalized = StringBuilder()
        var whitespacePending = false
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.getType(codePoint) == Character.FORMAT.toInt()) continue
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (normalized.isNotEmpty()) whitespacePending = true
                continue
            }
            if (Character.isISOControl(codePoint)) continue
            if (whitespacePending) normalized.append(' ')
            normalized.appendCodePoint(codePoint)
            whitespacePending = false
        }
        val compact = normalized.toString()
        if (compact.isEmpty()) return null
        val end = compact.offsetByCodePoints(0, compact.codePointCount(0, compact.length).coerceAtMost(maximum))
        return compact.substring(0, end)
    }

    /**
     * The optional external-subtitle fields of a `loadMedia` frame, in wire order.
     * Empty whenever no subtitle is attached — the frame then stays byte-identical to
     * the v=2 frame an un-updated receiver already parses, which is what lets ordinary
     * playback keep working without a protocol bump.
     */
    fun subtitleFields(url: String?, label: String?, language: String?): List<Pair<String, String>> {
        if (url.isNullOrEmpty()) return emptyList()
        // The receiver requires the label whenever the URL is present and rejects the
        // whole frame otherwise, so a label that normalizes to nothing drops the
        // attachment instead of costing the video its load.
        val safeLabel = normalizedLabel(label, SUBTITLE_LABEL_MAX) ?: return emptyList()
        val fields = ArrayList<Pair<String, String>>(3)
        fields.add("subUrl" to url)
        fields.add("subLabel" to safeLabel)
        languageTag(language)?.let { fields.add("subLang" to it) }
        return fields
    }

    /**
     * The ONE field a `setRotation` frame carries beyond the `t`/`v`/`castId`
     * envelope, and therefore which of the verb's two shapes this phone is
     * sending.
     *
     * The receiver validates each shape against its own exact field set, so the
     * two are alternatives rather than a base plus an option: `degrees` asserts a
     * quarter turn, `auto` hands the reading back. Auto is deliberately NOT a
     * value inside `degrees` — a sentinel there would put a mode in the value
     * domain of a numeric field, and a frame off the quarter-turn grid could no
     * longer be called malformed on sight.
     */
    fun rotationField(rotation: VideoRotation): Pair<String, Any> = when (val extra = rotation.extraDegrees) {
        null -> ROTATION_AUTO_FIELD to true
        else -> ROTATION_DEGREES_FIELD to extra
    }

    /** A well-formed BCP-47 tag, or null so the caller omits `subLang` entirely. */
    fun languageTag(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.length > LANGUAGE_TAG_MAX || !languageTagPattern.matches(trimmed)) return null
        return trimmed
    }

    /**
     * True when [subUrl] resolves to the same origin as the media [url]. The receiver
     * revalidates this, but the sender must never put a subtitle URL on the wire that
     * points anywhere except the media socket it just bound.
     */
    fun sameHttpOrigin(url: String, subUrl: String): Boolean {
        val media = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val sub = runCatching { java.net.URI(subUrl) }.getOrNull() ?: return false
        if (!"http".equals(media.scheme, ignoreCase = true) || media.host == null || media.port <= 0) return false
        return media.scheme.equals(sub.scheme, ignoreCase = true) &&
            media.host.equals(sub.host, ignoreCase = true) && media.port == sub.port &&
            sub.userInfo == null && sub.query == null && sub.fragment == null
    }

    fun randomId(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val data = ByteArray(16); random.nextBytes(data)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    fun transcript(
        role: String, tvId: String, keyId: String, clientNonce: String, serverNonce: String,
        peerIp: String, serverHost: String, serverPort: Int, tv: String,
    ): ByteArray {
        val fields = listOf(
            "Flick-Control-Resume-V2", role, "2", tvId, keyId, clientNonce, serverNonce,
            peerIp, serverHost, serverPort.toString(), tv, capabilities.joinToString(","),
        )
        val out = ByteArrayOutputStream()
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun proof(key: String, role: String, tvId: String, keyId: String, clientNonce: String,
              serverNonce: String, peerIp: String, serverHost: String, serverPort: Int, tv: String): String {
        val bytes = Base64.getUrlDecoder().decode(key)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(transcript(role, tvId, keyId, clientNonce, serverNonce, peerIp, serverHost, serverPort, tv)))
    }

    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(StandardCharsets.US_ASCII), actual.toByteArray(StandardCharsets.US_ASCII))

    fun legacyKeyId(key: String): String {
        val bytes = Base64.getUrlDecoder().decode(key)
        val out = ByteArrayOutputStream()
        listOf("Flick-KeyId-V2".toByteArray(StandardCharsets.UTF_8), bytes).forEach {
            out.write(ByteBuffer.allocate(4).putInt(it.size).array()); out.write(it)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()).copyOf(16))
    }

    private fun decodedLength(value: String): Int = runCatching {
        Base64.getUrlDecoder().decode(value).size
    }.getOrDefault(-1)
}

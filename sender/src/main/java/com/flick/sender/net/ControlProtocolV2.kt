package com.flick.sender.net

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
    val capabilities = listOf("cast-ack", "first-frame-ready", "structured-errors", "resume-hmac")
    private val idPattern = Regex("[A-Za-z0-9_-]{22}")
    private val proofPattern = Regex("[A-Za-z0-9_-]{43}")

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

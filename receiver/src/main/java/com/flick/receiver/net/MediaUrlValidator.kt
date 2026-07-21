package com.flick.receiver.net

import java.net.URI

/** Pure, canonical direct-play URL guard; no DNS or URL normalization is permitted. */
object MediaUrlValidator {
    fun isValid(raw: String?, peerIp: String): Boolean {
        val value = raw ?: return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return value.length <= 256 && isPrivateIpv4(peerIp) && uri.scheme == "http" &&
            uri.host == peerIp && uri.port == 8080 && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.matches(Regex("^/v/[A-Za-z0-9_-]{22}$")) == true &&
            !value.contains('%')
    }

    fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') || !it.all(Char::isDigit) }) return false
        val n = parts.map(String::toIntOrNull)
        return n.all { it != null && it in 0..255 } &&
            (n[0] == 10 || (n[0] == 172 && n[1]!! in 16..31) || (n[0] == 192 && n[1] == 168))
    }
}

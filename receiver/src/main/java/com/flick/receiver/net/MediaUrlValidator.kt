package com.flick.receiver.net

import java.net.URI

/** Pure, canonical direct-play URL guard; no DNS or URL normalization is permitted. */
object MediaUrlValidator {
    fun isValid(raw: String?, peerIp: String): Boolean = isPinned(raw, peerIp, MEDIA_PATH)

    /**
     * The sideloaded-subtitle route. Identical pinning to the media route —
     * same authenticated peer host, same port, same scheme, same no-redirect
     * shape — because `subUrl` arrives on the same untrusted wire as `url`.
     * Only the path differs, so neither route can be spelled as the other.
     */
    fun isValidSubtitle(raw: String?, peerIp: String): Boolean = isPinned(raw, peerIp, SUBTITLE_PATH)

    private fun isPinned(raw: String?, peerIp: String, path: Regex): Boolean {
        val value = raw ?: return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return value.length <= 256 && isPrivateIpv4(peerIp) && uri.scheme == "http" &&
            uri.host == peerIp && uri.port == 8080 && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath?.matches(path) == true &&
            !value.contains('%')
    }

    fun isPrivateIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') || !it.all(Char::isDigit) }) return false
        val n = parts.map(String::toIntOrNull)
        return n.all { it != null && it in 0..255 } &&
            (n[0] == 10 || (n[0] == 172 && n[1]!! in 16..31) || (n[0] == 192 && n[1] == 168))
    }

    private val MEDIA_PATH = Regex("^/v/[A-Za-z0-9_-]{22}$")
    private val SUBTITLE_PATH = Regex("^/s/[A-Za-z0-9_-]{22}$")
}

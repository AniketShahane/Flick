package com.flick.sender.net

import android.net.Uri

sealed interface PairLaunchParseResult {
    data object Valid : PairLaunchParseResult
    data object Invalid : PairLaunchParseResult
    data object UnsupportedVersion : PairLaunchParseResult
}

data class IncomingPairEvent(val eventId: Long, val result: PairLaunchParseResult)

/** Parses only the launch envelope. Deliberately never retains a caller supplied URI. */
object PairLaunch {
    fun parse(uri: Uri?): PairLaunchParseResult = uri?.toString()?.let(::parse) ?: PairLaunchParseResult.Invalid

    fun parse(raw: String): PairLaunchParseResult {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return PairLaunchParseResult.Invalid
        if (uri.isOpaque || !uri.isAbsolute) return PairLaunchParseResult.Invalid
        if (!uri.scheme.equals("flick", ignoreCase = true) || uri.host != "pair") return PairLaunchParseResult.Invalid
        if (uri.userInfo != null || uri.port != -1 || uri.fragment != null || uri.path.orEmpty().isNotEmpty()) {
            return PairLaunchParseResult.Invalid
        }
        val params = uri.rawQuery?.split('&')?.map {
            val separator = it.indexOf('=')
            if (separator < 0) return PairLaunchParseResult.Invalid
            it.substring(0, separator) to it.substring(separator + 1)
        } ?: return PairLaunchParseResult.Invalid
        if (params.size != 1 || params.single().first != "v") return PairLaunchParseResult.Invalid
        return when (params.single().second) {
            "2" -> PairLaunchParseResult.Valid
            "1" -> PairLaunchParseResult.UnsupportedVersion
            else -> PairLaunchParseResult.Invalid
        }
    }

    fun isCanonicalIpv4(value: String): Boolean {
        if (value.isEmpty() || value.any { it !in '0'..'9' && it != '.' }) return false
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return (first == 10 || first == 192 && second == 168 || first == 172 && second in 16..31)
    }

    fun isCanonicalPort(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' } &&
            !(value.length > 1 && value.startsWith('0')) && (value.toIntOrNull() ?: 0) in 1..65535

    fun isCode(value: String): Boolean = value.length == 4 && value.all { it in '0'..'9' }
}

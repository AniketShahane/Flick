package com.flick.sender.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One line of [IncidentJournal]: `<UTC ISO-8601 millis> <event> k=v k=v`.
 *
 * The journal outlives the process and is read out of release builds, so what it can hold
 * is limited by shape: a value is a Boolean, an Int, a Long, an enum name, or a String
 * token of letters, digits and underscores starting with a letter. An IP address, a URL or
 * a MAC cannot match, and is written as [INVALID] instead.
 *
 * The shape does not stop a secret or a name: many base64url tokens (the session token, a
 * cast id) and many network, host and device names fit it. For those the protection is the
 * call sites, which must NEVER pass an SSID, a BSSID, a hostname, a device or TV name, a
 * file name, a token or an id.
 */
internal object IncidentLine {

    const val INVALID = "invalid"

    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")
    private val TOKEN = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(epochMs: Long, event: String, fields: Array<out Pair<String, Any?>>): String {
        val line = StringBuilder(64)
        line.append(TIMESTAMP.format(Instant.ofEpochMilli(epochMs)))
        line.append(' ').append(name(event))
        for ((key, value) in fields) line.append(' ').append(name(key)).append('=').append(value(value))
        return line.toString()
    }

    private fun name(raw: String): String = if (NAME.matches(raw)) raw else INVALID

    private fun value(raw: Any?): String = when (raw) {
        is Boolean, is Int, is Long -> raw.toString()
        is Enum<*> -> raw.name.takeIf(TOKEN::matches) ?: INVALID
        is String -> raw.takeIf(TOKEN::matches) ?: INVALID
        else -> INVALID
    }
}

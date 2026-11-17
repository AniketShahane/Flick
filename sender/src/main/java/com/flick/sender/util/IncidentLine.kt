package com.flick.sender.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One line of [IncidentJournal]: `<UTC ISO-8601 millis> <event> k=v k=v`.
 *
 * The journal outlives the process and is read out of release builds, so what it can hold
 * is fixed by shape rather than by each caller's care: a value is a Boolean, an Int, a
 * Long, an enum name, or a String token of letters, digits and underscores starting with a
 * letter. An address, a URL, a MAC or a session token cannot match, and is written as
 * [INVALID] instead.
 *
 * NEVER pass an SSID, a BSSID, a hostname, a device or TV name, or a file name, even where
 * one would happen to fit the token shape.
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

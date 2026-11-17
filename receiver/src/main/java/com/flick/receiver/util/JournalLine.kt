package com.flick.receiver.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One [IncidentJournal] line: `<UTC ISO-8601 millis> <event> k=v k=v`.
 *
 * A value is a Boolean, an Int, a Long, an enum name or a short identifier-shaped
 * token, and anything else is written as [INVALID]. That makes IP addresses, MACs,
 * URLs and free text unrepresentable. It does not cover tokens and names: a session
 * token that starts with a letter, and many network names and hostnames, fit the
 * token shape, so the protection for those is the call sites, which must never pass one.
 */
internal object JournalLine {
    const val INVALID = "invalid"

    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")
    private val TOKEN = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(atEpochMs: Long, event: String, fields: List<Pair<String, Any?>>): String {
        val out = StringBuilder(64)
        out.append(STAMP.format(Instant.ofEpochMilli(atEpochMs))).append(' ').append(name(event))
        for ((key, value) in fields) out.append(' ').append(name(key)).append('=').append(value(value))
        return out.toString()
    }

    fun name(raw: String): String = if (NAME.matches(raw)) raw else INVALID

    fun value(raw: Any?): String = when (raw) {
        is Boolean, is Int, is Long -> raw.toString()
        is Enum<*> -> token(raw.name)
        is String -> token(raw)
        else -> INVALID
    }

    private fun token(raw: String): String = if (TOKEN.matches(raw)) raw else INVALID
}

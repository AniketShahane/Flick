package com.flick.sender.net

import org.json.JSONObject

/**
 * The platform JSONObject parser is convenient for values but does not expose enough
 * lexical information to enforce the wire contract by itself. Keep duplicate-key and
 * trailing-byte rejection at the transport boundary before schema validation.
 */
internal object StrictControlJson {
    sealed interface Result {
        data class Object(val value: JSONObject) : Result
        data object Malformed : Result
        data object Oversize : Result
    }

    fun parse(text: String): Result {
        if (text.toByteArray(Charsets.UTF_8).size > ControlProtocolV2.MAX_FRAME_BYTES) return Result.Oversize
        if (!hasUniqueTopLevelObject(text)) return Result.Malformed
        return runCatching { JSONObject(text) }
            .getOrNull()
            ?.takeIf { it.keys().hasNext() }
            ?.let(Result::Object)
            ?: Result.Malformed
    }

    internal fun hasUniqueTopLevelObject(text: String): Boolean {
        var index = skipWhitespace(text, 0)
        if (index >= text.length || text[index++] != '{') return false
        val names = HashSet<String>()
        index = skipWhitespace(text, index)
        if (index < text.length && text[index] == '}') return skipWhitespace(text, index + 1) == text.length
        while (true) {
            val key = readString(text, index) ?: return false
            if (!names.add(key.value)) return false
            index = skipWhitespace(text, key.next)
            if (index >= text.length || text[index++] != ':') return false
            index = skipValue(text, skipWhitespace(text, index)) ?: return false
            index = skipWhitespace(text, index)
            if (index >= text.length) return false
            when (text[index++]) {
                '}' -> return skipWhitespace(text, index) == text.length
                ',' -> index = skipWhitespace(text, index)
                else -> return false
            }
        }
    }

    private data class ParsedString(val value: String, val next: Int)

    private fun readString(text: String, start: Int): ParsedString? {
        if (start >= text.length || text[start] != '"') return null
        val value = StringBuilder(); var index = start + 1
        while (index < text.length) {
            when (val character = text[index++]) {
                '"' -> return ParsedString(value.toString(), index)
                '\\' -> {
                    if (index >= text.length) return null
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> value.append(escaped)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) return null
                            val code = text.substring(index, index + 4).toIntOrNull(16) ?: return null
                            value.append(code.toChar()); index += 4
                        }
                        else -> return null
                    }
                }
                else -> if (character.code < 0x20) return null else value.append(character)
            }
        }
        return null
    }

    private fun skipValue(text: String, start: Int): Int? {
        if (start >= text.length) return null
        return when (text[start]) {
            '"' -> readString(text, start)?.next
            '{' -> skipContainer(text, start, '{', '}')
            '[' -> skipContainer(text, start, '[', ']')
            else -> {
                var index = start
                while (index < text.length && text[index] !in ",]} \t\r\n") index++
                if (index == start) null else index
            }
        }
    }

    private fun skipContainer(text: String, start: Int, open: Char, close: Char): Int? {
        var index = start + 1
        index = skipWhitespace(text, index)
        if (index < text.length && text[index] == close) return index + 1
        while (true) {
            index = if (open == '{') {
                val key = readString(text, index) ?: return null
                var afterKey = skipWhitespace(text, key.next)
                if (afterKey >= text.length || text[afterKey++] != ':') return null
                skipValue(text, skipWhitespace(text, afterKey)) ?: return null
            } else skipValue(text, index) ?: return null
            index = skipWhitespace(text, index)
            if (index >= text.length) return null
            when (text[index++]) {
                close -> return index
                ',' -> index = skipWhitespace(text, index)
                else -> return null
            }
        }
    }

    private fun skipWhitespace(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }
}

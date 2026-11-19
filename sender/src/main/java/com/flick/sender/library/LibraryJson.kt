package com.flick.sender.library

/**
 * The library channel's JSON, written and read without `org.json`.
 *
 * The catalog's `rev` is a hash over the serialized items, so the bytes must be identical on
 * every run and in JVM tests. Android's `JSONObject` keeps insertion order but the JVM
 * `org.json` artifact used by unit tests does not, so neither could produce the canonical form.
 */
internal sealed interface LibraryJsonValue {
    data class Obj(val fields: LinkedHashMap<String, LibraryJsonValue>) : LibraryJsonValue
    data class Arr(val values: List<LibraryJsonValue>) : LibraryJsonValue
    data class Str(val value: String) : LibraryJsonValue
    data class Num(val token: String) : LibraryJsonValue
    data class Bool(val value: Boolean) : LibraryJsonValue
    data object Null : LibraryJsonValue
}

/** Builds one JSON object in field order. Values are strings, integers, booleans or raw JSON. */
internal class LibraryJsonWriter {
    private val out = StringBuilder("{")
    private var first = true

    fun str(key: String, value: String): LibraryJsonWriter = field(key) { quote(value, out) }

    fun num(key: String, value: Long): LibraryJsonWriter = field(key) { out.append(value) }

    fun num(key: String, value: Int): LibraryJsonWriter = num(key, value.toLong())

    fun bool(key: String, value: Boolean): LibraryJsonWriter = field(key) { out.append(value) }

    /** [json] must already be valid JSON; used for nested objects and arrays built elsewhere. */
    fun raw(key: String, json: String): LibraryJsonWriter = field(key) { out.append(json) }

    fun strings(key: String, values: List<String>): LibraryJsonWriter = field(key) {
        out.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            quote(value, out)
        }
        out.append(']')
    }

    fun build(): String = out.toString() + "}"

    private inline fun field(key: String, value: () -> Unit): LibraryJsonWriter {
        if (!first) out.append(',')
        first = false
        quote(key, out)
        out.append(':')
        value()
        return this
    }

    companion object {
        fun quote(value: String, out: StringBuilder) {
            out.append('"')
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }

        fun array(elements: List<String>): String = elements.joinToString(",", "[", "]")
    }
}

/** Strict reader: no duplicate keys, no trailing bytes, nesting capped. Mirrors the TV's `StrictJson`. */
internal object LibraryJsonReader {

    /** A catalog-free inbound frame nests at most one object inside the top level. */
    private const val MAX_DEPTH = 8

    fun objectOnly(input: String): LibraryJsonValue.Obj? = runCatching {
        Parser(input).parse() as? LibraryJsonValue.Obj
    }.getOrNull()

    private class Parser(private val source: String) {
        private var at = 0
        private var depth = 0

        fun parse(): LibraryJsonValue {
            whitespace()
            val value = value()
            whitespace()
            require(at == source.length)
            return value
        }

        private fun value(): LibraryJsonValue {
            require(at < source.length)
            return when (source[at]) {
                '{' -> nested(::obj)
                '[' -> nested(::array)
                '"' -> LibraryJsonValue.Str(string())
                't' -> literal("true", LibraryJsonValue.Bool(true))
                'f' -> literal("false", LibraryJsonValue.Bool(false))
                'n' -> literal("null", LibraryJsonValue.Null)
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException()
            }
        }

        private fun <T> nested(body: () -> T): T {
            require(++depth <= MAX_DEPTH)
            try {
                return body()
            } finally {
                depth--
            }
        }

        private fun obj(): LibraryJsonValue.Obj {
            take('{')
            whitespace()
            val result = linkedMapOf<String, LibraryJsonValue>()
            if (takeIf('}')) return LibraryJsonValue.Obj(result)
            while (true) {
                whitespace()
                require(peek() == '"')
                val key = string()
                whitespace()
                take(':')
                whitespace()
                require(result.put(key, value()) == null)
                whitespace()
                if (takeIf('}')) return LibraryJsonValue.Obj(result)
                take(',')
                whitespace()
            }
        }

        private fun array(): LibraryJsonValue.Arr {
            take('[')
            whitespace()
            val result = mutableListOf<LibraryJsonValue>()
            if (takeIf(']')) return LibraryJsonValue.Arr(result)
            while (true) {
                result += value()
                whitespace()
                if (takeIf(']')) return LibraryJsonValue.Arr(result)
                take(',')
                whitespace()
            }
        }

        private fun string(): String {
            take('"')
            val out = StringBuilder()
            while (at < source.length) {
                when (val c = source[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < source.length)
                        when (val escaped = source[at++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(at + 4 <= source.length)
                                val hex = source.substring(at, at + 4)
                                require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                                out.append(hex.toInt(16).toChar())
                                at += 4
                            }
                            else -> throw IllegalArgumentException()
                        }
                    }
                    else -> {
                        require(c.code >= 0x20)
                        out.append(c)
                    }
                }
            }
            throw IllegalArgumentException()
        }

        private fun number(): LibraryJsonValue.Num {
            val start = at
            takeIf('-')
            if (!takeIf('0')) {
                require(peek()?.let { it in '1'..'9' } == true)
                while (peek()?.let { it in '0'..'9' } == true) at++
            }
            if (takeIf('.')) {
                require(peek()?.let { it in '0'..'9' } == true)
                while (peek()?.let { it in '0'..'9' } == true) at++
            }
            if (peek() == 'e' || peek() == 'E') {
                at++
                if (!takeIf('+')) takeIf('-')
                require(peek()?.let { it in '0'..'9' } == true)
                while (peek()?.let { it in '0'..'9' } == true) at++
            }
            return LibraryJsonValue.Num(source.substring(start, at))
        }

        private fun literal(text: String, result: LibraryJsonValue): LibraryJsonValue {
            require(source.startsWith(text, at))
            at += text.length
            return result
        }

        private fun whitespace() {
            while (peek()?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) at++
        }

        private fun peek(): Char? = source.getOrNull(at)

        private fun take(c: Char) {
            require(peek() == c)
            at++
        }

        private fun takeIf(c: Char): Boolean = (peek() == c).also { if (it) at++ }
    }
}

internal fun LibraryJsonValue.Obj.str(key: String): String? = (fields[key] as? LibraryJsonValue.Str)?.value

/** A JSON integer token only: no fraction, exponent or sign other than a leading minus. */
internal fun LibraryJsonValue.Obj.long(key: String): Long? {
    val token = (fields[key] as? LibraryJsonValue.Num)?.token ?: return null
    if (token.length > 19 || token.any { it == '.' || it == 'e' || it == 'E' || it == '+' }) return null
    return token.toLongOrNull()
}

internal fun LibraryJsonValue.Obj.bool(key: String): Boolean? = (fields[key] as? LibraryJsonValue.Bool)?.value

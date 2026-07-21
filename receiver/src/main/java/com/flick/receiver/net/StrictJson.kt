package com.flick.receiver.net

/** Minimal strict JSON parser: no coercion, trailing bytes, or decoded duplicate keys. */
sealed interface StrictJsonValue {
    data class Obj(val fields: LinkedHashMap<String, StrictJsonValue>) : StrictJsonValue
    data class Arr(val values: List<StrictJsonValue>) : StrictJsonValue
    data class Str(val value: String) : StrictJsonValue
    data class Num(val token: String) : StrictJsonValue
    data class Bool(val value: Boolean) : StrictJsonValue
    data object Null : StrictJsonValue
}

object StrictJson {
    fun objectOnly(input: String): StrictJsonValue.Obj? = runCatching {
        Parser(input).parse().let { it as? StrictJsonValue.Obj ?: throw IllegalArgumentException() }
    }.getOrNull()

    private class Parser(private val source: String) {
        private var at = 0
        fun parse(): StrictJsonValue {
            whitespace()
            val value = value()
            whitespace()
            require(at == source.length) { "trailing JSON" }
            return value
        }
        private fun value(): StrictJsonValue {
            require(at < source.length)
            return when (source[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> StrictJsonValue.Str(string())
                't' -> literal("true", StrictJsonValue.Bool(true))
                'f' -> literal("false", StrictJsonValue.Bool(false))
                'n' -> literal("null", StrictJsonValue.Null)
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("value")
            }
        }
        private fun obj(): StrictJsonValue.Obj {
            take('{'); whitespace(); val result = linkedMapOf<String, StrictJsonValue>()
            if (takeIf('}')) return StrictJsonValue.Obj(result)
            while (true) {
                whitespace(); require(peek() == '"')
                val key = string(); whitespace(); take(':'); whitespace()
                require(result.put(key, value()) == null) { "duplicate key" }
                whitespace()
                if (takeIf('}')) return StrictJsonValue.Obj(result)
                take(','); whitespace()
            }
        }
        private fun array(): StrictJsonValue.Arr {
            take('['); whitespace(); val result = mutableListOf<StrictJsonValue>()
            if (takeIf(']')) return StrictJsonValue.Arr(result)
            while (true) {
                result += value(); whitespace()
                if (takeIf(']')) return StrictJsonValue.Arr(result)
                take(','); whitespace()
            }
        }
        private fun string(): String {
            take('"'); val out = StringBuilder()
            while (at < source.length) {
                when (val c = source[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(at < source.length)
                        when (val escaped = source[at++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                require(at + 4 <= source.length)
                                val hex = source.substring(at, at + 4)
                                require(hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                                out.append(hex.toInt(16).toChar()); at += 4
                            }
                            else -> throw IllegalArgumentException("escape")
                        }
                    }
                    else -> { require(c.code >= 0x20); out.append(c) }
                }
            }
            throw IllegalArgumentException("unterminated string")
        }
        private fun number(): StrictJsonValue.Num {
            val start = at
            takeIf('-'); if (takeIf('0')) Unit else { require(peek()?.let { it in '1'..'9' } == true); while (peek()?.let { it in '0'..'9' } == true) at++ }
            if (takeIf('.')) { require(peek()?.let { it in '0'..'9' } == true); while (peek()?.let { it in '0'..'9' } == true) at++ }
            if (peek() == 'e' || peek() == 'E') { at++; takeIf('+'); takeIf('-'); require(peek()?.let { it in '0'..'9' } == true); while (peek()?.let { it in '0'..'9' } == true) at++ }
            return StrictJsonValue.Num(source.substring(start, at))
        }
        private fun literal(text: String, result: StrictJsonValue): StrictJsonValue { require(source.startsWith(text, at)); at += text.length; return result }
        private fun whitespace() { while (peek()?.let { it == ' ' || it == '\n' || it == '\r' || it == '\t' } == true) at++ }
        private fun peek(): Char? = source.getOrNull(at)
        private fun take(c: Char) { require(peek() == c); at++ }
        private fun takeIf(c: Char): Boolean = (peek() == c).also { if (it) at++ }
    }
}

internal fun StrictJsonValue.Obj.exactly(fields: Set<String>) = this.fields.keys == fields
internal fun StrictJsonValue.Obj.string(name: String) = fields[name] as? StrictJsonValue.Str
internal fun StrictJsonValue.Obj.bool(name: String) = (fields[name] as? StrictJsonValue.Bool)?.value
internal fun StrictJsonValue.Obj.integer(name: String): Long? = (fields[name] as? StrictJsonValue.Num)?.token
    ?.takeIf { it.matches(Regex("-?(0|[1-9][0-9]*)")) }?.toLongOrNull()
internal fun StrictJsonValue.Obj.number(name: String): Double? = (fields[name] as? StrictJsonValue.Num)?.token?.toDoubleOrNull()?.takeIf(Double::isFinite)
internal fun StrictJsonValue.Obj.strings(name: String): List<String>? {
    val values = (fields[name] as? StrictJsonValue.Arr)?.values ?: return null
    return values.map { (it as? StrictJsonValue.Str)?.value ?: return null }
}

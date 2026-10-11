package com.pixelplus.owner.audit

import java.text.Normalizer

/**
 * Canonical JSON for one audit entry.
 *
 * The bytes are a JSON array in a fixed field order. Strings are NFC.
 * Objects sort their keys. There is no whitespace and no floating point.
 * Hash fields are not part of these bytes.
 *
 * No Android types.
 */
sealed class JsonValue {
    data class Text(val value: String) : JsonValue()
    data class Int64(val value: Long) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

object CanonicalJson {
    fun text(value: JsonValue): String = buildString { write(value, this) }

    fun bytes(value: JsonValue): ByteArray = text(value).toByteArray(Charsets.UTF_8)

    fun parse(bytes: ByteArray): JsonValue = JsonReader(bytes.toString(Charsets.UTF_8)).parse()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Int64 -> out.append(value.value.toString())
            is JsonValue.Text -> writeString(nfc(value.value), out)
            is JsonValue.Obj -> {
                out.append('{')
                value.fields.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(nfc(key), out)
                    out.append(':')
                    write(value.fields.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char.code < 0x20) {
                    out.append("\\u")
                    out.append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
}

private class JsonReader(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = readValue(allowArray = true)
        if (index != text.length) fail("trailing")
        return value
    }

    private fun readValue(allowArray: Boolean): JsonValue {
        if (index >= text.length) fail("end")
        return when (val char = text[index]) {
            '"' -> JsonValue.Text(readString())
            '{' -> readObject()
            '[' -> if (allowArray) readArray() else fail("array")
            'n' -> literal("null", JsonValue.Null)
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            '-', in '0'..'9' -> JsonValue.Int64(readNumber())
            else -> fail("value $char")
        }
    }

    private fun literal(token: String, value: JsonValue): JsonValue {
        if (!text.startsWith(token, index)) fail(token)
        index += token.length
        return value
    }

    private fun readNumber(): Long {
        val start = index
        if (text[index] == '-') index += 1
        if (index >= text.length || text[index] !in '0'..'9') fail("number")
        if (text[index] == '0') {
            index += 1
            if (index < text.length && text[index] in '0'..'9') fail("zero")
        } else {
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        if (index < text.length && text[index] in ".eE") fail("fraction")
        val raw = text.substring(start, index)
        if (raw == "-0") fail("negzero")
        return raw.toLong()
    }

    private fun readString(): String {
        if (text[index] != '"') fail("string")
        index += 1
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            index += 1
            when (char) {
                '"' -> return out.toString()
                '\\' -> out.append(escape())
                else -> {
                    if (char.code < 0x20) fail("control")
                    out.append(char)
                }
            }
        }
        fail("string end")
    }

    private fun escape(): Char {
        if (index >= text.length) fail("escape")
        val char = text[index]
        index += 1
        return when (char) {
            '"', '\\', '/' -> char
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) fail("unicode")
                val hex = text.substring(index, index + 4)
                index += 4
                if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) fail("hex")
                hex.toInt(16).toChar()
            }
            else -> fail("escape $char")
        }
    }

    private fun readObject(): JsonValue.Obj {
        index += 1
        if (index < text.length && text[index] == '}') {
            index += 1
            return JsonValue.Obj(emptyMap())
        }
        val fields = linkedMapOf<String, JsonValue>()
        while (true) {
            val key = readString()
            if (index >= text.length || text[index] != ':') fail("colon")
            index += 1
            if (key in fields) fail("duplicate")
            fields[key] = readValue(allowArray = false)
            if (index >= text.length) fail("object end")
            when (text[index]) {
                ',' -> index += 1
                '}' -> {
                    index += 1
                    return JsonValue.Obj(fields)
                }
                else -> fail("object")
            }
        }
    }

    private fun readArray(): JsonArray {
        index += 1
        if (index < text.length && text[index] == ']') {
            index += 1
            return JsonArray(emptyList())
        }
        val items = mutableListOf<JsonValue>()
        while (true) {
            items += readValue(allowArray = false)
            if (index >= text.length) fail("array end")
            when (text[index]) {
                ',' -> index += 1
                ']' -> {
                    index += 1
                    return JsonArray(items)
                }
                else -> fail("array")
            }
        }
    }

    private fun fail(reason: String): Nothing = throw IllegalArgumentException("bad json: $reason")
}

/** Top-level array. Nested values cannot be arrays. The canonical entry is one of these. */
class JsonArray internal constructor(val items: List<JsonValue>) : JsonValue()

internal fun JsonValue.arrayItems(): List<JsonValue> = (this as? JsonArray)?.items
    ?: throw IllegalArgumentException("bad json: array")

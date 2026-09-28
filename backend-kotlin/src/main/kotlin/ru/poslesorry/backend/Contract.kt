package ru.poslesorry.backend

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ApiProblem(val status: Int, val code: String) : RuntimeException(code)

/** The m1-cis-v1 wire contract and idempotency representation shared with the PHP API. */
object Contract {
    const val MAX_BODY_BYTES = 16_384

    val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    private val enums = linkedMapOf(
        "age" to setOf("adult", "minor"),
        "stage" to setOf("under6m", "6to12m", "1to3y", "3to7y", "7plus"),
        "safety" to setOf("no", "yes_unsure"),
        "boundary" to setOf("clear", "space", "no_contact", "unsure"),
        "timing" to setOf("today", "yesterday", "2to3days", "older"),
        "partner" to setOf("talk", "quiet", "angry", "space", "distant", "left", "other"),
        "user" to setOf("talk", "apology", "defend", "space", "messages", "withdraw", "help", "other"),
        "recurrence" to setOf("first", "sometimes", "often", "always"),
        "helpful" to setOf("pause", "listen", "practical", "none", "unsure"),
        "failureType" to setOf("messages", "apology", "talk", "pause", "other", "none", "unsure"),
        "goal" to setOf("talk", "apology", "calm", "space", "understand", "pattern", "other"),
    )
    private val requestIdPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")

    fun decode(bytes: ByteArray): JsonElement {
        if (bytes.size > MAX_BODY_BYTES) throw ApiProblem(413, "payload_too_large")
        val source = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw ApiProblem(400, "invalid_json")
        }
        // PHP json_decode(depth: 32) allows 31 nested arrays/objects. Explicit parsing also
        // rejects malformed escapes, raw controls and lone surrogates before text validation.
        return WireJsonParser(source).parse()
    }

    fun casePayload(body: JsonElement): JsonObject {
        val value = exactObject(body, setOf("schema_version", "synthetic", "client_request_id", "questionnaire"))
        if (string(value["schema_version"]) != "m1-cis-v1" || value["synthetic"] != JsonPrimitive(true)) {
            throw ApiProblem(422, "synthetic_case_required")
        }
        val requestId = requestId(value["client_request_id"])
        val data = exactObject(value.getValue("questionnaire"), enums.keys + setOf("situation", "success", "failure"))
        val questionnaire = buildJsonObject {
            for ((key, allowed) in enums) {
                val answer = string(data[key])
                if (answer == null || answer !in allowed) throw ApiProblem(422, "invalid_questionnaire_enum")
                put(key, answer)
            }
            put("situation", text(data["situation"], 2000, true))
            put("success", text(data["success"], 1000, false))
            put("failure", text(data["failure"], 1000, false))
        }
        return buildJsonObject {
            put("schema_version", "m1-cis-v1")
            put("synthetic", true)
            put("client_request_id", requestId)
            put("questionnaire", questionnaire)
        }
    }

    fun draftPayload(body: JsonElement): JsonObject {
        val value = exactObject(body, setOf("synthetic", "client_request_id", "text"))
        if (value["synthetic"] != JsonPrimitive(true)) throw ApiProblem(422, "synthetic_case_required")
        return buildJsonObject {
            put("synthetic", true)
            put("client_request_id", requestId(value["client_request_id"]))
            put("text", text(value["text"], 4000, true))
        }
    }

    /** Insertion order is deliberate: PHP hashes this exact encoding of validated payloads. */
    fun canonicalJson(element: JsonElement): String = buildString { appendCanonical(element) }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun exactObject(element: JsonElement, keys: Set<String>): JsonObject {
        val value = element as? JsonObject ?: throw ApiProblem(422, "invalid_payload")
        if (value.keys != keys) throw ApiProblem(422, "invalid_fields")
        return value
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun requestId(element: JsonElement?): String {
        val value = string(element)
        if (value == null || !requestIdPattern.matches(value)) throw ApiProblem(422, "invalid_client_request_id")
        return value
    }

    private fun text(element: JsonElement?, max: Int, required: Boolean): String {
        val value = string(element) ?: throw ApiProblem(422, "invalid_text")
        if (!hasValidSurrogates(value) || value.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 || it.code == 127 }) {
            throw ApiProblem(422, "invalid_text")
        }
        // Kotlin trim() also removes Unicode spaces; PHP trim() intentionally does not.
        val trimmed = value.trim(' ', '\t', '\n', '\r', '\u0000', '\u000B')
        if (trimmed.length > max || (required && trimmed.isEmpty())) throw ApiProblem(422, "invalid_text_length")
        return trimmed
    }

    private fun hasValidSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val ch = value[index++]
            if (ch.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (ch.isLowSurrogate()) return false
        }
        return true
    }

    private fun StringBuilder.appendCanonical(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                append('{')
                element.entries.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    appendQuoted(key)
                    append(':')
                    appendCanonical(value)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                element.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    appendCanonical(value)
                }
                append(']')
            }
            is JsonPrimitive -> if (element.isString) appendQuoted(element.content) else append(element.content)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        require(hasValidSurrogates(value)) { "Cannot encode an unpaired surrogate" }
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private class WireJsonParser(private val source: String) {
        private var position = 0

        fun parse(): JsonElement {
            val result = value(0)
            whitespace()
            if (position != source.length) invalid()
            return result
        }

        private fun value(depth: Int): JsonElement {
            whitespace()
            if (position == source.length) invalid()
            return when (source[position]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> JsonPrimitive(stringValue())
                't' -> literal("true", JsonPrimitive(true))
                'f' -> literal("false", JsonPrimitive(false))
                'n' -> literal("null", JsonNull)
                '-', in '0'..'9' -> numberValue()
                else -> invalid()
            }
        }

        private fun objectValue(depth: Int): JsonObject {
            if (depth >= 32) invalid()
            position++
            whitespace()
            val values = linkedMapOf<String, JsonElement>()
            if (consume('}')) return JsonObject(values)
            while (true) {
                if (position == source.length || source[position] != '"') invalid()
                val key = stringValue()
                // json_decode(..., associative: false) cannot create a NUL-prefixed property.
                if (key.startsWith('\u0000')) invalid()
                whitespace()
                if (!consume(':')) invalid()
                values[key] = value(depth)
                whitespace()
                if (consume('}')) return JsonObject(values)
                if (!consume(',')) invalid()
                whitespace()
            }
        }

        private fun arrayValue(depth: Int): JsonArray {
            if (depth >= 32) invalid()
            position++
            whitespace()
            val values = mutableListOf<JsonElement>()
            if (consume(']')) return JsonArray(values)
            while (true) {
                values += value(depth)
                whitespace()
                if (consume(']')) return JsonArray(values)
                if (!consume(',')) invalid()
            }
        }

        private fun stringValue(): String {
            position++ // Opening quote was checked by the caller.
            val result = StringBuilder()
            while (position < source.length) {
                val ch = source[position++]
                when {
                    ch == '"' -> return result.toString().also { if (!hasValidSurrogates(it)) invalid() }
                    ch.code < 32 -> invalid()
                    ch != '\\' -> result.append(ch)
                    position == source.length -> invalid()
                    else -> when (val escaped = source[position++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (source.length - position < 4) invalid()
                            var code = 0
                            repeat(4) {
                                val hex = source[position++]
                                if (hex !in "0123456789abcdefABCDEF") invalid()
                                val digit = hex.digitToInt(16)
                                code = (code shl 4) or digit
                            }
                            result.append(code.toChar())
                        }
                        else -> invalid()
                    }
                }
            }
            invalid()
        }

        private fun numberValue(): JsonElement {
            val start = position
            consume('-')
            if (!consume('0')) {
                if (position == source.length || source[position] !in '1'..'9') invalid()
                digits()
            }
            if (consume('.')) {
                val firstDigit = position
                digits()
                if (firstDigit == position) invalid()
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                val firstDigit = position
                digits()
                if (firstDigit == position) invalid()
            }
            return json.parseToJsonElement(source.substring(start, position))
        }

        private fun digits() {
            while (position < source.length && source[position] in '0'..'9') position++
        }

        private fun literal(expected: String, result: JsonElement): JsonElement {
            if (!source.startsWith(expected, position)) invalid()
            position += expected.length
            return result
        }

        private fun consume(char: Char): Boolean {
            if (position < source.length && source[position] == char) {
                position++
                return true
            }
            return false
        }

        private fun whitespace() {
            while (position < source.length && source[position] in " \t\r\n") position++
        }

        private fun invalid(): Nothing = throw ApiProblem(400, "invalid_json")
    }
}

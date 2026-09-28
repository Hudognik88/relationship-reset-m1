package ru.poslesorry.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val PAYMENT_CONFIG_ERROR = "Payment configuration unavailable"

/** This release can only create demo checkouts; there is deliberately no live mode. */
class DemoPaymentConfig(val secret: String, val sys: String? = null) {
    val payformUrl = "https://relationshipreset.payform.ru/"

    init {
        require(secret.matches(Regex("[!-~]{16,256}"))) { PAYMENT_CONFIG_ERROR }
        require(sys == null || sys.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { PAYMENT_CONFIG_ERROR }
    }

    override fun toString() = "DemoPaymentConfig(redacted)"

    companion object {
        fun fromEnvironment(env: Map<String, String>): DemoPaymentConfig? = try {
            readEnvironment(env)
        } catch (_: Exception) {
            // File names, parser excerpts, and underlying exceptions can contain secrets.
            throw IllegalArgumentException(PAYMENT_CONFIG_ERROR)
        }

        private fun readEnvironment(env: Map<String, String>): DemoPaymentConfig? {
            require("RR_PRODAMUS_SECRET" !in env)
            env["RR_PRODAMUS_CONFIG_FILE"]?.let { file ->
                require(env.keys.none { it.startsWith("RR_PRODAMUS_") && it != "RR_PRODAMUS_CONFIG_FILE" })
                return readConfigFile(Path.of(file))
            }
            val mode = env["RR_PRODAMUS_MODE"] ?: "disabled"
            require(mode in setOf("disabled", "demo"))
            if (mode == "disabled") {
                require(listOf("RR_PRODAMUS_SECRET_FILE", "RR_PRODAMUS_SYS", "RR_PRODAMUS_FORM_URL").none { it in env })
                return null
            }
            require(env["RR_PRODAMUS_FORM_URL"] in setOf(null, "https://relationshipreset.payform.ru/"))
            val path = Path.of(requireNotNull(env["RR_PRODAMUS_SECRET_FILE"]))
            return DemoPaymentConfig(readAsciiFile(path, 512).trim(), env["RR_PRODAMUS_SYS"])
        }

        private fun readConfigFile(path: Path): DemoPaymentConfig? {
            val fields = FlatPaymentJson(readAsciiFile(path, 2048)).read()
            val version = requireNotNull(fields["version"])
            require(!version.isString && version.content == "1")
            val mode = requireNotNull(fields["mode"])
            require(mode.isString)
            return when (mode.content) {
                "disabled" -> {
                    require(fields.keys == setOf("version", "mode"))
                    null
                }
                "demo" -> {
                    require(fields.keys in setOf(setOf("version", "mode", "secret"), setOf("version", "mode", "secret", "sys")))
                    val secret = requireNotNull(fields["secret"])
                    val sys = fields["sys"]
                    require(secret.isString && (sys == null || sys.isString))
                    DemoPaymentConfig(secret.content, sys?.content)
                }
                else -> throw IllegalArgumentException(PAYMENT_CONFIG_ERROR)
            }
        }

        private fun readAsciiFile(path: Path, limit: Int): String {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            val bytes = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(limit + 1) }
            require(bytes.size <= limit && bytes.all { it >= 0 })
            return bytes.toString(Charsets.US_ASCII)
        }
    }
}

/** A flat object parser keeps duplicate decoded names visible instead of silently overwriting them. */
private class FlatPaymentJson(private val input: String) {
    private var offset = 0

    fun read(): Map<String, JsonPrimitive> {
        val fields = linkedMapOf<String, JsonPrimitive>()
        whitespace()
        expect('{')
        whitespace()
        if (peek() != '}') {
            while (true) {
                val key = string().content
                require(key !in fields)
                whitespace()
                expect(':')
                whitespace()
                val value = if (peek() == '"') string() else {
                    // Only the literal integer version 1 is admitted; no coercion or exponents.
                    expect('1')
                    JsonPrimitive(1)
                }
                fields[key] = value
                whitespace()
                if (peek() != ',') break
                offset++
                whitespace()
                // The next iteration requires a string, also rejecting trailing commas.
            }
        }
        expect('}')
        whitespace()
        require(offset == input.length)
        return fields
    }

    private fun string(): JsonPrimitive {
        val start = offset
        expect('"')
        while (true) {
            require(offset < input.length)
            val current = input[offset++]
            require(current >= ' ')
            if (current == '"') break
            if (current == '\\') {
                require(offset < input.length)
                val escaped = input[offset++]
                require(escaped in "\"\\/bfnrtu")
                if (escaped == 'u') {
                    require(offset + 4 <= input.length)
                    require(input.substring(offset, offset + 4).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    offset += 4
                }
            }
        }
        // Decode just one string token: JSON escaping is delegated, object keys are not.
        val value = Json.parseToJsonElement(input.substring(start, offset))
        require(value is JsonPrimitive && value.isString)
        return value
    }

    private fun whitespace() {
        while (peek() in listOf(' ', '\t', '\r', '\n')) offset++
    }

    private fun expect(char: Char) {
        require(peek() == char)
        offset++
    }

    private fun peek(): Char? = input.getOrNull(offset)
}

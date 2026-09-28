package ru.poslesorry.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DemoPaymentConfigTest {
    private val secret = "synthetic-test-merchant-secret"

    @Test
    fun `payments are disabled unless demo is explicitly and completely configured`() {
        assertNull(DemoPaymentConfig.fromEnvironment(emptyMap()))
        assertNull(DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_MODE" to "disabled")))
        for (mode in listOf("live", "production", "true", "DEMO", "")) {
            assertFailsWith<IllegalArgumentException> { DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_MODE" to mode)) }
        }
        assertFails { DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_MODE" to "demo")) }
        for (key in listOf("RR_PRODAMUS_SECRET_FILE", "RR_PRODAMUS_SYS", "RR_PRODAMUS_FORM_URL")) {
            assertFailsWith<IllegalArgumentException> { DemoPaymentConfig.fromEnvironment(mapOf(key to "partial")) }
        }
    }

    @Test
    fun `direct secrets are rejected even when payments are disabled`() {
        for (mode in listOf("disabled", "demo")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_MODE" to mode, "RR_PRODAMUS_SECRET" to secret))
            }
            assertFalse(failure.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun `demo reads its private file and only uses the pinned merchant`() {
        val file = Files.createTempFile("rr-demo-test-", ".secret")
        try {
            Files.writeString(file, "$secret\n")
            val env = mapOf("RR_PRODAMUS_MODE" to "demo", "RR_PRODAMUS_SECRET_FILE" to file.toString(), "RR_PRODAMUS_SYS" to "synthetic-test")
            val settings = assertNotNull(DemoPaymentConfig.fromEnvironment(env))
            assertEquals(secret, settings.secret)
            assertEquals("synthetic-test", settings.sys)
            assertEquals("https://relationshipreset.payform.ru/", settings.payformUrl)
            assertEquals("DemoPaymentConfig(redacted)", settings.toString())
            for (url in listOf("http://relationshipreset.payform.ru/", "https://other.payform.ru/", "https://relationshipreset.payform.ru/?demo_mode=0")) {
                assertFailsWith<IllegalArgumentException> { DemoPaymentConfig.fromEnvironment(env + ("RR_PRODAMUS_FORM_URL" to url)) }
            }
            assertFailsWith<IllegalArgumentException> { DemoPaymentConfig.fromEnvironment(env + ("RR_PRODAMUS_SYS" to "bad/integrator")) }
        } finally { Files.deleteIfExists(file) }
    }

    @Test
    fun `missing oversized and malformed secret files fail without exposing secret data`() {
        val dir = Files.createTempDirectory("rr-demo-config-test-")
        val file = dir.resolve("merchant.secret")
        try {
            val env = mapOf("RR_PRODAMUS_MODE" to "demo", "RR_PRODAMUS_SECRET_FILE" to file.toString())
            assertFails { DemoPaymentConfig.fromEnvironment(env) }
            assertFails { DemoPaymentConfig.fromEnvironment(env + ("RR_PRODAMUS_SECRET_FILE" to dir.toString())) }
            for (value in listOf("short", "x".repeat(513), "x".repeat(257), "embedded white space in secret")) {
                Files.writeString(file, value)
                val failure = assertFails { DemoPaymentConfig.fromEnvironment(env) }
                assertFalse(failure.message.orEmpty().contains(value))
            }
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(dir) }
    }

    @Test
    fun `versioned private configuration supports only disabled or pinned demo`() = withConfigFile { file ->
        Files.writeString(file, """{"version":1,"mode":"disabled"}""")
        assertNull(readConfig(file))
        Files.writeString(file, """{"version":1,"mode":"demo","secret":"$secret"}""")
        val demo = assertNotNull(readConfig(file))
        assertEquals(secret, demo.secret)
        assertNull(demo.sys)
        assertEquals("https://relationshipreset.payform.ru/", demo.payformUrl)
        assertEquals("DemoPaymentConfig(redacted)", demo.toString())
        Files.writeString(file, """ { "sys":"rr_test-1", "se\u0063ret":"synthetic-test-merchant-se\u0063ret", "mode":"demo", "version":1 } """)
        val escaped = assertNotNull(readConfig(file))
        assertEquals(secret, escaped.secret)
        assertEquals("rr_test-1", escaped.sys)
    }

    @Test
    fun `config file cannot be combined with legacy or unknown payment variables`() = withConfigFile { file ->
        Files.writeString(file, """{"version":1,"mode":"disabled"}""")
        for (name in listOf("RR_PRODAMUS_MODE", "RR_PRODAMUS_SECRET", "RR_PRODAMUS_SECRET_FILE", "RR_PRODAMUS_SYS", "RR_PRODAMUS_FORM_URL", "RR_PRODAMUS_UNKNOWN")) {
            assertRedactedFailure { readConfig(file, mapOf(name to secret)) }
        }
        assertNull(readConfig(file, mapOf("UNRELATED_SETTING" to "allowed")))
    }

    @Test
    fun `file contract rejects ambiguous malformed or unsupported JSON without echoing it`() = withConfigFile { file ->
        val invalid = listOf(
            "", "{}", "[]", "null", "1", "\"$secret\"",
            """{"version":1,"mode":"live"}""",
            """{"version":1,"mode":"disabled","secret":"$secret"}""",
            """{"version":1,"mode":"disabled","sys":"rr"}""",
            """{"version":1,"mode":"disabled","unknown":1}""",
            """{"version":1,"mode":"demo"}""",
            """{"version":1,"mode":"demo","secret":"$secret","sys":null}""",
            """{"version":1,"mode":"demo","secret":"$secret","sys":1}""",
            """{"version":1,"mode":"demo","secret":1}""",
            """{"version":1,"mode":"demo","secret":"$secret","form_url":"https://other.payform.ru/"}""",
            """{"version":1,"mode":"demo","secret":{"value":"$secret"}}""",
            """{"version":1,"mode":"demo","secret":["$secret"]}""",
            """{"version":1,"mode":"demo","secret":null}""",
            """{"version":1,"mode":"demo","secret":true}""",
            """{"version":1,"mode":"disabled",}""",
            """{"version":1 "mode":"disabled"}""",
            """{"version":1,"mode":"disabled"}{}""",
            """{"version":1,"mode":"disabled"}// comment""",
            """{version:1,"mode":"disabled"}""",
            """{"version":1,"mode":disabled}""",
            """{"version":1,"mode":"demo","secret":"invalid\xescape"}""",
            """{"version":1,"mode":"demo","secret":"invalid\u00Q0escape"}""",
            "{\"version\":1,\"mode\":\"demo\",\"secret\":\"unescaped\nnewline\"}",
            "{\"version\":1,\"mode\":\"disabled\"}\u000b"
        )
        for (json in invalid) {
            Files.writeString(file, json)
            assertRedactedFailure { readConfig(file) }
        }
        for (version in listOf("0", "2", "-1", "+1", "01", "1.0", "1e0", "true", "null", "\"1\"")) {
            Files.writeString(file, """{"version":$version,"mode":"disabled"}""")
            assertRedactedFailure { readConfig(file) }
        }
    }

    @Test
    fun `duplicate field names are rejected including escaped aliases and identical values`() = withConfigFile { file ->
        for (json in listOf(
            """{"version":1,"version":1,"mode":"disabled"}""",
            """{"version":1,"\u0076ersion":1,"mode":"disabled"}""",
            """{"version":1,"mode":"disabled","mode":"demo","secret":"$secret"}""",
            """{"version":1,"mode":"demo","secret":"$secret","se\u0063ret":"$secret"}""",
            """{"version":1,"mode":"demo","secret":"$secret","sys":"rr","sys":"rr"}"""
        )) {
            Files.writeString(file, json)
            assertRedactedFailure { readConfig(file) }
        }
    }

    @Test
    fun `config file size is bounded by actual ASCII bytes`() = withConfigFile { file ->
        val disabled = """{"version":1,"mode":"disabled"}"""
        Files.writeString(file, disabled.padEnd(2048, ' '))
        assertNull(readConfig(file))
        Files.writeString(file, disabled.padEnd(2049, ' '))
        assertRedactedFailure { readConfig(file) }
        Files.write(file, disabled.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28))
        assertRedactedFailure { readConfig(file) }
        Files.write(file, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + disabled.toByteArray())
        assertRedactedFailure { readConfig(file) }
        Files.writeString(file, """{"version":1,"mode":"demo","secret":"$secret-é"}""")
        assertRedactedFailure { readConfig(file) }
    }

    @Test
    fun `decoded secret and sys obey exact value constraints`() = withConfigFile { file ->
        for (value in listOf("short", "x".repeat(257), "embedded white space in secret", "synthetic-\\u0000-secret", "synthetic-\\u00e9-secret")) {
            Files.writeString(file, """{"version":1,"mode":"demo","secret":"$value"}""")
            assertRedactedFailure { readConfig(file) }
        }
        for (sys in listOf("", "x".repeat(65), "bad/integrator", "bad integrator", "\\u00e9")) {
            Files.writeString(file, """{"version":1,"mode":"demo","secret":"$secret","sys":"$sys"}""")
            assertRedactedFailure { readConfig(file) }
        }
        for (length in listOf(16, 256)) {
            Files.writeString(file, """{"version":1,"mode":"demo","secret":"${"x".repeat(length)}"}""")
            assertEquals(length, assertNotNull(readConfig(file)).secret.length)
        }
    }

    @Test
    fun `missing directory and symbolic configuration files fail redacted`() = withConfigFile { file ->
        Files.writeString(file, """{"version":1,"mode":"disabled"}""")
        val link = file.resolveSibling("config-link.json")
        try {
            assertRedactedFailure { readConfig(file.resolveSibling("missing-config.json")) }
            assertRedactedFailure { readConfig(file.parent) }
            Files.createSymbolicLink(link, file)
            assertRedactedFailure { readConfig(link) }
            assertRedactedFailure { DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_CONFIG_FILE" to "\u0000$secret")) }
            assertRedactedFailure { DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_CONFIG_FILE" to "")) }
        } finally { Files.deleteIfExists(link) }
    }

    private fun readConfig(file: Path, extra: Map<String, String> = emptyMap()) =
        DemoPaymentConfig.fromEnvironment(mapOf("RR_PRODAMUS_CONFIG_FILE" to file.toString()) + extra)

    private fun assertRedactedFailure(block: () -> Any?) {
        val failure = assertFailsWith<IllegalArgumentException> { block() }
        assertEquals("Payment configuration unavailable", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.toString().contains(secret))
    }

    private fun withConfigFile(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("rr-file-config-test-")
        val file = directory.resolve("payment-config.json")
        try { block(file) } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}

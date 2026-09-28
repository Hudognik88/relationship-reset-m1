package ru.poslesorry.backend

import java.nio.file.Files
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
}

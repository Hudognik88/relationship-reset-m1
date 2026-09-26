package ru.poslesorry.backend

import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ConfigTest {
    private fun environment(): Map<String, String> = mapOf(
        "RR_ENVIRONMENT" to "staging",
        "RR_OPERATOR_TOKEN_SHA256" to Contract.sha256(UUID.randomUUID().toString()),
        "RR_DB_HOST" to "localhost",
        "RR_DB_NAME" to "rr_isolated_test",
        "RR_DB_USER" to "rr_test_user",
        "RR_DB_PASSWORD" to UUID.randomUUID().toString(),
    )

    @Test
    fun `minimal configuration is staging loopback and verified TLS by default`() {
        val env = environment()
        val config = AppConfig.fromEnvironment(env)
        assertEquals(env["RR_OPERATOR_TOKEN_SHA256"], config.operatorHash)
        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertFalse(config.allowLoopbackHttp)
        assertTrue(config.trustedProxyIps.isEmpty())
        assertNull(config.release)
        assertEquals(3306, config.database.port)
        assertEquals("VERIFY_IDENTITY", config.database.sslMode)
        assertEquals(env["RR_DB_PASSWORD"], config.database.password)
    }

    @Test
    fun `missing required values production and absent environment fail closed`() {
        val env = environment()
        for (key in env.keys) assertFails { AppConfig.fromEnvironment(env - key) }
        for (mode in listOf("production", "prod", "test", "", "STAGING")) {
            assertFails { AppConfig.fromEnvironment(env + ("RR_ENVIRONMENT" to mode)) }
        }
    }

    @Test
    fun `malformed hash ports host name and runtime flags are rejected`() {
        val env = environment()
        for (hash in listOf("", "0".repeat(64), "x".repeat(64), "A".repeat(64), "1".repeat(63))) {
            assertFails { AppConfig.fromEnvironment(env + ("RR_OPERATOR_TOKEN_SHA256" to hash)) }
        }
        for (key in listOf("RR_DB_PORT", "RR_PORT")) {
            for (port in listOf("0", "65536", "-1", "abc")) assertFails { AppConfig.fromEnvironment(env + (key to port)) }
        }
        for (host in listOf("db/?query=1", "db:3306", "", "db host")) assertFails { AppConfig.fromEnvironment(env + ("RR_DB_HOST" to host)) }
        for (name in listOf("db?allowMultiQueries=true", "db/name", "", "db-name")) assertFails { AppConfig.fromEnvironment(env + ("RR_DB_NAME" to name)) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_DB_USER" to "")) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_DB_PASSWORD" to "")) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_HOST" to "example.com")) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_ALLOW_LOOPBACK_HTTP" to "1")) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_DB_SSL_MODE" to "PREFERRED")) }
        assertFails { AppConfig.fromEnvironment(env + ("RR_RELEASE" to "branch-name")) }
    }

    @Test
    fun `explicit development options and trusted proxy addresses are parsed narrowly`() {
        val config = AppConfig.fromEnvironment(environment() + mapOf(
            "RR_HOST" to "0.0.0.0", "RR_PORT" to "8081", "RR_DB_PORT" to "3307",
            "RR_DB_SSL_MODE" to "DISABLED", "RR_ALLOW_LOOPBACK_HTTP" to "true",
            "RR_TRUSTED_PROXY_IPS" to "127.0.0.1, ::1, 127.0.0.1", "RR_RELEASE" to "a".repeat(40),
        ))
        assertEquals("0.0.0.0", config.host)
        assertEquals(8081, config.port)
        assertEquals(3307, config.database.port)
        assertTrue(config.allowLoopbackHttp)
        assertEquals(setOf("127.0.0.1", "::1"), config.trustedProxyIps)
        assertEquals("a".repeat(40), config.release)
        for (proxy in listOf("0.0.0.0/0", "example.com", "256.0.0.1", "::", "1.2.3")) {
            assertFails { AppConfig.fromEnvironment(environment() + ("RR_TRUSTED_PROXY_IPS" to proxy)) }
        }
    }

    @Test
    fun `secret files preserve database password bytes and conflict with direct values`() {
        val env = environment()
        val directory = Files.createTempDirectory("rr-kotlin-config-test-")
        val passwordFile = directory.resolve("database-password")
        val hashFile = directory.resolve("operator-hash")
        try {
            val password = " \t${UUID.randomUUID()}\n"
            Files.writeString(passwordFile, password)
            Files.writeString(hashFile, env.getValue("RR_OPERATOR_TOKEN_SHA256") + "\n")
            val fileEnv = env - setOf("RR_DB_PASSWORD", "RR_OPERATOR_TOKEN_SHA256") + mapOf(
                "RR_DB_PASSWORD_FILE" to passwordFile.toString(), "RR_OPERATOR_TOKEN_SHA256_FILE" to hashFile.toString(),
            )
            val config = AppConfig.fromEnvironment(fileEnv)
            assertEquals(password, config.database.password)
            assertEquals(env.getValue("RR_OPERATOR_TOKEN_SHA256"), config.operatorHash)
            assertFails { AppConfig.fromEnvironment(fileEnv + ("RR_DB_PASSWORD" to UUID.randomUUID().toString())) }
            assertFails { AppConfig.fromEnvironment(fileEnv + ("RR_OPERATOR_TOKEN_SHA256" to env.getValue("RR_OPERATOR_TOKEN_SHA256"))) }
            assertFails { AppConfig.fromEnvironment(fileEnv + ("RR_DB_PASSWORD_FILE" to directory.resolve("missing").toString())) }
        } finally {
            Files.deleteIfExists(passwordFile)
            Files.deleteIfExists(hashFile)
            Files.deleteIfExists(directory)
        }
    }
}

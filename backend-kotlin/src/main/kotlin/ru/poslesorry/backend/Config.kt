package ru.poslesorry.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path

data class DatabaseConfig(val host: String, val port: Int, val name: String, val user: String, val password: String, val sslMode: String) {
    override fun toString(): String = "DatabaseConfig(redacted)"
}

data class AppConfig(
    val operatorHash: String,
    val database: DatabaseConfig,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val trustedProxyIps: Set<String> = emptySet(),
    val allowLoopbackHttp: Boolean = false,
    val release: String? = null,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            fun required(key: String): String = env[key]?.takeIf { it.isNotEmpty() } ?: error("Configuration unavailable")
            fun secret(key: String): String {
                val direct = env[key]
                val file = env["${key}_FILE"]
                require((direct == null) != (file == null)) { "Configuration unavailable" }
                return if (file != null) Files.readString(Path.of(file)) else direct!!
            }
            require(env["RR_ENVIRONMENT"] == "staging") { "Only staging is supported" }
            val hash = secret("RR_OPERATOR_TOKEN_SHA256").trim()
            require(hash.matches(Regex("[a-f0-9]{64}")) && hash != "0".repeat(64))
            val dbHost = required("RR_DB_HOST")
            val dbName = required("RR_DB_NAME")
            require(dbHost.matches(Regex("[A-Za-z0-9._-]+")))
            require(dbName.matches(Regex("[A-Za-z0-9_]+")))
            val dbPort = (env["RR_DB_PORT"] ?: "3306").toInt().also { require(it in 1..65535) }
            val ssl = env["RR_DB_SSL_MODE"] ?: "VERIFY_IDENTITY"
            require(ssl in setOf("VERIFY_IDENTITY", "REQUIRED", "DISABLED"))
            val password = secret("RR_DB_PASSWORD").also { require(it.isNotEmpty()) }
            val listenHost = env["RR_HOST"] ?: "127.0.0.1"
            require(listenHost in setOf("127.0.0.1", "::1", "0.0.0.0"))
            val port = (env["RR_PORT"] ?: "8080").toInt().also { require(it in 1..65535) }
            val proxies = env["RR_TRUSTED_PROXY_IPS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            require(proxies.all { ip -> ip == "::1" || ip.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } } })
            val localHttp = env["RR_ALLOW_LOOPBACK_HTTP"] ?: "false"
            require(localHttp in setOf("true", "false"))
            val release = env["RR_RELEASE"]?.takeIf { it.isNotEmpty() }
            require(release == null || release.matches(Regex("[a-f0-9]{40}")))
            return AppConfig(hash, DatabaseConfig(dbHost, dbPort, dbName, required("RR_DB_USER"), password, ssl), listenHost, port, proxies, localHttp == "true", release)
        }
    }
}

fun dataSource(config: DatabaseConfig): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.name}"
    username = config.user
    password = config.password
    addDataSourceProperty("sslMode", config.sslMode)
    addDataSourceProperty("connectionTimeZone", "UTC")
    addDataSourceProperty("forceConnectionTimeZoneToSession", "true")
    addDataSourceProperty("characterEncoding", "UTF-8")
    addDataSourceProperty("allowMultiQueries", "false")
    addDataSourceProperty("useServerPrepStmts", "true")
    addDataSourceProperty("connectTimeout", "5000")
    addDataSourceProperty("socketTimeout", "10000")
    maximumPoolSize = 3
    minimumIdle = 0
    connectionTimeout = 5000
    initializationFailTimeout = -1
})

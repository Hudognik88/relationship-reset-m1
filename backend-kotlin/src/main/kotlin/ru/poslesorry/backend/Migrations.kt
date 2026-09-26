package ru.poslesorry.backend

import java.security.MessageDigest
import javax.sql.DataSource

internal object InitialSchema {
    const val VERSION = "001_initial"
    val bytes: ByteArray by lazy {
        checkNotNull(InitialSchema::class.java.getResourceAsStream("/migrations/001_initial.sql")) {
            "Initial migration resource unavailable"
        }.use { it.readBytes() }
    }
    val checksum: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Run only from an explicit migration command, never as an HTTP request or at server startup. */
class Migrations(private val dataSource: DataSource) {
    fun run() {
        dataSource.connection.use { db ->
            var locked = false
            try {
                db.createStatement().use { it.execute("SET time_zone = '+00:00'") }
                locked = db.createStatement().use { query ->
                    query.executeQuery("SELECT GET_LOCK('rr_schema_migrations', 10)").use { row ->
                        row.next() && row.getInt(1) == 1
                    }
                }
                check(locked) { "Migration lock unavailable" }
                db.createStatement().use { it.execute(LEDGER_SQL) }
                val previous = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { query ->
                    query.setString(1, InitialSchema.VERSION)
                    query.executeQuery().use { row -> if (row.next()) row.getString(1) else null }
                }
                if (previous != null) {
                    check(sameHash(previous, InitialSchema.checksum)) { "Migration checksum mismatch" }
                } else {
                    // Exactly the additive, rerunnable PHP migration and its raw byte checksum.
                    InitialSchema.bytes.toString(Charsets.UTF_8).split(';').forEach { statement ->
                        if (statement.isNotBlank()) db.createStatement().use { it.execute(statement) }
                    }
                    db.prepareStatement("INSERT INTO rr_schema_migrations (version, checksum) VALUES (?, ?)").use { query ->
                        query.setString(1, InitialSchema.VERSION)
                        query.setString(2, InitialSchema.checksum)
                        query.executeUpdate()
                    }
                }
                check(schemaReady(db)) { "Schema unavailable" }
            } finally {
                if (locked) db.createStatement().use { it.executeQuery("SELECT RELEASE_LOCK('rr_schema_migrations')").close() }
            }
        }
    }

    companion object {
        internal const val LEDGER_SQL = """CREATE TABLE IF NOT EXISTS rr_schema_migrations (
        version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
        checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
        applied_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"""
    }
}

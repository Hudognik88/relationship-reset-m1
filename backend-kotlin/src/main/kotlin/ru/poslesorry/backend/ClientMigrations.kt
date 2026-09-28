package ru.poslesorry.backend

import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

internal object ClientSchema {
    const val VERSION = "002_client_rehearsal"
    val bytes: ByteArray by lazy {
        checkNotNull(ClientSchema::class.java.getResourceAsStream("/client-migrations/002_client_rehearsal.sql")).use { it.readBytes() }
    }
    val checksum: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal object ClientWorkspaceSchema {
    const val VERSION = "003_client_workspace"
    val bytes: ByteArray by lazy {
        checkNotNull(ClientWorkspaceSchema::class.java.getResourceAsStream("/client-migrations/003_client_workspace.sql")).use { it.readBytes() }
    }
    val checksum: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Additive, explicit client migration; never invoked on startup or by an HTTP request. */
class ClientMigrations(private val source: DataSource) {
    fun run() {
        source.connection.use { db ->
            var locked = false
            try {
                locked = db.createStatement().use { q -> q.executeQuery("SELECT GET_LOCK('rr_schema_migrations', 10)").use { it.next() && it.getInt(1) == 1 } }
                check(locked) { "Migration lock unavailable" }
                check(schemaReady(db)) { "Initial schema unavailable" }
                for ((version, bytes, checksum) in listOf(Triple(ClientSchema.VERSION, ClientSchema.bytes, ClientSchema.checksum), Triple(ClientWorkspaceSchema.VERSION, ClientWorkspaceSchema.bytes, ClientWorkspaceSchema.checksum))) {
                    val previous = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { q ->
                        q.setString(1, version)
                        q.executeQuery().use { if (it.next()) it.getString(1) else null }
                    }
                    if (previous != null) check(sameHash(previous, checksum)) { "Client migration checksum mismatch" }
                    else {
                        bytes.toString(Charsets.UTF_8).split(';').filter { it.isNotBlank() }.forEach { sql -> db.createStatement().use { it.execute(sql) } }
                        db.prepareStatement("INSERT INTO rr_schema_migrations (version, checksum) VALUES (?, ?)").use { q ->
                            q.setString(1, version); q.setString(2, checksum); q.executeUpdate()
                        }
                    }
                }
                check(clientSchemaReady(db)) { "Client schema unavailable" }
            } finally {
                if (locked) db.createStatement().use { it.executeQuery("SELECT RELEASE_LOCK('rr_schema_migrations')").close() }
            }
        }
    }
}

internal fun clientSchemaReady(db: Connection): Boolean {
    for ((version, checksum) in listOf(ClientSchema.VERSION to ClientSchema.checksum, ClientWorkspaceSchema.VERSION to ClientWorkspaceSchema.checksum)) {
        val valid = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { q ->
            q.setString(1, version)
            q.executeQuery().use { it.next() && sameHash(it.getString(1), checksum) }
        }
        if (!valid) return false
    }
    for (sql in listOf("SELECT token_hash, expires_at, consumed_at FROM rr_client_invitations LIMIT 0",
        "SELECT id, token_hash, expires_at, revoked_at FROM rr_client_sessions LIMIT 0",
        "SELECT id, session_id, client_request_id, request_hash, questionnaire FROM rr_client_cases LIMIT 0",
        "SELECT case_id, review_text, published_at FROM rr_client_reviews LIMIT 0",
        "SELECT owner_session_id, token_hash, rotated_at FROM rr_client_access_keys LIMIT 0",
        "SELECT session_id, owner_session_id FROM rr_client_session_links LIMIT 0",
        "SELECT token_hash, expires_at, consumed_at FROM rr_owner_invitations LIMIT 0",
        "SELECT id, token_hash, expires_at, revoked_at FROM rr_owner_sessions LIMIT 0")) db.createStatement().use { it.executeQuery(sql).close() }
    return true
}

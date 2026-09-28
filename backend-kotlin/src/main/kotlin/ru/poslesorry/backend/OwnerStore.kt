package ru.poslesorry.backend

import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

interface OwnerStore {
    fun ready(): Boolean
    fun issueInvitation(now: Instant): IssuedClientToken
    fun exchangeInvitation(token: String, now: Instant): IssuedClientToken
    fun session(token: String, now: Instant): ClientSession?
    fun revokeSession(sessionId: String, now: Instant)
    fun listCases(sessionId: String, now: Instant): JsonObject
    fun getCase(sessionId: String, caseId: String, now: Instant): JsonObject
    fun issueClientInvitation(sessionId: String, now: Instant): IssuedClientToken
    fun publishReview(sessionId: String, caseId: String, text: String, expectedVersion: String, now: Instant): JsonObject
}

/** Owner cookies have their own invitation/session tables and never inherit client rights. */
class JdbcOwnerStore(private val source: DataSource) : OwnerStore {
    private val clients = JdbcClientStore(source)
    override fun ready(): Boolean = transaction { clientSchemaReady(it) }

    override fun issueInvitation(now: Instant): IssuedClientToken = transaction { db ->
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(600))
        db.prepareStatement("INSERT INTO rr_owner_invitations (token_hash, expires_at) VALUES (?, ?)").use { q ->
            q.setString(1, Contract.sha256(issued.token)); q.setTimestamp(2, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        issued
    }

    override fun exchangeInvitation(token: String, now: Instant): IssuedClientToken = transaction { db ->
        val consumed = db.prepareStatement("UPDATE rr_owner_invitations SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, Contract.sha256(token)); q.setTimestamp(3, Timestamp.from(now)); q.executeUpdate()
        }
        if (consumed != 1) throw ApiProblem(401, "owner_invitation_invalid")
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(3600))
        db.prepareStatement("INSERT INTO rr_owner_sessions (id, token_hash, expires_at) VALUES (?, ?, ?)").use { q ->
            q.setString(1, randomId()); q.setString(2, Contract.sha256(issued.token)); q.setTimestamp(3, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        issued
    }

    override fun session(token: String, now: Instant): ClientSession? = transaction { db ->
        db.prepareStatement("SELECT id, expires_at FROM rr_owner_sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?").use { q ->
            q.setString(1, Contract.sha256(token)); q.setTimestamp(2, Timestamp.from(now))
            q.executeQuery().use { row -> if (row.next()) ClientSession(row.getString(1), row.getTimestamp(2).toInstant()) else null }
        }
    }

    private fun active(db: Connection, sessionId: String, now: Instant) {
        val valid = db.prepareStatement("SELECT id FROM rr_owner_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE").use { q ->
            q.setString(1, sessionId); q.setTimestamp(2, Timestamp.from(now)); q.executeQuery().use { it.next() }
        }
        if (!valid) throw ApiProblem(401, "unauthorized")
    }

    override fun revokeSession(sessionId: String, now: Instant) = transaction { db ->
        active(db, sessionId, now)
        db.prepareStatement("UPDATE rr_owner_sessions SET revoked_at = ? WHERE id = ?").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, sessionId); q.executeUpdate()
        }
        Unit
    }

    override fun listCases(sessionId: String, now: Instant): JsonObject = transaction { db ->
        active(db, sessionId, now); clients.listCases(db)
    }

    override fun getCase(sessionId: String, caseId: String, now: Instant): JsonObject = transaction { db ->
        active(db, sessionId, now); clients.operatorCase(db, caseId, includeVersion = true)
    }

    override fun issueClientInvitation(sessionId: String, now: Instant): IssuedClientToken = transaction { db ->
        active(db, sessionId, now); clients.issueInvitation(db, now)
    }

    override fun publishReview(sessionId: String, caseId: String, text: String, expectedVersion: String, now: Instant): JsonObject = transaction { db ->
        active(db, sessionId, now); clients.publishReview(db, caseId, text, now, expectedVersion)
    }

    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { db ->
        db.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        db.autoCommit = false
        try { block(db).also { db.commit() } } catch (error: Throwable) { db.rollback(); throw error }
    }

    companion object {
        private val random = SecureRandom()
        private fun randomToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        private fun randomId() = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

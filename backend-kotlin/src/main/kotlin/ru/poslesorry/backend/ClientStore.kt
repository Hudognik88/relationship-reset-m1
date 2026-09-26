package ru.poslesorry.backend

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

data class IssuedClientToken(val token: String, val expiresAt: Instant) {
    override fun toString() = "IssuedClientToken(redacted)"
}
data class ClientSession(val id: String, val expiresAt: Instant)

interface ClientStore {
    fun ready(): Boolean
    fun issueInvitation(now: Instant): IssuedClientToken
    fun exchangeInvitation(token: String, now: Instant): IssuedClientToken
    fun session(token: String, now: Instant): ClientSession?
    fun revokeSession(sessionId: String, now: Instant)
    fun getCase(sessionId: String, now: Instant): JsonObject
    fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult
    fun deleteCase(sessionId: String, now: Instant)
    fun publishReview(caseId: String, text: String, now: Instant): JsonObject
    fun listCases(): JsonObject = throw UnsupportedOperationException()
    fun operatorCase(caseId: String): JsonObject = throw UnsupportedOperationException()
}

/** Client tables are separate from the unchanged operator rehearsal contract. */
class JdbcClientStore(private val source: DataSource) : ClientStore {
    override fun ready(): Boolean = transaction { clientSchemaReady(it) }

    override fun issueInvitation(now: Instant): IssuedClientToken = transaction { db ->
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(3600))
        db.prepareStatement("INSERT INTO rr_client_invitations (token_hash, expires_at) VALUES (?, ?)").use { q ->
            q.setString(1, Contract.sha256(issued.token)); q.setTimestamp(2, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        issued
    }

    override fun exchangeInvitation(token: String, now: Instant): IssuedClientToken = transaction { db ->
        val consumed = db.prepareStatement("UPDATE rr_client_invitations SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, Contract.sha256(token)); q.setTimestamp(3, Timestamp.from(now)); q.executeUpdate()
        }
        if (consumed != 1) throw ApiProblem(401, "invitation_invalid")
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(86400))
        db.prepareStatement("INSERT INTO rr_client_sessions (id, token_hash, expires_at) VALUES (?, ?, ?)").use { q ->
            q.setString(1, randomId()); q.setString(2, Contract.sha256(issued.token)); q.setTimestamp(3, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        issued
    }

    override fun session(token: String, now: Instant): ClientSession? = transaction { db ->
        db.prepareStatement("SELECT id, expires_at FROM rr_client_sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?").use { q ->
            q.setString(1, Contract.sha256(token)); q.setTimestamp(2, Timestamp.from(now))
            q.executeQuery().use { row -> if (row.next()) ClientSession(row.getString("id"), row.getTimestamp("expires_at").toInstant()) else null }
        }
    }

    private fun active(db: Connection, sessionId: String, now: Instant) {
        val valid = db.prepareStatement("SELECT id FROM rr_client_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE").use { q ->
            q.setString(1, sessionId); q.setTimestamp(2, Timestamp.from(now)); q.executeQuery().use { it.next() }
        }
        if (!valid) throw ApiProblem(401, "unauthorized")
    }

    override fun revokeSession(sessionId: String, now: Instant) = transaction { db ->
        active(db, sessionId, now)
        db.prepareStatement("UPDATE rr_client_sessions SET revoked_at = ? WHERE id = ?").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, sessionId); q.executeUpdate()
        }
        Unit
    }

    override fun getCase(sessionId: String, now: Instant): JsonObject = transaction { db ->
        active(db, sessionId, now)
        caseForSession(db, sessionId)?.let { envelope(db, it) } ?: emptyCase()
    }

    override fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult = transaction { db ->
        active(db, sessionId, now)
        val requestId = payload.getValue("client_request_id").jsonPrimitive.content
        val hash = Contract.sha256(Contract.canonicalJson(payload))
        val previous = db.prepareStatement("SELECT client_request_id, request_hash FROM rr_client_cases WHERE session_id = ?").use { q ->
            q.setString(1, sessionId); q.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        }
        if (previous != null) {
            if (previous.first != requestId) throw ApiProblem(409, "case_exists")
            if (!sameHash(previous.second, hash)) throw ApiProblem(409, "idempotency_conflict")
            return@transaction ApiResult(200, checkNotNull(caseForSession(db, sessionId)))
        }
        db.prepareStatement("INSERT INTO rr_client_cases (id, session_id, client_request_id, request_hash, questionnaire) VALUES (?, ?, ?, ?, ?)").use { q ->
            q.setString(1, randomId()); q.setString(2, sessionId); q.setString(3, requestId); q.setString(4, hash)
            q.setString(5, Contract.canonicalJson(payload.getValue("questionnaire"))); q.executeUpdate()
        }
        ApiResult(201, checkNotNull(caseForSession(db, sessionId)))
    }

    override fun deleteCase(sessionId: String, now: Instant) = transaction { db ->
        active(db, sessionId, now)
        db.prepareStatement("DELETE FROM rr_client_cases WHERE session_id = ?").use { q -> q.setString(1, sessionId); q.executeUpdate() }
        Unit
    }

    override fun publishReview(caseId: String, text: String, now: Instant): JsonObject = transaction { db ->
        val exists = db.prepareStatement("SELECT id FROM rr_client_cases WHERE id = ? FOR UPDATE").use { q -> q.setString(1, caseId); q.executeQuery().use { it.next() } }
        if (!exists) throw ApiProblem(404, "case_not_found")
        db.prepareStatement("INSERT INTO rr_client_reviews (case_id, review_text, published_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE review_text = VALUES(review_text), published_at = VALUES(published_at)").use { q ->
            q.setString(1, caseId); q.setString(2, text); q.setTimestamp(3, Timestamp.from(now)); q.executeUpdate()
        }
        buildJsonObject { put("published", true); put("case_id", caseId) }
    }

    override fun listCases(): JsonObject = transaction { db ->
        val cases = buildJsonArray {
            db.createStatement().use { q -> q.executeQuery("SELECT c.id, c.created_at, r.case_id AS reviewed FROM rr_client_cases c LEFT JOIN rr_client_reviews r ON r.case_id = c.id ORDER BY c.created_at DESC, c.id DESC LIMIT 20").use { row ->
                while (row.next()) add(buildJsonObject { put("id", row.getString("id")); put("created_at", row.getTimestamp("created_at").toInstant().toString()); put("status", if (row.getString("reviewed") == null) "awaiting_human_review" else "published") })
            } }
        }
        buildJsonObject { put("cases", cases) }
    }

    override fun operatorCase(caseId: String): JsonObject = transaction { db ->
        val case = db.prepareStatement("SELECT id, questionnaire, created_at FROM rr_client_cases WHERE id = ?").use { q ->
            q.setString(1, caseId); q.executeQuery().use { if (it.next()) caseJson(it) else null }
        } ?: throw ApiProblem(404, "case_not_found")
        envelope(db, case)
    }

    private fun caseForSession(db: Connection, sessionId: String): JsonObject? =
        db.prepareStatement("SELECT id, questionnaire, created_at FROM rr_client_cases WHERE session_id = ?").use { q ->
            q.setString(1, sessionId); q.executeQuery().use { if (it.next()) caseJson(it) else null }
        }

    private fun caseJson(row: ResultSet) = buildJsonObject {
        put("id", row.getString("id")); put("schema_version", "m1-cis-v1"); put("synthetic", true)
        put("questionnaire", Contract.json.parseToJsonElement(row.getString("questionnaire")))
        put("status", "stored_for_rehearsal"); put("created_at", row.getTimestamp("created_at").toInstant().toString())
    }

    private fun envelope(db: Connection, case: JsonObject): JsonObject {
        val review = db.prepareStatement("SELECT review_text, published_at FROM rr_client_reviews WHERE case_id = ?").use { q ->
            q.setString(1, case.getValue("id").jsonPrimitive.content)
            q.executeQuery().use { row -> if (row.next()) buildJsonObject { put("text", row.getString(1)); put("published_at", row.getTimestamp(2).toInstant().toString()) } else JsonNull }
        }
        return buildJsonObject { put("case", case); put("review", review) }
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
        private fun emptyCase() = buildJsonObject { put("case", JsonNull); put("review", JsonNull) }
    }
}

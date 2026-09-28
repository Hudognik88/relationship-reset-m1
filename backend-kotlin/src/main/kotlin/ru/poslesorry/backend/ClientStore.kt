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
data class ClientSession(val id: String, val expiresAt: Instant, val hasAccessKey: Boolean = false)

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
    fun rotateAccessKey(sessionId: String, now: Instant): String = throw UnsupportedOperationException()
    fun login(accessKey: String, now: Instant): IssuedClientToken = throw UnsupportedOperationException()
}

/** Client tables are separate from the unchanged operator rehearsal contract. */
class JdbcClientStore(private val source: DataSource) : ClientStore {
    override fun ready(): Boolean = transaction { clientSchemaReady(it) }

    override fun issueInvitation(now: Instant): IssuedClientToken = transaction { db -> issueInvitation(db, now) }

    internal fun issueInvitation(db: Connection, now: Instant): IssuedClientToken {
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(3600))
        db.prepareStatement("INSERT INTO rr_client_invitations (token_hash, expires_at) VALUES (?, ?)").use { q ->
            q.setString(1, Contract.sha256(issued.token)); q.setTimestamp(2, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        return issued
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
        db.prepareStatement("SELECT s.id, s.expires_at, k.owner_session_id AS access_owner FROM rr_client_sessions s LEFT JOIN rr_client_session_links l ON l.session_id = s.id LEFT JOIN rr_client_access_keys k ON k.owner_session_id = COALESCE(l.owner_session_id, s.id) WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?").use { q ->
            q.setString(1, Contract.sha256(token)); q.setTimestamp(2, Timestamp.from(now))
            q.executeQuery().use { row -> if (row.next()) ClientSession(row.getString("id"), row.getTimestamp("expires_at").toInstant(), row.getString("access_owner") != null) else null }
        }
    }

    /** All account operations lock the canonical owner before any resumed session. */
    internal fun active(db: Connection, sessionId: String, now: Instant): String {
        val owner = db.prepareStatement("SELECT owner_session_id FROM rr_client_session_links WHERE session_id = ?").use { q ->
            q.setString(1, sessionId); q.executeQuery().use { if (it.next()) it.getString(1) else sessionId }
        }
        lockOwner(db, owner)
        val valid = db.prepareStatement("SELECT id FROM rr_client_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE").use { q ->
            q.setString(1, sessionId); q.setTimestamp(2, Timestamp.from(now)); q.executeQuery().use { it.next() }
        }
        if (!valid) throw ApiProblem(401, "unauthorized")
        return owner
    }

    private fun lockOwner(db: Connection, owner: String) {
        val found = db.prepareStatement("SELECT id FROM rr_client_sessions WHERE id = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.executeQuery().use { it.next() }
        }
        if (!found) throw ApiProblem(401, "unauthorized")
    }

    override fun rotateAccessKey(sessionId: String, now: Instant): String = transaction { db ->
        val owner = active(db, sessionId, now)
        val key = randomToken()
        db.prepareStatement("INSERT INTO rr_client_access_keys (owner_session_id, token_hash, rotated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE token_hash = VALUES(token_hash), rotated_at = VALUES(rotated_at)").use { q ->
            q.setString(1, owner); q.setString(2, Contract.sha256(key)); q.setTimestamp(3, Timestamp.from(now)); q.executeUpdate()
        }
        db.prepareStatement("UPDATE rr_client_sessions SET revoked_at = ? WHERE id <> ? AND (id = ? OR id IN (SELECT session_id FROM rr_client_session_links WHERE owner_session_id = ?))").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, sessionId); q.setString(3, owner); q.setString(4, owner); q.executeUpdate()
        }
        key
    }

    override fun login(accessKey: String, now: Instant): IssuedClientToken = transaction { db ->
        val hash = Contract.sha256(accessKey)
        val owner = db.prepareStatement("SELECT owner_session_id FROM rr_client_access_keys WHERE token_hash = ?").use { q ->
            q.setString(1, hash); q.executeQuery().use { if (it.next()) it.getString(1) else null }
        } ?: throw ApiProblem(401, "access_key_invalid")
        lockOwner(db, owner)
        // Locking reread sees a rotation committed while this request waited for the owner.
        val valid = db.prepareStatement("SELECT owner_session_id FROM rr_client_access_keys WHERE owner_session_id = ? AND token_hash = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.setString(2, hash); q.executeQuery().use { it.next() }
        }
        if (!valid) throw ApiProblem(401, "access_key_invalid")
        val issued = IssuedClientToken(randomToken(), now.plusSeconds(86400))
        val id = randomId()
        db.prepareStatement("INSERT INTO rr_client_sessions (id, token_hash, expires_at) VALUES (?, ?, ?)").use { q ->
            q.setString(1, id); q.setString(2, Contract.sha256(issued.token)); q.setTimestamp(3, Timestamp.from(issued.expiresAt)); q.executeUpdate()
        }
        db.prepareStatement("INSERT INTO rr_client_session_links (session_id, owner_session_id) VALUES (?, ?)").use { q ->
            q.setString(1, id); q.setString(2, owner); q.executeUpdate()
        }
        issued
    }

    override fun revokeSession(sessionId: String, now: Instant) = transaction { db ->
        active(db, sessionId, now)
        db.prepareStatement("UPDATE rr_client_sessions SET revoked_at = ? WHERE id = ?").use { q ->
            q.setTimestamp(1, Timestamp.from(now)); q.setString(2, sessionId); q.executeUpdate()
        }
        Unit
    }

    override fun getCase(sessionId: String, now: Instant): JsonObject = transaction { db ->
        val owner = active(db, sessionId, now)
        caseForSession(db, owner)?.let { envelope(db, it) } ?: emptyCase()
    }

    override fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult = transaction { db ->
        val owner = active(db, sessionId, now)
        val requestId = payload.getValue("client_request_id").jsonPrimitive.content
        val hash = Contract.sha256(Contract.canonicalJson(payload))
        val previous = db.prepareStatement("SELECT client_request_id, request_hash FROM rr_client_cases WHERE session_id = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        }
        if (previous != null) {
            if (previous.first != requestId) throw ApiProblem(409, "case_exists")
            if (!sameHash(previous.second, hash)) throw ApiProblem(409, "idempotency_conflict")
            return@transaction ApiResult(200, checkNotNull(caseForSession(db, owner)))
        }
        db.prepareStatement("INSERT INTO rr_client_cases (id, session_id, client_request_id, request_hash, questionnaire) VALUES (?, ?, ?, ?, ?)").use { q ->
            q.setString(1, randomId()); q.setString(2, owner); q.setString(3, requestId); q.setString(4, hash)
            q.setString(5, Contract.canonicalJson(payload.getValue("questionnaire"))); q.executeUpdate()
        }
        ApiResult(201, checkNotNull(caseForSession(db, owner)))
    }

    override fun deleteCase(sessionId: String, now: Instant) = transaction { db ->
        val owner = active(db, sessionId, now)
        db.prepareStatement("DELETE FROM rr_client_cases WHERE session_id = ?").use { q -> q.setString(1, owner); q.executeUpdate() }
        Unit
    }

    override fun publishReview(caseId: String, text: String, now: Instant): JsonObject = transaction { db -> publishReview(db, caseId, text, now) }

    internal fun publishReview(db: Connection, caseId: String, text: String, now: Instant, expectedVersion: String? = null): JsonObject {
        val exists = db.prepareStatement("SELECT id FROM rr_client_cases WHERE id = ? FOR UPDATE").use { q -> q.setString(1, caseId); q.executeQuery().use { it.next() } }
        if (!exists) throw ApiProblem(404, "case_not_found")
        if (expectedVersion != null && !sameHash(reviewVersion(review(db, caseId)), expectedVersion)) throw ApiProblem(409, "review_conflict")
        db.prepareStatement("INSERT INTO rr_client_reviews (case_id, review_text, published_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE review_text = VALUES(review_text), published_at = VALUES(published_at)").use { q ->
            q.setString(1, caseId); q.setString(2, text); q.setTimestamp(3, Timestamp.from(now)); q.executeUpdate()
        }
        return buildJsonObject { put("published", true); put("case_id", caseId); if (expectedVersion != null) put("review_version", reviewVersion(review(db, caseId))) }
    }

    override fun listCases(): JsonObject = transaction { db -> listCases(db) }

    internal fun listCases(db: Connection): JsonObject {
        val cases = buildJsonArray {
            db.createStatement().use { q -> q.executeQuery("SELECT c.id, c.created_at, r.case_id AS reviewed FROM rr_client_cases c LEFT JOIN rr_client_reviews r ON r.case_id = c.id ORDER BY c.created_at DESC, c.id DESC LIMIT 20").use { row ->
                while (row.next()) add(buildJsonObject { put("id", row.getString("id")); put("created_at", row.getTimestamp("created_at").toInstant().toString()); put("status", if (row.getString("reviewed") == null) "awaiting_human_review" else "published") })
            } }
        }
        return buildJsonObject { put("cases", cases) }
    }

    override fun operatorCase(caseId: String): JsonObject = transaction { db -> operatorCase(db, caseId) }

    internal fun operatorCase(db: Connection, caseId: String, includeVersion: Boolean = false): JsonObject {
        val case = db.prepareStatement("SELECT id, questionnaire, created_at FROM rr_client_cases WHERE id = ? FOR UPDATE").use { q ->
            q.setString(1, caseId); q.executeQuery().use { if (it.next()) caseJson(it) else null }
        } ?: throw ApiProblem(404, "case_not_found")
        val result = envelope(db, case)
        return if (includeVersion) JsonObject(result + ("review_version" to JsonPrimitive(reviewVersion(result.getValue("review"))))) else result
    }

    private fun caseForSession(db: Connection, sessionId: String): JsonObject? =
        db.prepareStatement("SELECT id, questionnaire, created_at FROM rr_client_cases WHERE session_id = ? FOR UPDATE").use { q ->
            q.setString(1, sessionId); q.executeQuery().use { if (it.next()) caseJson(it) else null }
        }

    private fun caseJson(row: ResultSet) = buildJsonObject {
        put("id", row.getString("id")); put("schema_version", "m1-cis-v1"); put("synthetic", true)
        put("questionnaire", Contract.json.parseToJsonElement(row.getString("questionnaire")))
        put("status", "stored_for_rehearsal"); put("created_at", row.getTimestamp("created_at").toInstant().toString())
    }

    private fun envelope(db: Connection, case: JsonObject): JsonObject {
        return buildJsonObject { put("case", case); put("review", review(db, case.getValue("id").jsonPrimitive.content)) }
    }

    private fun review(db: Connection, caseId: String): JsonElement = db.prepareStatement("SELECT review_text, published_at FROM rr_client_reviews WHERE case_id = ? FOR UPDATE").use { q ->
            q.setString(1, caseId)
            q.executeQuery().use { row -> if (row.next()) buildJsonObject { put("text", row.getString(1)); put("published_at", row.getTimestamp(2).toInstant().toString()) } else JsonNull }
        }

    private fun reviewVersion(review: JsonElement): String = if (review == JsonNull) "unpublished" else Contract.sha256(Contract.canonicalJson(review))

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

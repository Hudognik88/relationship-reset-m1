package ru.poslesorry.backend

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

data class ApiResult(val status: Int, val body: JsonObject)

interface CaseStore {
    fun ready(): Boolean
    fun createCase(payload: JsonObject): ApiResult
    fun getCase(id: String): JsonObject
    fun createDraft(caseId: String, payload: JsonObject): ApiResult
}

/** Shares the PHP tables and idempotency keys; never creates or migrates tables implicitly. */
class JdbcCaseStore(private val dataSource: DataSource) : CaseStore {
    override fun ready(): Boolean = connection { schemaReady(it) }

    override fun createCase(payload: JsonObject): ApiResult = connection { db ->
        val hash = Contract.sha256(Contract.canonicalJson(payload))
        val id = newId()
        val requestId = payload.getValue("client_request_id").jsonPrimitive.content
        try {
            db.prepareStatement(
                "INSERT INTO rr_cases (id, client_request_id, request_hash, schema_version, synthetic, questionnaire) VALUES (?, ?, ?, ?, 1, ?)"
            ).use { query ->
                query.setString(1, id)
                query.setString(2, requestId)
                query.setString(3, hash)
                query.setString(4, "m1-cis-v1")
                query.setString(5, Contract.canonicalJson(payload.getValue("questionnaire")))
                query.executeUpdate()
            }
            ApiResult(201, getCase(db, id))
        } catch (error: SQLException) {
            if (error.errorCode != 1062) throw error
            val previous = findRequest(db, "rr_cases", requestId) ?: throw error
            if (!sameHash(previous.second, hash)) throw ApiProblem(409, "idempotency_conflict")
            ApiResult(200, getCase(db, previous.first))
        }
    }

    override fun getCase(id: String): JsonObject = connection { getCase(it, id) }

    override fun createDraft(caseId: String, payload: JsonObject): ApiResult = connection { db ->
        val case = getCase(db, caseId)
        if (case["synthetic"] != JsonPrimitive(true)) throw ApiProblem(409, "synthetic_case_required")
        val hash = Contract.sha256(Contract.canonicalJson(buildJsonObject {
            put("case_id", caseId)
            put("payload", payload)
        }))
        val id = newId()
        val requestId = payload.getValue("client_request_id").jsonPrimitive.content
        try {
            db.prepareStatement(
                "INSERT INTO rr_drafts (id, case_id, client_request_id, request_hash, draft_text, reviewed) VALUES (?, ?, ?, ?, ?, 0)"
            ).use { query ->
                query.setString(1, id)
                query.setString(2, caseId)
                query.setString(3, requestId)
                query.setString(4, hash)
                query.setString(5, payload.getValue("text").jsonPrimitive.content)
                query.executeUpdate()
            }
            ApiResult(201, draftResult(id, caseId))
        } catch (error: SQLException) {
            if (error.errorCode != 1062) throw error
            val previous = findRequest(db, "rr_drafts", requestId) ?: throw error
            if (!sameHash(previous.second, hash)) throw ApiProblem(409, "idempotency_conflict")
            ApiResult(200, draftResult(previous.first, caseId))
        }
    }

    private fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use { db ->
        // DATETIME has no timezone. PHP writes UTC; make this explicit for every borrowed connection.
        db.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        block(db)
    }

    private fun getCase(db: Connection, id: String): JsonObject = db.prepareStatement(
        "SELECT id, schema_version, synthetic, questionnaire, created_at FROM rr_cases WHERE id = ?"
    ).use { query ->
        query.setString(1, id)
        query.executeQuery().use { row ->
            if (!row.next()) throw ApiProblem(404, "case_not_found")
            buildJsonObject {
                put("id", row.getString("id"))
                put("schema_version", row.getString("schema_version"))
                put("synthetic", row.getBoolean("synthetic"))
                put("questionnaire", Contract.json.parseToJsonElement(row.getString("questionnaire")))
                put("status", "stored_for_rehearsal")
                put("created_at", row.getObject("created_at", LocalDateTime::class.java).format(TIMESTAMP_FORMAT))
            }
        }
    }

    private fun findRequest(db: Connection, table: String, requestId: String): Pair<String, String>? {
        // The table is selected only from these internal constants, never from a request.
        require(table == "rr_cases" || table == "rr_drafts")
        return db.prepareStatement("SELECT id, request_hash FROM $table WHERE client_request_id = ?").use { query ->
            query.setString(1, requestId)
            query.executeQuery().use { row ->
                if (row.next()) row.getString("id") to row.getString("request_hash") else null
            }
        }
    }

    private fun draftResult(id: String, caseId: String): JsonObject = buildJsonObject {
        put("id", id)
        put("case_id", caseId)
        put("synthetic", true)
        put("reviewed", false)
        put("status", "draft_requires_human_review")
    }

    companion object {
        private val RANDOM = SecureRandom()
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
        private fun newId(): String = ByteArray(16).also(RANDOM::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

internal fun sameHash(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII)
)

internal fun schemaReady(db: Connection): Boolean {
    val valid = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { query ->
        query.setString(1, InitialSchema.VERSION)
        query.executeQuery().use { row -> row.next() && sameHash(InitialSchema.checksum, row.getString(1)) }
    }
    if (!valid) return false
    db.createStatement().use { it.executeQuery("SELECT id, questionnaire FROM rr_cases LIMIT 0").close() }
    db.createStatement().use { it.executeQuery("SELECT id, reviewed FROM rr_drafts LIMIT 0").close() }
    return true
}

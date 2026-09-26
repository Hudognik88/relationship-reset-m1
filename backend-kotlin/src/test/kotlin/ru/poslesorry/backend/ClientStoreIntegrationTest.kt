package ru.poslesorry.backend

import com.mysql.cj.jdbc.MysqlDataSource
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Client ownership tests run only in an explicitly supplied, disposable *_test database. */
class ClientStoreIntegrationTest {
    private lateinit var dataSource: MysqlDataSource
    private lateinit var store: JdbcClientStore
    private val invitations = mutableSetOf<String>()
    private val sessionIds = mutableSetOf<String>()
    private val now = Instant.parse("2026-09-26T09:00:00Z")

    @BeforeEach
    fun prepare() {
        val name = System.getenv("RR_TEST_DB_NAME")
        assumeTrue(!name.isNullOrBlank(), "RR_TEST_DB_NAME not supplied; client database integration test skipped")
        require(name!!.matches(Regex("[A-Za-z0-9_]+_test"))) { "Integration database must end in _test" }
        dataSource = MysqlDataSource().apply {
            setServerName(System.getenv("RR_TEST_DB_HOST") ?: "127.0.0.1")
            setPort((System.getenv("RR_TEST_DB_PORT") ?: "3306").toInt())
            setDatabaseName(name)
            setUser(requireNotNull(System.getenv("RR_TEST_DB_USER")))
            setPassword(requireNotNull(System.getenv("RR_TEST_DB_PASSWORD")))
            setUseSSL(false)
            setAllowPublicKeyRetrieval(true)
            setServerTimezone("UTC")
        }
        Migrations(dataSource).run()
        ClientMigrations(dataSource).run()
        store = JdbcClientStore(dataSource)
    }

    @AfterEach
    fun cleanup() {
        if (!::dataSource.isInitialized) return
        dataSource.connection.use { db ->
            db.prepareStatement("DELETE FROM rr_client_sessions WHERE id = ?").use { query ->
                sessionIds.forEach { query.setString(1, it); query.executeUpdate() }
            }
            db.prepareStatement("DELETE FROM rr_client_invitations WHERE token_hash = ?").use { query ->
                invitations.forEach { query.setString(1, Contract.sha256(it)); query.executeUpdate() }
            }
        }
    }

    private fun invitation(at: Instant = now): IssuedClientToken = store.issueInvitation(at).also { invitations += it.token }
    private fun session(at: Instant = now): Pair<IssuedClientToken, ClientSession> {
        val issued = store.exchangeInvitation(invitation(at).token, at)
        val session = assertNotNull(store.session(issued.token, at))
        sessionIds += session.id
        return issued to session
    }

    @Test
    fun `client migration is repeatable and preserves the legacy migration ledger`() {
        fun ledger(): List<List<String>> = dataSource.connection.use { db ->
            db.createStatement().use { query ->
                query.executeQuery("SELECT version, checksum, applied_at FROM rr_schema_migrations ORDER BY version").use { rows ->
                    buildList { while (rows.next()) add(listOf(rows.getString(1), rows.getString(2), rows.getString(3))) }
                }
            }
        }
        val before = ledger()
        ClientMigrations(dataSource).run()
        assertEquals(before, ledger())
        assertTrue(store.ready())
        assertTrue(JdbcCaseStore(dataSource).ready())
        assertTrue(before.any { it[0] == InitialSchema.VERSION && it[1] == InitialSchema.checksum })
    }

    @Test
    fun `changed client migration checksum stops client readiness without breaking legacy readiness`() {
        fun checksum(value: String) = dataSource.connection.use { db ->
            db.prepareStatement("UPDATE rr_schema_migrations SET checksum = ? WHERE version = ?").use {
                it.setString(1, value); it.setString(2, ClientSchema.VERSION); it.executeUpdate()
            }
        }
        try {
            checksum("0".repeat(64))
            assertFalse(store.ready())
            assertTrue(JdbcCaseStore(dataSource).ready())
            assertFailsWith<IllegalStateException> { ClientMigrations(dataSource).run() }
        } finally { checksum(ClientSchema.checksum) }
        ClientMigrations(dataSource).run()
        assertTrue(store.ready())
    }

    @Test
    fun `invitation is single use expiring and only hashes are stored`() {
        val invite = invitation()
        assertTrue(invite.token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(now.plusSeconds(3600), invite.expiresAt)
        val issued = store.exchangeInvitation(invite.token, now)
        val session = assertNotNull(store.session(issued.token, now))
        sessionIds += session.id
        assertEquals(now.plusSeconds(86400), issued.expiresAt)
        assertEquals(issued.expiresAt, session.expiresAt)
        assertProblem(401, "invitation_invalid") { store.exchangeInvitation(invite.token, now) }
        val expired = invitation()
        assertProblem(401, "invitation_invalid") { store.exchangeInvitation(expired.token, expired.expiresAt) }
        assertProblem(401, "invitation_invalid") { store.exchangeInvitation("x".repeat(43), now) }
        dataSource.connection.use { db ->
            db.prepareStatement("SELECT token_hash FROM rr_client_invitations WHERE token_hash = ?").use { query ->
                query.setString(1, Contract.sha256(invite.token))
                query.executeQuery().use { row -> assertTrue(row.next()); assertNotEquals(invite.token, row.getString(1)) }
            }
            db.prepareStatement("SELECT token_hash FROM rr_client_sessions WHERE id = ?").use { query ->
                query.setString(1, session.id)
                query.executeQuery().use { row ->
                    assertTrue(row.next()); assertEquals(Contract.sha256(issued.token), row.getString(1)); assertNotEquals(issued.token, row.getString(1))
                }
            }
        }
    }

    @Test
    fun `concurrent invitation redemption establishes at most one session`() {
        val invite = invitation()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll((1..4).map { Callable { runCatching { store.exchangeInvitation(invite.token, now) } } })
                .map { it.get(20, TimeUnit.SECONDS) }
            val successes = results.mapNotNull { it.getOrNull() }
            successes.forEach { sessionIds += assertNotNull(store.session(it.token, now)).id }
            assertEquals(1, successes.size)
            results.filter { it.isFailure }.forEach {
                val error = assertIs<ApiProblem>(it.exceptionOrNull())
                assertEquals(401, error.status); assertEquals("invitation_invalid", error.code)
            }
        } finally { workers.shutdownNow() }
    }

    @Test
    fun `expired and revoked sessions cannot authenticate and logout preserves owned data`() {
        val (issued, active) = session()
        store.createCase(active.id, payload(), now)
        val before = store.getCase(active.id, now)
        assertNotNull(store.session(issued.token, issued.expiresAt.minusSeconds(1)))
        assertNull(store.session(issued.token, issued.expiresAt))
        assertNull(store.session("x".repeat(43), now))
        store.revokeSession(active.id, now.plusSeconds(1))
        assertNull(store.session(issued.token, now.plusSeconds(2)))
        assertProblem(401, "unauthorized") { store.getCase(active.id, now.plusSeconds(2)) }
        assertProblem(401, "unauthorized") { store.createCase(active.id, payload(), now.plusSeconds(2)) }
        assertProblem(401, "unauthorized") { store.deleteCase(active.id, now.plusSeconds(2)) }
        val id = before.getValue("case").jsonObject.getValue("id").jsonPrimitive.content
        dataSource.connection.use { db ->
            db.prepareStatement("SELECT id FROM rr_client_cases WHERE id = ? AND session_id = ?").use { query ->
                query.setString(1, id); query.setString(2, active.id)
                query.executeQuery().use { assertTrue(it.next(), "Logout must not silently delete a saved rehearsal") }
            }
        }
    }

    @Test
    fun `every owned storage action rechecks expiry even when called with a previously valid session id`() {
        val (issued, owner) = session()
        store.createCase(owner.id, payload(), now)
        assertProblem(401, "unauthorized") { store.getCase(owner.id, issued.expiresAt) }
        assertProblem(401, "unauthorized") { store.createCase(owner.id, payload(), issued.expiresAt) }
        assertProblem(401, "unauthorized") { store.deleteCase(owner.id, issued.expiresAt) }
        assertProblem(401, "unauthorized") { store.revokeSession(owner.id, issued.expiresAt) }
    }

    @Test
    fun `two owners cannot read each others case and idempotency is scoped to each owner`() {
        val (_, first) = session()
        val (_, second) = session()
        val payload = payload()
        val created = store.createCase(first.id, payload, now)
        assertEquals(201, created.status)
        assertEquals(JsonNull, store.getCase(second.id, now)["case"])
        assertEquals(JsonNull, store.getCase(second.id, now)["review"])
        val secondCreated = store.createCase(second.id, payload, now)
        assertEquals(201, secondCreated.status)
        val firstId = created.body.getValue("id").jsonPrimitive.content
        val secondId = secondCreated.body.getValue("id").jsonPrimitive.content
        assertNotEquals(firstId, secondId)
        assertEquals(JsonPrimitive(firstId), store.getCase(first.id, now).getValue("case").jsonObject["id"])
        assertEquals(JsonPrimitive(secondId), store.getCase(second.id, now).getValue("case").jsonObject["id"])
        assertEquals(ApiResult(200, created.body), store.createCase(first.id, payload, now))
        assertEquals(ApiResult(200, secondCreated.body), store.createCase(second.id, payload, now))
        assertProblem(409, "idempotency_conflict") { store.createCase(first.id, changeSituation(payload), now) }
        assertProblem(409, "case_exists") { store.createCase(first.id, payload(), now) }
    }

    @Test
    fun `concurrent case retries persist only one owner-bound row`() {
        val (_, owner) = session()
        val payload = payload()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll((1..4).map { Callable { store.createCase(owner.id, payload, now) } })
                .map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.status == 201 })
            assertEquals(3, results.count { it.status == 200 })
            assertEquals(1, results.map { it.body }.distinct().size)
        } finally { workers.shutdownNow() }
    }

    @Test
    fun `client only receives a review after manual publication and no other owners review`() {
        val (_, first) = session()
        val (_, second) = session()
        val created = store.createCase(first.id, payload(), now)
        store.createCase(second.id, payload(), now)
        val id = created.body.getValue("id").jsonPrimitive.content
        assertEquals(JsonNull, store.getCase(first.id, now)["review"])
        val text = "Вымышленный разбор: уважайте согласованную паузу."
        val published = store.publishReview(id, text, now.plusSeconds(30))
        assertEquals(JsonPrimitive(true), published["published"])
        val result = store.getCase(first.id, now.plusSeconds(31))
        assertEquals(JsonPrimitive(text), result.getValue("review").jsonObject["text"])
        assertNotNull(result.getValue("review").jsonObject["published_at"])
        assertEquals(JsonNull, store.getCase(second.id, now.plusSeconds(31))["review"])
        assertProblem(404, "case_not_found") { store.publishReview("0".repeat(32), text, now) }
    }

    @Test
    fun `delete removes only owners case and published review and permits a new rehearsal`() {
        val (_, first) = session()
        val (_, second) = session()
        val firstCase = store.createCase(first.id, payload(), now)
        val secondCase = store.createCase(second.id, payload(), now)
        val firstId = firstCase.body.getValue("id").jsonPrimitive.content
        store.publishReview(firstId, "Вымышленный проверенный разбор.", now)
        store.deleteCase(first.id, now)
        val after = store.getCase(first.id, now)
        assertEquals(JsonNull, after["case"]); assertEquals(JsonNull, after["review"])
        assertEquals(secondCase.body, store.getCase(second.id, now)["case"])
        dataSource.connection.use { db ->
            db.prepareStatement("SELECT case_id FROM rr_client_reviews WHERE case_id = ?").use { query ->
                query.setString(1, firstId); query.executeQuery().use { assertFalse(it.next()) }
            }
        }
        store.deleteCase(first.id, now)
        val replacement = store.createCase(first.id, payload(), now)
        assertEquals(201, replacement.status)
        assertNotEquals(firstId, replacement.body.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `operator legacy cases and client rehearsal tables do not share ownership or payloads`() {
        val (_, owner) = session()
        val payload = payload()
        val operatorStore = JdbcCaseStore(dataSource)
        val operatorCase = operatorStore.createCase(payload)
        val id = operatorCase.body.getValue("id").jsonPrimitive.content
        try {
            val clientCase = store.createCase(owner.id, payload, now)
            assertNotEquals(id, clientCase.body.getValue("id").jsonPrimitive.content)
            assertProblem(404, "case_not_found") { store.publishReview(id, "Вымышленный текст.", now) }
            store.deleteCase(owner.id, now)
            assertEquals(operatorCase.body, operatorStore.getCase(id))
        } finally {
            dataSource.connection.use { db -> db.prepareStatement("DELETE FROM rr_cases WHERE id = ?").use { it.setString(1, id); it.executeUpdate() } }
        }
    }

    @Test
    fun `operator queue omits questionnaire text and detail exposes only the selected case`() {
        val (_, owner) = session()
        val case = store.createCase(owner.id, payload(), now)
        val id = case.body.getValue("id").jsonPrimitive.content
        val queue = store.listCases().getValue("cases").jsonArray
        assertTrue(queue.size <= 20)
        val summary = queue.single { it.jsonObject["id"] == JsonPrimitive(id) }.jsonObject
        assertEquals(setOf("id", "created_at", "status"), summary.keys)
        assertEquals(JsonPrimitive("awaiting_human_review"), summary["status"])
        assertEquals(store.getCase(owner.id, now), store.operatorCase(id))
        store.publishReview(id, "Вымышленный разбор после ручной проверки.", now)
        val reviewed = store.listCases().getValue("cases").jsonArray.single { it.jsonObject["id"] == JsonPrimitive(id) }.jsonObject
        assertEquals(JsonPrimitive("published"), reviewed["status"])
        assertProblem(404, "case_not_found") { store.operatorCase("0".repeat(32)) }
    }

    private fun assertProblem(status: Int, code: String, block: () -> Unit) {
        val error = assertFailsWith<ApiProblem>(block = block)
        assertEquals(status, error.status); assertEquals(code, error.code)
    }

    private fun changeSituation(value: JsonObject): JsonObject = JsonObject(value + ("questionnaire" to
        JsonObject(value.getValue("questionnaire").jsonObject + ("situation" to JsonPrimitive("Другой вымышленный бытовой спор.")))))

    private fun payload(): JsonObject = Contract.casePayload(buildJsonObject {
        put("schema_version", "m1-cis-v1"); put("synthetic", true); put("client_request_id", UUID.randomUUID().toString())
        put("questionnaire", buildJsonObject {
            put("age", "adult"); put("stage", "1to3y"); put("safety", "no"); put("boundary", "space")
            put("timing", "today"); put("partner", "space"); put("user", "talk"); put("recurrence", "first")
            put("helpful", "pause"); put("failureType", "none"); put("goal", "calm")
            put("situation", "Вымышленные персонажи поспорили о бытовых делах."); put("success", ""); put("failure", "")
        })
    })
}

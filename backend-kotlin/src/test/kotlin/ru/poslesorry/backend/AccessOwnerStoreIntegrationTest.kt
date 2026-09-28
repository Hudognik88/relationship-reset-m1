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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Durable access and owner workspace tests are restricted to a disposable *_test database. */
class AccessOwnerStoreIntegrationTest {
    private lateinit var source: MysqlDataSource
    private lateinit var clients: JdbcClientStore
    private lateinit var owners: JdbcOwnerStore
    private val clientTokens = mutableSetOf<String>()
    private val ownerTokens = mutableSetOf<String>()
    private val clientInvitations = mutableSetOf<String>()
    private val ownerInvitations = mutableSetOf<String>()
    private val now = Instant.parse("2026-09-26T13:00:00Z")

    @BeforeEach
    fun prepare() {
        val name = System.getenv("RR_TEST_DB_NAME")
        assumeTrue(!name.isNullOrBlank(), "RR_TEST_DB_NAME not supplied; workspace database tests skipped")
        require(name!!.matches(Regex("[A-Za-z0-9_]+_test"))) { "Integration database must end in _test" }
        source = MysqlDataSource().apply {
            setServerName(System.getenv("RR_TEST_DB_HOST") ?: "127.0.0.1")
            setPort((System.getenv("RR_TEST_DB_PORT") ?: "3306").toInt())
            setDatabaseName(name); setUser(requireNotNull(System.getenv("RR_TEST_DB_USER")))
            setPassword(requireNotNull(System.getenv("RR_TEST_DB_PASSWORD")))
            setUseSSL(false); setAllowPublicKeyRetrieval(true); setServerTimezone("UTC")
        }
        Migrations(source).run(); ClientMigrations(source).run()
        clients = JdbcClientStore(source); owners = JdbcOwnerStore(source)
    }

    @AfterEach
    fun cleanup() {
        if (!::source.isInitialized) return
        source.connection.use { db ->
            for ((table, tokens) in listOf("rr_client_sessions" to clientTokens, "rr_owner_sessions" to ownerTokens,
                "rr_client_invitations" to clientInvitations, "rr_owner_invitations" to ownerInvitations)) {
                db.prepareStatement("DELETE FROM $table WHERE token_hash = ?").use { q ->
                    tokens.forEach { q.setString(1, Contract.sha256(it)); q.executeUpdate() }
                }
            }
        }
    }

    private fun clientSession(at: Instant = now): Pair<IssuedClientToken, ClientSession> {
        val invite = clients.issueInvitation(at).also { clientInvitations += it.token }
        val issued = clients.exchangeInvitation(invite.token, at).also { clientTokens += it.token }
        return issued to assertNotNull(clients.session(issued.token, at))
    }
    private fun login(key: String, at: Instant): Pair<IssuedClientToken, ClientSession> {
        val issued = clients.login(key, at).also { clientTokens += it.token }
        return issued to assertNotNull(clients.session(issued.token, at))
    }
    private fun ownerSession(at: Instant = now): Pair<IssuedClientToken, ClientSession> {
        val invite = owners.issueInvitation(at).also { ownerInvitations += it.token }
        val issued = owners.exchangeInvitation(invite.token, at).also { ownerTokens += it.token }
        return issued to assertNotNull(owners.session(issued.token, at))
    }

    @Test
    fun `workspace migration checksum protects both new stores without changing legacy readiness`() {
        fun checksum(value: String) = source.connection.use { db ->
            db.prepareStatement("UPDATE rr_schema_migrations SET checksum = ? WHERE version = ?").use {
                it.setString(1, value); it.setString(2, ClientWorkspaceSchema.VERSION); it.executeUpdate()
            }
        }
        try {
            checksum("0".repeat(64))
            assertFalse(clients.ready()); assertFalse(owners.ready()); assertTrue(JdbcCaseStore(source).ready())
            assertFailsWith<IllegalStateException> { ClientMigrations(source).run() }
        } finally { checksum(ClientWorkspaceSchema.checksum) }
        ClientMigrations(source).run()
        assertTrue(clients.ready()); assertTrue(owners.ready())
    }

    @Test
    fun `durable key is hash-only and restores the same case after original logout and expiry`() {
        val (originalToken, original) = clientSession()
        val payload = payload()
        val created = clients.createCase(original.id, payload, now)
        val key = clients.rotateAccessKey(original.id, now)
        assertTrue(key.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(assertNotNull(clients.session(originalToken.token, now)).hasAccessKey)
        source.connection.use { db ->
            db.prepareStatement("SELECT token_hash FROM rr_client_access_keys WHERE owner_session_id = ?").use {
                it.setString(1, original.id); it.executeQuery().use { row ->
                    assertTrue(row.next()); assertEquals(Contract.sha256(key), row.getString(1)); assertNotEquals(key, row.getString(1))
                }
            }
        }
        clients.revokeSession(original.id, now.plusSeconds(1))
        val later = now.plusSeconds(172800)
        assertNull(clients.session(originalToken.token, later))
        val (resumedToken, resumed) = login(key, later)
        assertNotEquals(original.id, resumed.id); assertTrue(resumed.hasAccessKey)
        assertEquals(later.plusSeconds(86400), resumedToken.expiresAt)
        assertEquals(created.body, clients.getCase(resumed.id, later)["case"])
        assertEquals(ApiResult(200, created.body), clients.createCase(resumed.id, payload, later))
        ClientMigrations(source).run()
        assertEquals(created.body, clients.getCase(resumed.id, later)["case"])
    }

    @Test
    fun `rotating from a resumed session invalidates old key and other linked sessions but keeps current and other account`() {
        val (originalToken, original) = clientSession()
        val created = clients.createCase(original.id, payload(), now)
        val oldKey = clients.rotateAccessKey(original.id, now)
        val (firstToken, first) = login(oldKey, now.plusSeconds(1))
        val (secondToken, _) = login(oldKey, now.plusSeconds(2))
        val (unrelatedToken, unrelated) = clientSession()
        val unrelatedCase = clients.createCase(unrelated.id, payload(), now)
        val newKey = clients.rotateAccessKey(first.id, now.plusSeconds(3))
        assertNotEquals(oldKey, newKey)
        assertNull(clients.session(originalToken.token, now.plusSeconds(4)))
        assertNull(clients.session(secondToken.token, now.plusSeconds(4)))
        assertNotNull(clients.session(firstToken.token, now.plusSeconds(4)))
        assertNotNull(clients.session(unrelatedToken.token, now.plusSeconds(4)))
        assertProblem(401, "access_key_invalid") { clients.login(oldKey, now.plusSeconds(4)) }
        val (_, resumed) = login(newKey, now.plusSeconds(4))
        assertEquals(created.body, clients.getCase(resumed.id, now.plusSeconds(4))["case"])
        assertEquals(unrelatedCase.body, clients.getCase(unrelated.id, now.plusSeconds(4))["case"])
        clients.deleteCase(resumed.id, now.plusSeconds(5))
        assertEquals(JsonNull, clients.getCase(first.id, now.plusSeconds(5))["case"])
        assertEquals(unrelatedCase.body, clients.getCase(unrelated.id, now.plusSeconds(5))["case"])
    }

    @Test
    fun `expired and revoked session ids cannot rotate their durable key`() {
        val (token, session) = clientSession()
        val key = clients.rotateAccessKey(session.id, now)
        assertProblem(401, "unauthorized") { clients.rotateAccessKey(session.id, token.expiresAt) }
        clients.revokeSession(session.id, now.plusSeconds(1))
        assertProblem(401, "unauthorized") { clients.rotateAccessKey(session.id, now.plusSeconds(2)) }
        val (_, resumed) = login(key, now.plusSeconds(2))
        assertTrue(resumed.hasAccessKey)
        assertProblem(401, "access_key_invalid") { clients.login("x".repeat(43), now) }
    }

    @Test
    fun `rotation and old-key login race leaves no valid session using the retired key`() {
        val (_, original) = clientSession()
        val oldKey = clients.rotateAccessKey(original.id, now)
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val rotation = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); clients.rotateAccessKey(original.id, now.plusSeconds(1)) })
            val attempt = pool.submit(Callable { barrier.await(10, TimeUnit.SECONDS); runCatching { clients.login(oldKey, now.plusSeconds(1)) } })
            val newKey = rotation.get(20, TimeUnit.SECONDS)
            val result = attempt.get(20, TimeUnit.SECONDS)
            val issued = result.getOrNull()
            if (issued != null) {
                clientTokens += issued.token
                assertNull(clients.session(issued.token, now.plusSeconds(2)))
            } else {
                val error = assertIs<ApiProblem>(result.exceptionOrNull())
                assertEquals(401, error.status); assertEquals("access_key_invalid", error.code)
            }
            assertProblem(401, "access_key_invalid") { clients.login(oldKey, now.plusSeconds(2)) }
            assertTrue(login(newKey, now.plusSeconds(2)).second.hasAccessKey)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `owner invitation is short-lived single-use and separate from client credentials`() {
        val invite = owners.issueInvitation(now).also { ownerInvitations += it.token }
        assertEquals(now.plusSeconds(600), invite.expiresAt)
        val issued = owners.exchangeInvitation(invite.token, now).also { ownerTokens += it.token }
        val owner = assertNotNull(owners.session(issued.token, now))
        assertEquals(now.plusSeconds(3600), issued.expiresAt)
        assertProblem(401, "owner_invitation_invalid") { owners.exchangeInvitation(invite.token, now) }
        val expired = owners.issueInvitation(now).also { ownerInvitations += it.token }
        assertProblem(401, "owner_invitation_invalid") { owners.exchangeInvitation(expired.token, expired.expiresAt) }
        val clientInvite = clients.issueInvitation(now).also { clientInvitations += it.token }
        assertProblem(401, "owner_invitation_invalid") { owners.exchangeInvitation(clientInvite.token, now) }
        assertProblem(401, "invitation_invalid") { clients.exchangeInvitation(expired.token, now) }
        val (clientToken, client) = clientSession()
        assertNull(owners.session(clientToken.token, now)); assertNull(clients.session(issued.token, now))
        assertProblem(401, "unauthorized") { owners.listCases(client.id, now) }
        assertProblem(401, "unauthorized") { clients.getCase(owner.id, now) }
        source.connection.use { db ->
            db.prepareStatement("SELECT token_hash FROM rr_owner_sessions WHERE id = ?").use {
                it.setString(1, owner.id); it.executeQuery().use { row -> assertTrue(row.next()); assertEquals(Contract.sha256(issued.token), row.getString(1)) }
            }
        }
    }

    @Test
    fun `concurrent owner invitation exchange yields exactly one authenticated owner`() {
        val invite = owners.issueInvitation(now).also { ownerInvitations += it.token }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..4).map { Callable { runCatching { owners.exchangeInvitation(invite.token, now) } } }).map { it.get(20, TimeUnit.SECONDS) }
            val successful = results.mapNotNull { it.getOrNull() }
            successful.forEach { ownerTokens += it.token }
            assertEquals(1, successful.size)
            assertNotNull(owners.session(successful.single().token, now))
            results.filter { it.isFailure }.forEach {
                val error = assertIs<ApiProblem>(it.exceptionOrNull())
                assertEquals(401, error.status); assertEquals("owner_invitation_invalid", error.code)
            }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `owner session expiry and logout prevent all workspace operations while other owner remains active`() {
        val (issued, owner) = ownerSession()
        val (otherToken, other) = ownerSession()
        val (_, client) = clientSession()
        val case = clients.createCase(client.id, payload(), now)
        val caseId = case.body.getValue("id").jsonPrimitive.content
        assertNull(owners.session(issued.token, issued.expiresAt))
        assertProblem(401, "unauthorized") { owners.listCases(owner.id, issued.expiresAt) }
        assertProblem(401, "unauthorized") { owners.getCase(owner.id, caseId, issued.expiresAt) }
        assertProblem(401, "unauthorized") { owners.issueClientInvitation(owner.id, issued.expiresAt) }
        assertProblem(401, "unauthorized") { owners.publishReview(owner.id, caseId, "Вымышленный разбор.", "unpublished", issued.expiresAt) }
        owners.revokeSession(owner.id, now.plusSeconds(1))
        assertNull(owners.session(issued.token, now.plusSeconds(2)))
        assertProblem(401, "unauthorized") { owners.listCases(owner.id, now.plusSeconds(2)) }
        assertNotNull(owners.session(otherToken.token, now.plusSeconds(2)))
        assertEquals(case.body, owners.getCase(other.id, caseId, now.plusSeconds(2))["case"])
        val invite = owners.issueClientInvitation(other.id, now.plusSeconds(2)).also { clientInvitations += it.token }
        val newClient = clients.exchangeInvitation(invite.token, now.plusSeconds(3)).also { clientTokens += it.token }
        assertNotNull(clients.session(newClient.token, now.plusSeconds(3)))
    }

    @Test
    fun `owner review uses optimistic version and stale publication cannot overwrite latest text`() {
        val (_, owner) = ownerSession()
        val (_, client) = clientSession()
        val case = clients.createCase(client.id, payload(), now)
        val id = case.body.getValue("id").jsonPrimitive.content
        val before = owners.getCase(owner.id, id, now)
        assertEquals(JsonPrimitive("unpublished"), before["review_version"])
        val text = "Первый вымышленный разбор после ручной проверки."
        val published = owners.publishReview(owner.id, id, text, "unpublished", now.plusSeconds(1))
        val version = published.getValue("review_version").jsonPrimitive.content
        assertTrue(version.matches(Regex("[a-f0-9]{64}")))
        val detail = owners.getCase(owner.id, id, now.plusSeconds(2))
        assertEquals(JsonPrimitive(version), detail["review_version"])
        assertEquals(Contract.sha256(Contract.canonicalJson(detail.getValue("review"))), version)
        assertEquals(JsonPrimitive(text), clients.getCase(client.id, now.plusSeconds(2)).getValue("review").jsonObject["text"])
        assertProblem(409, "review_conflict") { owners.publishReview(owner.id, id, "Устаревшая попытка.", "unpublished", now.plusSeconds(3)) }
        assertEquals(detail, owners.getCase(owner.id, id, now.plusSeconds(3)))
        val updated = owners.publishReview(owner.id, id, "Уточнённый вымышленный разбор.", version, now.plusSeconds(4))
        assertNotEquals(JsonPrimitive(version), updated["review_version"])
    }

    @Test
    fun `two owners publishing the same expected version have exactly one winner`() {
        val (_, first) = ownerSession(); val (_, second) = ownerSession()
        val (_, client) = clientSession()
        val id = clients.createCase(client.id, payload(), now).body.getValue("id").jsonPrimitive.content
        val gate = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(first, second).mapIndexed { index, owner -> Callable {
                gate.await(10, TimeUnit.SECONDS)
                runCatching { owners.publishReview(owner.id, id, "Вымышленный разбор ${index + 1}.", "unpublished", now.plusSeconds(1)) }
            } }).map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val loser = assertIs<ApiProblem>(results.single { it.isFailure }.exceptionOrNull())
            assertEquals(409, loser.status); assertEquals("review_conflict", loser.code)
            val winner = results.single { it.isSuccess }.getOrThrow()
            val detail = owners.getCase(first.id, id, now.plusSeconds(2))
            assertEquals(winner["review_version"], detail["review_version"])
            assertEquals(detail["review"], clients.getCase(client.id, now.plusSeconds(2))["review"])
        } finally { pool.shutdownNow() }
    }

    private fun assertProblem(status: Int, code: String, action: () -> Unit) {
        val error = assertFailsWith<ApiProblem>(block = action)
        assertEquals(status, error.status); assertEquals(code, error.code)
    }
    private fun payload(): JsonObject = Contract.casePayload(buildJsonObject {
        put("schema_version", "m1-cis-v1"); put("synthetic", true); put("client_request_id", UUID.randomUUID().toString())
        put("questionnaire", buildJsonObject {
            put("age", "adult"); put("stage", "1to3y"); put("safety", "no"); put("boundary", "space")
            put("timing", "today"); put("partner", "space"); put("user", "talk"); put("recurrence", "first")
            put("helpful", "pause"); put("failureType", "none"); put("goal", "calm")
            put("situation", "Вымышленные персонажи поспорили о домашних делах."); put("success", ""); put("failure", "")
        })
    })
}

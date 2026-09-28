package ru.poslesorry.backend

import com.mysql.cj.jdbc.MysqlDataSource
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

/** All payment data is synthetic and restricted to the CI disposable *_test database. */
class PaymentStoreIntegrationTest {
    private lateinit var source: MysqlDataSource
    private lateinit var clients: JdbcClientStore
    private lateinit var payments: JdbcPaymentStore
    private val clientTokens = mutableListOf<String>()
    private val invitations = mutableListOf<String>()
    private val owners = mutableListOf<String>()
    private val now = Instant.parse("2026-09-28T17:00:00Z")

    @BeforeEach
    fun prepare() {
        val name = System.getenv("RR_TEST_DB_NAME")
        assumeTrue(!name.isNullOrBlank(), "RR_TEST_DB_NAME not supplied; payment database tests skipped")
        require(name!!.matches(Regex("[A-Za-z0-9_]+_test"))) { "Integration database must end in _test" }
        source = MysqlDataSource().apply {
            setServerName(System.getenv("RR_TEST_DB_HOST") ?: "127.0.0.1")
            setPort((System.getenv("RR_TEST_DB_PORT") ?: "3306").toInt())
            setDatabaseName(name); setUser(requireNotNull(System.getenv("RR_TEST_DB_USER")))
            setPassword(requireNotNull(System.getenv("RR_TEST_DB_PASSWORD")))
            setUseSSL(false); setAllowPublicKeyRetrieval(true); setServerTimezone("UTC")
        }
        Migrations(source).run(); ClientMigrations(source).run(); PaymentMigrations(source).run()
        clients = JdbcClientStore(source); payments = JdbcPaymentStore(source)
    }

    @AfterEach
    fun cleanup() {
        if (!::source.isInitialized) return
        source.connection.use { db ->
            db.prepareStatement("DELETE FROM rr_client_orders WHERE owner_session_id = ?").use { q ->
                owners.forEach { q.setString(1, it); q.executeUpdate() }
            }
            for (token in clientTokens.asReversed()) db.prepareStatement("DELETE FROM rr_client_sessions WHERE token_hash = ?").use { q ->
                q.setString(1, Contract.sha256(token)); q.executeUpdate()
            }
            for (token in invitations) db.prepareStatement("DELETE FROM rr_client_invitations WHERE token_hash = ?").use { q ->
                q.setString(1, Contract.sha256(token)); q.executeUpdate()
            }
        }
    }

    private data class Buyer(val session: ClientSession, val key: String, val caseId: String)

    private fun buyer(saveKey: Boolean = true): Buyer {
        val invitation = clients.issueInvitation(now).also { invitations += it.token }
        val issued = clients.exchangeInvitation(invitation.token, now).also { clientTokens += it.token }
        val session = assertNotNull(clients.session(issued.token, now)).also { owners += it.id }
        val caseId = clients.createCase(session.id, payload(), now).body.getValue("id").jsonPrimitive.content
        return Buyer(session, if (saveKey) clients.rotateAccessKey(session.id, now) else "", caseId)
    }

    private fun resumed(key: String, at: Instant = now): ClientSession {
        val issued = clients.login(key, at).also { clientTokens += it.token }
        return assertNotNull(clients.session(issued.token, at))
    }

    private fun create(buyer: Buyer, request: String = UUID.randomUUID().toString()) = payments.createOrder(buyer.session.id, request, buyer.caseId, now)
    private fun orderId(result: ApiResult) = result.body.getValue("order").jsonObject.getValue("id").jsonPrimitive.content
    private fun notice(id: String, provider: String = "9" + UUID.randomUUID().toString().filter { it.isDigit() }.take(17).padEnd(17, '0')) =
        ProdamusNotification(id, provider, "relationshipreset.payform.ru", 99000, "RUB", "success", true)
    private fun status(value: JsonObject) = value.getValue("order").jsonObject.getValue("status").jsonPrimitive.content
    private fun count(table: String, owner: String): Int {
        require(table in setOf("rr_client_orders", "rr_client_entitlements", "rr_payment_receipts"))
        return source.connection.use { db ->
            val sql = if (table == "rr_payment_receipts") "SELECT COUNT(*) FROM rr_payment_receipts r JOIN rr_client_orders o ON o.id = r.order_id WHERE o.owner_session_id = ?"
                else "SELECT COUNT(*) FROM $table WHERE owner_session_id = ?"
            db.prepareStatement(sql).use { q -> q.setString(1, owner); q.executeQuery().use { it.next(); it.getInt(1) } }
        }
    }

    @Test
    fun `separate payment migration is repeatable and checksum tampering cannot affect rehearsal readiness`() {
        assertTrue(payments.ready())
        PaymentMigrations(source).run()
        fun checksum(value: String) = source.connection.use { db -> db.prepareStatement("UPDATE rr_schema_migrations SET checksum = ? WHERE version = ?").use { q ->
            q.setString(1, value); q.setString(2, PaymentSchema.VERSION); q.executeUpdate()
        } }
        try {
            checksum("0".repeat(64)); assertFalse(payments.ready()); assertTrue(clients.ready())
            assertFailsWith<IllegalStateException> { PaymentMigrations(source).run() }
        } finally { checksum(PaymentSchema.checksum) }
        assertTrue(payments.ready())
    }

    @Test
    fun `order requires saved key own current case and active session`() {
        val buyer = buyer(false)
        problem(409, "access_key_required") { create(buyer) }
        clients.rotateAccessKey(buyer.session.id, now)
        val stranger = buyer()
        problem(409, "case_unavailable") { payments.createOrder(buyer.session.id, UUID.randomUUID().toString(), stranger.caseId, now) }
        assertEquals(JsonNull, payments.order(stranger.session.id, now)["order"])
        problem(401, "unauthorized") { payments.createOrder(buyer.session.id, UUID.randomUUID().toString(), buyer.caseId, buyer.session.expiresAt) }
        clients.revokeSession(buyer.session.id, now)
        problem(401, "unauthorized") { create(buyer) }
        assertEquals(0, count("rr_client_orders", buyer.session.id))
    }

    @Test
    fun `same request and new request for the same case both return one immutable order`() {
        val buyer = buyer(); val request = UUID.randomUUID().toString(); val alias = UUID.randomUUID().toString()
        val first = create(buyer, request); val second = create(buyer, request); val third = create(buyer, alias)
        assertEquals(201, first.status); assertEquals(200, second.status); assertEquals(200, third.status)
        assertEquals(first.body, second.body); assertEquals(first.body, third.body)
        assertEquals(JsonPrimitive(99000), first.body.getValue("order").jsonObject["amount_minor"])
        assertEquals(1, count("rr_client_orders", buyer.session.id))
        clients.deleteCase(buyer.session.id, now)
        assertEquals(JsonPrimitive(false), payments.order(buyer.session.id, now).getValue("order").jsonObject["checkout_available"])
        val newCase = clients.createCase(buyer.session.id, payload(), now).body.getValue("id").jsonPrimitive.content
        problem(409, "idempotency_conflict") { payments.createOrder(buyer.session.id, request, newCase, now) }
        problem(409, "idempotency_conflict") { payments.createOrder(buyer.session.id, alias, newCase, now) }
        val currentOrder = payments.createOrder(buyer.session.id, UUID.randomUUID().toString(), newCase, now)
        val visible = payments.order(buyer.session.id, now)
        assertEquals(currentOrder.body, visible)
        assertEquals(JsonPrimitive(newCase), visible.getValue("order").jsonObject["case_id"])
        assertEquals(JsonPrimitive(true), visible.getValue("order").jsonObject["checkout_available"])
    }

    @Test
    fun `concurrent different request keys for one case produce one order`() {
        val buyer = buyer(); val gate = CyclicBarrier(4); val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..4).map { Callable { gate.await(10, TimeUnit.SECONDS); create(buyer) } }).map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.status == 201 }); assertEquals(1, results.map(::orderId).toSet().size)
            assertEquals(1, count("rr_client_orders", buyer.session.id))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `success grants exactly seven days and retries cannot extend or duplicate it`() {
        val buyer = buyer(); val order = create(buyer); val n = notice(orderId(order))
        payments.accept(n, now)
        val accepted = payments.order(buyer.session.id, now)
        assertEquals("paid", status(accepted))
        val entitlement = accepted.getValue("entitlement").jsonObject
        assertEquals(JsonPrimitive(orderId(order)), entitlement["order_id"])
        assertEquals(JsonPrimitive(buyer.caseId), entitlement["case_id"])
        assertEquals(JsonPrimitive(now.plusSeconds(604800).toString()), entitlement["expires_at"])
        assertEquals(JsonPrimitive(true), entitlement["active"])
        payments.accept(n, now.plusSeconds(60)); payments.accept(n.copy(status = "order_canceled"), now.plusSeconds(61))
        assertEquals(accepted, payments.order(buyer.session.id, now.plusSeconds(62)))
        assertEquals(1, count("rr_client_entitlements", buyer.session.id)); assertEquals(1, count("rr_payment_receipts", buyer.session.id))
        val expired = resumed(buyer.key, now.plusSeconds(604800))
        assertEquals(JsonPrimitive(false), payments.order(expired.id, now.plusSeconds(604800)).getValue("entitlement").jsonObject["active"])
        problem(409, "payment_conflict") { payments.accept(notice(orderId(order)), now.plusSeconds(100)) }
    }

    @Test
    fun `non-success callback records no grant but later verified success can complete order`() {
        val buyer = buyer(); val n = notice(orderId(create(buyer)))
        payments.accept(n.copy(status = "order_denied"), now)
        payments.accept(n.copy(status = "order_canceled"), now.plusSeconds(1))
        assertEquals("pending", status(payments.order(buyer.session.id, now)))
        assertEquals(0, count("rr_client_entitlements", buyer.session.id))
        payments.accept(n, now.plusSeconds(2))
        assertEquals("paid", status(payments.order(buyer.session.id, now.plusSeconds(2))))
        assertEquals(1, count("rr_client_entitlements", buyer.session.id))
    }

    @Test
    fun `amount currency mode merchant and unknown status mismatches never write receipt or grant`() {
        val buyer = buyer(); val n = notice(orderId(create(buyer)))
        for (bad in listOf(n.copy(amountMinor = 98999), n.copy(amountMinor = 99001), n.copy(currency = "USD"), n.copy(demoMode = false), n.copy(merchantDomain = "other.payform.ru")))
            problem(409, "payment_mismatch") { payments.accept(bad, now) }
        problem(422, "invalid_notification") { payments.accept(n.copy(status = "pending"), now) }
        problem(404, "order_not_found") { payments.accept(n.copy(merchantOrderId = "rrstg_" + "0".repeat(32)), now) }
        assertEquals("pending", status(payments.order(buyer.session.id, now)))
        assertEquals(0, count("rr_client_entitlements", buyer.session.id)); assertEquals(0, count("rr_payment_receipts", buyer.session.id))
    }

    @Test
    fun `concurrent duplicate success callbacks grant once`() {
        val buyer = buyer(); val n = notice(orderId(create(buyer))); val gate = CyclicBarrier(4); val pool = Executors.newFixedThreadPool(4)
        try {
            pool.invokeAll((1..4).map { Callable { gate.await(10, TimeUnit.SECONDS); payments.accept(n, now) } }).forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals("paid", status(payments.order(buyer.session.id, now)))
            assertEquals(1, count("rr_client_entitlements", buyer.session.id)); assertEquals(1, count("rr_payment_receipts", buyer.session.id))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `one provider transaction cannot pay two clients even concurrently`() {
        val first = buyer(); val second = buyer(); val firstOrder = create(first); val secondOrder = create(second)
        val n = notice(orderId(firstOrder)); val gate = CyclicBarrier(2); val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(listOf(n, n.copy(merchantOrderId = orderId(secondOrder))).map { value -> Callable {
                gate.await(10, TimeUnit.SECONDS); runCatching { payments.accept(value, now) }
            } }).map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            val error = assertIs<ApiProblem>(results.single { it.isFailure }.exceptionOrNull())
            assertEquals(409, error.status); assertEquals("payment_conflict", error.code)
            assertEquals(1, count("rr_client_entitlements", first.session.id) + count("rr_client_entitlements", second.session.id))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `durable reentry key rotation and webhook all retain same canonical order`() {
        val buyer = buyer(); val original = create(buyer); val n = notice(orderId(original))
        clients.revokeSession(buyer.session.id, now)
        val current = resumed(buyer.key, now.plusSeconds(172800))
        val gate = CyclicBarrier(2); val pool = Executors.newFixedThreadPool(2)
        try {
            val rotation = pool.submit(Callable { gate.await(10, TimeUnit.SECONDS); clients.rotateAccessKey(current.id, now.plusSeconds(172800)) })
            val callback = pool.submit(Callable { gate.await(10, TimeUnit.SECONDS); payments.accept(n, now.plusSeconds(172800)) })
            val key = rotation.get(20, TimeUnit.SECONDS); callback.get(20, TimeUnit.SECONDS)
            val fresh = resumed(key, now.plusSeconds(172801))
            val result = payments.order(fresh.id, now.plusSeconds(172801))
            assertEquals(JsonPrimitive(orderId(original)), result.getValue("order").jsonObject["id"]); assertEquals("paid", status(result))
            assertEquals(JsonPrimitive(true), result.getValue("entitlement").jsonObject["active"])
            val stranger = buyer(); assertEquals(JsonNull, payments.order(stranger.session.id, now)["order"])
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `paid after deletion is review required and cannot grant a replacement case`() {
        val buyer = buyer(); val original = create(buyer); val n = notice(orderId(original))
        clients.deleteCase(buyer.session.id, now)
        payments.accept(n, now.plusSeconds(1))
        val result = payments.order(buyer.session.id, now.plusSeconds(1))
        assertEquals("review_required", status(result)); assertEquals(JsonNull, result["entitlement"])
        assertEquals(JsonPrimitive(false), result.getValue("order").jsonObject["checkout_available"])
        assertEquals(JsonNull, clients.getCase(buyer.session.id, now)["case"])
        val replacement = clients.createCase(buyer.session.id, payload(), now.plusSeconds(2))
        assertNotEquals(JsonPrimitive(buyer.caseId), replacement.body["id"])
        payments.accept(n, now.plusSeconds(3)); assertEquals(result, payments.order(buyer.session.id, now.plusSeconds(3)))
        assertEquals(0, count("rr_client_entitlements", buyer.session.id))
    }

    @Test
    fun `concurrent deletion and payment never recreate case or transfer active grant`() {
        val buyer = buyer(); val n = notice(orderId(create(buyer))); val gate = CyclicBarrier(2); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(Callable { gate.await(10, TimeUnit.SECONDS); clients.deleteCase(buyer.session.id, now) },
                Callable { gate.await(10, TimeUnit.SECONDS); payments.accept(n, now) })
            pool.invokeAll(tasks).forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(JsonNull, clients.getCase(buyer.session.id, now)["case"])
            clients.createCase(buyer.session.id, payload(), now.plusSeconds(1))
            val result = payments.order(buyer.session.id, now.plusSeconds(1))
            assertTrue(status(result) in setOf("paid", "review_required"))
            if (result["entitlement"] != JsonNull) assertEquals(JsonPrimitive(false), result.getValue("entitlement").jsonObject["active"])
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `failed grant insert rolls back receipt paid status and entitlement then retry succeeds`() {
        val buyer = buyer(); val n = notice(orderId(create(buyer)))
        val broken = JdbcPaymentStore(failingEntitlementSource())
        assertFailsWith<SQLException> { broken.accept(n, now) }
        assertEquals("pending", status(payments.order(buyer.session.id, now)))
        assertEquals(0, count("rr_payment_receipts", buyer.session.id)); assertEquals(0, count("rr_client_entitlements", buyer.session.id))
        payments.accept(n, now.plusSeconds(1)); assertEquals("paid", status(payments.order(buyer.session.id, now.plusSeconds(1))))
    }

    private fun failingEntitlementSource(): DataSource = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
        val result = try { method.invoke(source, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        if (method.name != "getConnection") result else Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, operation, arguments ->
            if (operation.name == "prepareStatement" && (arguments?.firstOrNull() as? String)?.startsWith("INSERT INTO rr_client_entitlements") == true) throw SQLException("injected synthetic failure")
            try { operation.invoke(result, *(arguments ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
        }
    } as DataSource

    private fun problem(status: Int, code: String, action: () -> Unit) {
        val error = assertFailsWith<ApiProblem>(block = action); assertEquals(status, error.status); assertEquals(code, error.code)
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

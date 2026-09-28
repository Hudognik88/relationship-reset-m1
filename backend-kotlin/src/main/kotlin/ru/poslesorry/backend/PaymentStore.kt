package ru.poslesorry.backend

import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

interface PaymentStore {
    fun ready(): Boolean
    fun createOrder(sessionId: String, requestId: String, caseId: String, now: Instant): ApiResult
    fun order(sessionId: String, now: Instant): JsonObject
    fun accept(notification: ProdamusNotification, now: Instant)
}

/** A demo-only payment ledger. Account identity never comes from a callback or return URL. */
class JdbcPaymentStore(private val source: DataSource) : PaymentStore {
    private val clients = JdbcClientStore(source)
    override fun ready(): Boolean = transaction { paymentSchemaReady(it) }

    override fun createOrder(sessionId: String, requestId: String, caseId: String, now: Instant): ApiResult = transaction { db ->
        if (!REQUEST.matches(requestId) || !CASE.matches(caseId)) throw ApiProblem(422, "invalid_order_request")
        val owner = clients.active(db, sessionId, now)
        val hasKey = db.prepareStatement("SELECT owner_session_id FROM rr_client_access_keys WHERE owner_session_id = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.executeQuery().use { it.next() }
        }
        if (!hasKey) throw ApiProblem(409, "access_key_required")
        val reused = db.prepareStatement("SELECT o.* FROM rr_client_order_requests r JOIN rr_client_orders o ON o.id = r.order_id WHERE r.owner_session_id = ? AND r.client_request_id = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.setString(2, requestId); q.executeQuery().use { if (it.next()) readOrder(it) else null }
        }
        if (reused != null && reused.caseId != caseId) throw ApiProblem(409, "idempotency_conflict")
        val existing = reused ?: db.prepareStatement("SELECT * FROM rr_client_orders WHERE owner_session_id = ? AND case_id = ? AND product = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.setString(2, caseId); q.setString(3, PRODUCT); q.executeQuery().use { if (it.next()) readOrder(it) else null }
        }
        if (!caseExists(db, owner, caseId)) throw ApiProblem(409, "case_unavailable")
        if (existing != null) {
            if (reused == null) reserveRequest(db, owner, requestId, caseId, existing.id, now)
            return@transaction ApiResult(200, envelope(db, existing, now))
        }
        val id = "rrstg_" + ByteArray(16).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        db.prepareStatement("INSERT INTO rr_client_orders (id, owner_session_id, client_request_id, case_id, product, amount_minor, currency, mode, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)").use { q ->
            q.setString(1, id); q.setString(2, owner); q.setString(3, requestId); q.setString(4, caseId)
            q.setString(5, PRODUCT); q.setLong(6, AMOUNT); q.setString(7, CURRENCY); q.setString(8, MODE)
            q.setTimestamp(9, Timestamp.from(now)); q.executeUpdate()
        }
        reserveRequest(db, owner, requestId, caseId, id, now)
        ApiResult(201, envelope(db, checkNotNull(findOrder(db, id)), now))
    }

    override fun order(sessionId: String, now: Instant): JsonObject = transaction { db ->
        val owner = clients.active(db, sessionId, now)
        val latest = db.prepareStatement("SELECT o.* FROM rr_client_orders o LEFT JOIN rr_client_cases c ON c.id = o.case_id AND c.session_id = o.owner_session_id WHERE o.owner_session_id = ? ORDER BY (c.id IS NOT NULL) DESC, o.created_at DESC, o.id DESC LIMIT 1 FOR UPDATE").use { q ->
            q.setString(1, owner); q.executeQuery().use { if (it.next()) readOrder(it) else null }
        }
        if (latest == null) buildJsonObject { put("order", JsonNull); put("entitlement", JsonNull) }
        else envelope(db, latest, now)
    }

    override fun accept(notification: ProdamusNotification, now: Instant) = transaction { db ->
        val providerOrderId = validateNotification(notification)
        // Lookup is only a hint. Lock the immutable canonical account before locking its order,
        // exactly as create/delete/key rotation do. A locking reread avoids stale snapshots.
        val owner = db.prepareStatement("SELECT owner_session_id FROM rr_client_orders WHERE id = ?").use { q ->
            q.setString(1, notification.merchantOrderId); q.executeQuery().use { if (it.next()) it.getString(1) else null }
        } ?: throw ApiProblem(404, "order_not_found")
        val ownerExists = db.prepareStatement("SELECT id FROM rr_client_sessions WHERE id = ? FOR UPDATE").use { q ->
            q.setString(1, owner); q.executeQuery().use { it.next() }
        }
        if (!ownerExists) throw ApiProblem(409, "order_owner_unavailable")
        val order = findOrder(db, notification.merchantOrderId) ?: throw ApiProblem(404, "order_not_found")
        if (order.owner != owner || order.amount != notification.amountMinor || order.currency != notification.currency || order.mode != MODE || order.product != PRODUCT)
            throw ApiProblem(409, "payment_mismatch")
        if (order.paidProvider != null && order.paidProvider != providerOrderId) throw ApiProblem(409, "payment_conflict")
        // No read-before-insert gap lock. This locks a globally unique provider transaction;
        // concurrent callbacks claiming it for another account can never both commit.
        db.prepareStatement("INSERT INTO rr_payment_receipts (provider_order_id, order_id, merchant_domain, amount_minor, currency, demo_mode, status, first_received_at, last_received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE provider_order_id = VALUES(provider_order_id)").use { q ->
            q.setString(1, providerOrderId); q.setString(2, order.id); q.setString(3, notification.merchantDomain)
            q.setLong(4, notification.amountMinor); q.setString(5, notification.currency); q.setBoolean(6, notification.demoMode)
            q.setString(7, notification.status); q.setTimestamp(8, Timestamp.from(now)); q.setTimestamp(9, Timestamp.from(now)); q.executeUpdate()
        }
        val previousStatus = db.prepareStatement("SELECT order_id, merchant_domain, amount_minor, currency, demo_mode, status FROM rr_payment_receipts WHERE provider_order_id = ? FOR UPDATE").use { q ->
            q.setString(1, providerOrderId); q.executeQuery().use { r ->
                check(r.next())
                if (r.getString("order_id") != order.id || r.getString("merchant_domain") != notification.merchantDomain || r.getLong("amount_minor") != notification.amountMinor || r.getString("currency") != notification.currency || r.getBoolean("demo_mode") != notification.demoMode)
                    throw ApiProblem(409, "payment_conflict")
                r.getString("status")
            }
        }
        if (previousStatus != "success") {
            db.prepareStatement("UPDATE rr_payment_receipts SET status = ?, last_received_at = ? WHERE provider_order_id = ?").use { q ->
                q.setString(1, notification.status); q.setTimestamp(2, Timestamp.from(now)); q.setString(3, providerOrderId); q.executeUpdate()
            }
        }
        // Already accepted success and later failure deliveries are stable acknowledgements.
        if (order.paidProvider != null || notification.status != "success") return@transaction Unit
        val deliverable = caseExists(db, owner, order.caseId)
        db.prepareStatement("UPDATE rr_client_orders SET status = ?, paid_provider_order_id = ?, paid_at = ? WHERE id = ? AND status = 'pending' AND paid_provider_order_id IS NULL").use { q ->
            q.setString(1, if (deliverable) "paid" else "review_required"); q.setString(2, providerOrderId)
            q.setTimestamp(3, Timestamp.from(now)); q.setString(4, order.id); check(q.executeUpdate() == 1)
        }
        if (deliverable) db.prepareStatement("INSERT INTO rr_client_entitlements (order_id, owner_session_id, case_id, starts_at, expires_at) VALUES (?, ?, ?, ?, ?)").use { q ->
            q.setString(1, order.id); q.setString(2, owner); q.setString(3, order.caseId)
            q.setTimestamp(4, Timestamp.from(now)); q.setTimestamp(5, Timestamp.from(now.plusSeconds(7 * 86400L))); q.executeUpdate()
        }
        Unit
    }

    private fun validateNotification(value: ProdamusNotification): String {
        val providerOrderId = try { ProdamusContract.canonicalProviderOrderId(value.providerOrderId) }
            catch (_: IllegalArgumentException) { throw ApiProblem(422, "invalid_notification") }
        if (!ORDER.matches(value.merchantOrderId) || value.status !in STATUSES)
            throw ApiProblem(422, "invalid_notification")
        if (value.merchantDomain != MERCHANT || !value.demoMode || value.amountMinor != AMOUNT || value.currency != CURRENCY)
            throw ApiProblem(409, "payment_mismatch")
        return providerOrderId
    }

    private fun reserveRequest(db: Connection, owner: String, request: String, caseId: String, orderId: String, now: Instant) {
        db.prepareStatement("INSERT INTO rr_client_order_requests (owner_session_id, client_request_id, case_id, order_id, created_at) VALUES (?, ?, ?, ?, ?)").use { q ->
            q.setString(1, owner); q.setString(2, request); q.setString(3, caseId); q.setString(4, orderId)
            q.setTimestamp(5, Timestamp.from(now)); q.executeUpdate()
        }
    }

    private data class Order(val id: String, val owner: String, val caseId: String, val product: String,
        val amount: Long, val currency: String, val mode: String, val status: String, val paidProvider: String?, val created: Instant, val paid: Instant?)

    private fun readOrder(r: ResultSet) = Order(r.getString("id"), r.getString("owner_session_id"), r.getString("case_id"), r.getString("product"), r.getLong("amount_minor"), r.getString("currency"), r.getString("mode"), r.getString("status"), r.getString("paid_provider_order_id"), r.getTimestamp("created_at").toInstant(), r.getTimestamp("paid_at")?.toInstant())

    private fun findOrder(db: Connection, id: String): Order? = db.prepareStatement("SELECT * FROM rr_client_orders WHERE id = ? FOR UPDATE").use { q ->
        q.setString(1, id); q.executeQuery().use { if (it.next()) readOrder(it) else null }
    }

    private fun caseExists(db: Connection, owner: String, id: String): Boolean = db.prepareStatement("SELECT id FROM rr_client_cases WHERE id = ? AND session_id = ? FOR UPDATE").use { q ->
        q.setString(1, id); q.setString(2, owner); q.executeQuery().use { it.next() }
    }

    private fun envelope(db: Connection, order: Order, now: Instant): JsonObject {
        val currentCase = caseExists(db, order.owner, order.caseId)
        val entitlement = db.prepareStatement("SELECT order_id, case_id, starts_at, expires_at FROM rr_client_entitlements WHERE order_id = ? AND owner_session_id = ? FOR UPDATE").use { q ->
            q.setString(1, order.id); q.setString(2, order.owner); q.executeQuery().use { r ->
                if (!r.next()) JsonNull else buildJsonObject {
                    val start = r.getTimestamp("starts_at").toInstant(); val expires = r.getTimestamp("expires_at").toInstant()
                    put("order_id", r.getString("order_id")); put("case_id", r.getString("case_id"))
                    put("starts_at", start.toString()); put("expires_at", expires.toString())
                    put("active", currentCase && !now.isBefore(start) && now.isBefore(expires))
                }
            }
        }
        return buildJsonObject {
            put("order", buildJsonObject {
                put("id", order.id); put("case_id", order.caseId); put("product", order.product)
                put("amount_minor", order.amount); put("currency", order.currency); put("mode", order.mode); put("status", order.status)
                put("checkout_available", currentCase && order.status == "pending")
                put("created_at", order.created.toString()); put("paid_at", order.paid?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            })
            put("entitlement", entitlement)
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { db ->
        db.createStatement().use { it.execute("SET time_zone = '+00:00'") }
        db.autoCommit = false
        try { block(db).also { db.commit() } } catch (error: Throwable) { db.rollback(); throw error }
    }

    companion object {
        private val RANDOM = SecureRandom()
        private val REQUEST = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val CASE = Regex("[a-f0-9]{32}")
        private val ORDER = Regex("rrstg_[a-f0-9]{32}")
        private val STATUSES = setOf("success", "order_canceled", "order_denied")
        const val PRODUCT = "pilot_7d"
        const val AMOUNT = 99000L
        const val CURRENCY = "RUB"
        const val MODE = "demo"
        const val MERCHANT = "relationshipreset.payform.ru"
    }
}

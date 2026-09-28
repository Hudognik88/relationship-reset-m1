package ru.poslesorry.backend

import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

internal object PaymentSchema {
    const val VERSION = "004_client_payments"
    val bytes: ByteArray by lazy {
        checkNotNull(PaymentSchema::class.java.getResourceAsStream("/client-migrations/004_client_payments.sql")).use { it.readBytes() }
    }
    val checksum: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Separate explicit command: client and owner rehearsal never run payment migrations. */
class PaymentMigrations(private val source: DataSource) {
    fun run() {
        source.connection.use { db ->
            var locked = false
            try {
                locked = db.createStatement().use { q -> q.executeQuery("SELECT GET_LOCK('rr_schema_migrations', 10)").use { it.next() && it.getInt(1) == 1 } }
                check(locked) { "Migration lock unavailable" }
                check(schemaReady(db) && clientSchemaReady(db)) { "Client schema unavailable" }
                val previous = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { q ->
                    q.setString(1, PaymentSchema.VERSION); q.executeQuery().use { if (it.next()) it.getString(1) else null }
                }
                if (previous != null) check(sameHash(previous, PaymentSchema.checksum)) { "Payment migration checksum mismatch" }
                else {
                    PaymentSchema.bytes.toString(Charsets.UTF_8).split(';').filter { it.isNotBlank() }.forEach { sql -> db.createStatement().use { it.execute(sql) } }
                    db.prepareStatement("INSERT INTO rr_schema_migrations (version, checksum) VALUES (?, ?)").use { q ->
                        q.setString(1, PaymentSchema.VERSION); q.setString(2, PaymentSchema.checksum); q.executeUpdate()
                    }
                }
                check(paymentSchemaReady(db)) { "Payment schema unavailable" }
            } finally {
                if (locked) db.createStatement().use { it.executeQuery("SELECT RELEASE_LOCK('rr_schema_migrations')").close() }
            }
        }
    }
}

internal fun paymentSchemaReady(db: Connection): Boolean {
    val valid = db.prepareStatement("SELECT checksum FROM rr_schema_migrations WHERE version = ?").use { q ->
        q.setString(1, PaymentSchema.VERSION); q.executeQuery().use { it.next() && sameHash(it.getString(1), PaymentSchema.checksum) }
    }
    if (!valid) return false
    for (sql in listOf(
        "SELECT id, owner_session_id, client_request_id, case_id, product, amount_minor, currency, mode, status, paid_provider_order_id, created_at, paid_at FROM rr_client_orders LIMIT 0",
        "SELECT owner_session_id, client_request_id, case_id, order_id, created_at FROM rr_client_order_requests LIMIT 0",
        "SELECT provider_order_id, order_id, merchant_domain, amount_minor, currency, demo_mode, status, first_received_at, last_received_at FROM rr_payment_receipts LIMIT 0",
        "SELECT order_id, owner_session_id, case_id, starts_at, expires_at FROM rr_client_entitlements LIMIT 0"
    )) db.createStatement().use { it.executeQuery(sql).close() }
    return true
}

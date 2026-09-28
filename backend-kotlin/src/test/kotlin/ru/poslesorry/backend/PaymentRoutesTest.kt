package ru.poslesorry.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class PaymentRoutesTest {
    private val now = Instant.parse("2026-09-28T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val token = "a".repeat(43)
    private val owner = "b".repeat(32)
    private val caseId = "c".repeat(32)
    private val orderId = "rrstg_" + "d".repeat(32)
    private val requestId = "12345678-1234-1234-1234-123456789abc"
    private val secret = "synthetic-test-merchant-secret"
    private val csrf = Contract.sha256("csrf:$token")
    private val config = AppConfig(Contract.sha256("operator-test"), DatabaseConfig("localhost", 3306, "unused", "unused", "unused", "DISABLED"))
    private val demo = config.copy(payments = DemoPaymentConfig(secret))

    private inner class Clients : ClientStore {
        var available = true
        override fun session(token: String, now: Instant): ClientSession? =
            if (available && token == this@PaymentRoutesTest.token) ClientSession(owner, now.plusSeconds(3600), true) else null
        override fun getCase(sessionId: String, now: Instant): JsonObject = buildJsonObject {
            put("case", buildJsonObject { put("id", caseId) }); put("review", JsonNull)
        }
        override fun ready() = true
        override fun issueInvitation(now: Instant): IssuedClientToken = error("Unexpected invitation")
        override fun exchangeInvitation(token: String, now: Instant): IssuedClientToken = error("Unexpected invitation exchange")
        override fun revokeSession(sessionId: String, now: Instant) = error("Unexpected revocation")
        override fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult = error("Unexpected case creation")
        override fun deleteCase(sessionId: String, now: Instant) = error("Unexpected case deletion")
        override fun publishReview(caseId: String, text: String, now: Instant): JsonObject = error("Unexpected review")
    }

    private inner class Payments : PaymentStore {
        var readyCalls = 0
        var creates = 0
        var reads = 0
        var available = true
        var receivedOwner: String? = null
        var receivedCase: String? = null
        var receivedRequest: String? = null
        var receivedTime: Instant? = null
        var failure: RuntimeException? = null
        val notices = mutableListOf<ProdamusNotification>()
        var status = "pending"
        var checkoutAvailable = true
        fun envelope() = buildJsonObject {
            put("order", buildJsonObject {
                put("id", orderId); put("case_id", caseId); put("product", "pilot_7d")
                put("amount_minor", 99000); put("currency", "RUB"); put("mode", "demo")
                put("status", status); put("checkout_available", checkoutAvailable)
                put("created_at", now.toString()); put("paid_at", JsonNull)
            })
            put("entitlement", JsonNull)
        }
        override fun ready(): Boolean { readyCalls++; return available }
        override fun createOrder(sessionId: String, requestId: String, caseId: String, now: Instant): ApiResult {
            creates++; receivedOwner = sessionId; receivedRequest = requestId; receivedCase = caseId; receivedTime = now
            failure?.let { throw it }; return ApiResult(201, envelope())
        }
        override fun order(sessionId: String, now: Instant): JsonObject {
            reads++; receivedOwner = sessionId; receivedTime = now; failure?.let { throw it }; return envelope()
        }
        override fun accept(notification: ProdamusNotification, now: Instant) {
            failure?.let { throw it }; receivedTime = now; notices += notification
        }
    }

    private fun HttpRequestBuilder.cookie() { header(HttpHeaders.Cookie, "__Host-rr_client=$token") }
    private fun HttpRequestBuilder.mutation() { cookie(); header(HttpHeaders.Origin, config.clientOrigin); header("X-CSRF-Token", csrf) }
    private fun orderBody() = """{"synthetic":true,"product":"pilot_7d","client_request_id":"$requestId","case_id":"$caseId"}"""
    private fun notice(demoMode: String? = "1", status: String = "success") = buildJsonObject {
        put("order_num", orderId); put("order_id", "123456789"); put("domain", "relationshipreset.payform.ru")
        put("sum", "990.00"); put("currency", "rub"); put("payment_status", status)
        demoMode?.let { put("demo_mode", it) }
    }
    private fun form(data: JsonObject) = data.entries.joinToString("&") {
        URLEncoder.encode(it.key, UTF_8) + "=" + URLEncoder.encode(it.value.jsonPrimitive.content, UTF_8)
    }
    private fun HttpRequestBuilder.signed(data: JsonObject) {
        contentType(ContentType.Application.FormUrlEncoded); header("Sign", ProdamusContract.signature(data, secret)); setBody(form(data))
    }
    private suspend fun HttpResponse.json() = Contract.json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `disabled orders still require an active cookie and reveal no stored orders`() = testApplication {
        val clients = Clients(); val payments = Payments()
        application { paymentApi(config, clients, payments, clock) }
        for (method in listOf(HttpMethod.Get, HttpMethod.Post)) {
            val response = client.request("https://localhost/client/orders") { this.method = method; header(HttpHeaders.Origin, config.clientOrigin) }
            assertEquals(401, response.status.value)
        }
        val get = client.get("https://localhost/client/orders") { cookie() }
        assertEquals(200, get.status.value)
        assertEquals(buildJsonObject { put("enabled", false); put("order", JsonNull); put("entitlement", JsonNull); put("checkout_url", JsonNull) }, get.json())
        val post = client.post("https://localhost/client/orders") { mutation(); contentType(ContentType.Application.Json); setBody(orderBody()) }
        assertEquals(503, post.status.value)
        assertEquals(JsonPrimitive("payments_disabled"), post.json()["error"])
        assertEquals("no-store", get.headers[HttpHeaders.CacheControl])
        assertEquals(0, payments.readyCalls); assertEquals(0, payments.creates); assertEquals(0, payments.reads)
    }

    @Test
    fun `disabled callback has explicit closed response and only POST is allowed`() = testApplication {
        val payments = Payments()
        application { paymentApi(config, Clients(), payments, clock) }
        val post = client.post("https://localhost/payments/prodamus/webhook")
        assertEquals(503, post.status.value); assertEquals(JsonPrimitive("payments_disabled"), post.json()["error"])
        val get = client.get("https://localhost/payments/prodamus/webhook")
        assertEquals(405, get.status.value); assertEquals("POST", get.headers[HttpHeaders.Allow])
        assertEquals("no-store", post.headers[HttpHeaders.CacheControl])
        assertEquals(0, payments.readyCalls); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `authenticated order uses session owner and returns only a signed demo checkout`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        val response = client.post("https://localhost/client/orders") { mutation(); contentType(ContentType.Application.Json); setBody(orderBody()) }
        assertEquals(201, response.status.value)
        val body = response.json()
        assertEquals(JsonPrimitive(true), body["enabled"])
        assertEquals(owner, payments.receivedOwner); assertEquals(caseId, payments.receivedCase)
        assertEquals(requestId, payments.receivedRequest); assertEquals(now, payments.receivedTime)
        val checkout = URI(body.getValue("checkout_url").jsonPrimitive.content)
        assertEquals("https", checkout.scheme); assertEquals("relationshipreset.payform.ru", checkout.host)
        val fields = checkout.rawQuery.split('&').associate { field ->
            URLDecoder.decode(field.substringBefore('='), UTF_8) to URLDecoder.decode(field.substringAfter('='), UTF_8)
        }
        assertEquals("1", fields["demo_mode"]); assertEquals("pay", fields["do"])
        assertEquals(orderId, fields["order_id"]); assertEquals("990.00", fields["products[0][price]"])
        assertEquals("1", fields["products[0][quantity]"]); assertEquals("rub", fields["currency"])
        val unsignedForm = checkout.rawQuery.split('&').filterNot { it.startsWith("signature=") }.joinToString("&")
        assertTrue(ProdamusContract.verify(ProdamusContract.parse("application/x-www-form-urlencoded", unsignedForm.toByteArray(UTF_8)), secret, fields.getValue("signature")))
        assertFalse(response.bodyAsText().contains(token)); assertFalse(response.bodyAsText().contains(secret))
        assertEquals(JsonNull, body["entitlement"])
        assertEquals(0, payments.notices.size)
    }

    @Test
    fun `reading a paid order cannot create another checkout or change payment state`() = testApplication {
        val payments = Payments().also { it.status = "paid" }
        application { paymentApi(demo, Clients(), payments, clock) }
        val response = client.get("https://localhost/client/orders") { cookie() }
        assertEquals(200, response.status.value); assertEquals(JsonNull, response.json()["checkout_url"])
        assertEquals(owner, payments.receivedOwner); assertEquals(1, payments.reads)
        assertEquals(0, payments.creates); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `deleted-case pending order never returns a checkout URL`() = testApplication {
        val payments = Payments().also { it.checkoutAvailable = false }
        application { paymentApi(demo, Clients(), payments, clock) }
        val response = client.get("https://localhost/client/orders") { cookie() }
        assertEquals(200, response.status.value); assertEquals(JsonNull, response.json()["checkout_url"])
        assertEquals(JsonPrimitive("pending"), response.json().getValue("order").jsonObject["status"])
        assertEquals(0, payments.creates); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `order mutations reject foreign duplicate or absent origin and csrf before storage`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        for (origins in listOf(emptyList(), listOf("https://evil.example"), listOf(config.clientOrigin, config.clientOrigin))) {
            val response = client.post("https://localhost/client/orders") {
                cookie(); origins.forEach { header(HttpHeaders.Origin, it) }; header("X-CSRF-Token", csrf)
                contentType(ContentType.Application.Json); setBody(orderBody())
            }
            assertEquals(403, response.status.value)
        }
        for (tokens in listOf(emptyList(), listOf("x".repeat(64)), listOf(csrf, csrf))) {
            val response = client.post("https://localhost/client/orders") {
                cookie(); header(HttpHeaders.Origin, config.clientOrigin); tokens.forEach { header("X-CSRF-Token", it) }
                contentType(ContentType.Application.Json); setBody(orderBody())
            }
            assertEquals(403, response.status.value)
        }
        assertEquals(0, payments.readyCalls); assertEquals(0, payments.creates)
    }

    @Test
    fun `client cannot set payment amount owner or mode and malformed order identifiers fail`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        val invalid = listOf(
            orderBody().dropLast(1) + ",\"amount_minor\":1}",
            orderBody().dropLast(1) + ",\"owner\":\"other\"}",
            orderBody().dropLast(1) + ",\"mode\":\"live\"}",
            orderBody().replace("true", "false"),
            orderBody().replace("pilot_7d", "arbitrary_product"),
            orderBody().replace(requestId, "invalid"),
            orderBody().replace(caseId, "invalid"),
        )
        for (body in invalid) {
            val response = client.post("https://localhost/client/orders") { mutation(); contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(422, response.status.value, body)
        }
        val malformed = client.post("https://localhost/client/orders") { mutation(); contentType(ContentType.Application.Json); setBody("{") }
        assertEquals(400, malformed.status.value)
        val wrongType = client.post("https://localhost/client/orders") { mutation(); contentType(ContentType.Text.Plain); setBody(orderBody()) }
        assertEquals(415, wrongType.status.value)
        assertEquals(0, payments.readyCalls); assertEquals(0, payments.creates)
    }

    @Test
    fun `query parameters cannot supply success or callback fields and insecure transport is refused`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        assertEquals(404, client.get("https://localhost/client/orders?payment_status=success") { cookie() }.status.value)
        assertEquals(404, client.post("https://localhost/payments/prodamus/webhook?demo_mode=1") { signed(notice()) }.status.value)
        assertEquals(400, client.post("http://localhost/payments/prodamus/webhook") { header("X-Forwarded-Proto", "https"); signed(notice()) }.status.value)
        assertEquals(405, client.delete("https://localhost/client/orders") { mutation() }.status.value)
        assertEquals(0, payments.readyCalls); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `only signed demo callback reaches transaction and needs no browser credentials`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        val data = notice()
        val response = client.post("https://localhost/payments/prodamus/webhook") { signed(data) }
        assertEquals(200, response.status.value)
        assertEquals(buildJsonObject { put("accepted", true); put("mode", "demo") }, response.json())
        assertEquals(listOf(ProdamusContract.notification(data)), payments.notices)
        assertEquals(now, payments.receivedTime)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertFalse(response.bodyAsText().contains(secret))
    }

    @Test
    fun `missing duplicate invalid and tampered signatures never reach the payment transaction`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        val data = notice(); val valid = ProdamusContract.signature(data, secret)
        for (signs in listOf(emptyList(), listOf(valid, valid), listOf("invalid"), listOf("0".repeat(64)))) {
            val response = client.post("https://localhost/payments/prodamus/webhook") {
                contentType(ContentType.Application.FormUrlEncoded); signs.forEach { header("Sign", it) }; setBody(form(data))
            }
            assertEquals(401, response.status.value)
        }
        val tampered = client.post("https://localhost/payments/prodamus/webhook") {
            contentType(ContentType.Application.FormUrlEncoded); header("Sign", valid); setBody(form(data).replace("990.00", "1.00"))
        }
        assertEquals(401, tampered.status.value)
        assertEquals(0, payments.readyCalls); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `signed real-mode missing-mode and malformed callbacks fail closed`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        for (mode in listOf("0", null)) {
            val response = client.post("https://localhost/payments/prodamus/webhook") { signed(notice(mode)) }
            assertEquals(422, response.status.value)
            assertEquals(JsonPrimitive("demo_notification_required"), response.json()["error"])
        }
        val invalidMode = client.post("https://localhost/payments/prodamus/webhook") { signed(notice("true")) }
        assertEquals(400, invalidMode.status.value)
        val duplicate = client.post("https://localhost/payments/prodamus/webhook") {
            contentType(ContentType.Application.FormUrlEncoded); header("Sign", ProdamusContract.signature(notice(), secret)); setBody(form(notice()) + "&sum=990.00")
        }
        assertEquals(400, duplicate.status.value)
        val malformed = client.post("https://localhost/payments/prodamus/webhook") {
            contentType(ContentType.Application.FormUrlEncoded); header("Sign", "0".repeat(64)); setBody("sum=%XX")
        }
        assertEquals(400, malformed.status.value)
        assertEquals(0, payments.readyCalls); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `JSON and oversized streamed callbacks are rejected before parsing or storage`() = testApplication {
        val payments = Payments()
        application { paymentApi(demo, Clients(), payments, clock) }
        val json = client.post("https://localhost/payments/prodamus/webhook") {
            contentType(ContentType.Application.Json); header("Sign", "0".repeat(64)); setBody(notice().toString())
        }
        assertEquals(415, json.status.value)
        val response = client.post("https://localhost/payments/prodamus/webhook") {
            header("Sign", "0".repeat(64))
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.FormUrlEncoded
                override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(ByteArray(ProdamusContract.MAX_BODY_BYTES + 1) { 97 }) }
            })
        }
        assertEquals(413, response.status.value)
        assertEquals(0, payments.readyCalls); assertTrue(payments.notices.isEmpty())
    }

    @Test
    fun `storage failures are never acknowledged as successful payment or exposed to clients`() = testApplication {
        val payments = Payments().also { it.failure = IllegalStateException("private database credential") }
        application { paymentApi(demo, Clients(), payments, clock) }
        val response = client.post("https://localhost/payments/prodamus/webhook") { signed(notice()) }
        assertEquals(503, response.status.value)
        assertEquals(buildJsonObject { put("error", "service_unavailable") }, response.json())
        assertTrue(payments.notices.isEmpty())
        payments.failure = ApiProblem(409, "payment_mismatch")
        val mismatch = client.post("https://localhost/payments/prodamus/webhook") { signed(notice()) }
        assertEquals(409, mismatch.status.value)
        assertEquals(JsonPrimitive("payment_mismatch"), mismatch.json()["error"])
        payments.failure = null; payments.available = false
        val unavailable = client.post("https://localhost/payments/prodamus/webhook") { signed(notice()) }
        assertEquals(503, unavailable.status.value); assertTrue(payments.notices.isEmpty())
    }
}

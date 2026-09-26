package ru.poslesorry.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class ClientRoutesTest {
    private val now = Instant.parse("2026-09-26T09:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val token = "a".repeat(43)
    private val invitation = "b".repeat(43)
    private val operatorToken = "c".repeat(43)
    private val csrf = Contract.sha256("csrf:$token")
    private val config = AppConfig(
        Contract.sha256(operatorToken),
        DatabaseConfig("localhost", 3306, "unused_test", "unused", UUID.randomUUID().toString(), "DISABLED"),
    )

    private class RecordingClientStore : ClientStore {
        var readyCalls = 0
        var exchanges = 0
        var sessions = 0
        var invitations = 0
        var creates = 0
        var reads = 0
        var deletes = 0
        var revocations = 0
        var publications = 0
        var receivedPayload: JsonObject? = null
        var receivedOwner: String? = null
        var receivedTime: Instant? = null
        var failure: RuntimeException? = null
        var available = true
        var revoked = false

        override fun ready(): Boolean { readyCalls++; failure?.let { throw it }; return available }
        override fun issueInvitation(now: Instant): IssuedClientToken {
            invitations++; receivedTime = now
            return IssuedClientToken("b".repeat(43), now.plusSeconds(3600))
        }
        override fun exchangeInvitation(token: String, now: Instant): IssuedClientToken {
            exchanges++; receivedTime = now
            if (token != "b".repeat(43)) throw ApiProblem(401, "invitation_invalid")
            return IssuedClientToken("a".repeat(43), now.plusSeconds(86400))
        }
        override fun session(token: String, now: Instant): ClientSession? {
            sessions++; receivedTime = now
            return if (token == "a".repeat(43) && !revoked) ClientSession("d".repeat(32), now.plusSeconds(86400)) else null
        }
        override fun revokeSession(sessionId: String, now: Instant) {
            revocations++; receivedOwner = sessionId; receivedTime = now; revoked = true
        }
        override fun getCase(sessionId: String, now: Instant): JsonObject {
            reads++; receivedOwner = sessionId; receivedTime = now
            return buildJsonObject { put("case", JsonNull); put("review", JsonNull) }
        }
        override fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult {
            creates++; receivedOwner = sessionId; receivedPayload = payload; receivedTime = now
            return ApiResult(201, buildJsonObject { put("id", "e".repeat(32)); put("synthetic", true) })
        }
        override fun deleteCase(sessionId: String, now: Instant) {
            deletes++; receivedOwner = sessionId; receivedTime = now
        }
        override fun publishReview(caseId: String, text: String, now: Instant): JsonObject {
            publications++; receivedTime = now
            return buildJsonObject { put("published", true); put("case_id", caseId) }
        }
    }

    private class OperatorStore : CaseStore {
        var readyCalls = 0
        override fun ready(): Boolean { readyCalls++; return true }
        override fun createCase(payload: JsonObject): ApiResult = error("Unexpected operator create")
        override fun getCase(id: String): JsonObject = error("Unexpected operator get")
        override fun createDraft(caseId: String, payload: JsonObject): ApiResult = error("Unexpected operator draft")
    }

    private fun HttpRequestBuilder.sessionCookie(value: String = token) {
        header(HttpHeaders.Cookie, "__Host-rr_client=$value")
    }
    private fun HttpRequestBuilder.mutation() {
        sessionCookie(); header(HttpHeaders.Origin, config.clientOrigin); header("X-CSRF-Token", csrf)
    }
    private fun exchangeBody(synthetic: Boolean = true): String = """{"invitation":"$invitation","synthetic":$synthetic}"""
    private fun caseBody(age: String = "adult", safety: String = "no", situation: String = "Вымышленная ссора о бытовых делах."): String =
        Contract.canonicalJson(buildJsonObject {
            put("schema_version", "m1-cis-v1"); put("synthetic", true); put("client_request_id", UUID.randomUUID().toString())
            put("questionnaire", buildJsonObject {
                put("age", age); put("stage", "1to3y"); put("safety", safety); put("boundary", "space")
                put("timing", "today"); put("partner", "space"); put("user", "talk"); put("recurrence", "first")
                put("helpful", "pause"); put("failureType", "none"); put("goal", "calm")
                put("situation", situation); put("success", ""); put("failure", "")
            })
        })

    @Test
    fun `invitation exchange issues a secure host-only cookie and does not expose operator credential`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val response = client.post("https://localhost/client/session") {
            header(HttpHeaders.Origin, config.clientOrigin); contentType(ContentType.Application.Json); setBody(exchangeBody())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val cookie = response.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("__Host-rr_client=$token;"))
        assertTrue(cookie.contains("Secure", ignoreCase = true))
        assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookie.contains("SameSite=Strict", ignoreCase = true))
        assertTrue(cookie.contains("Path=/", ignoreCase = true))
        assertTrue(cookie.contains("Max-Age=86400", ignoreCase = true))
        assertFalse(cookie.contains("Domain=", ignoreCase = true))
        val body = Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(true), body["authenticated"])
        assertEquals(JsonPrimitive(csrf), body["csrf_token"])
        assertEquals(JsonPrimitive(now.plusSeconds(86400).toString()), body["expires_at"])
        assertFalse(response.bodyAsText().contains(token))
        assertFalse(response.bodyAsText().contains(operatorToken))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals(1, store.exchanges)
        assertEquals(now, store.receivedTime)
    }

    @Test
    fun `invitation exchange rejects missing cross-site duplicate origin and non-fictional declaration before exchange`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        for (origins in listOf(emptyList(), listOf("https://evil.example"), listOf("null"), listOf(config.clientOrigin + "/"), listOf(config.clientOrigin, config.clientOrigin))) {
            val response = client.post("https://localhost/client/session") {
                origins.forEach { header(HttpHeaders.Origin, it) }
                contentType(ContentType.Application.Json); setBody(exchangeBody())
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        val realData = client.post("https://localhost/client/session") {
            header(HttpHeaders.Origin, config.clientOrigin); contentType(ContentType.Application.Json); setBody(exchangeBody(false))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, realData.status)
        assertEquals(0, store.exchanges)
    }

    @Test
    fun `an active session cannot be replaced by invitation exchange`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val response = client.post("https://localhost/client/session") {
            sessionCookie(); header(HttpHeaders.Origin, config.clientOrigin)
            contentType(ContentType.Application.Json); setBody(exchangeBody())
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("{\"error\":\"session_exists\"}", response.bodyAsText())
        assertNull(response.headers[HttpHeaders.SetCookie])
        assertEquals(0, store.exchanges)
    }

    @Test
    fun `client transport and missing duplicate malformed or wrong cookies cannot access a case`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val insecure = client.get("http://localhost/client/case") {
            sessionCookie(); header("X-Forwarded-Proto", "https")
        }
        assertEquals(HttpStatusCode.BadRequest, insecure.status)
        for (cookie in listOf(null, "__Host-rr_client=short", "__Host-rr_client=" + "x".repeat(43), "__Host-rr_client=$token; __Host-rr_client=$token")) {
            val response = client.get("https://localhost/client/case") {
                cookie?.let { header(HttpHeaders.Cookie, it) }
                header(HttpHeaders.Authorization, "Bearer $operatorToken")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertEquals(0, store.reads)
    }

    @Test
    fun `session restore returns csrf and storage only sees authenticated owner`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val session = client.get("https://localhost/client/session") { sessionCookie() }
        assertEquals(HttpStatusCode.OK, session.status)
        assertEquals(JsonPrimitive(csrf), Contract.json.parseToJsonElement(session.bodyAsText()).jsonObject["csrf_token"])
        val case = client.get("https://localhost/client/case") { sessionCookie() }
        assertEquals(HttpStatusCode.OK, case.status)
        assertEquals("d".repeat(32), store.receivedOwner)
        assertEquals("{\"case\":null,\"review\":null}", case.bodyAsText())
        assertEquals("no-store", case.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `all authenticated mutations require both exact Origin and session-bound csrf`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        for (path in listOf("/client/case", "/client/session")) {
            for (csrfHeaders in listOf(emptyList(), listOf("x".repeat(64)), listOf(csrf, csrf))) {
                val response = client.delete("https://localhost$path") {
                    sessionCookie(); header(HttpHeaders.Origin, config.clientOrigin)
                    csrfHeaders.forEach { header("X-CSRF-Token", it) }
                }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
            val foreign = client.delete("https://localhost$path") {
                sessionCookie(); header(HttpHeaders.Origin, "https://evil.example"); header("X-CSRF-Token", csrf)
            }
            assertEquals(HttpStatusCode.Forbidden, foreign.status)
        }
        val create = client.post("https://localhost/client/case") {
            sessionCookie(); header(HttpHeaders.Origin, config.clientOrigin)
            contentType(ContentType.Application.Json); setBody(caseBody())
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertEquals(0, store.creates); assertEquals(0, store.deletes); assertEquals(0, store.revocations)
    }

    @Test
    fun `minor safety declaration and danger text are rejected before a case can be stored`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        for ((body, code) in listOf(
            caseBody(age = "minor") to "adult_required",
            caseBody(safety = "yes_unsure") to "safety_not_supported",
            caseBody(situation = "Вымышленный персонаж: партнёр угрожает и забрал паспорт.") to "safety_not_supported",
        )) {
            val response = client.post("https://localhost/client/case") {
                mutation(); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(JsonPrimitive(code), Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject["error"])
        }
        assertEquals(0, store.creates)
    }

    @Test
    fun `client case body is bounded strict JSON canonicalized and keeps synthetic requirement`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val cases = listOf(
            "text/plain" to caseBody(),
            "application/json" to "{",
            "application/json" to caseBody().replace("\"synthetic\":true", "\"synthetic\":false"),
            "application/json" to " ".repeat(Contract.MAX_BODY_BYTES + 1),
        )
        for ((index, item) in cases.withIndex()) {
            val response = client.post("https://localhost/client/case") {
                mutation(); header(HttpHeaders.ContentType, item.first); setBody(item.second)
            }
            assertEquals(listOf(415, 400, 422, 413)[index], response.status.value)
        }
        val streamed = client.post("https://localhost/client/case") {
            mutation()
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    channel.writeFully(ByteArray(Contract.MAX_BODY_BYTES + 1) { 32 })
                }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status)
        assertEquals(0, store.creates)
        val valid = client.post("https://localhost/client/case") {
            mutation(); contentType(ContentType.Application.Json); setBody(caseBody(situation = "  Вымышленный бытовой спор.  "))
        }
        assertEquals(HttpStatusCode.Created, valid.status)
        assertEquals("d".repeat(32), store.receivedOwner)
        assertEquals("Вымышленный бытовой спор.", store.receivedPayload!!["questionnaire"]!!.jsonObject["situation"]!!.jsonPrimitive.content)
        assertEquals(now, store.receivedTime)
    }

    @Test
    fun `deletion applies to the session owner and logout clears cookie and revokes access`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        val deleted = client.delete("https://localhost/client/case") { mutation() }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals("{\"deleted\":true}", deleted.bodyAsText())
        assertEquals("d".repeat(32), store.receivedOwner)
        val loggedOut = client.delete("https://localhost/client/session") { mutation() }
        assertEquals(HttpStatusCode.OK, loggedOut.status)
        assertEquals("{\"authenticated\":false}", loggedOut.bodyAsText())
        val cookie = loggedOut.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.contains("Max-Age=0", ignoreCase = true))
        assertTrue(cookie.contains("Secure", ignoreCase = true)); assertTrue(cookie.contains("HttpOnly", ignoreCase = true))
        assertEquals(1, store.revocations); assertEquals(1, store.deletes)
        val retry = client.get("https://localhost/client/case") { sessionCookie() }
        assertEquals(HttpStatusCode.Unauthorized, retry.status)
    }

    @Test
    fun `operator invitation and publication require bearer and never accept client cookie`() = testApplication {
        val store = RecordingClientStore()
        application { clientApi(config, store, clock) }
        for (path in listOf("invitations", "reviews")) {
            val forbidden = client.post("https://localhost/client/operator/$path") {
                mutation(); contentType(ContentType.Application.Json); setBody("{\"synthetic\":true}")
            }
            assertEquals(HttpStatusCode.Unauthorized, forbidden.status)
        }
        assertEquals(0, store.invitations); assertEquals(0, store.publications)
        val issued = client.post("https://localhost/client/operator/invitations") {
            header(HttpHeaders.Authorization, "Bearer $operatorToken")
            contentType(ContentType.Application.Json); setBody("{\"synthetic\":true}")
        }
        assertEquals(HttpStatusCode.Created, issued.status)
        assertEquals(JsonPrimitive(invitation), Contract.json.parseToJsonElement(issued.bodyAsText()).jsonObject["invitation"])
        val published = client.post("https://localhost/client/operator/reviews") {
            header(HttpHeaders.Authorization, "Bearer $operatorToken"); contentType(ContentType.Application.Json)
            setBody("""{"case_id":"${"e".repeat(32)}","synthetic":true,"text":"Вымышленный разбор после проверки человеком."}""")
        }
        assertEquals(HttpStatusCode.OK, published.status)
        assertEquals(1, store.invitations); assertEquals(1, store.publications)
    }

    @Test
    fun `adding client routes keeps operator bearer readiness independent`() = testApplication {
        val operatorStore = OperatorStore()
        val clientStore = RecordingClientStore()
        application { api(config, operatorStore, clientStore) }
        val clientCookie = client.get("https://localhost/api/index.php?route=/ready") { sessionCookie() }
        assertEquals(HttpStatusCode.Unauthorized, clientCookie.status)
        val operator = client.get("https://localhost/api/index.php?route=/ready") {
            header(HttpHeaders.Authorization, "Bearer $operatorToken")
        }
        assertEquals(HttpStatusCode.OK, operator.status)
        assertEquals(1, operatorStore.readyCalls)
        assertEquals(0, clientStore.readyCalls)
    }

    @Test
    fun `client storage failures are generic and never echo private messages`() = testApplication {
        val store = RecordingClientStore().apply { failure = IllegalStateException("database password and private questionnaire") }
        application { clientApi(config, store, clock) }
        val response = client.get("https://localhost/client/case") { sessionCookie() }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"error\":\"service_unavailable\"}", response.bodyAsText())
    }

    @Test
    fun `invitation rate limit stops storage work and recovers after the minute window`() = testApplication {
        val store = RecordingClientStore()
        var current = now
        val advancingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = current
        }
        application { clientApi(config, store, advancingClock) }
        suspend fun exchange() = client.post("https://localhost/client/session") {
            header(HttpHeaders.Origin, config.clientOrigin); contentType(ContentType.Application.Json); setBody(exchangeBody())
        }
        repeat(30) { assertEquals(HttpStatusCode.Created, exchange().status) }
        val limited = exchange()
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        assertEquals(30, store.exchanges)
        assertEquals(30, store.readyCalls)
        current = now.plusSeconds(60)
        assertEquals(HttpStatusCode.Created, exchange().status)
        assertEquals(31, store.exchanges)
    }

    @Test
    fun `anonymous requests cannot exhaust the authenticated client limit and the limit recovers`() = testApplication {
        val store = RecordingClientStore()
        var current = now
        val advancingClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = this
            override fun instant(): Instant = current
        }
        application { clientApi(config, store, advancingClock) }
        repeat(61) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("https://localhost/client/case").status)
        }
        assertEquals(0, store.sessions); assertEquals(0, store.reads)
        repeat(60) {
            assertEquals(HttpStatusCode.OK, client.get("https://localhost/client/case") { sessionCookie() }.status)
        }
        val limited = client.get("https://localhost/client/case") { sessionCookie() }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        assertEquals(60, store.reads)
        current = now.plusSeconds(60)
        assertEquals(HttpStatusCode.OK, client.get("https://localhost/client/case") { sessionCookie() }.status)
        assertEquals(61, store.reads)
    }
}

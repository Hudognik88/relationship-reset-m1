package ru.poslesorry.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

class ApplicationTest {
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private val config = AppConfig(
        Contract.sha256(token), DatabaseConfig("localhost", 3306, "unused_test_database", "unused", UUID.randomUUID().toString(), "DISABLED"),
    )
    private val caseId = "a".repeat(32)

    private class RecordingStore : CaseStore {
        var readyCalls = 0
        var isReady = true
        var failure: RuntimeException? = null
        var casePayload: JsonObject? = null
        var requestedCase: String? = null
        var draftCase: String? = null
        var draftPayload: JsonObject? = null

        override fun ready(): Boolean {
            readyCalls++
            failure?.let { throw it }
            return isReady
        }

        override fun createCase(payload: JsonObject): ApiResult {
            casePayload = payload
            return ApiResult(201, buildJsonObject { put("id", "a".repeat(32)); put("synthetic", true) })
        }

        override fun getCase(id: String): JsonObject {
            requestedCase = id
            return buildJsonObject { put("id", id); put("synthetic", true) }
        }

        override fun createDraft(caseId: String, payload: JsonObject): ApiResult {
            draftCase = caseId
            draftPayload = payload
            return ApiResult(201, buildJsonObject { put("case_id", caseId); put("reviewed", false) })
        }
    }

    private fun validCase(): String = """{
      "schema_version":"m1-cis-v1","synthetic":true,"client_request_id":"${UUID.randomUUID()}",
      "questionnaire":{"age":"adult","stage":"1to3y","safety":"no","boundary":"space",
      "timing":"today","partner":"space","user":"talk","recurrence":"first","helpful":"pause",
      "failureType":"none","goal":"calm","situation":"  Вымышленная ситуация.  ","success":"","failure":""}
    }"""

    private fun validDraft(): String = """{"synthetic":true,"client_request_id":"${UUID.randomUUID()}","text":"  Вымышленный разбор.  "}"""

    private fun HttpRequestBuilder.authorize() { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `health is public liveness without touching database and always disables caching`() = testApplication {
        val store = RecordingStore().apply { failure = RuntimeException("database must not be touched") }
        application { api(config.copy(release = "b".repeat(40)), store) }
        val response = client.get("/api/health.php")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("noindex, nofollow", response.headers["X-Robots-Tag"])
        val body = Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("staging", body["mode"]?.jsonPrimitive?.content)
        assertEquals("b".repeat(40), body["release"]?.jsonPrimitive?.content)
        assertEquals(0, store.readyCalls)
        val wrongMethod = client.post("/api/health.php")
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
        assertEquals("GET", wrongMethod.headers[HttpHeaders.Allow])
    }

    @Test
    fun `plain HTTP and client supplied proxy headers cannot bypass transport policy`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        for (spoof in listOf(false, true)) {
            val response = client.get("http://localhost/api/index.php?route=/ready") {
                authorize()
                if (spoof) {
                    header("X-Forwarded-Proto", "https")
                    header("X-Forwarded-For", "127.0.0.1")
                    header("Forwarded", "for=127.0.0.1;proto=https")
                }
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("{\"error\":\"https_required\"}", response.bodyAsText())
        }
        assertEquals(0, store.readyCalls)
    }

    @Test
    fun `missing malformed duplicate and incorrect tokens fail before database access`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        val invalid = listOf(null, "Basic $token", "bearer $token", "Bearer short", "Bearer " + "x".repeat(43), "Bearer " + "x".repeat(129), "Bearer ${token}=")
        for (authHeader in invalid) {
            val response = client.get("https://localhost/api/index.php?route=/ready") {
                if (authHeader != null) header(HttpHeaders.Authorization, authHeader)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("Bearer", response.headers[HttpHeaders.WWWAuthenticate])
            assertEquals("{\"error\":\"unauthorized\"}", response.bodyAsText())
        }
        val duplicate = client.get("https://localhost/api/index.php?route=/ready") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Unauthorized, duplicate.status)
        assertEquals(0, store.readyCalls)
    }

    @Test
    fun `authenticated readiness uses schema readiness and masks unexpected exceptions`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        val ready = client.get("https://localhost/api/index.php?route=/ready") { authorize() }
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("{\"status\":\"ready\",\"mode\":\"staging\"}", ready.bodyAsText())
        store.isReady = false
        val unavailable = client.get("https://localhost/api/index.php?route=/ready") { authorize() }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertEquals("{\"error\":\"service_unavailable\"}", unavailable.bodyAsText())
        store.failure = RuntimeException("SQL credentials and submitted private narrative must never be reflected")
        val failed = client.get("https://localhost/api/index.php?route=/ready") { authorize() }
        assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
        assertEquals("{\"error\":\"service_unavailable\"}", failed.bodyAsText())
    }

    @Test
    fun `unknown ambiguous routes and wrong methods stop before database access`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        for (query in listOf("", "?route=/missing", "?route=/ready&extra=1", "?route=/ready&route=/ready", "?route=/cases/../private", "?route=/cases/" + "A".repeat(32))) {
            val response = client.get("https://localhost/api/index.php$query") { authorize() }
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        val wrongCreate = client.get("https://localhost/api/index.php?route=/cases") { authorize() }
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongCreate.status)
        assertEquals("POST", wrongCreate.headers[HttpHeaders.Allow])
        val wrongReady = client.post("https://localhost/api/index.php?route=/ready") { authorize() }
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongReady.status)
        assertEquals("GET", wrongReady.headers[HttpHeaders.Allow])
        assertEquals(0, store.readyCalls)
    }

    @Test
    fun `invalid content type malformed JSON and invalid payload cannot reach storage`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        for (type in listOf("text/plain", "application/json; charset=iso-8859-1", "application/json;other=value")) {
            val response = client.post("https://localhost/api/index.php?route=/cases") {
                authorize(); header(HttpHeaders.ContentType, type); setBody(validCase())
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }
        val invalidJson = client.post("https://localhost/api/index.php?route=/cases") {
            authorize(); contentType(ContentType.Application.Json); setBody("{")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
        assertEquals("{\"error\":\"invalid_json\"}", invalidJson.bodyAsText())
        val invalidPayload = client.post("https://localhost/api/index.php?route=/cases") {
            authorize(); contentType(ContentType.Application.Json); setBody("{}")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidPayload.status)
        assertEquals("{\"error\":\"invalid_fields\"}", invalidPayload.bodyAsText())
        assertEquals(0, store.readyCalls)
    }

    @Test
    fun `body limit rejects both declared and streamed bodies without content length`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        val oversized = ByteArray(Contract.MAX_BODY_BYTES + 1) { 32 }
        val declared = client.post("https://localhost/api/index.php?route=/cases") {
            authorize(); contentType(ContentType.Application.Json); setBody(oversized)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        val streamed = client.post("https://localhost/api/index.php?route=/cases") {
            authorize()
            setBody(object : OutgoingContent.WriteChannelContent() {
                override val contentType: ContentType = ContentType.Application.Json
                override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(oversized) }
            })
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status)
        assertEquals("{\"error\":\"payload_too_large\"}", streamed.bodyAsText())
        assertEquals(0, store.readyCalls)
    }

    @Test
    fun `authenticated create get and draft dispatch canonical validated payloads`() = testApplication {
        val store = RecordingStore()
        application { api(config, store) }
        val created = client.post("https://localhost/api/index.php?route=/cases") {
            authorize(); contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8)); setBody(validCase())
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("Вымышленная ситуация.", store.casePayload?.get("questionnaire")?.jsonObject?.get("situation")?.jsonPrimitive?.content)
        val read = client.get("https://localhost/api/index.php?route=/cases/$caseId") { authorize() }
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(caseId, store.requestedCase)
        val drafted = client.post("https://localhost/api/index.php?route=/cases/$caseId/drafts") {
            authorize(); contentType(ContentType.Application.Json); setBody(validDraft())
        }
        assertEquals(HttpStatusCode.Created, drafted.status)
        assertEquals(caseId, store.draftCase)
        assertEquals("Вымышленный разбор.", store.draftPayload?.get("text")?.jsonPrimitive?.content)
        assertEquals(false, Contract.json.parseToJsonElement(drafted.bodyAsText()).jsonObject["reviewed"]?.jsonPrimitive?.boolean)
        assertEquals(3, store.readyCalls)
    }

    @Test
    fun `HTTPS transport trust uses actual peer and exactly one normalized protocol header`() {
        assertTrue(transportAllowed(config, "203.0.113.1", "https", null))
        assertFalse(transportAllowed(config, "127.0.0.1", "http", listOf("https")))
        val proxyConfig = config.copy(trustedProxyIps = setOf("127.0.0.1", "::1"))
        assertTrue(transportAllowed(proxyConfig, "127.0.0.1", "http", listOf("https")))
        assertTrue(transportAllowed(proxyConfig, "::1", "http", listOf("https")))
        assertFalse(transportAllowed(proxyConfig, "203.0.113.1", "http", listOf("https")))
        for (header in listOf(null, emptyList(), listOf("http"), listOf("HTTPS"), listOf(" https"), listOf("https,http"), listOf("https", "https"))) {
            assertFalse(transportAllowed(proxyConfig, "127.0.0.1", "http", header))
        }
        val local = config.copy(allowLoopbackHttp = true)
        assertTrue(transportAllowed(local, "127.0.0.1", "http", null))
        assertTrue(transportAllowed(local, "::1", "http", null))
        assertFalse(transportAllowed(local, "203.0.113.1", "http", null))
    }
}

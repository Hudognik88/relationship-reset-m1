package ru.poslesorry.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class AccessOwnerRoutesTest {
    private val now = Instant.parse("2026-09-26T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val clientToken = "c".repeat(43)
    private val ownerToken = "w".repeat(43)
    private val operatorToken = "o".repeat(43)
    private val accessKey = "k".repeat(43)
    private val ownerInvite = "i".repeat(43)
    private val caseId = "e".repeat(32)
    private val config = AppConfig(Contract.sha256(operatorToken), DatabaseConfig("localhost", 3306, "unused_test", "unused", UUID.randomUUID().toString(), "DISABLED"))

    private class Clients : ClientStore {
        var rotations = 0
        var logins = 0
        var hasKey = false
        var actor: String? = null
        var at: Instant? = null
        override fun ready() = true
        override fun issueInvitation(now: Instant) = IssuedClientToken("b".repeat(43), now.plusSeconds(3600))
        override fun exchangeInvitation(token: String, now: Instant) = IssuedClientToken("c".repeat(43), now.plusSeconds(86400))
        override fun session(token: String, now: Instant): ClientSession? =
            if (token == "c".repeat(43)) ClientSession("a".repeat(32), now.plusSeconds(86400), hasKey) else null
        override fun revokeSession(sessionId: String, now: Instant) = Unit
        override fun getCase(sessionId: String, now: Instant) = buildJsonObject { put("case", JsonNull); put("review", JsonNull) }
        override fun createCase(sessionId: String, payload: JsonObject, now: Instant): ApiResult = error("Unexpected case write")
        override fun deleteCase(sessionId: String, now: Instant) = Unit
        override fun publishReview(caseId: String, text: String, now: Instant): JsonObject = error("Unexpected legacy review")
        override fun rotateAccessKey(sessionId: String, now: Instant): String {
            rotations++; hasKey = true; actor = sessionId; at = now
            return "k".repeat(43)
        }
        override fun login(accessKey: String, now: Instant): IssuedClientToken {
            logins++; at = now
            if (accessKey != "k".repeat(43)) throw ApiProblem(401, "access_key_invalid")
            return IssuedClientToken("h".repeat(43), now.plusSeconds(86400))
        }
    }

    private class Owners : OwnerStore {
        var exchanges = 0
        var issued = 0
        var invitations = 0
        var lists = 0
        var reads = 0
        var reviews = 0
        var revocations = 0
        var revoked = false
        var actor: String? = null
        var at: Instant? = null
        var expected: String? = null
        var fail: RuntimeException? = null
        override fun ready(): Boolean { fail?.let { throw it }; return true }
        override fun issueInvitation(now: Instant): IssuedClientToken {
            issued++; at = now; return IssuedClientToken("i".repeat(43), now.plusSeconds(600))
        }
        override fun exchangeInvitation(token: String, now: Instant): IssuedClientToken {
            exchanges++; at = now
            if (token != "i".repeat(43)) throw ApiProblem(401, "owner_invitation_invalid")
            return IssuedClientToken("w".repeat(43), now.plusSeconds(3600))
        }
        override fun session(token: String, now: Instant): ClientSession? =
            if (token == "w".repeat(43) && !revoked) ClientSession("f".repeat(32), now.plusSeconds(3600)) else null
        override fun revokeSession(sessionId: String, now: Instant) { actor = sessionId; at = now; revoked = true; revocations++ }
        override fun listCases(sessionId: String, now: Instant): JsonObject {
            actor = sessionId; at = now; lists++; return buildJsonObject { put("cases", JsonArray(emptyList())) }
        }
        override fun getCase(sessionId: String, caseId: String, now: Instant): JsonObject {
            actor = sessionId; at = now; reads++
            return buildJsonObject { put("case", buildJsonObject { put("id", caseId) }); put("review", JsonNull); put("review_version", "unpublished") }
        }
        override fun issueClientInvitation(sessionId: String, now: Instant): IssuedClientToken {
            actor = sessionId; at = now; invitations++; return IssuedClientToken("b".repeat(43), now.plusSeconds(3600))
        }
        override fun publishReview(sessionId: String, caseId: String, text: String, expectedVersion: String, now: Instant): JsonObject {
            actor = sessionId; at = now; expected = expectedVersion; reviews++
            if (expectedVersion != "unpublished") throw ApiProblem(409, "review_conflict")
            return buildJsonObject { put("published", true); put("case_id", caseId); put("review_version", "d".repeat(64)) }
        }
    }

    private fun HttpRequestBuilder.clientCookie() { header(HttpHeaders.Cookie, "__Host-rr_client=$clientToken") }
    private fun HttpRequestBuilder.ownerCookie() { header(HttpHeaders.Cookie, "__Host-rr_owner=$ownerToken") }
    private fun HttpRequestBuilder.origin() { header(HttpHeaders.Origin, config.clientOrigin) }
    private fun HttpRequestBuilder.clientMutation() { clientCookie(); origin(); header("X-CSRF-Token", Contract.sha256("csrf:$clientToken")) }
    private fun HttpRequestBuilder.ownerMutation() { ownerCookie(); origin(); header("X-CSRF-Token", Contract.sha256("owner-csrf:$ownerToken")) }
    private fun HttpRequestBuilder.json(value: String) { contentType(ContentType.Application.Json); setBody(value) }
    private fun review(version: JsonElement = JsonPrimitive("unpublished"), reviewed: JsonElement = JsonPrimitive(true)): String = Contract.canonicalJson(buildJsonObject {
        put("case_id", caseId); put("text", "Вымышленный разбор после ручной проверки."); put("synthetic", true)
        put("reviewed", reviewed); put("expected_version", version)
    })

    @Test
    fun `access key rotation requires the client cookie origin and csrf and returns key only on rotation`() = testApplication {
        val clients = Clients()
        application { clientApi(config, clients, clock) }
        val before = client.get("https://localhost/client/session") { clientCookie() }
        assertEquals(JsonPrimitive(false), Contract.json.parseToJsonElement(before.bodyAsText()).jsonObject["has_access_key"])
        val unauthorized = client.post("https://localhost/client/access-key") { origin(); json("{\"synthetic\":true}") }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        val noCsrf = client.post("https://localhost/client/access-key") { clientCookie(); origin(); json("{\"synthetic\":true}") }
        assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
        val noOrigin = client.post("https://localhost/client/access-key") { clientCookie(); header("X-CSRF-Token", Contract.sha256("csrf:$clientToken")); json("{\"synthetic\":true}") }
        assertEquals(HttpStatusCode.Forbidden, noOrigin.status)
        assertEquals(0, clients.rotations)
        val result = client.post("https://localhost/client/access-key") { clientMutation(); json("{\"synthetic\":true}") }
        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals("{\"access_key\":\"$accessKey\"}", result.bodyAsText())
        assertEquals("no-store", result.headers[HttpHeaders.CacheControl])
        assertEquals("a".repeat(32), clients.actor); assertEquals(now, clients.at)
        val after = client.get("https://localhost/client/session") { clientCookie() }
        assertEquals(JsonPrimitive(true), Contract.json.parseToJsonElement(after.bodyAsText()).jsonObject["has_access_key"])
        assertFalse(after.bodyAsText().contains(accessKey))
    }

    @Test
    fun `access login issues a new protected session without exposing key or operator token`() = testApplication {
        val clients = Clients()
        application { clientApi(config, clients, clock) }
        val response = client.post("https://localhost/client/login") { origin(); json("""{"access_key":"$accessKey","synthetic":true}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        val cookie = response.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("__Host-rr_client=")); assertTrue(cookie.contains("Secure")); assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/")); assertTrue(cookie.contains("Max-Age=86400"))
        assertFalse(cookie.contains("Domain="))
        val body = Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(true), body["authenticated"])
        assertEquals(JsonPrimitive(true), body["has_access_key"])
        assertEquals(JsonPrimitive(Contract.sha256("csrf:" + "h".repeat(43))), body["csrf_token"])
        assertFalse(response.bodyAsText().contains(accessKey)); assertFalse(response.bodyAsText().contains(operatorToken))
        assertEquals(1, clients.logins); assertEquals(now, clients.at)
    }

    @Test
    fun `access login rejects active cookie foreign origin malformed key and false synthetic declaration`() = testApplication {
        val clients = Clients()
        application { clientApi(config, clients, clock) }
        val body = """{"access_key":"$accessKey","synthetic":true}"""
        val active = client.post("https://localhost/client/login") { clientCookie(); origin(); json(body) }
        assertEquals(HttpStatusCode.Conflict, active.status)
        val foreign = client.post("https://localhost/client/login") { header(HttpHeaders.Origin, "https://evil.example"); json(body) }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        val malformed = client.post("https://localhost/client/login") { origin(); json("""{"access_key":"short","synthetic":true}""") }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        val realData = client.post("https://localhost/client/login") { origin(); json(body.replace("true", "false")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, realData.status)
        assertEquals(0, clients.logins)
        val wrong = client.post("https://localhost/client/login") { origin(); json(body.replace(accessKey, "x".repeat(43))) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("{\"error\":\"access_key_invalid\"}", wrong.bodyAsText())
    }

    @Test
    fun `only the operator bearer can issue an owner invitation`() = testApplication {
        val owners = Owners()
        application { clientApi(config, Clients(), clock, owners) }
        for (cookie in listOf("__Host-rr_owner=$ownerToken", "__Host-rr_client=$clientToken")) {
            val response = client.post("https://localhost/client/operator/owner-invitations") {
                header(HttpHeaders.Cookie, cookie); origin(); json("{\"synthetic\":true}")
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        assertEquals(0, owners.issued)
        val issued = client.post("https://localhost/client/operator/owner-invitations") {
            header(HttpHeaders.Authorization, "Bearer $operatorToken"); json("{\"synthetic\":true}")
        }
        assertEquals(HttpStatusCode.Created, issued.status)
        val body = Contract.json.parseToJsonElement(issued.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(ownerInvite), body["invitation"])
        assertEquals(JsonPrimitive(now.plusSeconds(600).toString()), body["expires_at"])
        assertEquals(1, owners.issued)
    }

    @Test
    fun `owner invitation exchange creates a separate one-hour host-only cookie`() = testApplication {
        val owners = Owners()
        application { ownerApi(config, owners, clock) }
        val response = client.post("https://localhost/owner/api/session") { origin(); json("""{"invitation":"$ownerInvite","synthetic":true}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        val cookie = response.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("__Host-rr_owner=$ownerToken;"))
        for (flag in listOf("Secure", "HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=3600")) assertTrue(cookie.contains(flag))
        assertFalse(cookie.contains("Domain=")); assertFalse(cookie.contains("__Host-rr_client"))
        val body = Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JsonPrimitive(Contract.sha256("owner-csrf:$ownerToken")), body["csrf_token"])
        assertEquals(JsonPrimitive(now.plusSeconds(3600).toString()), body["expires_at"])
        assertFalse(response.bodyAsText().contains(ownerToken)); assertFalse(response.bodyAsText().contains(operatorToken))
        assertEquals(1, owners.exchanges)
    }

    @Test
    fun `owner login rejects foreign and duplicate origins active cookies and malformed invitations`() = testApplication {
        val owners = Owners()
        application { ownerApi(config, owners, clock) }
        val body = """{"invitation":"$ownerInvite","synthetic":true}"""
        for (origins in listOf(emptyList(), listOf("null"), listOf("https://evil.example"), listOf(config.clientOrigin, config.clientOrigin))) {
            val response = client.post("https://localhost/owner/api/session") { origins.forEach { header(HttpHeaders.Origin, it) }; json(body) }
            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
        val active = client.post("https://localhost/owner/api/session") { ownerCookie(); origin(); json(body) }
        assertEquals(HttpStatusCode.Conflict, active.status)
        val malformed = client.post("https://localhost/owner/api/session") { origin(); json(body.replace(ownerInvite, "short")) }
        assertEquals(HttpStatusCode.Unauthorized, malformed.status)
        assertEquals(0, owners.exchanges)
    }

    @Test
    fun `client and owner cookies and operator bearer are not interchangeable browser credentials`() = testApplication {
        val owners = Owners(); val clients = Clients()
        application { ownerApi(config, owners, clock); clientApi(config, clients, clock) }
        for (path in listOf("/owner/api/session", "/owner/api/cases", "/owner/api/cases/$caseId")) {
            val clientRole = client.get("https://localhost$path") { clientCookie() }
            assertEquals(HttpStatusCode.Unauthorized, clientRole.status)
            val bearer = client.get("https://localhost$path") { header(HttpHeaders.Authorization, "Bearer $operatorToken") }
            assertEquals(HttpStatusCode.Unauthorized, bearer.status)
        }
        val ownerRole = client.get("https://localhost/client/case") { ownerCookie() }
        assertEquals(HttpStatusCode.Unauthorized, ownerRole.status)
        val duplicate = client.get("https://localhost/owner/api/cases") { header(HttpHeaders.Cookie, "__Host-rr_owner=$ownerToken; __Host-rr_owner=$ownerToken") }
        assertEquals(HttpStatusCode.Unauthorized, duplicate.status)
        val insecure = client.get("http://localhost/owner/api/cases") { ownerCookie(); header("X-Forwarded-Proto", "https") }
        assertEquals(HttpStatusCode.BadRequest, insecure.status)
        assertEquals(0, owners.lists); assertEquals(0, owners.reads)
    }

    @Test
    fun `owner restore queue and case use authenticated owner and do not expose access credentials`() = testApplication {
        val owners = Owners()
        application { ownerApi(config, owners, clock) }
        val restored = client.get("https://localhost/owner/api/session") { ownerCookie() }
        assertEquals(HttpStatusCode.OK, restored.status)
        assertEquals(JsonPrimitive(Contract.sha256("owner-csrf:$ownerToken")), Contract.json.parseToJsonElement(restored.bodyAsText()).jsonObject["csrf_token"])
        val queue = client.get("https://localhost/owner/api/cases") { ownerCookie() }
        assertEquals(HttpStatusCode.OK, queue.status)
        val detail = client.get("https://localhost/owner/api/cases/$caseId") { ownerCookie() }
        assertEquals(HttpStatusCode.OK, detail.status)
        assertEquals(JsonPrimitive("unpublished"), Contract.json.parseToJsonElement(detail.bodyAsText()).jsonObject["review_version"])
        assertEquals("f".repeat(32), owners.actor); assertEquals(now, owners.at)
        assertEquals("no-store", detail.headers[HttpHeaders.CacheControl])
        assertFalse(detail.bodyAsText().contains(operatorToken)); assertFalse(detail.bodyAsText().contains(accessKey))
    }

    @Test
    fun `owner mutations require exact origin and owner-specific csrf`() = testApplication {
        val owners = Owners()
        application { ownerApi(config, owners, clock) }
        for (path in listOf("/owner/api/invitations", "/owner/api/reviews")) {
            for (submitted in listOf(null, Contract.sha256("csrf:$ownerToken"), Contract.sha256("csrf:$clientToken"))) {
                val response = client.post("https://localhost$path") {
                    ownerCookie(); origin(); submitted?.let { header("X-CSRF-Token", it) }
                    json(if (path.endsWith("reviews")) review() else "{\"synthetic\":true}")
                }
                assertEquals(HttpStatusCode.Forbidden, response.status)
            }
        }
        val noOrigin = client.delete("https://localhost/owner/api/session") { ownerCookie(); header("X-CSRF-Token", Contract.sha256("owner-csrf:$ownerToken")) }
        assertEquals(HttpStatusCode.Forbidden, noOrigin.status)
        assertEquals(0, owners.reviews); assertEquals(0, owners.invitations); assertEquals(0, owners.revocations)
        val invitation = client.post("https://localhost/owner/api/invitations") { ownerMutation(); json("{\"synthetic\":true}") }
        assertEquals(HttpStatusCode.Created, invitation.status)
        assertEquals(1, owners.invitations); assertEquals("f".repeat(32), owners.actor)
    }

    @Test
    fun `owner publication requires manual reviewed confirmation and an exact version token`() = testApplication {
        val owners = Owners()
        application { ownerApi(config, owners, clock) }
        for (reviewed in listOf(JsonPrimitive(false), JsonPrimitive("true"), JsonNull)) {
            val response = client.post("https://localhost/owner/api/reviews") { ownerMutation(); json(review(reviewed = reviewed)) }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(JsonPrimitive("review_required"), Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject["error"])
        }
        for (version in listOf(JsonPrimitive(0), JsonPrimitive(""), JsonPrimitive("A".repeat(64)), JsonNull)) {
            val response = client.post("https://localhost/owner/api/reviews") { ownerMutation(); json(review(version)) }
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            assertEquals(JsonPrimitive("invalid_review_version"), Contract.json.parseToJsonElement(response.bodyAsText()).jsonObject["error"])
        }
        assertEquals(0, owners.reviews)
        val published = client.post("https://localhost/owner/api/reviews") { ownerMutation(); json(review()) }
        assertEquals(HttpStatusCode.OK, published.status)
        assertEquals("unpublished", owners.expected); assertEquals("f".repeat(32), owners.actor)
        val stale = client.post("https://localhost/owner/api/reviews") { ownerMutation(); json(review(JsonPrimitive("a".repeat(64)))) }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("{\"error\":\"review_conflict\"}", stale.bodyAsText())
    }

    @Test
    fun `owner logout revokes only owner session and expires its cookie`() = testApplication {
        val owners = Owners(); val clients = Clients()
        application { ownerApi(config, owners, clock); clientApi(config, clients, clock) }
        val logout = client.delete("https://localhost/owner/api/session") { ownerMutation() }
        assertEquals(HttpStatusCode.OK, logout.status)
        assertEquals("{\"authenticated\":false}", logout.bodyAsText())
        val cookie = logout.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(cookie.startsWith("__Host-rr_owner=")); assertTrue(cookie.contains("Max-Age=0")); assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("Secure"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("https://localhost/owner/api/cases") { ownerCookie() }.status)
        assertEquals(HttpStatusCode.OK, client.get("https://localhost/client/session") { clientCookie() }.status)
        assertEquals(1, owners.revocations)
    }

    @Test
    fun `owner database failures never disclose credentials or review narratives`() = testApplication {
        val owners = Owners().apply { fail = IllegalStateException("secret access_key and unpublished personal review") }
        application { ownerApi(config, owners, clock) }
        val response = client.get("https://localhost/owner/api/cases") { ownerCookie() }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"error\":\"service_unavailable\"}", response.bodyAsText())
    }
}

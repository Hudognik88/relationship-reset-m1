package ru.poslesorry.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant

private const val OWNER_COOKIE = "__Host-rr_owner"
private val OWNER_TOKEN = Regex("[A-Za-z0-9_-]{43}")

/** Owner browser requests use only a dedicated short-lived cookie, never the operator bearer. */
fun Application.ownerApi(config: AppConfig, store: OwnerStore, clock: Clock = Clock.systemUTC()) {
    val limits = ClientRateLimits()
    routing {
        for (path in listOf("/owner/api/session", "/owner/api/cases", "/owner/api/invitations", "/owner/api/reviews")) {
            route(path) { handle { call.clientSafely { call.ownerDispatch(path, config, store, clock.instant(), limits) } } }
        }
        route("/owner/api/cases/{id}") {
            handle { call.clientSafely { call.ownerDispatch("/owner/api/cases/" + call.parameters["id"].orEmpty(), config, store, clock.instant(), limits) } }
        }
    }
}

private suspend fun ApplicationCall.ownerDispatch(path: String, config: AppConfig, store: OwnerStore, now: Instant, limits: ClientRateLimits): ApiResult {
    if (!transportAllowed(config, request.local.remoteAddress, request.local.scheme, request.headers.getAll("X-Forwarded-Proto"))) throw ApiProblem(400, "https_required")
    if (!request.queryParameters.isEmpty()) throw ApiProblem(404, "not_found")
    val method = request.httpMethod
    val allowed = when {
        path == "/owner/api/session" -> setOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete)
        path == "/owner/api/cases" || path.startsWith("/owner/api/cases/") -> setOf(HttpMethod.Get)
        path == "/owner/api/invitations" || path == "/owner/api/reviews" -> setOf(HttpMethod.Post)
        else -> throw ApiProblem(404, "not_found")
    }
    if (method !in allowed) { response.headers.append(HttpHeaders.Allow, allowed.joinToString(", ") { it.value }); throw ApiProblem(405, "method_not_allowed") }
    if (method != HttpMethod.Get && request.headers.getAll(HttpHeaders.Origin) != listOf(config.clientOrigin)) throw ApiProblem(403, "origin_forbidden")
    val cookie = ownerCookie()
    if (path == "/owner/api/session" && method == HttpMethod.Post) {
        if (!limits.accept(true, now)) { response.headers.append(HttpHeaders.RetryAfter, "60"); throw ApiProblem(429, "rate_limited") }
        val body = ClientContract.exact(limitedJson(), setOf("invitation", "synthetic"))
        val invitation = (body["invitation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (invitation == null || !OWNER_TOKEN.matches(invitation)) throw ApiProblem(401, "owner_invitation_invalid")
        val issued = withContext(Dispatchers.IO) {
            if (!store.ready()) throw ApiProblem(503, "service_unavailable")
            if (cookie != null && store.session(cookie, now) != null) throw ApiProblem(409, "session_exists")
            store.exchangeInvitation(invitation, now)
        }
        response.headers.append(HttpHeaders.SetCookie, "$OWNER_COOKIE=${issued.token}; Path=/; Max-Age=3600; Secure; HttpOnly; SameSite=Strict")
        return ApiResult(201, ownerSessionBody(issued.token, issued.expiresAt))
    }
    if (cookie == null) throw ApiProblem(401, "unauthorized")
    val session = withContext(Dispatchers.IO) { store.session(cookie, now) } ?: throw ApiProblem(401, "unauthorized")
    if (!limits.accept(false, now)) { response.headers.append(HttpHeaders.RetryAfter, "60"); throw ApiProblem(429, "rate_limited") }
    if (method != HttpMethod.Get) {
        val submitted = request.headers.getAll("X-CSRF-Token")?.singleOrNull()
        if (submitted == null || !Regex("[a-f0-9]{64}").matches(submitted) || !sameHash(submitted, ownerCsrf(cookie))) throw ApiProblem(403, "csrf_invalid")
    }
    val body = if (method == HttpMethod.Post) limitedJson() else null
    val review = if (path == "/owner/api/reviews") {
        val obj = ClientContract.exact(body!!, setOf("case_id", "text", "synthetic", "reviewed", "expected_version"))
        if (obj["reviewed"] != JsonPrimitive(true)) throw ApiProblem(422, "review_required")
        val caseId = (obj["case_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (caseId == null || !Regex("[a-f0-9]{32}").matches(caseId)) throw ApiProblem(422, "invalid_case_id")
        val version = (obj["expected_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version == null || (version != "unpublished" && !Regex("[a-f0-9]{64}").matches(version))) throw ApiProblem(422, "invalid_review_version")
        val text = Contract.draftPayload(buildJsonObject {
            put("synthetic", true); put("client_request_id", "00000000-0000-4000-8000-000000000000"); put("text", obj.getValue("text"))
        }).getValue("text").jsonPrimitive.content
        Triple(caseId, text, version)
    } else null
    if (path == "/owner/api/invitations") ClientContract.exact(body!!, setOf("synthetic"))
    val requestedId = if (path.startsWith("/owner/api/cases/")) path.removePrefix("/owner/api/cases/").also {
        if (!Regex("[a-f0-9]{32}").matches(it)) throw ApiProblem(422, "invalid_case_id")
    } else null
    return withContext(Dispatchers.IO) {
        if (!store.ready()) throw ApiProblem(503, "service_unavailable")
        when {
            path == "/owner/api/session" && method == HttpMethod.Get -> ApiResult(200, ownerSessionBody(cookie, session.expiresAt))
            path == "/owner/api/session" -> {
                store.revokeSession(session.id, now)
                response.headers.append(HttpHeaders.SetCookie, "$OWNER_COOKIE=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
                ApiResult(200, buildJsonObject { put("authenticated", false) })
            }
            path == "/owner/api/cases" -> ApiResult(200, store.listCases(session.id, now))
            requestedId != null -> ApiResult(200, store.getCase(session.id, requestedId, now))
            path == "/owner/api/invitations" -> {
                val issued = store.issueClientInvitation(session.id, now)
                ApiResult(201, buildJsonObject { put("invitation", issued.token); put("expires_at", issued.expiresAt.toString()) })
            }
            else -> ApiResult(200, store.publishReview(session.id, review!!.first, review.second, review.third, now))
        }
    }
}

private fun ApplicationCall.ownerCookie(): String? {
    val values = request.headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { it.split(';') }.map { it.trim() }
        .filter { it.substringBefore('=') == OWNER_COOKIE }.map { it.substringAfter('=', "") }
    if (values.isEmpty()) return null
    if (values.size != 1 || !OWNER_TOKEN.matches(values.single())) throw ApiProblem(401, "unauthorized")
    return values.single()
}

private fun ownerCsrf(token: String) = Contract.sha256("owner-csrf:" + token)
private fun ownerSessionBody(token: String, expires: Instant) = buildJsonObject { put("authenticated", true); put("csrf_token", ownerCsrf(token)); put("expires_at", expires.toString()) }

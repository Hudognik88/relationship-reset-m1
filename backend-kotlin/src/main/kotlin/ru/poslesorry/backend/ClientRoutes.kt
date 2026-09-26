package ru.poslesorry.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

internal object ClientContract {
    private val safetyPattern = Regex("""угро[зж]|насили|изби[лтв]|удари[лт]|бьет|(?:^|[^а-я])бил[аи]? (меня|его|ее|нас|ребенка)|побо[иe]|души[лт]|запира|не выпуска|преслед|принужд|застав.{0,30}(секс|спать)|контрол.{0,30}(деньг|телефон|общени)|забрал.{0,25}(деньг|паспорт|телефон)|страшно.{0,25}(дом|рядом|возвращ)|боюсь.{0,25}(его|ее|за себя|за жизнь)|суицид|самоповрежд|покончи.{0,10}с собой|убью|убить|не хочу жить|threat|violence|stalk|coerc|forced.{0,20}sex|hit me|hurt me|kill|suicid|self.harm|afraid of (him|her)|locked me""", RegexOption.IGNORE_CASE)
    fun casePayload(value: JsonElement): JsonObject {
        val result = Contract.casePayload(value)
        val data = result.getValue("questionnaire").jsonObject
        if (data.getValue("age").jsonPrimitive.content != "adult") throw ApiProblem(422, "adult_required")
        val text = listOf("situation", "success", "failure").joinToString(" ") { data.getValue(it).jsonPrimitive.content }.lowercase().replace('ё', 'е')
        if (data.getValue("safety").jsonPrimitive.content != "no" || safetyPattern.containsMatchIn(text)) throw ApiProblem(422, "safety_not_supported")
        return result
    }

    fun exact(value: JsonElement, fields: Set<String>): JsonObject {
        val obj = value as? JsonObject ?: throw ApiProblem(422, "invalid_payload")
        if (obj.keys != fields) throw ApiProblem(422, "invalid_fields")
        if (obj["synthetic"] != JsonPrimitive(true)) throw ApiProblem(422, "synthetic_case_required")
        return obj
    }
}

/** Two fixed-size counters bound invite exchange and authenticated browser traffic. */
private class ClientRateLimits {
    private data class Window(var start: Long = Long.MIN_VALUE, var count: Int = 0)
    private val invitations = Window()
    private val requests = Window()
    @Synchronized fun accept(invitation: Boolean, now: Instant): Boolean {
        val window = if (invitation) invitations else requests
        val second = now.epochSecond
        if (window.start == Long.MIN_VALUE || second < window.start || second - window.start >= 60) { window.start = second; window.count = 0 }
        if (window.count >= if (invitation) 30 else 60) return false
        window.count++
        return true
    }
}

private const val COOKIE = "__Host-rr_client"
private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
private val CASE_ID = Regex("[a-f0-9]{32}")

fun Application.clientApi(config: AppConfig, store: ClientStore, clock: Clock = Clock.systemUTC()) {
    val limits = ClientRateLimits()
    routing {
        for (path in listOf("/client/session", "/client/case", "/client/operator/invitations", "/client/operator/reviews", "/client/operator/cases")) {
            route(path) { handle { call.clientSafely { call.clientDispatch(path, config, store, clock.instant(), limits) } } }
        }
        route("/client/operator/cases/{id}") {
            handle { call.clientSafely { call.clientDispatch("/client/operator/cases/" + call.parameters["id"].orEmpty(), config, store, clock.instant(), limits) } }
        }
        route("/rehearsal") { handle { call.rehearsalHeaders(); call.respondRedirect("/rehearsal/", permanent = false) } }
        for ((path, resource, type) in listOf(
            Triple("/rehearsal/", "rehearsal/index.html", ContentType.Text.Html),
            Triple("/rehearsal/app.js", "rehearsal/app.js", ContentType.Application.JavaScript),
            Triple("/rehearsal/style.css", "rehearsal/style.css", ContentType.Text.CSS))) {
            route(path) { handle {
                call.rehearsalHeaders()
                if (call.request.httpMethod !in setOf(HttpMethod.Get, HttpMethod.Head)) {
                    call.response.headers.append(HttpHeaders.Allow, "GET, HEAD")
                    call.respondText("Method not allowed", status = HttpStatusCode.MethodNotAllowed)
                } else {
                    val bytes = ClientContract::class.java.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
                    if (bytes == null) call.respondText("Not found", status = HttpStatusCode.NotFound)
                    else call.respondBytes(bytes, type.withCharset(Charsets.UTF_8))
                }
            } }
        }
    }
}

private fun ApplicationCall.rehearsalHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Robots-Tag", "noindex, nofollow")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; connect-src 'self'")
}

private suspend fun ApplicationCall.clientSafely(block: suspend () -> ApiResult) {
    rehearsalHeaders()
    val result = try { block() }
    catch (error: ApiProblem) { ApiResult(error.status, buildJsonObject { put("error", error.code) }) }
    catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (_: Exception) { ApiResult(503, buildJsonObject { put("error", "service_unavailable") }) }
    respondText(Contract.canonicalJson(result.body), ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(result.status))
}

private suspend fun ApplicationCall.clientDispatch(path: String, config: AppConfig, store: ClientStore, now: Instant, limits: ClientRateLimits): ApiResult {
    if (!transportAllowed(config, request.local.remoteAddress, request.local.scheme, request.headers.getAll("X-Forwarded-Proto"))) throw ApiProblem(400, "https_required")
    if (!request.queryParameters.isEmpty()) throw ApiProblem(404, "not_found")
    val operator = path.startsWith("/client/operator/")
    val method = request.httpMethod
    val allowed = when {
        path == "/client/session" -> setOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete)
        path == "/client/case" -> setOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Delete)
        path == "/client/operator/invitations" || path == "/client/operator/reviews" -> setOf(HttpMethod.Post)
        path == "/client/operator/cases" || path.startsWith("/client/operator/cases/") -> setOf(HttpMethod.Get)
        else -> throw ApiProblem(404, "not_found")
    }
    if (method !in allowed) { response.headers.append(HttpHeaders.Allow, allowed.joinToString(", ") { it.value }); throw ApiProblem(405, "method_not_allowed") }
    if (operator) {
        val bearer = request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()?.let { Regex("Bearer ([A-Za-z0-9_-]{43,128})").matchEntire(it)?.groupValues?.get(1) }
        if (bearer == null || !sameHash(Contract.sha256(bearer), config.operatorHash)) throw ApiProblem(401, "unauthorized")
        return operatorDispatch(path, store, now)
    }
    if (method != HttpMethod.Get && request.headers.getAll(HttpHeaders.Origin) != listOf(config.clientOrigin)) throw ApiProblem(403, "origin_forbidden")
    val cookie = cookieToken()
    if (path == "/client/session" && method == HttpMethod.Post) {
        if (!limits.accept(true, now)) { response.headers.append(HttpHeaders.RetryAfter, "60"); throw ApiProblem(429, "rate_limited") }
        val body = ClientContract.exact(limitedJson(), setOf("invitation", "synthetic"))
        val invitation = (body["invitation"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (invitation == null || !TOKEN.matches(invitation)) throw ApiProblem(401, "invitation_invalid")
        val issued = withContext(Dispatchers.IO) {
            if (!store.ready()) throw ApiProblem(503, "service_unavailable")
            if (cookie != null && store.session(cookie, now) != null) throw ApiProblem(409, "session_exists")
            store.exchangeInvitation(invitation, now)
        }
        response.headers.append(HttpHeaders.SetCookie, "$COOKIE=${issued.token}; Path=/; Max-Age=86400; Secure; HttpOnly; SameSite=Strict")
        return ApiResult(201, sessionBody(issued.token, issued.expiresAt))
    }
    if (cookie == null) throw ApiProblem(401, "unauthorized")
    val session = withContext(Dispatchers.IO) { store.session(cookie, now) } ?: throw ApiProblem(401, "unauthorized")
    if (!limits.accept(false, now)) { response.headers.append(HttpHeaders.RetryAfter, "60"); throw ApiProblem(429, "rate_limited") }
    if (method != HttpMethod.Get) {
        val submitted = request.headers.getAll("X-CSRF-Token")?.singleOrNull()
        if (submitted == null || !Regex("[a-f0-9]{64}").matches(submitted) || !MessageDigest.isEqual(submitted.toByteArray(), csrf(cookie).toByteArray())) throw ApiProblem(403, "csrf_invalid")
    }
    val payload = if (path == "/client/case" && method == HttpMethod.Post) ClientContract.casePayload(limitedJson()) else null
    return withContext(Dispatchers.IO) {
        if (!store.ready()) throw ApiProblem(503, "service_unavailable")
        when {
            path == "/client/session" && method == HttpMethod.Get -> ApiResult(200, sessionBody(cookie, session.expiresAt))
            path == "/client/session" -> {
                store.revokeSession(session.id, now)
                response.headers.append(HttpHeaders.SetCookie, "$COOKIE=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict")
                ApiResult(200, buildJsonObject { put("authenticated", false) })
            }
            method == HttpMethod.Post -> store.createCase(session.id, payload!!, now)
            method == HttpMethod.Delete -> { store.deleteCase(session.id, now); ApiResult(200, buildJsonObject { put("deleted", true) }) }
            else -> ApiResult(200, store.getCase(session.id, now))
        }
    }
}

private suspend fun ApplicationCall.operatorDispatch(path: String, store: ClientStore, now: Instant): ApiResult {
    val body = if (request.httpMethod == HttpMethod.Post) limitedJson() else null
    val obj = when (path) {
        "/client/operator/invitations" -> ClientContract.exact(body!!, setOf("synthetic"))
        "/client/operator/reviews" -> ClientContract.exact(body!!, setOf("case_id", "text", "synthetic"))
        else -> null
    }
    val caseId = when {
        path == "/client/operator/reviews" -> (obj!!["case_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        path.startsWith("/client/operator/cases/") -> path.removePrefix("/client/operator/cases/")
        else -> null
    }
    if ((path == "/client/operator/reviews" || path.startsWith("/client/operator/cases/")) && (caseId == null || !CASE_ID.matches(caseId))) throw ApiProblem(422, "invalid_case_id")
    val review = if (path == "/client/operator/reviews") Contract.draftPayload(buildJsonObject {
        put("synthetic", true); put("client_request_id", "00000000-0000-4000-8000-000000000000"); put("text", obj!!.getValue("text"))
    }).getValue("text").jsonPrimitive.content else null
    return withContext(Dispatchers.IO) {
        if (!store.ready()) throw ApiProblem(503, "service_unavailable")
        when (path) {
            "/client/operator/invitations" -> {
                val issued = store.issueInvitation(now)
                ApiResult(201, buildJsonObject { put("invitation", issued.token); put("expires_at", issued.expiresAt.toString()) })
            }
            "/client/operator/reviews" -> ApiResult(200, store.publishReview(caseId!!, review!!, now))
            "/client/operator/cases" -> ApiResult(200, store.listCases())
            else -> ApiResult(200, store.operatorCase(caseId!!))
        }
    }
}

private fun ApplicationCall.cookieToken(): String? {
    val values = request.headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { it.split(';') }.map { it.trim() }
        .filter { it.substringBefore('=') == COOKIE }.map { it.substringAfter('=', "") }
    if (values.isEmpty()) return null
    if (values.size != 1 || !TOKEN.matches(values.single())) throw ApiProblem(401, "unauthorized")
    return values.single()
}

private fun csrf(token: String) = Contract.sha256("csrf:" + token)
private fun sessionBody(token: String, expires: Instant) = buildJsonObject { put("authenticated", true); put("csrf_token", csrf(token)); put("expires_at", expires.toString()) }

private suspend fun ApplicationCall.limitedJson(): JsonElement {
    val type = request.headers[HttpHeaders.ContentType].orEmpty()
    if (!Regex("application/json(?:\\s*;\\s*charset=utf-8)?", RegexOption.IGNORE_CASE).matches(type)) throw ApiProblem(415, "json_required")
    if ((request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > Contract.MAX_BODY_BYTES) throw ApiProblem(413, "payload_too_large")
    val bytes = ByteArrayOutputStream()
    val channel = receiveChannel()
    val buffer = ByteArray(4096)
    while (true) {
        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, Contract.MAX_BODY_BYTES + 1 - bytes.size()))
        if (count == -1) break
        if (count == 0) continue
        bytes.write(buffer, 0, count)
        if (bytes.size() > Contract.MAX_BODY_BYTES) throw ApiProblem(413, "payload_too_large")
    }
    return Contract.decode(bytes.toByteArray())
}

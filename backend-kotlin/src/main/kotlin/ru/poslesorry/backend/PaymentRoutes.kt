package ru.poslesorry.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant

/** Payment callbacks authenticate independently from browser sessions. */
fun Application.paymentApi(config: AppConfig, clients: ClientStore, payments: PaymentStore, clock: Clock = Clock.systemUTC()) {
    val clientLimits = ClientRateLimits()
    val webhookLimits = ClientRateLimits()
    routing {
        route("/client/orders") {
            handle { call.clientSafely { call.paymentOrder(config, clients, payments, clock.instant(), clientLimits) } }
        }
        route("/payments/prodamus/webhook") {
            handle { call.clientSafely { call.paymentNotification(config, payments, clock.instant(), webhookLimits) } }
        }
    }
}

private fun ApplicationCall.paymentTransport(config: AppConfig) {
    if (!transportAllowed(config, request.local.remoteAddress, request.local.scheme, request.headers.getAll("X-Forwarded-Proto"))) throw ApiProblem(400, "https_required")
    if (!request.queryParameters.isEmpty()) throw ApiProblem(404, "not_found")
}

private suspend fun ApplicationCall.paymentOrder(config: AppConfig, clients: ClientStore, payments: PaymentStore, now: Instant, limits: ClientRateLimits): ApiResult {
    paymentTransport(config)
    val method = request.httpMethod
    if (method !in setOf(HttpMethod.Get, HttpMethod.Post)) {
        response.headers.append(HttpHeaders.Allow, "GET, POST")
        throw ApiProblem(405, "method_not_allowed")
    }
    if (method == HttpMethod.Post && request.headers.getAll(HttpHeaders.Origin) != listOf(config.clientOrigin)) throw ApiProblem(403, "origin_forbidden")
    val token = cookieToken() ?: throw ApiProblem(401, "unauthorized")
    val session = withContext(Dispatchers.IO) { clients.session(token, now) } ?: throw ApiProblem(401, "unauthorized")
    if (!limits.accept(false, now)) {
        response.headers.append(HttpHeaders.RetryAfter, "60")
        throw ApiProblem(429, "rate_limited")
    }
    if (method == HttpMethod.Post) {
        val submitted = request.headers.getAll("X-CSRF-Token")?.singleOrNull()
        if (submitted == null || !sameHash(submitted, csrf(token))) throw ApiProblem(403, "csrf_invalid")
    }
    val settings = config.payments
    if (settings == null) {
        if (method == HttpMethod.Post) throw ApiProblem(503, "payments_disabled")
        return ApiResult(200, buildJsonObject {
            put("enabled", false); put("order", JsonNull); put("entitlement", JsonNull); put("checkout_url", JsonNull)
        })
    }
    val body = if (method == HttpMethod.Post) {
        ClientContract.exact(limitedJson(), setOf("synthetic", "product", "client_request_id", "case_id")).also { value ->
            fun field(key: String) = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (field("product") != "pilot_7d") throw ApiProblem(422, "invalid_product")
            if (field("client_request_id")?.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) != true) throw ApiProblem(422, "invalid_request_id")
            if (field("case_id")?.matches(Regex("[a-f0-9]{32}")) != true) throw ApiProblem(422, "invalid_case_id")
        }
    } else null
    val result = withContext(Dispatchers.IO) {
        if (!payments.ready()) throw ApiProblem(503, "service_unavailable")
        if (body != null) payments.createOrder(session.id, body.getValue("client_request_id").jsonPrimitive.content, body.getValue("case_id").jsonPrimitive.content, now)
        else ApiResult(200, payments.order(session.id, now))
    }
    val order = result.body["order"] as? JsonObject
    val checkout = if (order?.get("status") == JsonPrimitive("pending") && order["checkout_available"] == JsonPrimitive(true))
        ProdamusContract.checkoutUrl(order.getValue("id").jsonPrimitive.content, settings.payformUrl, settings.secret, settings.sys) else null
    return ApiResult(result.status, JsonObject(result.body + mapOf("enabled" to JsonPrimitive(true), "checkout_url" to (checkout?.let(::JsonPrimitive) ?: JsonNull))))
}

private suspend fun ApplicationCall.paymentNotification(config: AppConfig, payments: PaymentStore, now: Instant, limits: ClientRateLimits): ApiResult {
    paymentTransport(config)
    if (request.httpMethod != HttpMethod.Post) {
        response.headers.append(HttpHeaders.Allow, "POST")
        throw ApiProblem(405, "method_not_allowed")
    }
    val settings = config.payments ?: throw ApiProblem(503, "payments_disabled")
    val sign = request.headers.getAll("Sign")?.singleOrNull() ?: throw ApiProblem(401, "signature_invalid")
    val type = request.headers.getAll(HttpHeaders.ContentType)?.singleOrNull() ?: throw ApiProblem(415, "form_required")
    if (type.substringBefore(';').trim().lowercase() !in setOf("application/x-www-form-urlencoded", "multipart/form-data")) throw ApiProblem(415, "form_required")
    val bytes = paymentBody()
    val notice = try {
        val data = ProdamusContract.parse(type, bytes)
        if (!ProdamusContract.verify(data, settings.secret, sign)) throw ApiProblem(401, "signature_invalid")
        ProdamusContract.notification(data)
    } catch (_: IllegalArgumentException) {
        throw ApiProblem(400, "invalid_notification")
    }
    // There is no live entitlement in this release, even if a real callback is received.
    if (!notice.demoMode) throw ApiProblem(422, "demo_notification_required")
    // Unauthenticated traffic cannot consume the valid delivery budget.
    if (!limits.accept(false, now)) {
        response.headers.append(HttpHeaders.RetryAfter, "60")
        throw ApiProblem(429, "rate_limited")
    }
    withContext(Dispatchers.IO) {
        if (!payments.ready()) throw ApiProblem(503, "service_unavailable")
        payments.accept(notice, now)
    }
    // Acknowledge only after the transaction committed; delivery attempts may repeat.
    return ApiResult(200, buildJsonObject { put("accepted", true); put("mode", "demo") })
}

private suspend fun ApplicationCall.paymentBody(): ByteArray {
    val lengths = request.headers.getAll(HttpHeaders.ContentLength)
    if (lengths != null) {
        val length = lengths.singleOrNull()?.toLongOrNull() ?: throw ApiProblem(400, "invalid_payload")
        if (length < 0) throw ApiProblem(400, "invalid_payload")
        if (length > ProdamusContract.MAX_BODY_BYTES) throw ApiProblem(413, "payload_too_large")
    }
    val output = ByteArrayOutputStream()
    val channel = receiveChannel()
    val buffer = ByteArray(4096)
    while (true) {
        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, ProdamusContract.MAX_BODY_BYTES + 1 - output.size()))
        if (count == -1) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        if (output.size() > ProdamusContract.MAX_BODY_BYTES) throw ApiProblem(413, "payload_too_large")
    }
    return output.toByteArray()
}

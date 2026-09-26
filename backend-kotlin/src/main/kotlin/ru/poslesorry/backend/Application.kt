package ru.poslesorry.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.isEmpty() || args.contentEquals(arrayOf("migrate")))
        val config = AppConfig.fromEnvironment()
        val ds = dataSource(config.database)
        if (args.isNotEmpty()) {
            ds.use { Migrations(it).run() }
            println("Schema ready.")
        } else {
            val server = embeddedServer(Netty, host = config.host, port = config.port) {
                api(config, JdbcCaseStore(ds))
                monitor.subscribe(ApplicationStopped) { ds.close() }
            }
            server.start(wait = true)
        }
    } catch (_: Exception) {
        // Credentials, submitted text, database messages and stack traces stay out of logs.
        System.err.println("Service failed. Check private configuration, connectivity and schema.")
        exitProcess(1)
    }
}

fun Application.api(config: AppConfig, store: CaseStore) {
    routing {
        route("/api/health.php") {
            handle {
                call.safely {
                    if (call.request.httpMethod != HttpMethod.Get) {
                        call.response.headers.append(HttpHeaders.Allow, "GET")
                        throw ApiProblem(405, "method_not_allowed")
                    }
                    ApiResult(200, buildJsonObject {
                        put("status", "ok"); put("mode", "staging")
                        config.release?.let { put("release", it) }
                    })
                }
            }
        }
        route("/api/index.php") {
            handle { call.safely { call.dispatch(config, store) } }
        }
    }
}

private suspend fun ApplicationCall.safely(block: suspend () -> ApiResult) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Robots-Tag", "noindex, nofollow")
    val result = try { block() }
    catch (e: ApiProblem) { ApiResult(e.status, buildJsonObject { put("error", e.code) }) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { ApiResult(503, buildJsonObject { put("error", "service_unavailable") }) }
    respondText(Contract.canonicalJson(result.body), ContentType.Application.Json.withCharset(Charsets.UTF_8), HttpStatusCode.fromValue(result.status))
}

internal fun transportAllowed(config: AppConfig, peer: String, scheme: String, forwardedProto: List<String>?): Boolean =
    scheme == "https" ||
        (config.allowLoopbackHttp && peer in setOf("127.0.0.1", "::1")) ||
        (peer in config.trustedProxyIps && forwardedProto == listOf("https"))

private suspend fun ApplicationCall.dispatch(config: AppConfig, store: CaseStore): ApiResult {
    // Do not install ForwardedHeaders: only the actual transport peer can be a trusted proxy.
    if (!transportAllowed(config, request.local.remoteAddress, request.local.scheme, request.headers.getAll("X-Forwarded-Proto"))) {
        throw ApiProblem(400, "https_required")
    }
    val headers = request.headers.getAll(HttpHeaders.Authorization)
    val token = headers?.singleOrNull()?.let { Regex("Bearer ([A-Za-z0-9_-]{43,128})").matchEntire(it)?.groupValues?.get(1) }
    if (token == null || !MessageDigest.isEqual(Contract.sha256(token).toByteArray(), config.operatorHash.toByteArray())) {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        throw ApiProblem(401, "unauthorized")
    }
    val query = request.queryParameters
    val route = query.getAll("route")?.singleOrNull()
    if (query.names() != setOf("route") || route == null) throw ApiProblem(404, "not_found")
    val match = Regex("/cases/([a-f0-9]{32})(/drafts)?").matchEntire(route)
    val action = when {
        route == "/ready" -> "ready"
        route == "/cases" -> "create"
        match != null && match.groupValues[2].isNotEmpty() -> "draft"
        match != null -> "get"
        else -> throw ApiProblem(404, "not_found")
    }
    val method = if (action in setOf("create", "draft")) HttpMethod.Post else HttpMethod.Get
    if (request.httpMethod != method) {
        response.headers.append(HttpHeaders.Allow, method.value)
        throw ApiProblem(405, "method_not_allowed")
    }
    val payload = if (method == HttpMethod.Post) {
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
        val body = Contract.decode(bytes.toByteArray())
        if (action == "create") Contract.casePayload(body) else Contract.draftPayload(body)
    } else null
    return withContext(Dispatchers.IO) {
        if (!store.ready()) throw ApiProblem(503, "service_unavailable")
        when (action) {
            "ready" -> ApiResult(200, buildJsonObject { put("status", "ready"); put("mode", "staging") })
            "create" -> store.createCase(payload!!)
            "get" -> ApiResult(200, store.getCase(match!!.groupValues[1]))
            else -> store.createDraft(match!!.groupValues[1], payload!!)
        }
    }
}

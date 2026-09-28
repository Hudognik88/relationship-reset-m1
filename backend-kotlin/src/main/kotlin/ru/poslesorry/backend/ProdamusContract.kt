package ru.poslesorry.backend

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ProdamusNotification(
    val merchantOrderId: String,
    val providerOrderId: String,
    val merchantDomain: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val demoMode: Boolean,
)

/** Form-only, bounded implementation of the Prodamus POST/Sign contract.
 * JSON callbacks are deliberately unsupported until their wire contract is verified.
 * All parsed leaves are strings, as in PHP $_POST; query parameters never enter here.
 */
object ProdamusContract {
    const val MAX_BODY_BYTES = 65_536
    private const val MAX_FIELDS = 256
    private const val MAX_VALUE_BYTES = 8_192
    private const val MAX_DEPTH = 8
    private const val MAX_ARRAY_SIZE = 64
    private const val MERCHANT_DOMAIN = "relationshipreset.payform.ru"
    private const val RETURN_URL = "https://api-staging.poslessory.ru/rehearsal/"
    private const val WEBHOOK_URL = "https://api-staging.poslessory.ru/payments/prodamus/webhook"
    private val orderPattern = Regex("rrstg_[a-f0-9]{32}")
    private val segmentPattern = Regex("(?:[A-Za-z_][A-Za-z0-9_-]{0,63}|0|[1-9][0-9]{0,2})")
    private val rootPattern = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
    private val parameterPattern = Regex("([A-Za-z][A-Za-z0-9_-]*)=(?:\"([^\"]*)\"|([A-Za-z0-9!#$%&'*+.^_`|~-]+))")

    fun parse(contentType: String, bytes: ByteArray): JsonObject {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BODY_BYTES) { "invalid_body_size" }
        val media = mediaType(contentType)
        val fields = when (media.first) {
            "application/x-www-form-urlencoded" -> {
                require(media.second.keys.all { it == "charset" }) { "unsupported_content_type" }
                val text = utf8(bytes)
                require(text.count { it == '&' } < MAX_FIELDS) { "too_many_fields" }
                text.split('&').map { field ->
                    require(field.isNotEmpty() && '=' in field) { "invalid_form_field" }
                    val at = field.indexOf('=')
                    decodeForm(field.substring(0, at)) to decodeForm(field.substring(at + 1))
                }
            }
            "multipart/form-data" -> {
                require(media.second.keys.all { it == "charset" || it == "boundary" }) { "unsupported_content_type" }
                multipart(bytes, media.second["boundary"] ?: throw IllegalArgumentException("missing_boundary"))
            }
            else -> throw IllegalArgumentException("unsupported_content_type")
        }
        require(fields.isNotEmpty() && fields.size <= MAX_FIELDS) { "invalid_field_count" }
        val root = Branch()
        for ((name, value) in fields) {
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_VALUE_BYTES) { "value_too_long" }
            val path = path(name)
            require(!path.first().equals("signature", ignoreCase = true)) { "body_signature_forbidden" }
            insert(root, path, value)
        }
        return objectValue(root, 0)
    }

    /** String leaves are required: a form value "1.0" must not become number 1. */
    fun signature(data: JsonObject, secret: String): String {
        require(secret.isNotEmpty() && secret.length <= 4_096) { "invalid_secret" }
        require(data.keys.none { it.equals("signature", ignoreCase = true) }) { "body_signature_forbidden" }
        val json = canonical(data, 0)
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_BODY_BYTES * 6) { "canonical_body_too_large" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(json.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun verify(data: JsonObject, secret: String, sign: String): Boolean {
        if (!Regex("[a-fA-F0-9]{64}").matches(sign)) return false
        return try {
            MessageDigest.isEqual(
                signature(data, secret).toByteArray(StandardCharsets.US_ASCII),
                sign.lowercase(Locale.ROOT).toByteArray(StandardCharsets.US_ASCII),
            )
        } catch (_: IllegalArgumentException) { false }
    }

    fun notification(data: JsonObject): ProdamusNotification {
        fun field(name: String): String {
            val value = data[name] as? JsonPrimitive
            require(value != null && value.isString) { "invalid_$name" }
            return value.content
        }
        val merchantOrder = field("order_num")
        require(orderPattern.matches(merchantOrder)) { "invalid_order_num" }
        val providerOrder = field("order_id")
        require(Regex("[1-9][0-9]{0,19}").matches(providerOrder)) { "invalid_order_id" }
        val domain = field("domain")
        require(domain == MERCHANT_DOMAIN) { "invalid_domain" }
        val amount = field("sum")
        require(Regex("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,2})?").matches(amount)) { "invalid_sum" }
        val minor = BigDecimal(amount).movePointRight(2).longValueExact()
        require(minor > 0) { "invalid_sum" }
        val currency = field("currency").uppercase(Locale.ROOT)
        require(currency == "RUB") { "invalid_currency" }
        val status = field("payment_status")
        require(status in setOf("success", "order_canceled", "order_denied")) { "invalid_payment_status" }
        val demo = if ("demo_mode" in data) field("demo_mode") else "0"
        require(demo == "0" || demo == "1") { "invalid_demo_mode" }
        return ProdamusNotification(merchantOrder, providerOrder, domain, minor, currency, status, demo == "1")
    }

    /** No network call, contacts or case text: only a signed demo checkout URL. */
    fun checkoutUrl(orderId: String, payformUrl: String, secret: String, sys: String? = null): String {
        require(orderPattern.matches(orderId)) { "invalid_order_id" }
        val uri = try { URI(payformUrl) } catch (_: Exception) { throw IllegalArgumentException("invalid_payform_url") }
        require(uri.scheme == "https" && uri.host == MERCHANT_DOMAIN && uri.port == -1 && uri.userInfo == null &&
            (uri.rawPath == "/" || uri.rawPath.isNullOrEmpty()) && uri.rawQuery == null && uri.rawFragment == null) { "invalid_payform_url" }
        require(sys == null || Regex("[A-Za-z0-9_-]{1,64}").matches(sys)) { "invalid_sys" }
        val data = buildJsonObject {
            put("do", "pay")
            put("order_id", orderId)
            put("currency", "rub")
            put("demo_mode", "1")
            put("installments_disabled", "1")
            put("payments_limit", "1")
            put("urlSuccess", RETURN_URL)
            put("urlReturn", RETURN_URL)
            put("products", buildJsonArray { add(buildJsonObject {
                put("name", "Тестовый письменный разбор «После ссоры»: 7 дней")
                put("price", "990.00")
                put("quantity", "1")
                put("type", "service")
            }) })
            if (sys != null) { put("sys", sys); put("urlNotification", WEBHOOK_URL) }
        }
        val fields = mutableListOf<Pair<String, String>>()
        flatten(data, "", fields)
        fields += "signature" to signature(data, secret)
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        return "https://$MERCHANT_DOMAIN/?" + fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }

    private fun mediaType(value: String): Pair<String, Map<String, String>> {
        require(value.length in 1..512 && value.all { it.code in 32..126 }) { "invalid_content_type" }
        val pieces = value.split(';').map(String::trim)
        val params = linkedMapOf<String, String>()
        for (part in pieces.drop(1)) {
            val match = parameterPattern.matchEntire(part) ?: throw IllegalArgumentException("invalid_content_type")
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val parameter = if (part.substringAfter('=').startsWith('"')) match.groupValues[2] else match.groupValues[3]
            require(params.put(name, parameter) == null) { "duplicate_content_type_parameter" }
        }
        require(params["charset"] == null || params["charset"].equals("utf-8", ignoreCase = true)) { "unsupported_charset" }
        return pieces.first().lowercase(Locale.ROOT) to params
    }

    private fun multipart(bytes: ByteArray, boundary: String): List<Pair<String, String>> {
        require(boundary.length in 1..70 && Regex("[0-9A-Za-z'()+_,./:=? -]+").matches(boundary) && !boundary.endsWith(' ')) { "invalid_boundary" }
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val delimiter = "--$boundary"
        require(text.startsWith("$delimiter\r\n")) { "invalid_multipart" }
        val fields = mutableListOf<Pair<String, String>>()
        var offset = delimiter.length + 2
        while (true) {
            require(fields.size < MAX_FIELDS) { "too_many_fields" }
            val headerEnd = text.indexOf("\r\n\r\n", offset)
            require(headerEnd >= offset && headerEnd - offset <= 2_048) { "invalid_part_headers" }
            val headers = linkedMapOf<String, String>()
            for (line in text.substring(offset, headerEnd).split("\r\n")) {
                require(line.all { it.code in 32..126 } && ':' in line) { "invalid_part_headers" }
                val name = line.substringBefore(':').lowercase(Locale.ROOT)
                require(name == "content-disposition" || name == "content-type") { "unsupported_part_header" }
                require(headers.put(name, line.substringAfter(':').trim()) == null) { "duplicate_part_header" }
            }
            val disposition = headers["content-disposition"] ?: throw IllegalArgumentException("missing_disposition")
            val field = Regex("form-data;[ ]*name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).matchEntire(disposition)
                ?: throw IllegalArgumentException("invalid_disposition")
            headers["content-type"]?.let {
                val media = mediaType(it)
                require(media.first == "text/plain" && media.second.keys.all { key -> key == "charset" }) { "unsupported_part_type" }
            }
            val valueStart = headerEnd + 4
            val valueEnd = text.indexOf("\r\n$delimiter", valueStart)
            require(valueEnd >= valueStart && valueEnd - valueStart <= MAX_VALUE_BYTES) { "invalid_part_value" }
            fields += field.groupValues[1] to utf8(text.substring(valueStart, valueEnd).toByteArray(StandardCharsets.ISO_8859_1))
            offset = valueEnd + 2 + delimiter.length
            if (text.startsWith("--", offset)) {
                val end = text.substring(offset + 2)
                require(end.isEmpty() || end == "\r\n") { "invalid_multipart_epilogue" }
                return fields
            }
            require(text.startsWith("\r\n", offset)) { "invalid_multipart_boundary" }
            offset += 2
        }
    }

    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw IllegalArgumentException("invalid_utf8") }

    private fun decodeForm(value: String): String {
        val source = value.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(source.size)
        var i = 0
        while (i < source.size) {
            when (source[i].toInt() and 0xff) {
                43 -> out.write(32)
                37 -> {
                    require(i + 2 < source.size) { "invalid_percent_encoding" }
                    val high = (source[i + 1].toInt() and 0xff).toChar().digitToIntOrNull(16)
                    val low = (source[i + 2].toInt() and 0xff).toChar().digitToIntOrNull(16)
                    require(high != null && low != null) { "invalid_percent_encoding" }
                    out.write(high * 16 + low); i += 2
                }
                else -> out.write(source[i].toInt() and 0xff)
            }
            i++
        }
        return utf8(out.toByteArray())
    }

    private sealed interface Node
    private class Branch(val children: MutableMap<String, Node> = linkedMapOf()) : Node
    private class Leaf(val value: String) : Node

    private fun path(name: String): List<String> {
        require(name.length in 1..256) { "invalid_field_name" }
        val root = name.substringBefore('[')
        require(rootPattern.matches(root)) { "invalid_field_name" }
        val result = mutableListOf(root)
        var offset = root.length
        while (offset < name.length) {
            require(name[offset] == '[' && result.size < MAX_DEPTH) { "invalid_field_depth" }
            val end = name.indexOf(']', offset + 1)
            require(end > offset + 1) { "invalid_field_name" }
            val segment = name.substring(offset + 1, end)
            require(segmentPattern.matches(segment)) { "invalid_field_name" }
            if (segment.first().isDigit()) require(segment.toInt() < MAX_ARRAY_SIZE) { "array_too_large" }
            result += segment
            offset = end + 1
        }
        return result
    }

    private fun insert(root: Branch, path: List<String>, value: String) {
        var branch = root
        for ((index, part) in path.withIndex()) {
            if (index == path.lastIndex) {
                require(part !in branch.children) { "duplicate_or_conflicting_field" }
                branch.children[part] = Leaf(value)
            } else {
                val previous = branch.children[part]
                require(previous == null || previous is Branch) { "conflicting_field" }
                branch = if (previous is Branch) previous else Branch().also { branch.children[part] = it }
            }
        }
    }

    private fun objectValue(branch: Branch, depth: Int): JsonObject = JsonObject(branch.children.mapValues { (_, node) -> nodeValue(node, depth + 1) })
    private fun nodeValue(node: Node, depth: Int): JsonElement {
        require(depth <= MAX_DEPTH) { "invalid_field_depth" }
        if (node is Leaf) return JsonPrimitive(node.value)
        val branch = node as Branch
        val numeric = branch.children.keys.count { it.first().isDigit() }
        if (numeric == 0) return objectValue(branch, depth)
        require(numeric == branch.children.size && (0 until numeric).all { it.toString() in branch.children }) { "sparse_or_mixed_array" }
        return JsonArray((0 until numeric).map { nodeValue(branch.children.getValue(it.toString()), depth + 1) })
    }

    private fun canonical(value: JsonElement, depth: Int): String {
        require(depth <= MAX_DEPTH) { "invalid_field_depth" }
        return when (value) {
            is JsonObject -> {
                require(value.size <= MAX_FIELDS) { "too_many_fields" }
                value.keys.sorted().joinToString(",", "{", "}") { key ->
                    // Numeric PHP keys must be represented by the dense JsonArray
                    // produced by parse(), never by a lexically sorted object.
                    require(rootPattern.matches(key)) { "invalid_field_name" }
                    quote(key) + ":" + canonical(value.getValue(key), depth + 1)
                }
            }
            is JsonArray -> {
                require(value.size <= MAX_ARRAY_SIZE) { "array_too_large" }
                value.joinToString(",", "[", "]") { canonical(it, depth + 1) }
            }
            is JsonPrimitive -> {
                require(value.isString) { "non_string_scalar" }
                quote(value.content)
            }
        }
    }

    private fun quote(value: String): String {
        require(value.length <= MAX_VALUE_BYTES) { "value_too_long" }
        val result = StringBuilder("\"")
        for ((i, ch) in value.withIndex()) {
            require(!ch.isHighSurrogate() || (i + 1 < value.length && value[i + 1].isLowSurrogate())) { "invalid_unicode" }
            require(!ch.isLowSurrogate() || (i > 0 && value[i - 1].isHighSurrogate())) { "invalid_unicode" }
            when (ch) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '/' -> result.append("\\/")
                '\b' -> result.append("\\b")
                '\u000c' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> if (ch.code < 0x20 || ch == '\u2028' || ch == '\u2029') result.append("\\u%04x".format(ch.code)) else result.append(ch)
            }
        }
        return result.append('"').toString()
    }

    private fun flatten(value: JsonElement, prefix: String, out: MutableList<Pair<String, String>>) {
        when (value) {
            is JsonObject -> value.entries.sortedBy { it.key }.forEach { (key, child) -> flatten(child, if (prefix.isEmpty()) key else "$prefix[$key]", out) }
            is JsonArray -> value.forEachIndexed { index, child -> flatten(child, "$prefix[$index]", out) }
            is JsonPrimitive -> out += prefix to value.content
        }
    }
}

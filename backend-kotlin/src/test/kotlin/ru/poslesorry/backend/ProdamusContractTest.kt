package ru.poslesorry.backend

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.*

class ProdamusContractTest {
    private val form = "application/x-www-form-urlencoded; charset=UTF-8"
    private val order = "rrstg_" + "a".repeat(32)
    private val secret = "unit-test-secret-not-a-credential"
    private val merchant = "https://relationshipreset.payform.ru/"
    private fun parse(body: String) = ProdamusContract.parse(form, body.toByteArray())
    private fun notificationFields() = linkedMapOf(
        "order_num" to order, "order_id" to "123456", "domain" to "relationshipreset.payform.ru",
        "sum" to "990.00", "currency" to "rub", "payment_status" to "success", "demo_mode" to "1",
    )
    private fun json(fields: Map<String, String>) = JsonObject(fields.mapValues { JsonPrimitive(it.value) })
    private fun multipart(vararg fields: Pair<String, String>, boundary: String = "test-boundary"): ByteArray = buildString {
        for ((name, value) in fields) {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        append("--$boundary--\r\n")
    }.toByteArray()

    @Test
    fun `urlencoded parser reconstructs dense product arrays and preserves literal string values`() {
        val data = parse("products%5B1%5D%5Bname%5D=Second&products[0][price]=990.00&products[0][name]=%D0%A0%D0%B0%D0%B7%D0%B1%D0%BE%D1%80+%2B&empty=&count=01")
        val products = data.getValue("products").jsonArray
        assertEquals(2, products.size)
        assertEquals("Разбор +", products[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("990.00", products[0].jsonObject.getValue("price").jsonPrimitive.content)
        assertEquals("Second", products[1].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(JsonPrimitive(""), data["empty"])
        assertEquals(JsonPrimitive("01"), data["count"])
    }

    @Test
    fun `urlencoded rejects duplicate decoded names and scalar container collisions in either order`() {
        for (body in listOf("a=1&a=2", "a=1&%61=2", "a=1&a[b]=2", "a[b]=2&a=1", "a[0]=1&a[0]=2")) {
            assertFailsWith<IllegalArgumentException>(body) { parse(body) }
        }
    }

    @Test
    fun `array keys require canonical dense indices and cannot mix object fields`() {
        for (body in listOf("p[1]=one", "p[0]=a&p[2]=b", "p[01]=a", "p[]=a", "p[-1]=a", "p[64]=a", "p[0]=a&p[name]=b")) {
            assertFailsWith<IllegalArgumentException>(body) { parse(body) }
        }
    }

    @Test
    fun `rejects invalid and ambiguous field names and excessive nesting`() {
        for (name in listOf("a.b", "a+b", "[x]", "a[x", "a[x]z", "a[x]]", "a[[x]]", "a[0][0][0][0][0][0][0][0]")) {
            assertFailsWith<IllegalArgumentException>(name) { parse("$name=value") }
        }
        assertEquals(JsonPrimitive("x"), parse("a[b][c][d][e][f][g][h]=x")["a"]!!.jsonObject["b"]!!.jsonObject["c"]!!.jsonObject["d"]!!.jsonObject["e"]!!.jsonObject["f"]!!.jsonObject["g"]!!.jsonObject["h"])
    }

    @Test
    fun `rejects malformed UTF8 percent escapes and incomplete form fields`() {
        for (body in listOf("a=%", "a=%0", "a=%GG", "a=%c3%28", "a=%ED%A0%80", "a=%C0%AF", "a", "a=x&", "&a=x", "a=x&&b=y")) {
            assertFailsWith<IllegalArgumentException>(body) { parse(body) }
        }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse(form, byteArrayOf(97, 61, 0xc3.toByte(), 0x28)) }
    }

    @Test
    fun `body size fields values depth and content type are bounded`() {
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse(form, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse(form, ByteArray(ProdamusContract.MAX_BODY_BYTES + 1) { 97 }) }
        assertFailsWith<IllegalArgumentException> { parse("v=" + "x".repeat(8193)) }
        assertFailsWith<IllegalArgumentException> { parse((0..256).joinToString("&") { "v$it=x" }) }
        for (type in listOf("application/json", "text/plain", "application/x-www-form-urlencoded; charset=iso-8859-1", "$form; charset=UTF-8", "application/x-www-form-urlencoded\r\nX-Evil: x")) {
            assertFailsWith<IllegalArgumentException>(type) { ProdamusContract.parse(type, "a=b".toByteArray()) }
        }
    }

    @Test
    fun `header signature cannot be smuggled in POST data`() {
        for (body in listOf("signature=abc", "Signature=abc", "signature[a]=abc")) {
            assertFailsWith<IllegalArgumentException> { parse(body) }
        }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.signature(json(mapOf("signature" to "x")), secret) }
    }

    @Test
    fun `multipart yields the same tree and signature as urlencoded including Unicode and newlines`() {
        val values = arrayOf("products[0][name]" to "После ссоры", "sum" to "990.00", "extra" to "строка\r\nстрока + /", "empty" to "")
        val encoded = values.joinToString("&") { (name, value) -> "${URLEncoder.encode(name, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val first = parse(encoded)
        val second = ProdamusContract.parse("multipart/form-data; boundary=\"test-boundary\"", multipart(*values))
        assertEquals(first, second)
        assertEquals(ProdamusContract.signature(first, secret), ProdamusContract.signature(second, secret))
    }

    @Test
    fun `multipart rejects duplicates files unsupported headers incomplete boundaries and epilogues`() {
        val valid = String(multipart("a" to "b"))
        val bodies = listOf(
            String(multipart("a" to "b", "a" to "c")),
            valid.replace("name=\"a\"", "name=\"a\"; filename=\"file.txt\""),
            valid.replace("\r\n\r\n", "\r\nContent-Transfer-Encoding: base64\r\n\r\n"),
            valid.replace("\r\n\r\n", "\r\nContent-Disposition: form-data; name=\"b\"\r\n\r\n"),
            valid.replace("--test-boundary--", "--test-boundary"),
            "preamble" + valid,
            valid + "epilogue",
            valid.replace("\r\n", "\n"),
        )
        for (body in bodies) assertFailsWith<IllegalArgumentException> { ProdamusContract.parse("multipart/form-data; boundary=test-boundary", body.toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse("multipart/form-data", valid.toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse("multipart/form-data; boundary=a; boundary=b", valid.toByteArray()) }
    }

    @Test
    fun `multipart rejects malformed UTF8 and accepts explicit UTF8 text part`() {
        val valid = String(multipart("a" to "b"))
        val withType = valid.replace("\r\n\r\n", "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n")
        assertEquals(JsonPrimitive("b"), ProdamusContract.parse("multipart/form-data; boundary=test-boundary", withType.toByteArray())["a"])
        val badBytes = valid.toByteArray().apply { this[valid.indexOf("\r\n\r\nb") + 4] = 0xff.toByte() }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse("multipart/form-data; boundary=test-boundary", badBytes) }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.parse("multipart/form-data; boundary=test-boundary", withType.replace("UTF-8", "ISO-8859-1").toByteArray()) }
    }

    @Test
    fun `golden PHP compatible signature preserves Unicode escapes slash and line separators and sorts nested keys`() {
        // Compact JSON_UNESCAPED_UNICODE with default slash/line-terminator escaping.
        // Expected digest calculated independently with Python's hmac over those canonical bytes.
        val data = buildJsonObject {
            put("z", "")
            put("products", buildJsonArray { add(buildJsonObject { put("quantity", "1"); put("price", "990.00"); put("name", "После ссоры") }) })
            put("line", "\u2028\u2029")
            put("a", "https://пример.рф/путь")
        }
        val expected = "282883e009eab4c6cc632dc6c0a2d148ca343e98f7cb5e7a3c11511b21c7ebed"
        assertEquals(expected, ProdamusContract.signature(data, secret))
        assertTrue(ProdamusContract.verify(data, secret, expected.uppercase()))
        assertFalse(ProdamusContract.verify(data, "wrong-test-secret", expected))
        assertFalse(ProdamusContract.verify(JsonObject(data + ("z" to JsonPrimitive("changed"))), secret, expected))
        for (invalid in listOf("", expected + "0", " " + expected, "g".repeat(64), expected.substring(1))) assertFalse(ProdamusContract.verify(data, secret, invalid))
    }

    @Test
    fun `signature does not reorder arrays or coerce decimal strings and rejects nonstring scalars`() {
        assertNotEquals(ProdamusContract.signature(parse("p[0]=a&p[1]=b"), secret), ProdamusContract.signature(parse("p[0]=b&p[1]=a"), secret))
        assertNotEquals(ProdamusContract.signature(parse("amount=1.0"), secret), ProdamusContract.signature(parse("amount=1"), secret))
        for (value in listOf(JsonPrimitive(1), JsonPrimitive(true), JsonNull)) {
            assertFailsWith<IllegalArgumentException> { ProdamusContract.signature(buildJsonObject { put("a", value) }, secret) }
        }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.signature(buildJsonObject { put("a", "\ud800") }, secret) }
    }

    @Test
    fun `notification maps provider and merchant ids and exact money without trusting a return redirect`() {
        val value = ProdamusContract.notification(json(notificationFields()))
        assertEquals(order, value.merchantOrderId)
        assertEquals("123456", value.providerOrderId)
        assertEquals("relationshipreset.payform.ru", value.merchantDomain)
        assertEquals(99000L, value.amountMinor)
        assertEquals("RUB", value.currency)
        assertEquals("success", value.status)
        assertTrue(value.demoMode)
        for (amount in listOf("990", "990.0", "990.00")) assertEquals(99000L, ProdamusContract.notification(json(notificationFields() + ("sum" to amount))).amountMinor)
        assertFalse(ProdamusContract.notification(json(notificationFields() - "demo_mode")).demoMode)
        assertFalse(ProdamusContract.notification(json(notificationFields() + ("demo_mode" to "0"))).demoMode)
    }

    @Test
    fun `PF2 UUID is signed verbatim then canonicalized while PF1 numeric ids remain unchanged`() {
        val uuid = "019EA75C-B41C-7B83-BDDD-7369A7071F97"
        val wire = parse(notificationFields().plus("order_id" to uuid).entries.joinToString("&") { "${it.key}=${it.value}" })
        val sign = ProdamusContract.signature(wire, secret)
        assertTrue(ProdamusContract.verify(wire, secret, sign))
        val normalized = ProdamusContract.notification(wire)
        assertEquals(uuid.lowercase(), normalized.providerOrderId)
        assertFalse(ProdamusContract.verify(JsonObject(wire + ("order_id" to JsonPrimitive(uuid.lowercase()))), secret, sign))
        assertEquals("123456", ProdamusContract.canonicalProviderOrderId("123456"))
        for (invalid in listOf("019ea75c-b41c-7b83-bddd-7369a7071f9", "019ea75c-b41c-7b83-bddd-7369a7071f977", "019ea75c-b41c-7b83-bddd-7369a7071f9g", "{019ea75c-b41c-7b83-bddd-7369a7071f97}", "019ea75cb41c7b83bddd7369a7071f97", " 019ea75c-b41c-7b83-bddd-7369a7071f97", "1-2-3-4-5", "01")) {
            assertFailsWith<IllegalArgumentException>(invalid) { ProdamusContract.notification(json(notificationFields() + ("order_id" to invalid))) }
        }
    }

    @Test
    fun `notification rejects missing critical fields wrong merchant malformed ids amounts currencies and states`() {
        for (name in listOf("order_num", "order_id", "domain", "sum", "currency", "payment_status")) {
            assertFailsWith<IllegalArgumentException>(name) { ProdamusContract.notification(json(notificationFields() - name)) }
        }
        val invalid = listOf(
            "domain" to "another.payform.ru", "domain" to "https://relationshipreset.payform.ru/",
            "order_num" to "test", "order_num" to "rrstg_" + "A".repeat(32), "order_id" to "-1", "order_id" to "0", "order_id" to "1".repeat(21),
            "sum" to "-990", "sum" to "0", "sum" to "990,00", "sum" to "990.001", "sum" to "9.9e2", "sum" to "NaN", "sum" to " 990", "sum" to "999999999999999999999",
            "currency" to "usd", "currency" to "", "payment_status" to "pending", "payment_status" to "SUCCESS", "demo_mode" to "true", "demo_mode" to "",
        )
        for (pair in invalid) assertFailsWith<IllegalArgumentException>(pair.toString()) { ProdamusContract.notification(json(notificationFields() + pair)) }
        for (status in listOf("order_denied", "order_canceled")) assertEquals(status, ProdamusContract.notification(json(notificationFields() + ("payment_status" to status))).status)
    }

    @Test
    fun `checkout is pinned demo only signed and contains no customer information or order in return URLs`() {
        val url = ProdamusContract.checkoutUrl(order, merchant, secret)
        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("relationshipreset.payform.ru", uri.host)
        val pairs = uri.rawQuery.split('&').associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8) to URLDecoder.decode(pair.substringAfter('='), StandardCharsets.UTF_8)
        }
        assertEquals("pay", pairs["do"])
        assertEquals("1", pairs["demo_mode"])
        assertEquals("990.00", pairs["products[0][price]"])
        assertEquals("1", pairs["products[0][quantity]"])
        assertEquals("rub", pairs["currency"])
        assertEquals("1", pairs["payments_limit"])
        assertEquals("1", pairs["installments_disabled"])
        assertEquals(order, pairs["order_id"])
        assertEquals("https://api-staging.poslessory.ru/rehearsal/", pairs["urlSuccess"])
        assertEquals(pairs["urlSuccess"], pairs["urlReturn"])
        assertFalse(pairs.keys.any { it.startsWith("customer_") || it == "callbackType" || it == "urlNotification" || it == "sys" })
        val withoutSign = uri.rawQuery.split('&').filterNot { it.startsWith("signature=") }.joinToString("&")
        assertTrue(ProdamusContract.verify(parse(withoutSign), secret, pairs.getValue("signature")))
        val withSys = URI(ProdamusContract.checkoutUrl(order, merchant, secret, "approved_sys")).rawQuery
        assertTrue(withSys.contains("sys=approved_sys"))
        assertTrue(withSys.contains("urlNotification="))
    }

    @Test
    fun `checkout rejects arbitrary merchant URLs order identifiers and invalid sys`() {
        for (url in listOf("http://relationshipreset.payform.ru/", "https://evil.example/", "$merchant?demo_mode=0", "$merchant#fragment", "https://user@relationshipreset.payform.ru/", "https://relationshipreset.payform.ru:443/", "${merchant}path", "https://relationshipreset.payform.ru.evil.example/")) {
            assertFailsWith<IllegalArgumentException>(url) { ProdamusContract.checkoutUrl(order, url, secret) }
        }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.checkoutUrl("123", merchant, secret) }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.checkoutUrl(order, merchant, secret, "") }
        assertFailsWith<IllegalArgumentException> { ProdamusContract.checkoutUrl(order, merchant, secret, "x&demo_mode=0") }
    }
}

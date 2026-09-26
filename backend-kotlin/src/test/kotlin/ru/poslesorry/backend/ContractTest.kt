package ru.poslesorry.backend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractTest {
    private val requestId = "00112233-4455-4677-8899-aabbccddeeff"

    private fun fixture(): JsonObject = buildJsonObject {
        put("schema_version", "m1-cis-v1")
        put("synthetic", true)
        put("client_request_id", requestId)
        put("questionnaire", buildJsonObject {
            put("age", "adult")
            put("stage", "1to3y")
            put("safety", "no")
            put("boundary", "space")
            put("timing", "yesterday")
            put("partner", "space")
            put("user", "messages")
            put("recurrence", "sometimes")
            put("helpful", "listen")
            put("failureType", "messages")
            put("goal", "understand")
            put("situation", "Вымышленная ссора.")
            put("success", "")
            put("failure", "")
        })
    }

    private fun draft(text: String = "Вымышленный черновик."): JsonObject = buildJsonObject {
        put("synthetic", true)
        put("client_request_id", requestId)
        put("text", text)
    }

    private fun JsonObject.changed(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))

    private fun caseText(key: String, value: String): JsonObject {
        val body = fixture()
        return body.changed("questionnaire", body.getValue("questionnaire").jsonObject.changed(key, JsonPrimitive(value)))
    }

    private fun rejected(status: Int, code: String, call: () -> Unit) {
        val problem = assertFailsWith<ApiProblem>(block = call)
        assertEquals(status, problem.status)
        assertEquals(code, problem.code)
    }

    @Test
    fun `case roundtrips exact PHP field order independent of input order`() {
        val body = fixture()
        val scrambledQuestionnaire = JsonObject(body.getValue("questionnaire").jsonObject.entries.reversed().associate { it.toPair() })
        val scrambled = JsonObject(body.changed("questionnaire", scrambledQuestionnaire).entries.reversed().associate { it.toPair() })
        val actual = Contract.casePayload(Contract.decode(scrambled.toString().toByteArray()))
        assertEquals(Contract.canonicalJson(body), Contract.canonicalJson(actual))
    }

    @Test
    fun `case canonical bytes and hash match independently generated PHP fixture`() {
        var body = caseText("situation", " \tВымышленная ссора 🙂 / \\\"\nстрока\u2028ещё\u2029конец\r\n ")
        var questionnaire = body.getValue("questionnaire").jsonObject
        questionnaire = questionnaire.changed("success", JsonPrimitive("\u00a0пауза\u00a0"))
            .changed("failure", JsonPrimitive("\t\n"))
        body = body.changed("questionnaire", questionnaire)
        val canonical = Contract.canonicalJson(Contract.casePayload(body))
        // Generated with backend/src/Validation.php and JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES.
        val expected = """{"schema_version":"m1-cis-v1","synthetic":true,"client_request_id":"00112233-4455-4677-8899-aabbccddeeff","questionnaire":{"age":"adult","stage":"1to3y","safety":"no","boundary":"space","timing":"yesterday","partner":"space","user":"messages","recurrence":"sometimes","helpful":"listen","failureType":"messages","goal":"understand","situation":"Вымышленная ссора 🙂 / \\\"\nстрока\u2028ещё\u2029конец","success":" пауза ","failure":""}}"""
        assertEquals(expected, canonical)
        assertEquals("420d29798067d106effb6dfcb11dfb25cac76fd1956351caebd102249aff32a5", Contract.sha256(canonical))
    }

    @Test
    fun `draft canonical bytes and hash match independently generated PHP fixture`() {
        val body = draft("\r\nВымышленный разбор 🙂 / <>& \"цитата\" \\\n\u2028\u2029\t ")
        val canonical = Contract.canonicalJson(Contract.draftPayload(body))
        val expected = """{"synthetic":true,"client_request_id":"00112233-4455-4677-8899-aabbccddeeff","text":"Вымышленный разбор 🙂 / <>& \"цитата\" \\\n\u2028\u2029"}"""
        assertEquals(expected, canonical)
        assertEquals("993c2aac61414702b49d5d78ec11958ab53608854f43ece215a0f5897f65e04e", Contract.sha256(canonical))
    }

    @Test
    fun `non objects and extra or missing fields fail with PHP error codes`() {
        rejected(422, "invalid_payload") { Contract.casePayload(JsonArray(emptyList())) }
        rejected(422, "invalid_payload") { Contract.draftPayload(JsonNull) }
        rejected(422, "invalid_fields") { Contract.casePayload(fixture().changed("email", JsonPrimitive("unused@example.invalid"))) }
        rejected(422, "invalid_fields") { Contract.casePayload(JsonObject(fixture() - "synthetic")) }
        rejected(422, "invalid_payload") { Contract.casePayload(fixture().changed("questionnaire", JsonArray(emptyList()))) }
        rejected(422, "invalid_fields") {
            Contract.casePayload(fixture().changed("questionnaire", JsonObject(fixture().getValue("questionnaire").jsonObject - "safety")))
        }
        rejected(422, "invalid_fields") { Contract.draftPayload(draft().changed("reviewed", JsonPrimitive(true))) }
    }

    @Test
    fun `synthetic and schema checks are strict and precede request validation`() {
        for (value in listOf(JsonPrimitive(false), JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) {
            rejected(422, "synthetic_case_required") { Contract.casePayload(fixture().changed("synthetic", value)) }
            rejected(422, "synthetic_case_required") { Contract.draftPayload(draft().changed("synthetic", value)) }
        }
        for (value in listOf(JsonPrimitive("m1-cis-v2"), JsonNull, JsonPrimitive(1))) {
            rejected(422, "synthetic_case_required") { Contract.casePayload(fixture().changed("schema_version", value)) }
        }
        rejected(422, "synthetic_case_required") {
            Contract.casePayload(fixture().changed("synthetic", JsonPrimitive(false)).changed("client_request_id", JsonNull))
        }
    }

    @Test
    fun `UUID requires lowercase v4 and correct variant with no trailing newline`() {
        val invalid = listOf(requestId.uppercase(), requestId + "\n", requestId.replace("-4677-", "-1677-"),
            requestId.replace("-8899-", "-7899-"), "../../private", "")
        for (value in invalid) {
            rejected(422, "invalid_client_request_id") { Contract.casePayload(fixture().changed("client_request_id", JsonPrimitive(value))) }
        }
        rejected(422, "invalid_client_request_id") { Contract.draftPayload(draft().changed("client_request_id", JsonPrimitive(1))) }
        for (variant in listOf('8', '9', 'a', 'b')) {
            val id = requestId.replace("-8899-", "-${variant}899-")
            assertEquals(id, Contract.draftPayload(draft().changed("client_request_id", JsonPrimitive(id)))["client_request_id"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `questionnaire values are strict while safety enums are preserved for later policy`() {
        for (value in listOf(JsonPrimitive("yes"), JsonPrimitive(true), JsonPrimitive(1), JsonNull)) {
            val body = fixture()
            val q = body.getValue("questionnaire").jsonObject.changed("age", value)
            rejected(422, "invalid_questionnaire_enum") { Contract.casePayload(body.changed("questionnaire", q)) }
        }
        val body = fixture()
        val q = body.getValue("questionnaire").jsonObject.changed("age", JsonPrimitive("minor"))
            .changed("safety", JsonPrimitive("yes_unsure"))
        assertEquals(q, Contract.casePayload(body.changed("questionnaire", q))["questionnaire"])
    }

    @Test
    fun `text limits count UTF16 units after PHP trim`() {
        assertEquals("я".repeat(2000), Contract.casePayload(caseText("situation", " \t" + "я".repeat(2000) + "\n"))["questionnaire"]?.jsonObject?.get("situation")?.jsonPrimitive?.content)
        assertTrue(Contract.casePayload(caseText("situation", "🙂".repeat(1000))).isNotEmpty())
        rejected(422, "invalid_text_length") { Contract.casePayload(caseText("situation", "🙂".repeat(1000) + "x")) }
        assertTrue(Contract.casePayload(caseText("success", "🙂".repeat(500))).isNotEmpty())
        rejected(422, "invalid_text_length") { Contract.casePayload(caseText("success", "🙂".repeat(501))) }
        rejected(422, "invalid_text_length") { Contract.casePayload(caseText("failure", "я".repeat(1001))) }
        assertTrue(Contract.draftPayload(draft("🙂".repeat(2000))).isNotEmpty())
        rejected(422, "invalid_text_length") { Contract.draftPayload(draft("🙂".repeat(2001))) }
        rejected(422, "invalid_text_length") { Contract.casePayload(caseText("situation", " \t\r\n")) }
        rejected(422, "invalid_text_length") { Contract.draftPayload(draft("")) }
        assertEquals("\u00a0", Contract.draftPayload(draft(" \u00a0 "))["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `controls are checked before trim and only permitted line controls survive`() {
        for (code in (0..31).filter { it !in listOf(9, 10, 13) } + 127) {
            rejected(422, "invalid_text") { Contract.draftPayload(draft(code.toChar() + "x")) }
        }
        assertEquals("x\t\r\ny", Contract.draftPayload(draft("x\t\r\ny"))["text"]?.jsonPrimitive?.content)
        rejected(422, "invalid_text") { Contract.draftPayload(draft().changed("text", JsonPrimitive(123))) }
        rejected(422, "invalid_text") { Contract.draftPayload(draft("\uD800")) }
        rejected(422, "invalid_text") { Contract.draftPayload(draft("\uDC00")) }
    }

    @Test
    fun `decode body size is byte based and checked before parsing`() {
        val atLimit = ("\"" + "x".repeat(16_382) + "\"").toByteArray()
        assertEquals(16_384, atLimit.size)
        assertEquals(16_382, Contract.decode(atLimit).jsonPrimitive.content.length)
        rejected(413, "payload_too_large") { Contract.decode(atLimit + byteArrayOf(32)) }
        rejected(413, "payload_too_large") { Contract.decode("я".repeat(8193).toByteArray()) }
    }

    @Test
    fun `UTF8 decoder rejects malformed overlong surrogate and truncated sequences`() {
        val invalid = listOf(
            byteArrayOf(34, 0xff.toByte(), 34),
            byteArrayOf(34, 0xc0.toByte(), 0xaf.toByte(), 34),
            byteArrayOf(34, 0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 34),
            byteArrayOf(34, 0xf0.toByte(), 0x9f.toByte(), 0x99.toByte(), 34),
        )
        for (bytes in invalid) rejected(400, "invalid_json") { Contract.decode(bytes) }
    }

    @Test
    fun `decode follows PHP JSON nesting boundary and ignores brackets inside strings`() {
        assertTrue(Contract.decode(("[".repeat(31) + "0" + "]".repeat(31)).toByteArray()) is JsonArray)
        rejected(400, "invalid_json") { Contract.decode(("[".repeat(32) + "0" + "]".repeat(32)).toByteArray()) }
        assertEquals("[".repeat(100), Contract.decode(("\"" + "[".repeat(100) + "\"").toByteArray()).jsonPrimitive.content)
        assertTrue(Contract.decode(("{\"a\":".repeat(31) + "0" + "}".repeat(31)).toByteArray()) is JsonObject)
        rejected(400, "invalid_json") { Contract.decode(("{\"a\":".repeat(32) + "0" + "}".repeat(32)).toByteArray()) }
    }

    @Test
    fun `decode rejects invalid JSON syntax and escaped lone surrogates`() {
        val invalid = listOf("", " ", "{", "[1,]", "{\"a\":1,}", "{a:1}", "[NaN]", "Infinity", "01", "+1", "1.", "-.1", "1e", "1e+", "true false", "/*x*/1", "\uFEFF{}", "\u00a0{}", "\"\n\"", "\"\\x41\"", "\"\\uD800\"", "\"\\uDC00\"", "\"\\uD800\\u0061\"", "\"\\u００００\"", "{\"\\u0000x\":1}")
        for (text in invalid) rejected(400, "invalid_json") { Contract.decode(text.toByteArray()) }
    }

    @Test
    fun `decode permits valid scalars surrogate pair and PHP duplicate key semantics`() {
        assertEquals(JsonNull, Contract.decode("null".toByteArray()))
        assertEquals(JsonPrimitive(true), Contract.decode(" \ntrue\t".toByteArray()))
        assertEquals("🙂", Contract.decode("\"\\ud83d\\ude42\"".toByteArray()).jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), Contract.decode("{\"a\":1,\"a\":2}".toByteArray()).jsonObject["a"])
        assertEquals("1e999", Contract.decode("1e999".toByteArray()).jsonPrimitive.content)
        assertEquals("x\u0000", Contract.decode("{\"x\\u0000\":1}".toByteArray()).jsonObject.keys.single())
        for (number in listOf("0", "-0", "-123", "1.2", "1e-10", "1E+10")) {
            assertEquals(number, Contract.decode(number.toByteArray()).jsonPrimitive.content)
        }
    }
}

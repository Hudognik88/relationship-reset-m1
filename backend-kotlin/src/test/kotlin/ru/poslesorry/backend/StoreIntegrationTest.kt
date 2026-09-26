package ru.poslesorry.backend

import com.mysql.cj.jdbc.MysqlDataSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses only an explicitly supplied disposable database ending in _test. */
class StoreIntegrationTest {
    private lateinit var dataSource: MysqlDataSource
    private lateinit var store: JdbcCaseStore
    private val createdCases = mutableSetOf<String>()

    @BeforeEach
    fun prepare() {
        val name = System.getenv("RR_TEST_DB_NAME")
        assumeTrue(!name.isNullOrBlank(), "RR_TEST_DB_NAME not supplied; database integration test skipped")
        require(name!!.matches(Regex("[A-Za-z0-9_]+_test"))) { "Integration database must end in _test" }
        dataSource = MysqlDataSource().apply {
            setServerName(System.getenv("RR_TEST_DB_HOST") ?: "127.0.0.1")
            setPort((System.getenv("RR_TEST_DB_PORT") ?: "3306").toInt())
            setDatabaseName(name)
            setUser(requireNotNull(System.getenv("RR_TEST_DB_USER")))
            setPassword(requireNotNull(System.getenv("RR_TEST_DB_PASSWORD")))
            setUseSSL(false)
            setAllowPublicKeyRetrieval(true)
            setServerTimezone("UTC")
        }
        Migrations(dataSource).run()
        store = JdbcCaseStore(dataSource)
    }

    @AfterEach
    fun cleanup() {
        if (!::dataSource.isInitialized || createdCases.isEmpty()) return
        dataSource.connection.use { db ->
            db.prepareStatement("DELETE FROM rr_cases WHERE id = ?").use { query ->
                createdCases.forEach { id -> query.setString(1, id); query.executeUpdate() }
            }
        }
    }

    @Test
    fun `explicit migration is repeatable without rewriting the ledger`() {
        fun appliedAt(): String = dataSource.connection.use { db ->
            db.createStatement().use { query ->
                query.executeQuery("SELECT applied_at FROM rr_schema_migrations WHERE version = '001_initial'").use {
                    assertTrue(it.next())
                    it.getString(1)
                }
            }
        }
        val before = appliedAt()
        Migrations(dataSource).run()
        assertEquals(before, appliedAt())
        assertTrue(store.ready())
    }

    @Test
    fun `changed migration checksum is refused and readiness becomes false`() {
        fun checksum(value: String) = dataSource.connection.use { db ->
            db.prepareStatement("UPDATE rr_schema_migrations SET checksum = ? WHERE version = '001_initial'").use {
                it.setString(1, value)
                it.executeUpdate()
            }
        }
        try {
            checksum("0".repeat(64))
            assertFalse(store.ready())
            val error = assertFailsWith<IllegalStateException> { Migrations(dataSource).run() }
            assertEquals("Migration checksum mismatch", error.message)
            assertFalse(store.ready())
        } finally {
            checksum(InitialSchema.checksum)
        }
        Migrations(dataSource).run()
        assertTrue(store.ready())
    }

    @Test
    fun `case replay returns the same row and changed content conflicts`() {
        val payload = casePayload()
        val first = createCase(payload)
        assertEquals(201, first.status)
        val id = first.body.getValue("id").jsonPrimitive.content
        assertTrue(id.matches(Regex("[a-f0-9]{32}")))
        assertTrue(first.body.getValue("created_at").jsonPrimitive.content.matches(
            Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z")
        ))
        assertEquals(payload.getValue("questionnaire"), first.body.getValue("questionnaire"))
        assertEquals(first.body, store.getCase(id))
        assertEquals(ApiResult(200, first.body), store.createCase(payload))
        val changedQuestionnaire = JsonObject(payload.getValue("questionnaire").jsonObject +
            ("situation" to JsonPrimitive("Другой вымышленный пример.")))
        assertProblem(409, "idempotency_conflict") {
            store.createCase(JsonObject(payload + ("questionnaire" to changedQuestionnaire)))
        }
    }

    @Test
    fun `concurrent retries use the unique constraint to create exactly one case`() {
        val payload = casePayload()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val results = workers.invokeAll((1..4).map { Callable { store.createCase(payload) } })
                .map { it.get(20, TimeUnit.SECONDS) }
            results.forEach { createdCases += it.body.getValue("id").jsonPrimitive.content }
            assertEquals(1, createdCases.size)
            assertEquals(1, results.count { it.status == 201 })
            assertEquals(3, results.count { it.status == 200 })
            assertEquals(1, results.map { it.body }.distinct().size)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `draft replay is stable and request keys cannot be reused for another case`() {
        val caseId = createCase(casePayload()).body.getValue("id").jsonPrimitive.content
        val anotherId = createCase(casePayload()).body.getValue("id").jsonPrimitive.content
        val payload = Contract.draftPayload(buildJsonObject {
            put("synthetic", true)
            put("client_request_id", UUID.randomUUID().toString())
            put("text", "Вымышленный черновик: сделать паузу и уважать просьбу персонажа.")
        })
        val first = store.createDraft(caseId, payload)
        assertEquals(201, first.status)
        assertEquals(JsonPrimitive(false), first.body["reviewed"])
        assertEquals(JsonPrimitive("draft_requires_human_review"), first.body["status"])
        assertEquals(ApiResult(200, first.body), store.createDraft(caseId, payload))
        assertProblem(409, "idempotency_conflict") { store.createDraft(anotherId, payload) }
        assertProblem(409, "idempotency_conflict") {
            store.createDraft(caseId, JsonObject(payload + ("text" to JsonPrimitive("Другой вымышленный черновик."))))
        }
        dataSource.connection.use { db ->
            db.prepareStatement("SELECT draft_text, reviewed FROM rr_drafts WHERE id = ?").use { query ->
                query.setString(1, first.body.getValue("id").jsonPrimitive.content)
                query.executeQuery().use {
                    assertTrue(it.next())
                    assertEquals(payload.getValue("text").jsonPrimitive.content, it.getString(1))
                    assertEquals(0, it.getInt(2))
                }
            }
        }
    }

    @Test
    fun `missing and non-synthetic cases reject draft creation`() {
        assertProblem(404, "case_not_found") { store.getCase("0".repeat(32)) }
        val caseId = createCase(casePayload()).body.getValue("id").jsonPrimitive.content
        dataSource.connection.use { db ->
            db.prepareStatement("UPDATE rr_cases SET synthetic = 0 WHERE id = ?").use {
                it.setString(1, caseId)
                it.executeUpdate()
            }
        }
        val payload = Contract.draftPayload(buildJsonObject {
            put("synthetic", true)
            put("client_request_id", UUID.randomUUID().toString())
            put("text", "Вымышленный черновик.")
        })
        assertProblem(409, "synthetic_case_required") { store.createDraft(caseId, payload) }
        assertProblem(404, "case_not_found") { store.createDraft("0".repeat(32), payload) }
    }

    @Test
    fun `PHP canonical row is readable and replayable without schema conversion`() {
        // Raw JSON and hash were produced by PHP 8.3 RR\Validation::json, not by Kotlin.
        // The Unicode separator checks an otherwise easy-to-miss hash incompatibility.
        val phpJson = """{"schema_version":"m1-cis-v1","synthetic":true,"client_request_id":"00000000-0000-4000-8000-000000000001","questionnaire":{"age":"adult","stage":"1to3y","safety":"no","boundary":"space","timing":"yesterday","partner":"space","user":"messages","recurrence":"sometimes","helpful":"listen","failureType":"messages","goal":"understand","situation":"Вымышленная репетиция 😀\u2028Пауза.","success":"Вымышленный пример: раньше помогало выслушать без спора.","failure":"Вымышленный пример: повторное объяснение усилило напряжение."}}"""
        val phpHash = "763b84de3b00ae7d3bd4f2395fbc698208555b8473255a6375b37d9a130707d4"
        val payload = Contract.casePayload(Contract.json.parseToJsonElement(phpJson))
        val id = UUID.randomUUID().toString().replace("-", "")
        val questionnaireJson = phpJson.substringAfter("\"questionnaire\":").dropLast(1)
        dataSource.connection.use { db ->
            db.prepareStatement("INSERT INTO rr_cases (id, client_request_id, request_hash, schema_version, synthetic, questionnaire, created_at) VALUES (?, ?, ?, 'm1-cis-v1', 1, ?, '2026-09-25 20:01:02.123456')").use {
                it.setString(1, id)
                it.setString(2, payload.getValue("client_request_id").jsonPrimitive.content)
                it.setString(3, phpHash)
                it.setString(4, questionnaireJson)
                it.executeUpdate()
            }
        }
        createdCases += id
        Migrations(dataSource).run()
        val result = store.createCase(payload)
        assertEquals(200, result.status)
        assertEquals(JsonPrimitive(id), result.body["id"])
        assertEquals(JsonPrimitive("2026-09-25T20:01:02.123456Z"), result.body["created_at"])
        assertEquals(payload.getValue("questionnaire"), result.body["questionnaire"])
    }

    @Test
    fun `PHP can read Kotlin rows and replay both case and draft after rollback`() {
        val phpBinary = System.getenv("RR_TEST_PHP_BINARY")
        assumeTrue(!phpBinary.isNullOrBlank(), "RR_TEST_PHP_BINARY not supplied; reverse PHP integration test skipped")
        val casePayload = casePayload()
        val case = createCase(casePayload)
        val caseId = case.body.getValue("id").jsonPrimitive.content
        val draftPayload = Contract.draftPayload(buildJsonObject {
            put("synthetic", true)
            put("client_request_id", UUID.randomUUID().toString())
            put("text", "Вымышленный черновик 😀\u2028Пауза / уважение просьбы.")
        })
        val draft = store.createDraft(caseId, draftPayload)
        val bootstrap = File("../backend/bootstrap.php").canonicalFile
        assertTrue(bootstrap.isFile, "Legacy PHP backend must be present for rollback test")
        // The script reads disposable DB credentials only from the inherited test environment.
        // No credentials appear in the command, stdout, assertion messages or files.
        val script = """
            try {
                require getenv('RR_TEST_LEGACY_BOOTSTRAP');
                ${'$'}input = json_decode(stream_get_contents(STDIN), false, 32, JSON_THROW_ON_ERROR);
                ${'$'}config = ['database' => [
                    'host' => getenv('RR_TEST_DB_HOST') ?: '127.0.0.1',
                    'port' => (int) (getenv('RR_TEST_DB_PORT') ?: 3306),
                    'name' => getenv('RR_TEST_DB_NAME'),
                    'user' => getenv('RR_TEST_DB_USER'),
                    'password' => getenv('RR_TEST_DB_PASSWORD'),
                ]];
                ${'$'}store = new RR\Store(rr_db(${'$'}config));
                echo RR\Validation::json([
                    'ready' => ${'$'}store->ready(),
                    'case' => ${'$'}store->getCase(${'$'}input->case_id),
                    'case_replay' => ${'$'}store->createCase(RR\Validation::casePayload(${'$'}input->case_payload)),
                    'draft_replay' => ${'$'}store->createDraft(${'$'}input->case_id, RR\Validation::draftPayload(${'$'}input->draft_payload)),
                ]);
            } catch (Throwable ${'$'}error) {
                fwrite(STDERR, 'Legacy PHP compatibility test failed.');
                exit(1);
            }
        """.trimIndent()
        val process = ProcessBuilder(phpBinary!!, "-r", script).redirectErrorStream(true).apply {
            environment()["RR_TEST_LEGACY_BOOTSTRAP"] = bootstrap.path
        }.start()
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(Contract.canonicalJson(buildJsonObject {
                    put("case_id", caseId)
                    put("case_payload", casePayload)
                    put("draft_payload", draftPayload)
                }))
            }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Legacy PHP compatibility test timed out")
            assertEquals(0, process.exitValue(), "Legacy PHP compatibility test failed")
            val result = Contract.json.parseToJsonElement(process.inputStream.bufferedReader().readText()).jsonObject
            assertEquals(JsonPrimitive(true), result["ready"])
            assertEquals(case.body, result["case"])
            assertEquals(JsonPrimitive(200), result.getValue("case_replay").jsonArray[0])
            assertEquals(case.body, result.getValue("case_replay").jsonArray[1])
            assertEquals(JsonPrimitive(200), result.getValue("draft_replay").jsonArray[0])
            assertEquals(draft.body, result.getValue("draft_replay").jsonArray[1])
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun createCase(payload: JsonObject): ApiResult = store.createCase(payload).also {
        createdCases += it.body.getValue("id").jsonPrimitive.content
    }

    private fun assertProblem(status: Int, code: String, block: () -> Unit) {
        val problem = assertFailsWith<ApiProblem>(block = block)
        assertEquals(status, problem.status)
        assertEquals(code, problem.code)
    }

    private fun casePayload(): JsonObject = Contract.casePayload(buildJsonObject {
        put("schema_version", "m1-cis-v1")
        put("synthetic", true)
        put("client_request_id", UUID.randomUUID().toString())
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
            put("situation", "Вымышленная репетиция: персонажи спорят о бытовых делах.")
            put("success", "Вымышленный пример: спокойный разговор.")
            put("failure", "Вымышленный пример: множество сообщений.")
        })
    })
}

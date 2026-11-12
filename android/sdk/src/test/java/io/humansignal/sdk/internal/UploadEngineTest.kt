package io.humansignal.sdk.internal

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

class UploadEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var store: LocalStore
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    /** path → (kod, gövde) kuralları; ilk eşleşen kullanılır. */
    private val rules = CopyOnWriteArrayList<Pair<(RecordedRequest) -> Boolean, MockResponse>>()

    private val local = "aaaaaaaa-1111-4111-8111-000000000001"
    private val serverId = "5e7e7e7e-0000-4000-8000-000000000009"

    @Before fun setUp() {
        store = LocalStore(tmp.root, AesGcmCipher { key }, 10_000_000)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                rules.firstOrNull { it.first(request) }?.let { return it.second }
                val p = request.path!!
                return when {
                    request.method == "POST" && p == "/v1/sessions" -> json(201, """{"session_id":"$serverId","status":"open","max_chunk_bytes":5242880}""")
                    request.method == "PUT" && p.contains("/chunks/") -> json(201, """{"status":"stored","seq":${p.substringAfterLast('/')}}""")
                    request.method == "POST" && p.endsWith("/complete") -> json(200, """{"status":"complete","missing":[]}""")
                    else -> json(404, """{"error":{"code":"NOT_FOUND","message":"x","details":{}}}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun err(code: Int, c: String) = json(code, """{"error":{"code":"$c","message":"x","details":{}}}""")

    private fun engine() = UploadEngine(store, ApiClient(server.url("/").toString(), "test-key", "test", 5))

    private fun session(state: String, chunks: Int, extra: Map<String, Any?> = emptyMap()) {
        store.writeSessionMeta(local, linkedMapOf(
            "state" to state, "local_session_id" to local, "server_session_id" to null, "install_id" to "inst-0000-1",
            "participant" to null, "consent_version" to "v1",
            "label" to mapOf("kind" to "bot", "bot_tool" to "adb_input", "bot_host" to "real_device"),
            "context" to mapOf("placement" to "table", "posture" to "na", "hands" to "na"), "notes" to null,
            "device" to mapOf("device" to mapOf("model" to "X")), "clock" to mapOf("t0_elapsed_ns" to 1L, "t0_uptime_ns" to 1L, "t0_wall_ms" to 1L),
            "sdk_version" to "1.0.0", "app_version" to "1", "config_version" to 1, "started_at_wall_ms" to 1L,
            "total_chunks" to chunks, "stop_reason" to "user", "ended_at_wall_ms" to 2L,
            "stats" to mapOf("duration_s" to 10.0, "touch_events" to 5), "dropped_chunks" to 0,
        ) + extra)
        for (s in 0 until chunks) {
            val gz = ChunkEncoder.gzip("{\"k\":\"header\",\"seq\":$s}\n".toByteArray())
            store.writeChunk(local, null, EncodedChunk(s, s * 5L, s * 5L + 5, gz, ChunkEncoder.sha256Hex(gz), 1, listOf("sensor", "touch"), 10))
        }
    }

    private fun body(r: RecordedRequest) = JsonParser.parseObject(r.body.readUtf8())

    @Test fun registersUploadsInOrderCompletesAndCleansUp() {
        session("closed", 3)
        assertEquals(UploadOutcome.DONE, engine().runOnce())

        assertEquals(listOf("POST /v1/sessions", "PUT /v1/sessions/$serverId/chunks/0", "PUT /v1/sessions/$serverId/chunks/1",
            "PUT /v1/sessions/$serverId/chunks/2", "POST /v1/sessions/$serverId/complete"), requests.map { "${it.method} ${it.path}" })

        val create = body(requests[0])
        assertEquals(local, create["local_session_id"])
        assertEquals("test-key", requests[0].getHeader("X-Api-Key"))
        val put = requests[1]
        assertEquals("application/octet-stream", put.getHeader("Content-Type"))
        assertNull(put.getHeader("Content-Encoding"))
        assertEquals("sensor,touch", put.getHeader("X-Chunk-Streams"))
        assertEquals("0", put.getHeader("X-Chunk-T-Start"))
        val sent = put.body.readByteArray()
        assertEquals(ChunkEncoder.sha256Hex(sent), put.getHeader("X-Chunk-Sha256"))
        assertArrayEquals(ChunkEncoder.gzip("{\"k\":\"header\",\"seq\":0}\n".toByteArray()), sent)

        val complete = body(requests[4])
        assertEquals(3L, complete["total_chunks"])
        assertEquals("user", complete["stop_reason"])
        @Suppress("UNCHECKED_CAST") val stats = complete["stats"] as Map<String, Any?>
        assertEquals(0L, stats["failed_chunks"]); assertEquals(5L, stats["touch_events"])

        assertEquals(0, store.pendingCount())
        assertFalse(File(tmp.root, local).exists())
    }

    @Test fun openSessionUploadsChunksButDoesNotComplete() {
        session("open", 2)
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertFalse(requests.any { it.path!!.endsWith("/complete") })
        assertEquals(serverId, store.readSessionMeta(local)!!["server_session_id"])
        assertTrue(File(tmp.root, local).exists())
    }

    @Test fun A3_offlineKeepsEverythingThenUploadsWhenOnline() {
        session("closed", 4)
        val port = server.port
        server.shutdown()
        assertEquals(UploadOutcome.RETRY, UploadEngine(store, ApiClient("http://127.0.0.1:$port", "k", "t", 2)).runOnce())
        assertEquals(4, store.pendingCount())
        assertNull(store.readSessionMeta(local)!!["server_session_id"])

        server = MockWebServer().also { s -> s.dispatcher = this.server.dispatcher }
        setUp() // yeni sunucu, aynı kurallar
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertEquals(4, requests.count { it.method == "PUT" })
        assertEquals(0, store.pendingCount())
    }

    @Test fun serverErrorRetriesAndKeepsChunk() {
        session("closed", 2)
        rules.add({ r: RecordedRequest -> r.path!!.endsWith("/chunks/1") } to err(503, "INTERNAL"))
        assertEquals(UploadOutcome.RETRY, engine().runOnce())
        assertEquals(listOf(1), store.pending().map { it.seq })
        rules.clear()
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertEquals(1, requests.count { it.method == "POST" && it.path == "/v1/sessions" }) // yeniden kayıt yok
    }

    @Test fun conflictAndPermanentFailuresAreMovedAsideAndCounted() {
        session("closed", 3)
        rules.add({ r: RecordedRequest -> r.path!!.endsWith("/chunks/0") } to err(409, "CHUNK_CONFLICT"))
        rules.add({ r: RecordedRequest -> r.path!!.endsWith("/chunks/1") } to err(400, "BAD_CHECKSUM"))
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertTrue(File(tmp.root, "conflict/$local/000000.ndjson.gz.enc").exists())
        assertTrue(File(tmp.root, "failed/$local/000001.ndjson.gz.enc").exists())
        @Suppress("UNCHECKED_CAST")
        val stats = body(requests.last())["stats"] as Map<String, Any?>
        assertEquals(2L, stats["failed_chunks"])
    }

    @Test fun authFailureStops() {
        session("closed", 2)
        rules.add({ r: RecordedRequest -> r.path!!.contains("/chunks/") } to err(401, "UNAUTHORIZED"))
        assertEquals(UploadOutcome.AUTH_FAILED, engine().runOnce())
        assertEquals(1, requests.count { it.method == "PUT" })
        assertEquals(2, store.pendingCount())
    }

    @Test fun duplicateResponseCountsAsUploaded() {
        session("closed", 1)
        rules.add({ r: RecordedRequest -> r.method == "PUT" } to json(200, """{"status":"duplicate","seq":0}"""))
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertEquals(0, store.pendingCount())
    }

    @Test fun unknownServerSessionTriggersReRegistration() {
        session("closed", 1, mapOf("server_session_id" to "dead0000-0000-4000-8000-000000000000"))
        rules.add({ r: RecordedRequest -> r.path!!.contains("dead0000") } to err(404, "SESSION_NOT_FOUND"))
        assertEquals(UploadOutcome.RETRY, engine().runOnce())
        assertNull(store.readSessionMeta(local)!!["server_session_id"])
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertTrue(requests.any { it.method == "PUT" && it.path!!.contains(serverId) })
    }

    @Test fun rejectedRegistrationMovesChunksToFailed() {
        session("closed", 2)
        rules.add({ r: RecordedRequest -> r.path == "/v1/sessions" } to err(400, "VALIDATION_ERROR"))
        assertEquals(UploadOutcome.DONE, engine().runOnce())
        assertEquals(0, store.pendingCount())
        assertTrue(File(tmp.root, "failed/$local/000001.ndjson.gz.enc").exists())
        assertFalse(File(tmp.root, local).exists())
    }

    @Test fun invalidBaseUrlIsAConfigErrorNotACrash() {
        session("closed", 1)
        assertEquals(UploadOutcome.CONFIG_ERROR, UploadEngine(store, ApiClient("not a url", "k", "t", 2)).runOnce())
        assertEquals(1, store.pendingCount())
    }

    @Test fun backoffIsExponentialFrom30sCappedAtOneHour() {
        assertEquals(listOf(30L, 60L, 120L, 240L, 480L, 960L, 1920L, 3600L, 3600L), (1..9).map { UploadWorker.backoffSeconds(it) })
    }
}

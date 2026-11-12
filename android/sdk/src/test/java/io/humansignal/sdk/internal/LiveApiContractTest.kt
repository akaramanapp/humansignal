package io.humansignal.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

/**
 * Gerçek API'ye karşı sözleşme testi (isteğe bağlı). Çalıştırmak için:
 * ```
 * HS_API_URL=http://localhost:8787 HS_API_KEY=dev-app-key ./gradlew :sdk:testDebugUnitTest --tests '*LiveApiContractTest*'
 * ```
 * Değişkenler yoksa atlanır.
 */
class LiveApiContractTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun sdkUploadMatchesRealApi() {
        val url = System.getenv("HS_API_URL")
        val key = System.getenv("HS_API_KEY")
        assumeTrue("HS_API_URL / HS_API_KEY not set", !url.isNullOrBlank() && !key.isNullOrBlank())

        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = LocalStore(tmp.root, AesGcmCipher { aes }, 50_000_000)
        val sid = UUID.randomUUID().toString()

        // SDK'nın gerçekte yazdığı biçimde bir oturum: sensör + dokunma + marker.
        val clock = SessionClock(object : TimeSource {
            var t = 1_000_000_000L
            override fun elapsedNanos() = t.also { t += 1000 }
            override fun uptimeNanos() = t - 500_000_000L
            override fun wallMillis() = 1_762_934_400_000L
        })
        store.writeSessionMeta(sid, linkedMapOf(
            "v" to 1, "state" to "open", "local_session_id" to sid, "server_session_id" to null,
            "install_id" to "contract-test-install-0001", "participant" to linkedMapOf("code" to "P900", "handedness" to "right", "age_range" to "25-34", "gender" to null),
            "consent_version" to "2026-11-v1",
            "label" to linkedMapOf("kind" to "human", "bot_tool" to null, "bot_host" to null),
            "context" to linkedMapOf("placement" to "hand", "posture" to "sitting", "hands" to "one_right"),
            "notes" to "contract-test",
            "device" to linkedMapOf("device" to linkedMapOf("manufacturer" to "test", "model" to "jvm", "android_sdk" to 35), "integrity" to linkedMapOf("adb_enabled" to true)),
            "clock" to clock.toMetaMap(), "sdk_version" to "1.0.0", "app_version" to "0.1.0 (1)", "config_version" to 1,
            "mode" to "research", "started_at_wall_ms" to 1L, "dropped_chunks" to 0,
        ))
        val total = 3
        for (seq in 0 until total) {
            val acc = ColumnBuffer("acc", 3).apply { for (i in 0 until 500) add(seq * 5_000_000_000L + i * 10_000_000L, floatArrayOf(0.1f, 9.8f, i / 100f)) }
            val ev = EventLine(seq * 5_000_000_000L + 1, "marker", JsonWriter.obj { field("k", "marker"); field("t", seq * 5_000_000_000L + 1); field("name", "task_start"); name("attrs").any(mapOf("task" to "t1")) })
            val enc = ChunkEncoder.encode(ChunkContent(seq, seq * 5_000_000_000L, (seq + 1) * 5_000_000_000L, listOf(acc), listOf(ev)), sid, null, "1.0.0")
            store.writeChunk(sid, null, enc)
        }

        val api = ApiClient(url!!, key!!, "contract-test", 10)
        val engine = UploadEngine(store, api, log = { println("upload: $it") })

        // Açık oturum: kayıt + parçalar, complete yok
        assertEquals(UploadOutcome.DONE, engine.runOnce())
        assertEquals(0, store.pendingCount())
        val serverId = store.readSessionMeta(sid)!!.str("server_session_id")!!

        // Aynı parçayı tekrar göndermek çiftleme yapmamalı (A4)
        val again = ColumnBuffer("acc", 3).apply { for (i in 0 until 500) add(i * 10_000_000L, floatArrayOf(0.1f, 9.8f, i / 100f)) }
        val dupEv = EventLine(1, "marker", JsonWriter.obj { field("k", "marker"); field("t", 1L); field("name", "task_start"); name("attrs").any(mapOf("task" to "t1")) })
        val dup = ChunkEncoder.encode(ChunkContent(0, 0, 5_000_000_000L, listOf(again), listOf(dupEv)), sid, null, "1.0.0")
        store.writeChunk(sid, serverId, dup)
        assertEquals(UploadOutcome.DONE, engine.runOnce())
        assertEquals(0, store.pendingCount())

        // Kapat → complete
        store.updateSessionMeta(sid) {
            it["state"] = "closed"; it["total_chunks"] = total; it["stop_reason"] = "user"; it["ended_at_wall_ms"] = 1_762_935_000_000L
            it["stats"] = mapOf("duration_s" to 15.0, "sensor_hz" to mapOf("acc" to 100.0), "touch_events" to 0)
        }
        assertEquals(UploadOutcome.DONE, engine.runOnce())
        assertFalse("local session must be cleaned up after complete", File(tmp.root, sid).exists())
        println("CONTRACT_OK server_session_id=$serverId")
    }
}

package io.humansignal.sdk.internal

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import kotlin.random.Random

/**
 * §3.11 için kaba ölçüm: gerçekçi 5 sn'lik bir parçayı (emülatörde gözlenen hızlarla tüm akışlar) kodlama +
 * gzip + SHA-256 + AES-GCM süresi. Sonuç PERF.md'ye yazılır; eşik geniş tutulur (CI makinesine göre değişir).
 */
class EncodePerfTest {
    private fun chunk(seed: Int): ChunkContent {
        val r = Random(seed)
        // (akış, boyut, Hz) — emülatör ölçümü: çoğu sensör 100 Hz, gyr_u 200 Hz, mag_u 50 Hz, press 10 Hz
        val streams = listOf("acc" to 3 to 100, "gyr" to 3 to 100, "gyr_u" to 6 to 200, "mag" to 3 to 100, "mag_u" to 6 to 50,
            "grav" to 3 to 100, "lin" to 3 to 100, "rot" to 5 to 100, "grot" to 4 to 100, "press" to 1 to 10)
        val bufs = streams.map { (sd, hz) ->
            val (s, d) = sd
            ColumnBuffer(s, d).apply {
                for (i in 0 until hz * 5) add(i * (1_000_000_000L / hz), FloatArray(d) { r.nextFloat() * 20 - 10 })
            }
        }
        val touches = (0 until 300).map { i ->
            EventLine(i * 16_000_000L, "touch", """{"k":"touch","t":${i * 16_000_000L},"h":0,"a":"MOVE","pc":1,"p":[{"id":0,"x":512.3,"y":1204.8,"pr":0.43,"sz":0.12,"ma":38.1,"mi":31,"o":-0.21,"tool":"finger"}],"src":"touchscreen","dev":3,"fl":0,"scr":"TaskActivity"}""")
        }
        return ChunkContent(seed, 0, 5_000_000_000L, bufs, touches)
    }

    @Test fun encodeFiveSecondChunk() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmCipher { key }
        repeat(5) { ChunkEncoder.encode(chunk(it), "local", null, "1.0.0").also { e -> cipher.encrypt(e.gzip) } } // ısınma
        val n = 20
        var raw = 0; var gz = 0
        val t0 = System.nanoTime()
        for (i in 0 until n) {
            val e = ChunkEncoder.encode(chunk(100 + i), "local", null, "1.0.0")
            cipher.encrypt(e.gzip)
            raw += e.uncompressedBytes; gz += e.gzip.size
        }
        val perChunkMs = (System.nanoTime() - t0) / 1e6 / n
        val buildT0 = System.nanoTime(); repeat(n) { chunk(200 + it) }; val buildMs = (System.nanoTime() - buildT0) / 1e6 / n
        val encodeMs = perChunkMs - buildMs
        println(String.format(java.util.Locale.US,
            "PERF encode: %.1f ms per 5 s chunk (%.2f%% of one core), raw %.0f KB → gzip %.0f KB (%.1fx), %.2f MB/min",
            encodeMs, encodeMs / 5000 * 100, raw / 1024.0 / n, gz / 1024.0 / n, raw.toDouble() / gz, gz / 1024.0 / 1024.0 / n * 12))
        assertTrue("encoding a 5 s chunk should take well under 1 s", encodeMs < 1000)
    }
}

package io.humansignal.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class ChunkTest {
    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).readBytes().toString(Charsets.UTF_8)

    @Test fun columnarSensorEncoding() {
        val b = ColumnBuffer("acc", 3, initialCapacity = 1)  // büyümeyi de test eder
        b.add(85_000_123_000L, floatArrayOf(0.124f, 9.71f, 0.331f))
        b.add(85_010_119_000L, floatArrayOf(0.131f, 9.702f, 0.309f, 42f)) // fazla değer kırpılır
        b.add(85_020_000_000L, floatArrayOf(1f))                         // eksik değer → null
        val w = JsonWriter()
        b.writeLine(w)
        assertEquals(
            """{"k":"sensor","s":"acc","t":[85000123000,85010119000,85020000000],""" +
                """"v":[[0.124,9.71,0.331],[0.131,9.702,0.309],[1,null,null]]}""",
            w.toString(),
        )
    }

    @Test fun ndjsonHasHeaderFirstAndOneObjectPerLine() {
        val acc = ColumnBuffer("acc", 3).apply { add(1L, floatArrayOf(1f, 2f, 3f)) }
        val gyr = ColumnBuffer("gyr", 3).apply { add(2L, floatArrayOf(0.1f, 0.2f, 0.3f)) }
        val empty = ColumnBuffer("mag", 3)
        val ev = EventLine(3L, "marker", """{"k":"marker","t":3,"name":"task_start","attrs":{}}""")
        val c = ChunkContent(17, 85_000_000_000L, 90_000_000_000L, listOf(acc, gyr, empty), listOf(ev))

        val enc = ChunkEncoder.encode(c, "6f0c-local-id", null, "1.0.0")
        val lines = gunzip(enc.gzip).trimEnd('\n').split('\n')

        assertEquals(4, lines.size)
        assertEquals(4, enc.records)
        val header = JsonParser.parseObject(lines[0])
        assertEquals("header", header["k"])
        assertEquals(1L, header["v"])
        assertEquals(17L, header["seq"])
        assertEquals(85_000_000_000L, header["t_start"])
        assertEquals(90_000_000_000L, header["t_end"])
        assertEquals(null, header["session_id"])
        assertEquals("6f0c-local-id", header["local_session_id"])
        assertEquals("1.0.0", header["sdk"])
        lines.forEach { JsonParser.parseObject(it) } // her satır geçerli JSON
        assertEquals(listOf("sensor", "marker"), enc.streams)
        assertEquals(ChunkEncoder.sha256Hex(enc.gzip), enc.sha256)
        assertEquals(64, enc.sha256.length)
    }

    @Test fun emptyChunkDetection() {
        assertTrue(ChunkContent(0, 0, 1, listOf(ColumnBuffer("acc", 3)), emptyList()).isEmpty)
    }

    @Test fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ChunkEncoder.sha256Hex("abc".toByteArray()),
        )
    }
}

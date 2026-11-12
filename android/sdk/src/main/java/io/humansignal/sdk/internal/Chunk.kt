package io.humansignal.sdk.internal

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Tek bir sensör akışı için büyüyebilen sütunlu tampon (§3.8). Yalnızca sensör thread'inden yazılır;
 * parça kesilirken [ChunkAssembler] tarafından devralınıp yenisiyle değiştirilir.
 */
internal class ColumnBuffer(val stream: String, val dims: Int, initialCapacity: Int = 1024) {
    var size = 0
        private set
    private var times = LongArray(initialCapacity)
    private var vals = FloatArray(initialCapacity * dims)

    /** `src` dizisinin ilk `dims` elemanını kopyalar (SensorEvent.values yeniden kullanıldığı için şart). */
    fun add(t: Long, src: FloatArray) {
        if (size == times.size) grow()
        times[size] = t
        val base = size * dims
        val n = minOf(dims, src.size)
        System.arraycopy(src, 0, vals, base, n)
        for (j in n until dims) vals[base + j] = Float.NaN
        size++
    }

    fun time(i: Int): Long = times[i]
    fun value(i: Int, d: Int): Float = vals[i * dims + d]

    /** JSON boyutu için kaba tahmin (2 MB sınırı kontrolünde kullanılır). */
    fun estimatedBytes(): Int = 40 + size * (14 + dims * 9)

    private fun grow() {
        times = times.copyOf(times.size * 2)
        vals = vals.copyOf(vals.size * 2)
    }

    /** Sütunlu satırı yazar: `{"k":"sensor","s":"acc","t":[...],"v":[[...],...]}` */
    fun writeLine(w: JsonWriter) {
        w.beginObject().field("k", "sensor").field("s", stream)
        w.name("t").beginArray()
        for (i in 0 until size) w.value(times[i])
        w.endArray()
        w.name("v").beginArray()
        for (i in 0 until size) {
            w.beginArray()
            val base = i * dims
            for (d in 0 until dims) {
                val f = vals[base + d]
                if (f.isNaN()) w.nullValue() else w.sig5(f)
            }
            w.endArray()
        }
        w.endArray()
        w.endObject()
    }
}

/** Parçaya eklenecek tek satırlık olay (önceden JSON'a kodlanmış). */
internal class EventLine(val t: Long, val stream: String, val json: String)

/** Bir parçanın ham içeriği (gzip öncesi). */
internal class ChunkContent(
    val seq: Int,
    val tStart: Long,
    val tEnd: Long,
    val sensors: List<ColumnBuffer>,
    val events: List<EventLine>,
) {
    val isEmpty: Boolean get() = sensors.all { it.size == 0 } && events.isEmpty()

    /** Bu parçada bulunan akış türleri (`X-Chunk-Streams`). */
    fun streams(): List<String> {
        val s = LinkedHashSet<String>()
        if (sensors.any { it.size > 0 }) s.add("sensor")
        events.forEach { s.add(it.stream) }
        return s.toList()
    }
}

/** Kodlanmış parça: gzip baytları + meta. */
internal class EncodedChunk(
    val seq: Int,
    val tStart: Long,
    val tEnd: Long,
    val gzip: ByteArray,
    val sha256: String,
    val records: Int,
    val streams: List<String>,
    val uncompressedBytes: Int,
)

internal object ChunkEncoder {
    const val FORMAT_VERSION = 1

    /** NDJSON metnini üretir. İlk satır her zaman `header` (§3.8). */
    fun toNdjson(
        c: ChunkContent,
        localSessionId: String,
        serverSessionId: String?,
        sdkVersion: String,
    ): Pair<String, Int> {
        val w = JsonWriter(StringBuilder(64 * 1024))
        var records = 0
        w.beginObject()
            .field("k", "header").field("v", FORMAT_VERSION)
            .field("session_id", serverSessionId)
            .field("local_session_id", localSessionId)
            .field("seq", c.seq).field("t_start", c.tStart).field("t_end", c.tEnd)
            .field("sdk", sdkVersion)
            .endObject()
        records++
        for (b in c.sensors) {
            if (b.size == 0) continue
            w.sb.append('\n')
            b.writeLine(newLineWriter(w))
            records++
        }
        for (e in c.events) {
            w.sb.append('\n').append(e.json)
            records++
        }
        w.sb.append('\n')
        return w.sb.toString() to records
    }

    fun encode(c: ChunkContent, localSessionId: String, serverSessionId: String?, sdkVersion: String): EncodedChunk {
        val (text, records) = toNdjson(c, localSessionId, serverSessionId, sdkVersion)
        val raw = text.toByteArray(Charsets.UTF_8)
        val gz = gzip(raw)
        return EncodedChunk(c.seq, c.tStart, c.tEnd, gz, sha256Hex(gz), records, c.streams(), raw.size)
    }

    fun gzip(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(raw.size / 4 + 64)
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    fun sha256Hex(b: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(b)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(d.size * 2)
        for (x in d) { val v = x.toInt() and 0xff; sb.append(hex[v ushr 4]).append(hex[v and 0x0f]) }
        return sb.toString()
    }

    // Aynı StringBuilder'a yazan, virgül durumu sıfırlanmış yeni bir yazıcı (her satır bağımsız JSON).
    private fun newLineWriter(w: JsonWriter): JsonWriter = JsonWriter(w.sb)
}

package io.humansignal.sdk.internal

import java.io.File
import java.io.IOException

/** Yükleme kuyruğundaki bir parça (§3.9 "Kuyruk"). */
internal data class QueueEntry(
    val localSessionId: String,
    val serverSessionId: String?,
    val seq: Int,
    val path: String,           // kök klasöre göre görece
    val sha256: String,         // gzip baytlarının özeti (şifreleme öncesi)
    val state: String,          // pending | uploaded
    val attempts: Int,
    val sizeBytes: Long,        // diskteki (şifreli) boyut
    val tStart: Long,
    val tEnd: Long,
    val streams: List<String>,
    val records: Int,
    val order: Long,            // eklenme sırası (kota için "en eski")
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "local_session_id" to localSessionId, "server_session_id" to serverSessionId, "seq" to seq,
        "path" to path, "sha256" to sha256, "state" to state, "attempts" to attempts, "size" to sizeBytes,
        "t_start" to tStart, "t_end" to tEnd, "streams" to streams, "records" to records, "order" to order,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>) = QueueEntry(
            localSessionId = m.str("local_session_id")!!,
            serverSessionId = m.str("server_session_id"),
            seq = m.int("seq")!!,
            path = m.str("path")!!,
            sha256 = m.str("sha256")!!,
            state = m.str("state") ?: STATE_PENDING,
            attempts = m.int("attempts") ?: 0,
            sizeBytes = m.long("size") ?: 0,
            tStart = m.long("t_start") ?: 0,
            tEnd = m.long("t_end") ?: 0,
            streams = m.list("streams")?.map { it.toString() } ?: emptyList(),
            records = m.int("records") ?: 0,
            order = m.long("order") ?: 0,
        )
    }
}

internal const val STATE_PENDING = "pending"
internal const val STATE_UPLOADED = "uploaded"

/** [LocalStore.writeChunk] sonucu. */
internal data class WriteResult(val entry: QueueEntry, val droppedEntries: List<QueueEntry>)

/**
 * Telefonda saklama (§3.9). Kök: `noBackupFilesDir/humansignal/`.
 *
 * ```
 * humansignal/
 *   queue.json                                 # yükleme kuyruğu (düz JSON; Room/SQLite yok)
 *   {local_session_id}/session.json            # oturum meta verisi (Uploader için)
 *   {local_session_id}/{seq:000000}.ndjson.gz.enc
 *   conflict/  failed/                         # M3 Uploader'ın taşıdığı dosyalar
 * ```
 *
 * Tüm metotlar thread-safe'tir (tek kilit). Disk işi çağıran thread'de yapılır; ana thread'den çağırmayın.
 */
internal class LocalStore(
    val root: File,
    private val cipher: ChunkCipher,
    @Volatile var maxBytes: Long,
) {
    private val lock = Any()
    private val queueFile = File(root, "queue.json")
    private val entries = ArrayList<QueueEntry>()
    private var nextOrder = 0L

    init {
        root.mkdirs()
        load()
    }

    fun sessionDir(localSessionId: String): File {
        require(SAFE_ID.matches(localSessionId)) { "bad session id" }
        return File(root, localSessionId)
    }

    /** Parçayı şifreleyip yazar, kuyruğa ekler, kotayı uygular. */
    fun writeChunk(localSessionId: String, serverSessionId: String?, chunk: EncodedChunk): WriteResult {
        val dir = sessionDir(localSessionId).apply { mkdirs() }
        val name = chunkFileName(chunk.seq)
        val file = File(dir, name)
        val blob = cipher.encrypt(chunk.gzip)
        atomicWrite(file, blob)
        synchronized(lock) {
            entries.removeAll { it.localSessionId == localSessionId && it.seq == chunk.seq }
            val e = QueueEntry(
                localSessionId, serverSessionId, chunk.seq, "$localSessionId/$name", chunk.sha256,
                STATE_PENDING, 0, blob.size.toLong(), chunk.tStart, chunk.tEnd, chunk.streams, chunk.records,
                nextOrder++,
            )
            entries.add(e)
            val dropped = enforceQuotaLocked()
            persistLocked()
            return WriteResult(e, dropped)
        }
    }

    /** Diskteki parçayı çözer ve gzip baytlarını döner (yükleme öncesi). */
    fun readChunk(e: QueueEntry): ByteArray = cipher.decrypt(File(root, e.path).readBytes())

    /** Sunucu 200/201 döndü: dosya hemen silinir, kayıt kuyruktan çıkar. */
    fun markUploaded(localSessionId: String, seq: Int) {
        synchronized(lock) {
            val e = entries.firstOrNull { it.localSessionId == localSessionId && it.seq == seq } ?: return
            File(root, e.path).delete()
            entries.remove(e)
            persistLocked()
        }
    }

    fun incrementAttempts(localSessionId: String, seq: Int) = synchronized(lock) {
        val i = entries.indexOfFirst { it.localSessionId == localSessionId && it.seq == seq }
        if (i >= 0) { entries[i] = entries[i].copy(attempts = entries[i].attempts + 1); persistLocked() }
    }

    /** Parçayı `conflict/` ya da `failed/` klasörüne taşır ve kuyruktan çıkarır (§3.10). */
    fun moveAside(localSessionId: String, seq: Int, folder: String) {
        require(folder == "conflict" || folder == "failed")
        synchronized(lock) {
            val e = entries.firstOrNull { it.localSessionId == localSessionId && it.seq == seq } ?: return
            val dst = File(File(root, folder), e.path).apply { parentFile?.mkdirs() }
            if (!File(root, e.path).renameTo(dst)) File(root, e.path).delete()
            entries.remove(e)
            persistLocked()
        }
    }

    fun setServerSessionId(localSessionId: String, serverSessionId: String) = synchronized(lock) {
        for (i in entries.indices) {
            if (entries[i].localSessionId == localSessionId) entries[i] = entries[i].copy(serverSessionId = serverSessionId)
        }
        persistLocked()
    }

    fun pending(): List<QueueEntry> = synchronized(lock) {
        entries.filter { it.state == STATE_PENDING }.sortedWith(compareBy({ it.order }, { it.seq }))
    }

    fun pendingCount(): Int = synchronized(lock) { entries.count { it.state == STATE_PENDING } }

    fun pendingBytes(): Long = synchronized(lock) { entries.filter { it.state == STATE_PENDING }.sumOf { it.sizeBytes } }

    // ---- oturum meta verisi ----

    fun writeSessionMeta(localSessionId: String, meta: Map<String, Any?>) {
        val dir = sessionDir(localSessionId).apply { mkdirs() }
        atomicWrite(File(dir, SESSION_FILE), JsonWriter.encode(meta).toByteArray(Charsets.UTF_8))
    }

    fun readSessionMeta(localSessionId: String): Map<String, Any?>? {
        val f = File(sessionDir(localSessionId), SESSION_FILE)
        if (!f.exists()) return null
        return try { JsonParser.parseObject(f.readText()) } catch (e: Exception) { null }
    }

    /** Oturum meta verisini kilit altında okuyup günceller. */
    fun updateSessionMeta(localSessionId: String, fn: (MutableMap<String, Any?>) -> Unit) = synchronized(lock) {
        val m = LinkedHashMap(readSessionMeta(localSessionId) ?: emptyMap())
        fn(m)
        writeSessionMeta(localSessionId, m)
    }

    fun sessionIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory && File(it, SESSION_FILE).exists() }?.map { it.name } ?: emptyList()

    /** Tüm yerel veriyi siler (rıza geri alma / test). */
    fun deleteAll() = synchronized(lock) {
        entries.clear()
        root.listFiles()?.forEach { it.deleteRecursively() }
        persistLocked()
    }

    /** Diskte kaplanan toplam boyut. */
    fun diskBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---- iç ----

    /** En eski yüklenmemiş parçaları siler; düşürülen her parça için oturumun `dropped_chunks` sayacı artar. */
    private fun enforceQuotaLocked(): List<QueueEntry> {
        val dropped = ArrayList<QueueEntry>()
        var total = entries.filter { it.state == STATE_PENDING }.sumOf { it.sizeBytes }
        if (total <= maxBytes) return dropped
        val byAge = entries.filter { it.state == STATE_PENDING }.sortedBy { it.order }
        for (e in byAge) {
            if (total <= maxBytes) break
            File(root, e.path).delete()
            entries.remove(e)
            total -= e.sizeBytes
            dropped.add(e)
        }
        dropped.groupBy { it.localSessionId }.forEach { (sid, list) ->
            try {
                val m = LinkedHashMap(readSessionMeta(sid) ?: emptyMap())
                m["dropped_chunks"] = ((m["dropped_chunks"] as? Number)?.toInt() ?: 0) + list.size
                writeSessionMeta(sid, m)
            } catch (_: Exception) { /* meta yoksa sayacı atla */ }
        }
        return dropped
    }

    private fun load() {
        if (!queueFile.exists()) return
        try {
            val m = JsonParser.parseObject(queueFile.readText())
            m.list("entries")?.forEach { x ->
                @Suppress("UNCHECKED_CAST")
                val e = QueueEntry.fromMap(x as Map<String, Any?>)
                if (File(root, e.path).exists()) entries.add(e)
            }
            nextOrder = maxOf(m.long("next_order") ?: 0, (entries.maxOfOrNull { it.order } ?: -1) + 1)
        } catch (e: Exception) {
            // Bozuk kuyruk: dosyaları kaybetmemek için diskten yeniden kur.
            rebuildFromDisk()
        }
    }

    private fun rebuildFromDisk() {
        entries.clear()
        root.listFiles()?.filter { it.isDirectory && SAFE_ID.matches(it.name) }?.forEach { dir ->
            dir.listFiles()?.filter { it.name.endsWith(CHUNK_SUFFIX) }?.sortedBy { it.name }?.forEach { f ->
                val seq = f.name.removeSuffix(CHUNK_SUFFIX).toIntOrNull() ?: return@forEach
                try {
                    val gz = cipher.decrypt(f.readBytes())
                    entries.add(
                        QueueEntry(dir.name, null, seq, "${dir.name}/${f.name}", ChunkEncoder.sha256Hex(gz),
                            STATE_PENDING, 0, f.length(), 0, 0, emptyList(), 0, nextOrder++),
                    )
                } catch (_: Exception) { /* çözülemeyen dosya atlanır */ }
            }
        }
        persistLocked()
    }

    private fun persistLocked() {
        val m = linkedMapOf("v" to 1, "next_order" to nextOrder, "entries" to entries.map { it.toMap() })
        atomicWrite(queueFile, JsonWriter.encode(m).toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val SESSION_FILE = "session.json"
        const val CHUNK_SUFFIX = ".ndjson.gz.enc"
        private val SAFE_ID = Regex("^[A-Za-z0-9-]{8,64}$")

        fun chunkFileName(seq: Int) = String.format("%06d", seq) + CHUNK_SUFFIX

        fun atomicWrite(f: File, bytes: ByteArray) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("rename failed: ${f.name}")
            }
        }
    }
}

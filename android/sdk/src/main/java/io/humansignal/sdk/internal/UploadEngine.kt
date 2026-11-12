package io.humansignal.sdk.internal

import java.io.IOException

/** Bir yükleme turunun sonucu. */
internal enum class UploadOutcome { DONE, RETRY, AUTH_FAILED, CONFIG_ERROR }

/**
 * Yükleme sırası (§3.10):
 *  1. Sunucu kaydı yoksa `POST /v1/sessions`; dönen `session_id` meta veriye ve kuyruğa yazılır.
 *  2. Parçalar `seq` sırasıyla `PUT /v1/sessions/{id}/chunks/{seq}` (paralel yok).
 *  3. Oturum kapandıysa ve tüm parçalar gittiyse `POST /v1/sessions/{id}/complete`; ardından yerel klasör silinir.
 *
 * Android'den bağımsızdır (JVM birim testli); WorkManager sarmalayıcısı [UploadWorker]'dır.
 */
internal class UploadEngine(
    private val store: LocalStore,
    private val api: ApiClient,
    private val onProgress: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    /** Kaydedilmemiş tek bir oturumu sunucuya kaydeder. Başarılıysa sunucu id'sini döner. */
    @Throws(IOException::class)
    fun register(localSessionId: String): Pair<UploadOutcome, String?> {
        val meta = store.readSessionMeta(localSessionId) ?: return UploadOutcome.DONE to null
        meta.str("server_session_id")?.let { return UploadOutcome.DONE to it }
        val r = api.createSession(createBody(meta))
        return when {
            r.ok -> {
                val sid = r.json?.str("session_id") ?: return UploadOutcome.RETRY to null
                store.updateSessionMeta(localSessionId) { it["server_session_id"] = sid }
                store.setServerSessionId(localSessionId, sid)
                log("registered local=$localSessionId server=$sid")
                UploadOutcome.DONE to sid
            }
            r.code == 401 || r.code == 403 -> UploadOutcome.AUTH_FAILED to null
            r.code == 429 || r.code >= 500 -> UploadOutcome.RETRY to null
            else -> {
                // Kalıcı doğrulama hatası: bu oturum asla kaydedilemez; parçaları failed/ klasörüne alınır.
                log("register rejected local=$localSessionId code=${r.code} err=${r.errorCode}")
                for (e in store.pending().filter { it.localSessionId == localSessionId }) store.moveAside(localSessionId, e.seq, "failed")
                store.updateSessionMeta(localSessionId) { it["upload_rejected"] = r.errorCode ?: r.code.toString() }
                UploadOutcome.DONE to null
            }
        }
    }

    /** Bekleyen her şeyi yüklemeye çalışır. Ağ/sunucu hatasında [UploadOutcome.RETRY]. */
    fun runOnce(): UploadOutcome {
        try {
            val sessions = store.sessionIds()
                .mapNotNull { id -> store.readSessionMeta(id)?.let { id to it } }
                .sortedBy { (_, m) -> m.long("started_at_wall_ms") ?: 0L }
            for ((sid, meta0) in sessions) {
                if (meta0["upload_rejected"] != null) { finishLocal(sid, meta0); continue }
                val outcome = uploadSession(sid)
                if (outcome != UploadOutcome.DONE) return outcome
            }
            return UploadOutcome.DONE
        } catch (e: IOException) {
            log("network: ${e.javaClass.simpleName}")
            return UploadOutcome.RETRY
        } catch (e: IllegalArgumentException) {
            // ör. geçersiz baseUrl (OkHttp URL ayrıştıramadı): yeniden denemek düzeltmez.
            log("config: ${e.javaClass.simpleName}: ${e.message}")
            return UploadOutcome.CONFIG_ERROR
        }
    }

    private fun uploadSession(sid: String): UploadOutcome {
        var server = store.readSessionMeta(sid)?.str("server_session_id")
        if (server == null) {
            val (o, id) = register(sid)
            if (o != UploadOutcome.DONE) return o
            server = id ?: run {
                // Kayıt kalıcı olarak reddedildi: kapalıysa yerel kaydı hemen temizle.
                store.readSessionMeta(sid)?.let { finishLocal(sid, it) }
                return UploadOutcome.DONE
            }
        }

        var reRegistered = false
        for (e in store.pending().filter { it.localSessionId == sid }.sortedBy { it.seq }) {
            val gz = try { store.readChunk(e) } catch (ex: Exception) {
                log("unreadable chunk seq=${e.seq}: ${ex.javaClass.simpleName}")
                store.moveAside(sid, e.seq, "failed"); bump(sid, "failed_chunks"); continue
            }
            val r = api.putChunk(server!!, e, gz)
            when {
                r.ok -> { store.markUploaded(sid, e.seq); onProgress() }
                r.code == 409 -> {
                    // CHUNK_CONFLICT ya da SESSION_CLOSED: dosya conflict/ klasörüne, devam (§3.10).
                    log("conflict seq=${e.seq} err=${r.errorCode}")
                    store.moveAside(sid, e.seq, "conflict"); bump(sid, "conflict_chunks"); onProgress()
                }
                r.code == 404 && !reRegistered -> {
                    // Sunucu oturumu bilmiyor: yeniden kaydol (POST idempotent) ve sonraki turda devam et.
                    log("server lost session $server; re-registering")
                    store.updateSessionMeta(sid) { it["server_session_id"] = null }
                    reRegistered = true
                    return UploadOutcome.RETRY
                }
                r.code == 400 || r.code == 404 || r.code == 413 || r.code == 422 -> {
                    log("permanent failure seq=${e.seq} code=${r.code} err=${r.errorCode}")
                    store.moveAside(sid, e.seq, "failed"); bump(sid, "failed_chunks"); onProgress()
                }
                r.code == 401 || r.code == 403 -> return UploadOutcome.AUTH_FAILED
                else -> return UploadOutcome.RETRY // 429, 5xx
            }
        }

        val meta = store.readSessionMeta(sid) ?: return UploadOutcome.DONE
        if (meta.str("state") != "closed") return UploadOutcome.DONE // kayıt sürüyor
        if (store.pending().any { it.localSessionId == sid }) return UploadOutcome.DONE

        val r = api.complete(server!!, completeBody(meta))
        return when {
            r.ok -> {
                val status = r.json?.str("status")
                @Suppress("UNCHECKED_CAST")
                val missing = (r.json?.list("missing") ?: emptyList<Any?>()).map { (it as Number).toInt() }
                log("complete server=$server status=$status missing=$missing")
                // Eksikler yerelde yoksa (düşürülmüş/başarısız) tekrar denemenin anlamı yok: yerel kaydı kapat.
                finishLocal(sid, meta)
                UploadOutcome.DONE
            }
            r.code == 401 || r.code == 403 -> UploadOutcome.AUTH_FAILED
            r.code == 404 -> { store.updateSessionMeta(sid) { it["server_session_id"] = null }; UploadOutcome.RETRY }
            r.code == 429 || r.code >= 500 -> UploadOutcome.RETRY
            else -> { log("complete rejected code=${r.code}"); finishLocal(sid, meta); UploadOutcome.DONE }
        }
    }

    private fun finishLocal(sid: String, meta: Map<String, Any?>) {
        if (meta.str("state") != "closed") return
        if (store.pending().any { it.localSessionId == sid }) return
        store.sessionDir(sid).deleteRecursively()
        onProgress()
    }

    private fun bump(sid: String, key: String) = store.updateSessionMeta(sid) {
        it[key] = ((it[key] as? Number)?.toInt() ?: 0) + 1
    }

    companion object {
        /** §6.4 `POST /v1/sessions` gövdesi — yerel `session.json` alanları zaten aynı adlarla tutulur. */
        fun createBody(m: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
            "local_session_id" to m["local_session_id"],
            "install_id" to m["install_id"],
            "participant" to m["participant"],
            "consent_version" to m["consent_version"],
            "label" to m["label"],
            "context" to m["context"],
            "notes" to m["notes"],
            "device" to (m["device"] ?: emptyMap<String, Any?>()),
            "clock" to m["clock"],
            "sdk_version" to m["sdk_version"],
            "app_version" to m["app_version"],
            "config_version" to m["config_version"],
        )

        fun completeBody(m: Map<String, Any?>): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val stats = LinkedHashMap((m["stats"] as? Map<String, Any?>) ?: emptyMap())
            stats["dropped_chunks"] = (m["dropped_chunks"] as? Number)?.toInt() ?: 0
            stats["failed_chunks"] = ((m["failed_chunks"] as? Number)?.toInt() ?: 0) + ((m["conflict_chunks"] as? Number)?.toInt() ?: 0)
            return linkedMapOf(
                "total_chunks" to (m["total_chunks"] ?: 0),
                "ended_at_wall_ms" to m["ended_at_wall_ms"],
                "stop_reason" to m["stop_reason"],
                "stats" to stats,
                "clock" to m["clock"], // uzantı: oturum sonunda kesinleşen sensor_clock
            )
        }
    }
}

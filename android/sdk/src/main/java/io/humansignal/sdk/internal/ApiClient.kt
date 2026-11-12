package io.humansignal.sdk.internal

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** API yanıtı: HTTP kodu, JSON gövde (varsa) ve §6.3 hata kodu (varsa). */
internal data class ApiResponse(val code: Int, val json: Map<String, Any?>?, val errorCode: String?) {
    val ok: Boolean get() = code in 200..299
}

/**
 * §6 uçlarına ince istemci. Ağ hatasında [IOException] fırlatır. API anahtarı asla loglanmaz.
 * Tek istek zaman aşımı 30 sn (§3.10).
 */
internal class ApiClient(
    baseUrl: String,
    private val apiKey: String,
    private val userAgent: String,
    timeoutSec: Long = 30,
) {
    private val base = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder()
        .callTimeout(timeoutSec, TimeUnit.SECONDS)
        .connectTimeout(minOf(timeoutSec, 15), TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun withTimeout(sec: Long) = ApiClient(base, apiKey, userAgent, sec)

    fun getConfig(): ApiResponse = exec(req("/v1/config").get().build())

    fun createSession(body: Map<String, Any?>): ApiResponse =
        exec(req("/v1/sessions").post(JsonWriter.encode(body).toRequestBody(JSON)).build())

    fun putChunk(serverSessionId: String, e: QueueEntry, gzip: ByteArray): ApiResponse {
        val r = req("/v1/sessions/$serverSessionId/chunks/${e.seq}")
            .put(gzip.toRequestBody(OCTET))
            .header("X-Chunk-Sha256", e.sha256)
            .header("X-Chunk-T-Start", e.tStart.toString())
            .header("X-Chunk-T-End", e.tEnd.toString())
            .header("X-Chunk-Streams", e.streams.ifEmpty { listOf("unknown") }.joinToString(","))
            .apply { if (e.records > 0) header("X-Chunk-Records", e.records.toString()) }
            .build()
        return exec(r)
    }

    fun complete(serverSessionId: String, body: Map<String, Any?>): ApiResponse =
        exec(req("/v1/sessions/$serverSessionId/complete").post(JsonWriter.encode(body).toRequestBody(JSON)).build())

    private fun req(path: String) = Request.Builder()
        .url(base + path)
        .header("X-Api-Key", apiKey)
        .header("User-Agent", userAgent)

    @Throws(IOException::class)
    private fun exec(r: Request): ApiResponse = http.newCall(r).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        val json = if (text.startsWith("{")) try { JsonParser.parseObject(text) } catch (_: Exception) { null } else null
        val err = json?.obj("error")?.str("code")
        ApiResponse(resp.code, json, err)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }
}

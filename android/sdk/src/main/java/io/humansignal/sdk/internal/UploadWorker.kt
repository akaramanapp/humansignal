package io.humansignal.sdk.internal

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.humansignal.sdk.HumanSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager sarmalayıcısı (§3.10). Benzersiz iş adı `humansignal-upload`, politika `APPEND_OR_REPLACE`.
 *
 * Geri çekilme: 30 sn'den başlayan üstel, **en fazla 1 saat**. WorkManager'ın kendi üst sınırı 5 saat olduğu için
 * yeniden deneme, hesaplanmış gecikmeyle yeni bir iş olarak kuyruğa eklenir.
 */
internal class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val engine = HumanSignal.uploadEngine() ?: return@withContext Result.success() // init yok / anahtar yok
        when (engine.runOnce()) {
            UploadOutcome.DONE -> { HumanSignal.onUploadResult(null); Result.success() }
            UploadOutcome.AUTH_FAILED -> { HumanSignal.onUploadResult("upload: unauthorized (401/403)"); Result.failure() }
            UploadOutcome.CONFIG_ERROR -> { HumanSignal.onUploadResult("upload: invalid configuration (baseUrl)"); Result.failure() }
            UploadOutcome.RETRY -> {
                val delay = backoffSeconds(attempt + 1)
                HumanSignal.onUploadResult(null, retryInSec = delay)
                enqueue(applicationContext, HumanSignal.wifiOnly(), delaySec = delay, attempt = attempt + 1)
                Result.success()
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "humansignal-upload"
        private const val KEY_ATTEMPT = "attempt"

        /** 30, 60, 120, … sn; en fazla 3600 sn. */
        fun backoffSeconds(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 20)
            return minOf(30L shl shift, 3600L)
        }

        /**
         * `NetworkType.CONNECTED`, ağın *doğrulanmış* (internete çıkabilen) olmasını ister. Faz 1'de API internetsiz bir
         * laboratuvar Wi-Fi'ında ya da Tailscale (VPN) arkasında olabilir (§6.5); bu yüzden API 28+'da doğrulama ve
         * VPN dışlaması olmadan kendi ağ isteğimizi veririz. Ulaşılamazsa motor zaten geri çekilerek yeniden dener.
         */
        private fun constraints(wifiOnly: Boolean): Constraints {
            val b = Constraints.Builder()
            if (Build.VERSION.SDK_INT >= 28) {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .apply { if (wifiOnly) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
                    .build()
                b.setRequiredNetworkRequest(req, if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            } else {
                b.setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            }
            return b.build()
        }

        /**
         * Uygulama açılışında: zincirde bekleyen (eski kısıtlarla ya da uzun geri çekilmeyle kalmış) işi `REPLACE` ile
         * değiştirip hemen bir tur başlatır. Aksi halde `APPEND_OR_REPLACE` yeni işi bekleyen işin arkasına ekler.
         */
        fun restart(ctx: Context, wifiOnly: Boolean) = enqueue(ctx, wifiOnly, policy = ExistingWorkPolicy.REPLACE)

        fun enqueue(
            ctx: Context,
            wifiOnly: Boolean,
            delaySec: Long = 0,
            attempt: Int = 0,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        ) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(wifiOnly))
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ATTEMPT to attempt))
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NAME, policy, req)
        }
    }
}

package io.humansignal.sdk

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A7 (§10): SDK hiçbir hata senaryosunda host uygulamayı çökertmez. Ağ yokluğu A3'te, sensör eksikliği
 * (`sensors_missing`) M1'de sınandı; burada disk, aşırı büyük olaylar ve yanlış kullanım sırası.
 */
@RunWith(AndroidJUnit4::class)
class RobustnessTest {
    companion object {
        @JvmStatic @BeforeClass fun initSdk() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            HumanSignal.init(app, HumanSignalConfig(baseUrl = "http://localhost", apiKey = "test", debugLogging = true))
            HumanSignal.setConsent(true, "test")
        }
        private val meta = SessionMeta(null, Label(Label.Kind.BOT, BotTool.ESPRESSO, BotHost.EMULATOR_OTHER), UsageContext(Placement.TABLE, Posture.NA, Hands.NA))
    }

    private fun start() = runBlocking { HumanSignal.startSession(meta) }
    private fun stop() = runBlocking { HumanSignal.stopSession() }

    @Test fun storageDirectoryDeletedMidSession() {
        start()
        Thread.sleep(1200)
        HumanSignal.store!!.root.deleteRecursively()   // ör. kullanıcı "verileri temizle" / disk sorunu
        Thread.sleep(6000)                             // en az bir parça kesilsin
        stop()
        assertFalse(HumanSignal.status.value.sessionActive)
        assertNotNull(HumanSignal.lastSummary.value)
    }

    @Test fun hugeMarkersAreSplitAcrossChunks() {
        start()
        val big = "x".repeat(200_000)
        repeat(15) { HumanSignal.mark("big", mapOf("blob" to big)) } // ~3 MB > 2 MB parça sınırı
        Thread.sleep(1500)
        stop()
        assertTrue("expected an early cut", (HumanSignal.lastSummary.value?.totalChunks ?: 0) >= 2)
    }

    @Test fun misuseIsReportedNotCrashing() {
        // Oturum yokken çağrılar sessizce yok sayılır.
        HumanSignal.mark("nothing")
        stop()
        // İkinci startSession: belgelenmiş IllegalStateException.
        start()
        try { start(); fail("second session must be rejected") } catch (e: IllegalStateException) {}
        // Açık oturum sırasında yerel veriyi silmek oturumu kayıtsız kapatır.
        HumanSignal.deleteLocalData()
        var waited = 0
        while (HumanSignal.status.value.sessionActive && waited < 5000) { Thread.sleep(100); waited += 100 }
        assertFalse(HumanSignal.status.value.sessionActive)
        assertEquals(0, HumanSignal.store!!.pendingCount())
    }
}

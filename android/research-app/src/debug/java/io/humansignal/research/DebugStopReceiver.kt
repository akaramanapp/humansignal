package io.humansignal.research

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.humansignal.sdk.HumanSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * YALNIZCA debug build (§4.4): bot script'i bitince oturumu durdurur.
 *   adb shell am broadcast -a io.humansignal.research.STOP -p io.humansignal.research
 * Açık TaskActivity oturumun kapandığını görüp Özet ekranına geçer.
 */
class DebugStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HumanSignal.stopSession()
                Log.i("HS-MARK", "{\"name\":\"debug_stop\",\"attrs\":{}}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object { const val ACTION = "io.humansignal.research.STOP" }
}

package io.humansignal.research

import android.util.Log
import io.humansignal.sdk.HumanSignal

/**
 * `HumanSignal.mark` + debug build'de logcat'e aynı marker (§9.1: adb bot'ları hedef koordinatlarını
 * logcat'teki marker'lardan okur). `adb logcat -s HS-MARK`
 */
object Marks {
    fun mark(name: String, attrs: Map<String, String> = emptyMap()) {
        HumanSignal.mark(name, attrs)
        if (BuildConfig.DEBUG) {
            val a = attrs.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            Log.i("HS-MARK", "{\"name\":\"$name\",\"attrs\":{$a}}")
        }
    }

    fun taskStart(task: String, trial: Int, extra: Map<String, String> = emptyMap()) =
        mark("task_start", mapOf("task" to task, "trial" to trial.toString()) + extra)

    fun taskEnd(task: String, trial: Int, result: String, extra: Map<String, String> = emptyMap()) =
        mark("task_end", mapOf("task" to task, "trial" to trial.toString(), "result" to result) + extra)

    fun screen(name: String) = mark("screen", mapOf("name" to name))
}

package io.humansignal.research

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.humansignal.research.tasks.CardSwipeTask
import io.humansignal.research.tasks.FakeTransferTask
import io.humansignal.research.tasks.FreeUseTask
import io.humansignal.research.tasks.ListScrollTask
import io.humansignal.research.tasks.PinEntryTask
import io.humansignal.research.tasks.TapTargetsTask
import io.humansignal.research.tasks.TextEntryTask
import io.humansignal.sdk.HumanSignal
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Görevin, ana Activity'den ihtiyaç duyduğu her şey. */
interface TaskHost {
    val activity: AppCompatActivity
    val random: Random
    /** Deneme hazır (görünüm yerleşti): `task_start` marker'ı yazılır. */
    fun trialStarted(extra: Map<String, String> = emptyMap())
    /** Deneme bitti: `task_end` yazılır, sıradaki deneme/görev başlar. */
    fun trialEnded(result: String, extra: Map<String, String> = emptyMap())
    fun setInstruction(text: String)
    fun hideKeyboard()
}

/**
 * Bir görev (§4.3). Her deneme `container` içine kendi görünümünü kurar ve bitince [TaskHost.trialEnded] çağırır.
 * Tüm görevler tam ekran bu Activity içinde çalışır: Dialog/PopupWindow dokunmaları Faz 1'de yakalanmaz (§3.5).
 */
abstract class Task(val code: String, val name: String, val title: String, val trials: Int) {
    lateinit var host: TaskHost
    abstract fun startTrial(trial: Int, container: FrameLayout)
    /** Deneme/görev sonunda kaynakları bırak (ör. trackTextField). */
    open fun cleanup() {}
}

/** Görevler (§4.3): sıra sabit, T1 → T7. Oturum ~8–10 dakika. */
class TaskActivity : AppCompatActivity(), TaskHost {
    override val activity: AppCompatActivity get() = this
    override val random = Random(System.nanoTime())

    private val tasks: List<Task> = listOf(
        TapTargetsTask(), ListScrollTask(), CardSwipeTask(), PinEntryTask(), TextEntryTask(), FakeTransferTask(), FreeUseTask(),
    )
    private var taskIndex = 0
    private var trial = 0
    private var trialOpen = false
    private var finishing = false

    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var instruction: TextView
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Görevler"
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.task_progress; contentDescription = "task_progress"; max = tasks.sumOf { it.trials }
        }
        header = label("", 13f).apply { id = R.id.task_header; contentDescription = "task_header" }
        instruction = TextView(this).apply {
            id = R.id.task_instruction; contentDescription = "task_instruction"; textSize = 18f; setPadding(0, dp(4), 0, dp(8))
        }
        container = FrameLayout(this).apply { id = R.id.task_container }
        val skip = button(R.id.task_skip, "task_skip", "Atla") { skipTask() }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(progress, MATCH_PARENT, WRAP_CONTENT)
            addView(header, MATCH_PARENT, WRAP_CONTENT)
            addView(instruction, MATCH_PARENT, WRAP_CONTENT)
            addView(container, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(skip, MATCH_PARENT, WRAP_CONTENT)
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() =
                Toast.makeText(this@TaskActivity, "Görevi geçmek için \"Atla\"yı kullanın", Toast.LENGTH_SHORT).show()
        })

        // Oturum başka bir yoldan kapanırsa (STOP broadcast, 10 dk zaman aşımı, rıza geri alma) özete geç.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HumanSignal.status.collect { if (!it.sessionActive && !finishing) goToSummary() }
            }
        }

        if (!HumanSignal.status.value.sessionActive) { goToSummary(); return }
        startTask(0)
    }

    private fun startTask(i: Int) {
        tasks.getOrNull(taskIndex)?.cleanup()
        taskIndex = i
        val t = tasks.getOrNull(i) ?: return finishAll()
        t.host = this
        trial = 0
        Marks.screen(t.name)
        nextTrial()
    }

    private fun nextTrial() {
        val t = tasks[taskIndex]
        trial++
        if (trial > t.trials) return startTask(taskIndex + 1)
        header.text = "${t.code.uppercase()} · ${t.title}  (${trial}/${t.trials})"
        container.removeAllViews()
        container.setOnClickListener(null)
        container.isClickable = false
        trialOpen = false
        t.startTrial(trial, container)
    }

    override fun trialStarted(extra: Map<String, String>) {
        val t = tasks[taskIndex]
        trialOpen = true
        Marks.taskStart(t.name, trial, extra)
    }

    override fun trialEnded(result: String, extra: Map<String, String>) {
        if (finishing) return
        val t = tasks[taskIndex]
        if (trialOpen) Marks.taskEnd(t.name, trial, result, extra)
        trialOpen = false
        progress.progress = tasks.take(taskIndex).sumOf { it.trials } + trial
        hideKeyboard()
        container.post { nextTrial() }
    }

    /** "Atla": görevin kalanı `result:"skipped"` olarak işaretlenir (§4.3). */
    private fun skipTask() {
        val t = tasks.getOrNull(taskIndex) ?: return
        if (!trialOpen) Marks.taskStart(t.name, trial)
        Marks.taskEnd(t.name, trial, "skipped", mapOf("remaining" to (t.trials - trial).toString()))
        trialOpen = false
        hideKeyboard()
        progress.progress = tasks.take(taskIndex + 1).sumOf { it.trials }
        container.post { startTask(taskIndex + 1) }
    }

    private fun finishAll() {
        if (finishing) return
        finishing = true
        Marks.mark("session_tasks_done")
        container.removeAllViews()
        instruction.text = "Görevler tamamlandı. Kaydediliyor…"
        lifecycleScope.launch {
            HumanSignal.stopSession()
            goToSummary()
        }
    }

    private fun goToSummary() {
        finishing = true
        tasks.getOrNull(taskIndex)?.cleanup()
        startActivity(Intent(this, SummaryActivity::class.java))
        finish()
    }

    override fun setInstruction(text: String) { instruction.text = text }

    override fun hideKeyboard() {
        val v = currentFocus ?: container
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    override fun onDestroy() {
        tasks.getOrNull(taskIndex)?.cleanup()
        super.onDestroy()
    }
}

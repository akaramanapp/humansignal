package io.humansignal.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.humansignal.sdk.internal.JsonParser
import io.humansignal.sdk.internal.list
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.zip.GZIPInputStream

/** Uygulamanın kendi Window.Callback sarmalayıcısı (SDK'dan sonra kurulur) + dokunma sayacı. */
class TestActivity : Activity() {
    lateinit var keypad: SecureKeypadView
    lateinit var target: FrameLayout
    lateinit var edit: EditText
    var appWrapperDowns = 0
    var targetDowns = 0
    val pressed = ArrayList<KeyPress>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // SDK burada (onActivityCreated) sarar
        // Uygulama SDK'nın üstüne kendi sarmalayıcısını koyar: zincir korunmalı.
        val inner = window.callback
        window.callback = Proxy.newProxyInstance(Window.Callback::class.java.classLoader, arrayOf(Window.Callback::class.java)) { _, m, args ->
            if (m.name == "dispatchTouchEvent" && (args[0] as MotionEvent).actionMasked == MotionEvent.ACTION_DOWN) appWrapperDowns++
            if (args == null) m.invoke(inner) else m.invoke(inner, *args)
        } as Window.Callback

        target = FrameLayout(this).apply {
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) targetDowns++; true }
        }
        edit = EditText(this)
        keypad = SecureKeypadView(this).apply { fieldId = "pin"; listener = { pressed.add(it) } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(target, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400))
            addView(edit)
            addView(keypad)
        })
    }
}

@RunWith(AndroidJUnit4::class)
class SdkInstrumentedTest {
    companion object {
        @JvmStatic @BeforeClass fun initSdk() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            HumanSignal.init(app, HumanSignalConfig(baseUrl = "http://localhost", apiKey = "test", debugLogging = true))
            HumanSignal.setConsent(true, "test")
        }
    }

    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<TestActivity>
    private lateinit var act: TestActivity
    private lateinit var sid: String

    @Before fun setUp() {
        scenario = ActivityScenario.launch(TestActivity::class.java)
        scenario.onActivity { act = it }
        instr.waitForIdleSync()
        sid = runBlocking {
            HumanSignal.startSession(
                SessionMeta(null, Label(Label.Kind.BOT, BotTool.ESPRESSO, BotHost.EMULATOR_OTHER), UsageContext(Placement.TABLE, Posture.NA, Hands.NA)),
            ).sessionId
        }
        instr.waitForIdleSync()
    }

    @After fun tearDown() {
        runBlocking { HumanSignal.stopSession() }
        scenario.close()
    }

    private fun tapScreen(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        instr.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        instr.sendPointerSync(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun centerOnScreen(v: android.view.View): Pair<Float, Float> {
        val loc = IntArray(2)
        instr.runOnMainSync { v.getLocationOnScreen(loc) }
        return (loc[0] + v.width / 2f) to (loc[1] + v.height / 2f)
    }

    /** Oturumu kapatıp tüm parçaları çözer ve satırları döner. */
    private fun stopAndReadLines(): List<Map<String, Any?>> {
        runBlocking { HumanSignal.stopSession() }
        val store = HumanSignal.store!!
        return store.pending().filter { it.localSessionId == sid }.sortedBy { it.seq }.flatMap { e ->
            val text = GZIPInputStream(ByteArrayInputStream(store.readChunk(e))).readBytes().toString(Charsets.UTF_8)
            text.trimEnd('\n').split('\n').map { JsonParser.parseObject(it) }
        }
    }

    @Test fun windowCallbackChainPreservedAndNoDuplicates() {
        val (x, y) = centerOnScreen(act.target)
        repeat(3) { tapScreen(x, y) }
        instr.waitForIdleSync()
        assertEquals("event must reach the original target", 3, act.targetDowns)
        assertEquals("app's own wrapper still called", 3, act.appWrapperDowns)

        val lines = stopAndReadLines()
        val downs = lines.filter { it["k"] == "touch" && it["a"] == "DOWN" }
        assertEquals("each DOWN recorded exactly once", 3, downs.size)
        @Suppress("UNCHECKED_CAST")
        val p = (downs[0].list("p")!![0] as Map<String, Any?>)
        assertEquals(x.toDouble(), (p["x"] as Number).toDouble(), 1.0)
        assertEquals(y.toDouble(), (p["y"] as Number).toDouble(), 1.0)
        assertEquals("TestActivity", downs[0]["scr"])
        assertTrue(lines.any { it["k"] == "input_device" })
    }

    @Test fun secureKeypadTouchesAreMasked() {
        val key5 = act.keypad.findViewById<android.view.View>(R.id.keypad_key_5)
        val (x, y) = centerOnScreen(key5)
        tapScreen(x + 3, y - 2)
        instr.waitForIdleSync()
        assertEquals(listOf(KeyPress(KeyClass.DIGIT, 5)), act.pressed)

        val lines = stopAndReadLines()
        val touches = lines.filter { it["k"] == "touch" }
        assertTrue(touches.isNotEmpty())
        for (t in touches) {
            @Suppress("UNCHECKED_CAST")
            for (p in t.list("p")!!.map { it as Map<String, Any?> }) {
                assertEquals(true, p["masked"])
                assertFalse("absolute x must not be recorded", p.containsKey("x"))
                assertFalse("absolute y must not be recorded", p.containsKey("y"))
                assertTrue(p.containsKey("pr"))
            }
        }
        val keys = lines.filter { it["k"] == "key" }
        assertEquals(1, keys.size)
        val k = keys[0]
        assertEquals("pin", k["f"]); assertEquals("digit", k["kc"])
        assertEquals("kid must be null unless recordKeyIdentity", null, k["kid"])
        assertEquals(3.0, (k["dx"] as Number).toDouble(), 1.0)
        assertEquals(-2.0, (k["dy"] as Number).toDouble(), 1.0)
        assertTrue((k["tu"] as Long) - (k["td"] as Long) in 50_000_000L..70_000_000L)
        assertFalse(lines.joinToString().contains("\"5\""))
    }

    @Test fun trackedTextFieldRecordsLengthsNotContent() {
        instr.runOnMainSync {
            HumanSignal.trackTextField(act.edit, "amount")
            act.edit.requestFocus()
        }
        instr.waitForIdleSync()
        instr.runOnMainSync {
            act.edit.append("7"); act.edit.append("4"); act.edit.append("2")
            act.edit.text.delete(2, 3)
            act.edit.append("ZQXW") // "yapıştırma"
        }
        instr.waitForIdleSync()
        val lines = stopAndReadLines()
        val text = lines.filter { it["k"] == "text" }
        assertEquals(listOf(1L, 1L, 1L, -1L, 4L), text.map { it["d"] })
        assertEquals(listOf(1L, 2L, 3L, 2L, 6L), text.map { it["len"] })
        assertEquals(true, text.last()["paste"])
        assertFalse(lines.joinToString().contains("ZQXW"))
        instr.runOnMainSync { HumanSignal.untrackTextField(act.edit) }
    }

    @Test fun deviceSnapshotIsStoredInSessionMeta() {
        val meta = HumanSignal.store!!.readSessionMeta(sid)!!
        @Suppress("UNCHECKED_CAST")
        val device = meta["device"] as Map<String, Any?>
        assertTrue(device.keys.containsAll(listOf("device", "screen", "sensors", "integrity", "app")))
        @Suppress("UNCHECKED_CAST")
        val integrity = device["integrity"] as Map<String, Any?>
        assertTrue(integrity.containsKey("adb_enabled"))
        assertTrue(integrity.containsKey("emulator_hints"))
    }
}

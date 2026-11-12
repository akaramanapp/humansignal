package io.humansignal.sdk.internal

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Açık oturuma olay yazmak için gereken asgari arayüz (HumanSignal sağlar). */
internal interface EventSink {
    val clock: SessionClock
    fun add(e: EventLine)
}

/**
 * Dokunma toplayıcı (§3.5). Her Activity penceresinin `Window.Callback`'i [ObservingCallback] ile sarılır;
 * sarmalayıcı olayı **gözlemler, tüketmez** ve zincirdeki bir önceki callback'e iletir.
 *
 * Yalnızca ana thread'de çalışır.
 */
internal class TouchCollector(
    private val sink: () -> EventSink?,
    private val screenName: () -> String?,
    private val onError: (String, Throwable) -> Unit,
) {
    /** Dokunmaları maskelenecek alanlar (SecureKeypadView'lar). */
    private val maskViews = WeakHashMap<View, Unit>()
    private val seenDevices = HashSet<Int>()
    private var maskGesture = false
    private var dispatchDepth = 0

    var touchLines = 0L
        private set
    private var windowLines = 0
    private var windowStartNs = System.nanoTime()
    var ratePerSec = 0.0
        private set

    /** Saat testi gibi tüketiciler için: DOWN zamanları (oturum zamanı, ns). */
    var downListener: ((Long) -> Unit)? = null

    internal fun sinkActive() = sink() != null

    fun registerMaskView(v: View) { maskViews[v] = Unit }
    fun unregisterMaskView(v: View) { maskViews.remove(v) }

    fun resetForSession() {
        seenDevices.clear(); touchLines = 0; windowLines = 0; ratePerSec = 0.0; maskGesture = false; observeCount = 0
        windowStartNs = System.nanoTime()
    }

    /** Saniyede bir (ana thread dışında da çağrılabilir; yaklaşık değer yeterli). */
    fun tickRate() {
        val now = System.nanoTime()
        val dt = (now - windowStartNs) / 1e9
        if (dt <= 0) return
        ratePerSec = windowLines / dt
        windowLines = 0
        windowStartNs = now
    }

    /** Activity penceresini sarar; zaten sarılıysa dokunmaz. Mevcut sarmalayıcı zinciri korunur. */
    fun install(activity: Activity) {
        val w = activity.window ?: return
        val cb = w.callback ?: return
        if (cb is ObservingCallback) return
        w.callback = ObservingCallback(cb, this)
    }

    /** Oturum başında: bağlı tüm giriş cihazlarını bir kez yazar. */
    fun writeAllInputDevices() {
        try { for (id in InputDevice.getDeviceIds()) writeInputDevice(id, force = true) } catch (e: Exception) { onError("input_device", e) }
    }

    fun onInputDeviceChanged(id: Int) = writeInputDevice(id, force = true)

    private fun writeInputDevice(id: Int, force: Boolean) {
        val s = sink() ?: return
        if (!force && id in seenDevices) return
        seenDevices.add(id)
        val d = InputDevice.getDevice(id)
        val t = s.clock.now()
        s.add(EventLine(t, "input_device", JsonWriter.obj {
            field("k", "input_device"); field("t", t); field("id", id)
            if (d == null) { field("removed", true); return@obj }
            field("name", d.name); field("vendor", d.vendorId); field("product", d.productId)
            field("sources", sourcesString(d.sources)); field("virtual", d.isVirtual)
        }))
    }

    // ---------------------------------------------------------------- kayıt

    internal fun onDispatch(ev: MotionEvent) {
        // İç içe sarmalayıcılar (ör. SDK iki kez sarıldıysa) aynı olayı iki kez yazmasın.
        if (dispatchDepth > 1) return
        val s = sink() ?: return
        try {
            record(s, ev)
        } catch (e: Exception) {
            onError("touch", e)
        }
    }

    private fun record(s: EventSink, ev: MotionEvent) {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) maskGesture = false
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = ev.actionIndex
            if (isInMaskArea(rawX(ev, i), rawY(ev, i))) maskGesture = true
        }
        val dev = ev.deviceId
        if (dev !in seenDevices) writeInputDevice(dev, force = false)

        val masked = maskGesture
        val pc = ev.pointerCount
        val actionName = actionName(action)
        val src = sourceName(ev.source)
        val scr = screenName()
        val fl = ev.flags
        // window → ekran ofseti (historical örneklerde ham koordinat yok)
        val offX = ev.rawX - ev.x
        val offY = ev.rawY - ev.y
        val c = s.clock

        for (h in 0 until ev.historySize) {
            val t = if (Build.VERSION.SDK_INT >= 34) c.fromUptime(ev.getHistoricalEventTimeNanos(h))
            else c.fromUptimeMillis(ev.getHistoricalEventTime(h))
            val w = JsonWriter(StringBuilder(160 + pc * 120))
            w.beginObject().field("k", "touch").field("t", t).field("h", 1).field("a", actionName).field("pc", pc)
            w.name("p").beginArray()
            for (i in 0 until pc) {
                w.beginObject().field("id", ev.getPointerId(i))
                if (masked) w.field("masked", true)
                else { w.name("x").dec1((ev.getHistoricalX(i, h) + offX).toDouble()); w.name("y").dec1((ev.getHistoricalY(i, h) + offY).toDouble()) }
                w.name("pr").sig5(ev.getHistoricalPressure(i, h))
                w.name("sz").sig5(ev.getHistoricalSize(i, h))
                w.name("ma").dec1(ev.getHistoricalTouchMajor(i, h).toDouble())
                w.name("mi").dec1(ev.getHistoricalTouchMinor(i, h).toDouble())
                w.name("o").sig5(ev.getHistoricalOrientation(i, h))
                w.field("tool", toolName(ev.getToolType(i)))
                w.endObject()
            }
            w.endArray()
            w.field("src", src).field("dev", dev).field("fl", fl).field("scr", scr).endObject()
            s.add(EventLine(t, "touch", w.toString()))
            touchLines++; windowLines++
        }

        val t = if (Build.VERSION.SDK_INT >= 34) c.fromUptime(ev.eventTimeNanos) else c.fromUptimeMillis(ev.eventTime)
        val w = JsonWriter(StringBuilder(160 + pc * 120))
        w.beginObject().field("k", "touch").field("t", t).field("h", 0).field("a", actionName).field("pc", pc)
        w.name("p").beginArray()
        for (i in 0 until pc) {
            w.beginObject().field("id", ev.getPointerId(i))
            if (masked) w.field("masked", true)
            else { w.name("x").dec1(rawX(ev, i).toDouble()); w.name("y").dec1(rawY(ev, i).toDouble()) }
            w.name("pr").sig5(ev.getPressure(i))
            w.name("sz").sig5(ev.getSize(i))
            w.name("ma").dec1(ev.getTouchMajor(i).toDouble())
            w.name("mi").dec1(ev.getTouchMinor(i).toDouble())
            w.name("o").sig5(ev.getOrientation(i))
            w.field("tool", toolName(ev.getToolType(i)))
            w.endObject()
        }
        w.endArray()
        w.field("src", src).field("dev", dev).field("fl", fl).field("scr", scr).endObject()
        s.add(EventLine(t, "touch", w.toString()))
        touchLines++; windowLines++

        if (action == MotionEvent.ACTION_DOWN) downListener?.invoke(t)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) maskGesture = false
    }

    private val tmpRect = Rect()
    private val tmpLoc = IntArray(2)

    private fun isInMaskArea(x: Float, y: Float): Boolean {
        for (v in maskViews.keys) {
            if (!v.isShown) continue
            v.getLocationOnScreen(tmpLoc)
            tmpRect.set(tmpLoc[0], tmpLoc[1], tmpLoc[0] + v.width, tmpLoc[1] + v.height)
            if (tmpRect.contains(x.toInt(), y.toInt())) return true
        }
        return false
    }

    private fun rawX(ev: MotionEvent, i: Int): Float =
        if (Build.VERSION.SDK_INT >= 29) ev.getRawX(i) else ev.getX(i) + (ev.rawX - ev.x)

    private fun rawY(ev: MotionEvent, i: Int): Float =
        if (Build.VERSION.SDK_INT >= 29) ev.getRawY(i) else ev.getY(i) + (ev.rawY - ev.y)

    // ---------------------------------------------------------------- sarmalayıcı

    /** Gözlemci `Window.Callback`: her çağrıyı olduğu gibi [inner]'a iletir. */
    internal class ObservingCallback(val inner: Window.Callback, collector: TouchCollector) : Window.Callback {
        private val ref = WeakReference(collector)

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val c = ref.get()
            if (c != null) {
                c.dispatchDepth++
                try {
                    val t0 = System.nanoTime()
                    c.onDispatch(event)
                    if (c.sinkActive()) c.recordObserveTime(System.nanoTime() - t0)
                    return inner.dispatchTouchEvent(event)
                } finally {
                    c.dispatchDepth--
                }
            }
            return inner.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = inner.dispatchKeyEvent(event)
        override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = inner.dispatchKeyShortcutEvent(event)
        override fun dispatchTrackballEvent(event: MotionEvent): Boolean = inner.dispatchTrackballEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = inner.dispatchGenericMotionEvent(event)
        override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean =
            inner.dispatchPopulateAccessibilityEvent(event)
        override fun onCreatePanelView(featureId: Int): View? = inner.onCreatePanelView(featureId)
        override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = inner.onCreatePanelMenu(featureId, menu)
        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = inner.onPreparePanel(featureId, view, menu)
        override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = inner.onMenuOpened(featureId, menu)
        override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = inner.onMenuItemSelected(featureId, item)
        override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = inner.onWindowAttributesChanged(attrs)
        override fun onContentChanged() = inner.onContentChanged()
        override fun onWindowFocusChanged(hasFocus: Boolean) = inner.onWindowFocusChanged(hasFocus)
        override fun onAttachedToWindow() = inner.onAttachedToWindow()
        override fun onDetachedFromWindow() = inner.onDetachedFromWindow()
        override fun onPanelClosed(featureId: Int, menu: Menu) = inner.onPanelClosed(featureId, menu)
        override fun onSearchRequested(): Boolean = inner.onSearchRequested()
        override fun onSearchRequested(searchEvent: SearchEvent): Boolean = inner.onSearchRequested(searchEvent)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? =
            inner.onWindowStartingActionMode(callback)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
            inner.onWindowStartingActionMode(callback, type)
        override fun onActionModeStarted(mode: ActionMode) = inner.onActionModeStarted(mode)
        override fun onActionModeFinished(mode: ActionMode) = inner.onActionModeFinished(mode)
        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) =
            inner.onProvideKeyboardShortcuts(data, menu, deviceId)
        override fun onPointerCaptureChanged(hasCapture: Boolean) = inner.onPointerCaptureChanged(hasCapture)
    }

    // §3.11 ölçümü: ana thread'de olay başına gözlem süresi (son 4096 olay, halka tampon).
    private val observeNs = LongArray(4096)
    private var observeCount = 0

    internal fun recordObserveTime(ns: Long) {
        observeNs[observeCount % observeNs.size] = ns
        observeCount++
    }

    /** (n, p50 µs, p99 µs, max µs) — yalnızca açık oturumdaki olaylar sayılır. */
    fun observeStats(): List<Double> {
        val n = minOf(observeCount, observeNs.size)
        if (n == 0) return listOf(0.0, 0.0, 0.0, 0.0)
        val xs = observeNs.copyOf(n).also { it.sort() }
        return listOf(observeCount.toDouble(), xs[n / 2] / 1e3, xs[minOf(n - 1, (n * 99) / 100)] / 1e3, xs[n - 1] / 1e3)
    }

    companion object {

        fun actionName(a: Int): String = when (a) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
            MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
            MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
            else -> "A$a"
        }

        fun toolName(t: Int): String = when (t) {
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            else -> "unknown"
        }

        /** `getSource` bit maskesinden tek bir ad (§3.5 `src`). Stylus, dokunmatik ekran bitlerini de içerir; önce bakılır. */
        fun sourceName(src: Int): String = when {
            src and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS -> "stylus"
            src and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE -> "mouse"
            src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN -> "touchscreen"
            src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD -> "keyboard"
            else -> "unknown"
        }

        /** Bir giriş cihazının tüm kaynakları, `|` ile ayrılmış. */
        fun sourcesString(src: Int): String {
            val names = ArrayList<String>()
            if (src and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN) names.add("touchscreen")
            if (src and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE) names.add("mouse")
            if (src and InputDevice.SOURCE_STYLUS == InputDevice.SOURCE_STYLUS) names.add("stylus")
            if (src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD) names.add("keyboard")
            if (src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD) names.add("dpad")
            if (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) names.add("gamepad")
            if (src and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) names.add("touchpad")
            if (src and InputDevice.SOURCE_TRACKBALL == InputDevice.SOURCE_TRACKBALL) names.add("trackball")
            return if (names.isEmpty()) "unknown" else names.joinToString("|")
        }
    }
}

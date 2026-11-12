import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * İnsan benzeri dokunma enjektörü (tools/bots, --mode humanlike). `app_process` ile shell kullanıcısı olarak çalışır
 * (scrcpy ile aynı yöntem): `input motionevent` saniyede ~6 olay üretebilirken bu süreç açık kalıp ~120 Hz enjekte eder.
 *
 * stdin protokolü (satır başına):
 *   G                     yeni hareket başlar
 *   D|M|U t x y p s       t = hareket başından ms; p = basınç; s = boyut (0..1)
 *   E                     hareketi yürüt; bitince stdout'a "OK" yazar
 *   Q                     çık
 * Argüman: [deviceId]  (verilmezse ilk fiziksel dokunmatik ekranın id'si)
 *
 * Gözlem: API 30 emülatörde sistem enjekte edilen olayların deviceId'sini -1 olarak teslim ediyor; Android 10 gerçek
 * cihazda (Huawei) ise `input` komutu bile fiziksel ekranın id'sini kullanıyor. `dev` sinyali sürüme bağlıdır. Aracı, finger/basınç/boyut/geçmiş örnekleri gerçekçi üretir; deviceId'yi değil.
 */
public final class Injector {
    private static Object inputManager;
    private static Method inject;

    public static void main(String[] args) throws Exception {
        Class<?> imc = Class.forName("android.hardware.input.InputManager");
        inputManager = imc.getMethod("getInstance").invoke(null);
        inject = imc.getMethod("injectInputEvent", InputEvent.class, int.class);
        int deviceId = args.length > 0 ? Integer.parseInt(args[0]) : findTouchscreen();
        if (deviceId < 0) deviceId = 0;

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        java.util.ArrayList<String[]> g = new java.util.ArrayList<>();
        System.out.println("READY " + deviceId);
        System.out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            char c = line.charAt(0);
            if (c == 'Q') break;
            if (c == 'G') { g.clear(); continue; }
            if (c == 'E') { run(g, deviceId); System.out.println("OK"); System.out.flush(); continue; }
            g.add(line.split("\\s+"));
        }
    }

    private static void run(java.util.List<String[]> g, int deviceId) throws Exception {
        long start = SystemClock.uptimeMillis();
        long downTime = start;
        for (String[] p : g) {
            long t = start + (long) Double.parseDouble(p[1]);
            long wait = t - SystemClock.uptimeMillis();
            if (wait > 0) Thread.sleep(wait);
            int action;
            switch (p[0]) {
                case "D": action = MotionEvent.ACTION_DOWN; downTime = t; break;
                case "U": action = MotionEvent.ACTION_UP; break;
                default: action = MotionEvent.ACTION_MOVE;
            }
            MotionEvent.PointerProperties[] props = { new MotionEvent.PointerProperties() };
            props[0].id = 0;
            props[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
            coords[0].x = Float.parseFloat(p[2]);
            coords[0].y = Float.parseFloat(p[3]);
            coords[0].pressure = Float.parseFloat(p[4]);
            coords[0].size = Float.parseFloat(p[5]);
            coords[0].touchMajor = coords[0].size * 300f;
            coords[0].touchMinor = coords[0].size * 260f;
            MotionEvent ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords,
                    0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            inject.invoke(inputManager, ev, 0 /* INJECT_INPUT_EVENT_MODE_ASYNC */);
            ev.recycle();
        }
    }

    private static int findTouchscreen() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) return id;
        }
        return 0;
    }
}

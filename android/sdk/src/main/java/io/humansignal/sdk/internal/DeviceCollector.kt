package io.humansignal.sdk.internal

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.pm.PackageInfoCompat

/**
 * Cihaz anlık görüntüsü, bütünlük sinyalleri ve batarya (§3.7). Yalnızca bilgi toplar, karar vermez.
 *
 * **Toplanmayanlar:** IMEI, Android ID, reklam kimliği, telefon numarası, konum, Wi-Fi/Bluetooth, mikrofon, kamera,
 * yüklü uygulama listesi, rehber, metin içerikleri.
 */
internal class DeviceCollector(private val ctx: Context, private val productionMode: Boolean, private val sdkVersion: String) {

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "device" to deviceInfo(),
        "screen" to screen(),
        "sensors" to sensors(),
        "integrity" to integrity(),
        "app" to app(),
    )

    private fun deviceInfo(): Map<String, Any?> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER, "brand" to Build.BRAND, "model" to Build.MODEL,
        "device" to Build.DEVICE, "product" to Build.PRODUCT, "hardware" to Build.HARDWARE, "board" to Build.BOARD,
        "fingerprint" to Build.FINGERPRINT, "android_sdk" to Build.VERSION.SDK_INT,
        "android_release" to Build.VERSION.RELEASE, "security_patch" to Build.VERSION.SECURITY_PATCH,
    )

    @Suppress("DEPRECATION")
    private fun screen(): Map<String, Any?>? = safe {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics().also { d.getRealMetrics(it) }
        linkedMapOf<String, Any?>(
            "width_px" to m.widthPixels, "height_px" to m.heightPixels, "density" to m.density.toDouble(),
            "xdpi" to m.xdpi.toDouble(), "ydpi" to m.ydpi.toDouble(), "refresh_rate" to d.refreshRate.toDouble(),
        )
    }

    private fun sensorList(): List<Sensor> = try {
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getSensorList(Sensor.TYPE_ALL)
    } catch (_: Exception) { emptyList() }

    private fun sensors(): List<Map<String, Any?>> = sensorList().map { s ->
        linkedMapOf<String, Any?>(
            "type" to s.type, "string_type" to s.stringType, "name" to s.name, "vendor" to s.vendor,
            "version" to s.version, "resolution" to s.resolution.toDouble(), "max_range" to s.maximumRange.toDouble(),
            "min_delay_us" to s.minDelay, "max_delay_us" to s.maxDelay, "power_ma" to s.power.toDouble(),
            "fifo_max" to s.fifoMaxEventCount,
        )
    }

    fun integrity(): Map<String, Any?> = linkedMapOf(
        "adb_enabled" to adbEnabled(),
        "dev_options_enabled" to devOptionsEnabled(),
        "accessibility_services" to accessibilityServices(),
        "is_user_a_monkey" to safeBool { ActivityManager.isUserAMonkey() },
        "running_in_test_harness" to (if (Build.VERSION.SDK_INT >= 29) safeBool { ActivityManager.isRunningInUserTestHarness() } else null),
        "emulator_hints" to emulatorHints(),
        "displays" to safe { (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays.size },
    )

    fun adbEnabled(): Boolean? = safeBool { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }

    fun devOptionsEnabled(): Boolean? =
        safeBool { Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }

    /** RESEARCH: bileşen adları; PRODUCTION: SHA-256 özetleri + sayı (§3.7). */
    fun accessibilityServices(): Map<String, Any?> {
        val raw = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } catch (_: Exception) { null }
        val list = raw?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return if (productionMode) linkedMapOf("count" to list.size, "sha256" to list.map { ChunkEncoder.sha256Hex(it.toByteArray()) })
        else linkedMapOf("count" to list.size, "names" to list)
    }

    fun emulatorHints(): List<String> = emulatorHints(
        mapOf("fingerprint" to Build.FINGERPRINT, "model" to Build.MODEL, "product" to Build.PRODUCT, "hardware" to Build.HARDWARE),
        sensorList().map { it.vendor to it.name },
    )

    private fun app(): Map<String, Any?> {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        val version = try {
            val pi = pm.getPackageInfo(pkg, 0)
            "${pi.versionName} (${PackageInfoCompat.getLongVersionCode(pi)})"
        } catch (_: Exception) { null }
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        } catch (_: Exception) { null }
        return linkedMapOf("app_package" to pkg, "app_version" to version, "sdk_version" to sdkVersion, "installer_package" to installer)
    }

    /** `{"k":"battery",...}` satırı; sticky intent'ten okunur, alıcı kaydı yapılmaz. */
    fun batteryLine(t: Long): String? {
        val i = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null } ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "none"
            else -> "other"
        }
        val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        return JsonWriter.obj {
            field("k", "battery"); field("t", t)
            name("lvl"); if (level >= 0 && scale > 0) value(Math.round(level * 100.0 / scale) / 100.0) else nullValue()
            field("plug", plug)
            field("temp_c", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            field("volt_mv", i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
            field("status", status)
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }
    private inline fun safeBool(block: () -> Boolean): Boolean? = try { block() } catch (_: Exception) { null }

    companion object {
        private val BUILD_TOKENS = listOf("generic", "emulator", "sdk_gphone", "goldfish", "ranchu", "vbox", "genymotion", "nox", "bluestacks")
        private val SENSOR_TOKENS = listOf("goldfish", "aosp", "genymotion")

        /** Saf fonksiyon (birim testli): eşleşen her (alan, belirteç) için `build.<alan>:<belirteç>` / `sensor:<belirteç>`. */
        fun emulatorHints(build: Map<String, String?>, sensors: List<Pair<String?, String?>>): List<String> {
            val out = LinkedHashSet<String>()
            for ((field, value) in build) {
                val v = value?.lowercase() ?: continue
                for (tok in BUILD_TOKENS) if (v.contains(tok)) out.add("build.$field:$tok")
            }
            for ((vendor, name) in sensors) {
                val v = "${vendor ?: ""} ${name ?: ""}".lowercase()
                for (tok in SENSOR_TOKENS) if (v.contains(tok)) out.add("sensor:$tok")
            }
            return out.toList()
        }
    }
}

package io.humansignal.research

import android.content.Context

/**
 * Uygulama ayarları ve yerel katılımcı kayıtları. Gerçek isim **hiçbir yerde** tutulmaz (§2, §8): yalnızca
 * katılımcı kodları (P001…) — "ilk oturum mu?" ve "sıradaki kod" önerisi için.
 */
class AppPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("research_app", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = p.getString("api_url", null) ?: BuildConfig.DEFAULT_API_URL
        set(v) = p.edit().putString("api_url", v.trim()).apply()

    var apiKey: String
        get() = p.getString("api_key", null) ?: BuildConfig.DEFAULT_API_KEY
        set(v) = p.edit().putString("api_key", v.trim()).apply()

    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", false)
        set(v) = p.edit().putBoolean("wifi_only", v).apply()

    /** §4.2: varsayılan açık — uygulama yalnızca sahte PIN kullanır. */
    var recordKeyIdentity: Boolean
        get() = p.getBoolean("record_key_identity", true)
        set(v) = p.edit().putBoolean("record_key_identity", v).apply()

    var settingsDone: Boolean
        get() = p.getBoolean("settings_done", false)
        set(v) = p.edit().putBoolean("settings_done", v).apply()

    /** Bu cihazda oturumu açılmış katılımcı kodları. */
    val knownParticipants: Set<String> get() = p.getStringSet("participants", emptySet())!!

    fun addParticipant(code: String) = p.edit().putStringSet("participants", knownParticipants + code).apply()

    /** "Uygulama bir sonraki numarayı önerir" (§4.2). */
    fun suggestNextCode(): String {
        val max = knownParticipants.mapNotNull { it.removePrefix("P").toIntOrNull() }.maxOrNull() ?: 0
        return "P" + (max + 1).toString().padStart(3, '0')
    }

    companion object {
        const val CONSENT_VERSION = "2026-11-v1"
        val CODE_RE = Regex("^P\\d{3}$")
    }
}

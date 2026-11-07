package com.siyuanink

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", normalizeUrl(v)).apply()

    /** Si es true, se pide a SiYuan la interfaz de escritorio en lugar de la móvil. */
    var desktopUi: Boolean
        get() = sp.getBoolean("desktopUi", false)
        set(v) = sp.edit().putBoolean("desktopUi", v).apply()

    /** Token de GitHub (solo lectura) para descargar actualizaciones del repositorio privado. */
    var githubToken: String
        get() = sp.getString("githubToken", "") ?: ""
        set(v) = sp.edit().putString("githubToken", v.trim()).apply()

    companion object {
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u.trimEnd('/')
        }
    }
}

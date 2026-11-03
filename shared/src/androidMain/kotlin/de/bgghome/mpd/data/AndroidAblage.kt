package de.bgghome.mpd.data

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences als [Ablage] — Datei und Schluessel wie vor der Aufteilung. */
class AndroidAblage(context: Context) : Ablage {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mpd_settings", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default
    override fun putStringSet(key: String, value: Set<String>) { prefs.edit().putStringSet(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/** Bequemer Aufruf wie bisher: `SettingsStore(context)`. */
fun SettingsStore(context: Context): SettingsStore = SettingsStore(AndroidAblage(context))

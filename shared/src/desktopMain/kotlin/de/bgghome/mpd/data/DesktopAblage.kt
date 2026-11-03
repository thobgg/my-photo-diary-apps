package de.bgghome.mpd.data

import java.util.prefs.Preferences

/**
 * java.util.prefs als [Ablage] fuer den Desktop-Client: unter Linux eine
 * Datei in ~/.java/.userPrefs, unter Windows die Registry (HKCU). Mengen
 * werden mit einem Trennzeichen abgelegt, das in Ordnernamen nicht vorkommt.
 */
class DesktopAblage(node: String = "de/bgghome/mpd") : Ablage {
    private val prefs: Preferences = Preferences.userRoot().node(node)
    private val trenner = "\u0000"

    override fun getString(key: String, default: String?): String? = prefs.get(key, default)
    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.putBoolean(key, value); prefs.flush() }
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.putLong(key, value); prefs.flush() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.putInt(key, value); prefs.flush() }
    override fun getStringSet(key: String, default: Set<String>): Set<String> {
        val roh = prefs.get(key, null) ?: return default
        return if (roh.isEmpty()) emptySet() else roh.split(trenner).toSet()
    }
    override fun putStringSet(key: String, value: Set<String>) {
        prefs.put(key, value.joinToString(trenner)); prefs.flush()
    }
    override fun remove(key: String) { prefs.remove(key); prefs.flush() }
}

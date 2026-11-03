package de.bgghome.mpd.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/*
 * Sprachumschalter wie im Web-Hamburger (08.09.2026: „wo soll der
 * Umschalter auf Englisch in App sein — so wie webview").
 *
 * Ab Android 13 macht das System die Arbeit: LocaleManager speichert die
 * Pro-App-Sprache, sie erscheint auch in den Systemeinstellungen, und die
 * Activity wird von selbst neu aufgebaut. Darunter merkt sich die App die
 * Wahl selbst und legt sie in attachBaseContext ueber die Konfiguration
 * (dann gilt sie fuer die Oberflaeche; Texte aus dem ViewModel ueber den
 * Application-Context bleiben dort in der Systemsprache — beide Geraete
 * laufen auf Android 16, der Rueckfall ist Vorsorge).
 */
object AppLanguage {

    val supported = listOf("de", "en")

    /** Aktive Sprache der App: „de" oder „en". */
    fun current(context: Context): String {
        val chosen = if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) null else list[0].language
        } else SettingsStore(context).appLanguage
        val lang = chosen ?: Locale.getDefault().language
        return if (lang in supported) lang else "de"
    }

    /** Wechselt de → en → de; die Oberflaeche baut sich danach neu auf. */
    fun toggle(context: Context) {
        val next = if (current(context) == "de") "en" else "de"
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(next)
            // Das System baut die Activity neu auf.
        } else {
            SettingsStore(context).appLanguage = next
            activityOf(context)?.recreate()
        }
    }

    /** Fuer MainActivity.attachBaseContext (nur < Android 13). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val lang = SettingsStore(base).appLanguage ?: return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

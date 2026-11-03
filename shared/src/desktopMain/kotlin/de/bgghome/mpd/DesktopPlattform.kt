package de.bgghome.mpd

import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.TimelineItem
import de.bgghome.mpd.data.DesktopAblage
import de.bgghome.mpd.data.LocalFolder
import de.bgghome.mpd.data.PcOrdner
import de.bgghome.mpd.data.SettingsStore

/**
 * Desktop-Umsetzung von [Plattform]: Einstellungen in java.util.prefs und
 * seit 04.10.2026 die Ordner auf diesem PC im Zeitstrahl (PcOrdner) —
 * markieren, hochladen, in den Papierkorb. Kein Hintergrund-Backup: das
 * Ordner-Backup am PC wollte Thomas erst ueberlegen (22.09.2026, Sorge:
 * Handy- und PC-Backup kommen sich ins Gehege); hochgeladen wird nur, was
 * man ausdruecklich sichert.
 */
class DesktopPlattform : Plattform {
    override val settings = SettingsStore(DesktopAblage())
    override val cacheOrdner: java.io.File by lazy {
        val basis = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: java.io.File(System.getProperty("user.home"), ".cache")
        java.io.File(basis, "mpd").apply { mkdirs() }
    }

    /** Was nicht verloren gehen darf (Buch der hochgeladenen Dateien):
     *  Linux ~/.local/share/mpd, Windows %APPDATA%\mpd — nicht der Cache. */
    private val datenOrdner: java.io.File by lazy {
        val basis = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
            ?: java.io.File(System.getProperty("user.home"), ".local/share")
        java.io.File(basis, "mpd").apply { mkdirs() }
    }

    private val pc by lazy { PcOrdner(settings, cacheOrdner, datenOrdner) }
    private val api by lazy { MpdApi(settings) }

    override val hatLokaleOrdner = true
    override val papierkorbFragtSelbst = false

    override fun scanLocalMedia(hidden: Set<String>): List<TimelineItem> = pc.scan()
    override fun ledgerKeys(): Set<String> = pc.buch()
    override fun localFolders(): List<LocalFolder> = pc.ordner()
    override fun lokaleExif(url: String) = pc.exif(url)

    override suspend fun inDenPapierkorb(uris: List<String>): Int = pc.inDenPapierkorb(uris)

    override suspend fun sichereUndWarte(
        keys: List<String>,
        fortschritt: (fertig: Int, gesamt: Int) -> Unit,
    ): SicherErgebnis = pc.sichere(api, keys, fortschritt)
}

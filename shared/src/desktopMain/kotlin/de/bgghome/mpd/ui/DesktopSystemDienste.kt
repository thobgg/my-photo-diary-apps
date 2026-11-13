package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import de.bgghome.mpd.state.Texte
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop-Fassung der [SystemDienste]. Ein Teilen-Blatt gibt es hier
 * nicht; Weitergeben heisst Zwischenablage, und das sagt die Meldung.
 * Der Versionsname kommt von jpackage (jpackage.app-version), beim Start
 * aus dem Gradle-Lauf steht "dev".
 */
class DesktopSystemDienste(private val settings: de.bgghome.mpd.data.SettingsStore) : SystemDienste {
    override val webIdiom = true

    override fun toggleSprache() {
        val neu = if ((settings.appLanguage ?: java.util.Locale.getDefault().language) == "de") "en" else "de"
        settings.appLanguage = neu
        java.util.Locale.setDefault(java.util.Locale(neu))
    }

    private fun inDieZwischenablage(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun kopiere(label: String, wert: String, meldung: (String) -> Unit) {
        inDieZwischenablage(wert)
        meldung(Texte.t(Res.string.common_copied_x, label))
    }

    override fun teileText(text: String, meldung: (String) -> Unit) {
        inDieZwischenablage(text)
        meldung(Texte.t(Res.string.common_copied_x, Texte.t(Res.string.share_link_chooser_title)))
    }

    override fun teileFoto(vm: de.bgghome.mpd.AppViewModel, photo: de.bgghome.mpd.api.Photo) {
        vm.meldung("Weitergeben an andere Programme gibt es am Desktop noch nicht.", kurz = true)
    }

    /** Ordnerwahl ueber den Swing-Dateidialog im Aussehen des Systems
     *  (GTK unter Linux, Explorer-Stil unter Windows). */
    override fun waehleOrdner(titel: String): String? = runCatching {
        runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
        val start = listOf("Bilder", "Pictures").map { java.io.File(System.getProperty("user.home"), it) }
            .firstOrNull { it.isDirectory } ?: java.io.File(System.getProperty("user.home"))
        val wahl = javax.swing.JFileChooser(start).apply {
            dialogTitle = titel
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        if (wahl.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) wahl.selectedFile?.absolutePath
        else null
    }.getOrNull()

    override val geraeteName: String =
        System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() } ?: System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull() ?: "desktop"

    override val kannHerunterladen = true

    /**
     * Downloads-Ordner des Benutzers: unter Linux der aus user-dirs.dirs
     * (deutsch oft „Downloads", kann aber anders heissen), sonst
     * ~/Downloads (Windows: %USERPROFILE%\Downloads).
     */
    private fun downloadsOrdner(): java.io.File {
        val home = System.getProperty("user.home")
        val xdg = runCatching {
            val conf = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
            java.io.File(conf, "user-dirs.dirs").readLines()
                .firstOrNull { it.startsWith("XDG_DOWNLOAD_DIR=") }
                ?.substringAfter('=')?.trim('"')?.replace("\$HOME", home)
        }.getOrNull()
        return (xdg?.let { java.io.File(it) } ?: java.io.File(home, "Downloads")).apply { mkdirs() }
    }

    override suspend fun ladeInDownloads(vm: de.bgghome.mpd.AppViewModel, url: String, filename: String): String? {
        val dir = downloadsOrdner()
        val stamm = filename.substringBeforeLast('.')
        val endung = filename.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var ziel = java.io.File(dir, filename)
        var n = 2
        while (ziel.exists()) { ziel = java.io.File(dir, "$stamm ($n)$endung"); n++ }
        // Erst in eine Teil-Datei, dann umbenennen: ein abgebrochener
        // Download soll nicht wie ein fertiges Foto aussehen.
        val teil = java.io.File(dir, ziel.name + ".part")
        try {
            teil.outputStream().use { vm.api.downloadStream(url, it) }
            if (!teil.renameTo(ziel)) throw java.io.IOException("Umbenennen fehlgeschlagen: ${ziel.name}")
        } catch (e: Exception) {
            teil.delete()
            throw e
        }
        return ziel.absolutePath
    }

    /** In den Downloads-Ordner des Benutzers, mit der Sitzung des API-Clients. */
    override suspend fun speichereDownload(vm: de.bgghome.mpd.AppViewModel, url: String, filename: String, mime: String) {
        vm.meldung(Texte.t(Res.string.trails_dl_started, filename), kurz = true)
        try {
            val dir = downloadsOrdner()
            val file = java.io.File(dir, filename)
            vm.api.downloadStream(url, file.outputStream())
            vm.meldung(Texte.t(Res.string.trails_dl_saved, file.absolutePath))
        } catch (e: Exception) {
            vm.meldung("Download fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override val kannGpxOeffnen = true

    /** GPX in einen Temp-Ordner laden und dem Systemprogramm fuer .gpx
     *  geben (xdg-open bzw. die Windows-Zuordnung). Gibt es keines, bleibt
     *  die Datei liegen und die Meldung nennt den Pfad. */
    override suspend fun oeffneGpx(vm: de.bgghome.mpd.AppViewModel, url: String, filename: String) {
        try {
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "mpd-gpx").apply { mkdirs() }
            val file = java.io.File(dir, filename.substringAfterLast('/').ifBlank { "tour.gpx" })
            file.outputStream().use { vm.api.downloadStream(url, it) }
            val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
            if (desktop != null && desktop.isSupported(java.awt.Desktop.Action.OPEN)) desktop.open(file)
            else vm.meldung(Texte.t(Res.string.trails_dl_saved, file.absolutePath))
        } catch (e: Exception) {
            vm.meldung(Texte.t(Res.string.gpx_open_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    /** „1.19.0 (Build 1)": Versionsnummer des gemeinsamen Codes plus Desktop-Build-Zaehler. */
    override val versionName: String = run {
        val version = System.getProperty("jpackage.app-version") ?: "dev"
        val build = System.getProperty("mpd.desktopBuild")
        if (build.isNullOrBlank()) version else "$version (Build $build)"
    }
}

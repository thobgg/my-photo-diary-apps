package de.bgghome.mpd.ui

import de.bgghome.mpd.AppViewModel
import java.awt.Desktop
import java.io.File
import de.bgghome.mpd.meldung

/**
 * Video und Ton am Desktop (22.09.2026, „moeglichst nativ"): kein
 * eingebetteter Player - die Datei kommt mit der Sitzung des API-Clients in
 * den Cache, dann oeffnet sie der Systemplayer (VLC, mpv, Windows Media
 * Player), der jedes Format kennt. Ein Fenster ausserhalb der App, dafuer
 * nichts am Paket.
 */
object DesktopMedien {
    suspend fun abspielen(vm: AppViewModel, url: String) {
        val endung = url.substringAfterLast('/').substringAfterLast('.', "").take(5)
            .takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() } }?.let { ".$it" } ?: ""
        vm.meldung("Wird geladen und im Systemplayer geöffnet …", kurz = true)
        try {
            val file = vm.api.downloadToCache(File(vm.plattform.cacheOrdner, "media"), url, endung)
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
            else vm.meldung("Kein Systemplayer gefunden: ${file.absolutePath}")
        } catch (e: Exception) {
            vm.meldung("Abspielen fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

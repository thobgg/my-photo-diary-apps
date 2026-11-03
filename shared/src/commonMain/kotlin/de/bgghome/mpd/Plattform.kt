package de.bgghome.mpd

import de.bgghome.mpd.api.TimelineItem
import de.bgghome.mpd.data.LocalFolder
import de.bgghome.mpd.data.SettingsStore

/**
 * Was das ViewModel von der Plattform braucht und was es auf dem Desktop
 * (noch) nicht gibt. Android setzt alles um (AndroidPlattform:
 * WorkManager, MediaStore); der Desktop nimmt vorerst die Vorgaben —
 * kein Geraete-Backup, keine Geraetefotos, keine Hintergrund-Worker.
 *
 * Bewusst schmal gehalten (Aufteilung 22.09.2026): Alles, was
 * Android-Typen in der Signatur traegt (Context, Uri, Intents), liegt
 * NICHT hier, sondern in der Android-Unterklasse AndroidAppViewModel.
 */
interface Plattform {
    val settings: SettingsStore

    /** App-eigener Cache-Ordner (Dokumente, Downloads). */
    val cacheOrdner: java.io.File

    /** Gibt es Aufnahmen auf dem Geraet (MediaStore, Papierkorb, Backup)? */
    val hatGeraetefotos: Boolean get() = false

    /** Beim Start: liegengebliebene Uploads, Beobachter, Benachrichtigungen. */
    fun starteHintergrund(backupEnabled: Boolean) {}

    /** Einen Upload-Lauf anstossen (Outbox, Kamera-Backup). */
    fun kickUpload() {}

    /** Benachrichtigungen sofort abholen (Dedupe/Ack macht der Worker). */
    fun kickNotify() {}

    /** Letzter Fehler des Upload-Workers, einmalig abgeholt (danach null). */
    fun holeUploadFehler(): String? = null

    /** Aufnahmen auf dem Geraet, ohne die versteckten Ordner. */
    fun scanLocalMedia(hidden: Set<String>): List<TimelineItem> = emptyList()

    /** Schluessel der bereits gesicherten Geraete-Aufnahmen (Backup-Ledger). */
    fun ledgerKeys(): Set<String> = emptySet()

    /** Geraeteordner mit Aufnahmen. */
    fun localFolders(): List<LocalFolder> = emptyList()

    /** EXIF einer Geraete-Aufnahme (content://-Adresse); null ohne Geraetefotos. */
    fun lokaleExif(url: String): de.bgghome.mpd.api.ExifData? = null

    /** Vom Server abgelehnte Geraete-Aufnahmen (Format), gemerkt vom Upload-Worker. */
    fun abgelehnteUploads(): List<String> = emptyList()

    /** Absagen vergessen und erneut versuchen. */
    fun abgelehnteErneut() {}

    /** „Jetzt sichern" fuer ausdruecklich gewaehlte Geraete-Aufnahmen. */
    fun sichereJetzt(keys: List<String>) {}

    // -- Desktop: Ordner auf diesem PC (04.10.2026) --------------------------

    /**
     * Ordner auf diesem Rechner, die der Nutzer selbst auswaehlt (Desktop).
     * Android hat stattdessen [hatGeraetefotos] mit MediaStore, Backup und
     * Geraete-Papierkorb — die Einstellungen dafuer gelten am PC nicht.
     */
    val hatLokaleOrdner: Boolean get() = false

    /**
     * Fragt das System beim Wegwerfen lokaler Dateien selbst nach? Android
     * ja (createTrashRequest), der Desktop nicht — dort muss die App fragen.
     */
    val papierkorbFragtSelbst: Boolean get() = true

    /**
     * Lokale Dateien direkt in den Papierkorb des Systems legen; liefert die
     * Zahl der erfolgreich verschobenen. null = geht nur ueber einen
     * System-Dialog der Oberflaeche (Android, vm.pendingTrash).
     */
    suspend fun inDenPapierkorb(uris: List<String>): Int? = null

    /**
     * „Jetzt sichern" im Vordergrund: laedt nacheinander hoch und kehrt
     * erst zurueck, wenn alles durch ist. null = laeuft als Hintergrund-
     * Auftrag ([sichereJetzt], Android/WorkManager).
     */
    suspend fun sichereUndWarte(
        keys: List<String>,
        fortschritt: (fertig: Int, gesamt: Int) -> Unit,
    ): SicherErgebnis? = null
}

/** Ergebnis von [Plattform.sichereUndWarte]. */
data class SicherErgebnis(val ok: Int, val gesamt: Int, val fehler: String?)

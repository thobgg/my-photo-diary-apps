package de.bgghome.mpd.ui

import androidx.compose.runtime.staticCompositionLocalOf
import de.bgghome.mpd.AppViewModel

/**
 * Systemdienste, die ein Bildschirm braucht und die je Plattform anders
 * aussehen: Zwischenablage, Weitergeben, Versionsname. In der Komposition
 * ueber [LocalSystemDienste]; bereitgestellt von MainActivity
 * (AndroidSystemDienste) bzw. dem Desktop-Fenster (DesktopSystemDienste).
 *
 * Aufteilung 22.09.2026: vorher liefen diese Handgriffe ueber
 * LocalContext und Intents und hielten Bildschirme in androidMain fest,
 * die sonst nichts von Android wussten (Beisteuern, Teilen-Dialoge).
 */
interface SystemDienste {
    /**
     * Bedien-Idiom: false = Handy (auch am Tablet, siehe CLAUDE.md),
     * true = Web (Desktop; Vorgabe, 22.09.2026: „es ist ja eine Desktop-App
     * und keine Mobile App, demenstprechend darf es sich staerker am
     * Webview orientieren"). Kopfzeile statt Leiste unten, Chips wie im
     * main-header des Webs.
     */
    val webIdiom: Boolean get() = false

    /**
     * In die Zwischenablage. [meldung] wird nur gerufen, wenn das System
     * keine eigene Vorschau zeigt (Android ab 13 zeigt sie selbst).
     */
    fun kopiere(label: String, wert: String, meldung: (String) -> Unit)

    /** Text an andere Programme geben — am Handy das Teilen-Blatt
     *  (WhatsApp, Signal, Mail), am Desktop die Zwischenablage. */
    fun teileText(text: String, meldung: (String) -> Unit)

    /** Versionsname der installierten App, wie Store und Paket ihn tragen. */
    val versionName: String

    /** Gibt es ein Teilen-Blatt fuer Fotos (Android)? Sonst bleibt der Knopf weg. */
    val kannFotosTeilen: Boolean get() = false

    /** Die Zeitstrahl-Auswahl an andere Apps geben (Android: Share-Sheet
     *  mit Dateien, laeuft im ViewModel weiter). */
    fun teileAuswahl(vm: AppViewModel) {}

    /** Ein einzelnes Foto als Datei an andere Apps geben (Android: Share-Sheet). */
    fun teileFoto(vm: AppViewModel, photo: de.bgghome.mpd.api.Photo) {}

    /** Geraete-Aufnahme lokal bearbeiten (Stufe 1); Android rechnet die
     *  Bytes und legt sie in vm.pendingLocalEdit, das Schreiben folgt nach
     *  der Zustimmung. Ohne Geraetefotos nur eine Fehlermeldung. */
    fun bearbeiteLokal(vm: AppViewModel, photo: de.bgghome.mpd.api.Photo, params: de.bgghome.mpd.api.PhotoEditParams, onError: (String) -> Unit) {
        onError("Lokales Bearbeiten gibt es nur auf dem Geraet.")
    }

    /** Kann Originale in den Downloads-Ordner dieses Rechners laden (Desktop). */
    val kannHerunterladen: Boolean get() = false

    /** Eine Datei still in den Downloads-Ordner laden, nie ueberschreiben
     *  (gleicher Name → „name (2).jpg"); liefert den Pfad, null = nicht
     *  moeglich. Fehler kommen als Ausnahme. */
    suspend fun ladeInDownloads(vm: AppViewModel, url: String, filename: String): String? = null

    /** Ordner waehlen (Desktop: Dateidialog des Systems); null = abgebrochen
     *  oder auf dieser Plattform nicht vorhanden. */
    fun waehleOrdner(titel: String): String? = null

    /** Name dieses Geraets als Vorschlag (Android: Modell, Desktop: Rechnername). */
    val geraeteName: String

    /** Datei mit Sitzung des API-Clients laden und in den Downloads-Ordner
     *  legen; meldet Start und Ergebnis ueber vm.meldung. */
    suspend fun speichereDownload(vm: AppViewModel, url: String, filename: String, mime: String)

    /** Sprache umschalten (de ↔ en). Android: Pro-App-Sprache des Systems
     *  (AppLanguage); Desktop: Einstellung + Locale, wirkt nach Neustart. */
    fun toggleSprache()

    /** Kann eine GPX an ein anderes Programm geben (Android: „Oeffnen mit",
     *  Desktop: Systemprogramm)? Sonst bleibt der Knopf weg. */
    val kannGpxOeffnen: Boolean get() = false

    /** Die GPX einer Tour mit der Sitzung laden und an eine Karten-App
     *  geben — OsmAnd, Komoot, Locus (Wunsch 13.11.2026: „Touren per Tap
     *  in OsmAnd oeffnen"). Bewusst die Systemauswahl und nicht ein festes
     *  Paket: welche Karten-App einer hat, weiss nur sein Geraet. Fehler
     *  kommen als vm.meldung. */
    suspend fun oeffneGpx(vm: AppViewModel, url: String, filename: String) {}
}

val LocalSystemDienste = staticCompositionLocalOf<SystemDienste> {
    error("LocalSystemDienste nicht bereitgestellt")
}

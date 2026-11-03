package de.bgghome.mpd.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Der Aufnahmezeitpunkt in den drei Schreibweisen, die die App braucht —
 * an EINER Stelle (08.09.2026).
 *
 * Bis dahin stand dieselbe Formatierung dreimal getrennt im Code:
 * UploadWorker baute den Server-Dateinamen, IncomingMedia dasselbe fuer
 * hereingereichte Dateien, LocalMedia das ISO-Datum fuer den Zeitstrahl.
 * Der gemischte Strom erkennt eine gesicherte Aufnahme aber genau daran,
 * dass Dateiname und Zeitstrahl-Datum zusammenpassen (Regeln.nurAufDemGeraet).
 * Driftet eine der drei Fassungen — ein Zeichen, eine fuehrende Null, eine
 * Zeitzone —, steht jede gesicherte Aufnahme doppelt da, oder schlimmer:
 * eine ungesicherte gilt als gesichert und verschwindet.
 *
 * Deshalb hier: eine Umrechnung, und ein Test, der die Rundreise prueft.
 */
internal object Stempel {

    private fun lokal(ms: Long): LocalDateTime =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** ms → `2026-06-21T11:24:30`, dasselbe Format wie die Server-Daten. */
    fun iso(ms: Long): String = lokal(ms).let { t ->
        "%04d-%02d-%02dT%02d:%02d:%02d".format(
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second,
        )
    }

    /**
     * ms → `2026-06-21_11-24-30` — der Vorname der Server-Datei. UploadWorker
     * haengt `-<kuerzel>.<ext>` an; die ersten vier Zeichen sind zugleich
     * der Durchlauf-Ordner (nackte Jahreszahl).
     */
    fun datei(ms: Long): String = vonIso(iso(ms))

    /**
     * `2026-06-21T11:24:30` → `2026-06-21_11-24-30`. Zu kurze Werte bleiben
     * unveraendert: lieber gar kein Treffer als ein zufaelliger.
     */
    fun vonIso(iso: String): String =
        if (iso.length < 19) iso
        else iso.substring(0, 10) + "_" + iso.substring(11, 19).replace(':', '-')

    /** Der Durchlauf-Ordner zu einem Stempel: die nackte Jahreszahl. */
    fun jahr(stempel: String): String = stempel.take(4)
}

package de.bgghome.mpd.util

import java.time.Instant
import java.time.ZoneOffset

/*
 * Fotos auf der Tour-Karte.
 *
 * Bis zum 05.11.2026 stand ein Foto nur dann auf der Tour, wenn seine
 * Uhrzeit aus dem Dateinamen in die Aufzeichnung fiel. Die Uhrzeit wird
 * als Zeit der Geraetezone gelesen, die Spur rechnet in UTC — auf Madeira
 * (UTC, im Maerz eine Stunde hinter Deutschland) lag damit jedes Foto eine
 * Stunde neben der Spur, der Spaziergang von 1:35 h blieb ohne Bilder,
 * obwohl alle Fotos Koordinaten im EXIF hatten. Vorgabe: „die Photos die
 * drauf muessten haben das selbe Datum in exif oder dateiname".
 *
 * Erster Versuch am selben Tag: Foto mit Koordinate steht an der
 * Koordinate, Uhr egal. Ergebnis: „jetzt sind in der karte photo von
 * mehreren tage" — ohne Zeitpruefung gehoert jedes Foto mit Ort zur Tour.
 *
 * Darum jetzt in zwei Schritten:
 *  1. Die Koordinaten verraten den ZONENVERSATZ: Fuer jedes Foto mit Ort
 *     wird der Spurpunkt gesucht, der ihm am naechsten liegt; der Abstand
 *     zwischen Foto-Uhrzeit und Spurzeit dort, auf halbe Stunden gerundet,
 *     ist der Versatz. Der haeufigste Wert gilt, wenn ihn mindestens die
 *     Haelfte der Kandidaten tragen (in Deutschland: 0).
 *  2. Mit dem Versatz gilt wieder die alte Regel: Nur Fotos aus der Zeit
 *     der Tour (bis 10 min davor und danach), ueber die korrigierte Uhr auf
 *     die Spur gelegt ([positionAt]). Die Koordinate greift nur, wo die
 *     Spur keine Position hergibt — in einer Aufzeichnungspause, beim
 *     Abstecher mit abgeschalteter Uhr. Fotos ohne Uhrzeit im Dateinamen
 *     bleiben weg wie bisher.
 *
 * Warum die Spur vor der Koordinate: Dritte Fassung am 05.11.2026 setzte
 * jedes Foto mit Ortsangabe an die Ortsangabe — „einige aber falscher
 * Ort". Das Handy schreibt ins EXIF oft den letzten bekannten Standort,
 * hundert Meter und mehr daneben; die Uhr zeichnet jede Sekunde auf. Fuer
 * den Ort eines Fotos ist die Spur zur Aufnahmezeit die bessere Quelle,
 * die Koordinate ist Zeuge fuer die Zone und Rueckfall fuer die Luecke.
 */

/** Ortsangabe eines Fotos aus dem EXIF, gebuendelt ueber /api/photos/geo. */
data class FotoOrt(val lat: Double, val lon: Double)

/** Ein Foto des Albums mit dem, was die Karte von ihm wissen muss. */
data class TourFoto(val file: String, val ort: FotoOrt?)

/** Schluessel in der Ortsliste: Bereich, aufgeloester Albumordner, Datei. */
fun fotoOrtSchluessel(space: String, album: String, file: String): String = "$space/$album/$file"

/** Ein Foto zaehlt als Zeuge fuer den Versatz, wenn seine Ortsangabe so nah
 *  an der Spur liegt — GPS-Streuung von Handy und Uhr zusammen. */
internal const val ZEUGE_METER = 250.0

/** Zonen liegen hoechstens 14 h auseinander, in halben Stunden gezaehlt. */
private const val VERSATZ_MAX_HALBE = 28

/** Bis 10 min vor und nach der Spur gehoert ein Foto noch zur Tour (wie positionAt). */
private const val TOUR_RAND_SEKUNDEN = 600L

/** Ausserhalb der Aufzeichnung zaehlt ein Foto mit Koordinate noch dazu, wenn es
 *  binnen 12 h um die Spur entstand und hoechstens so weit neben ihr liegt. */
private const val NEBEN_SPUR_SEKUNDEN = 12 * 3600L
internal const val NEBEN_SPUR_METER = 100.0

/**
 * Wo jedes Foto auf der Tour-Karte steht — gleiche Reihenfolge wie [fotos],
 * null fuer Fotos, die nicht zur Tour gehoeren.
 */
fun tourFotoPositionen(track: GpxTrack, fotos: List<TourFoto>): List<TrackPosition?> {
    val idx = track.zeitIndex
    if (idx.epochs.size < 2) return fotos.map { null }
    val versatz = zonenVersatz(track, fotos)
    val von = idx.epochs.first() - TOUR_RAND_SEKUNDEN
    val bis = idx.epochs.last() + TOUR_RAND_SEKUNDEN
    return fotos.map { foto ->
        val ts = filenameEpochSeconds(foto.file)?.minus(versatz) ?: return@map null
        val ort = foto.ort?.takeIf { it.lat.isFinite() && it.lon.isFinite() }
        if (ts < von || ts > bis) {
            // Ausserhalb der Aufzeichnung (05.11.2026, Madeira: die Uhr lief
            // erst ab der zweiten Stunde des Spaziergangs): ein Foto vom
            // selben Tag, dessen Koordinate dicht an der Spur liegt, gehoert
            // dazu und steht an der Koordinate. Das Hotel am Abend liegt
            // abseits und bleibt weg.
            if (ort == null || ts < von - NEBEN_SPUR_SEKUNDEN || ts > bis + NEBEN_SPUR_SEKUNDEN) return@map null
            val nah = idx.points.any { haversineKm(it.lat, it.lon, ort.lat, ort.lon) * 1000 <= NEBEN_SPUR_METER }
            return@map if (nah) TrackPosition(ort.lat, ort.lon) else null
        }
        positionAt(track, ts) ?: ort?.let { TrackPosition(it.lat, it.lon) }
    }
}

/**
 * Versatz der Foto-Uhrzeiten gegen die Spur in Sekunden (Foto minus Spur),
 * aus den Fotos mit Ortsangabe geschaetzt; 0, wenn es keine Zeugen gibt
 * oder sie sich nicht einig sind.
 *
 * Probiert alle Versaetze in halben Stunden (Zonen sind volle oder halbe
 * Stunden) von -14 h bis +14 h durch und zaehlt je Versatz die Fotos, die
 * mit korrigierter Uhr auf der Spur liegen UND deren Ortsangabe hoechstens
 * [ZEUGE_METER] von der Spur an dieser Zeit entfernt ist. Der Versatz mit
 * den meisten Treffern gilt, wenn er eindeutig ist.
 *
 * Erster Anlauf war umgekehrt (naechster Spurpunkt zum Foto, Zeit dort
 * ablesen) — „gar nichts kein Foto": fuehrt ein Spaziergang denselben Weg
 * zurueck, liegt der naechste Punkt oft auf dem Rueckweg, die Abstaende
 * streuen, keine Mehrheit. Von der Zeit her gedacht ist das eindeutig.
 */
internal fun zonenVersatz(track: GpxTrack, fotos: List<TourFoto>): Long {
    if (track.zeitIndex.epochs.size < 2) return 0
    val zeugen = fotos.mapNotNull { foto ->
        val ort = foto.ort?.takeIf { it.lat.isFinite() && it.lon.isFinite() } ?: return@mapNotNull null
        val ts = filenameEpochSeconds(foto.file) ?: return@mapNotNull null
        ts to ort
    }
    if (zeugen.isEmpty()) return 0
    var bester = 0L
    var besteZahl = 0
    var eindeutig = true
    for (halbe in -VERSATZ_MAX_HALBE..VERSATZ_MAX_HALBE) {
        val versatz = halbe * 1800L
        val zahl = zeugen.count { (ts, ort) ->
            val auf = positionAt(track, ts - versatz) ?: return@count false
            haversineKm(auf.lat, auf.lon, ort.lat, ort.lon) * 1000 <= ZEUGE_METER
        }
        when {
            zahl > besteZahl -> { bester = versatz; besteZahl = zahl; eindeutig = true }
            zahl == besteZahl && zahl > 0 -> eindeutig = false
        }
    }
    return if (besteZahl > 0 && eindeutig) bester else 0
}

/**
 * Tageszeitraum fuer die Ortsabfrage: erster bis letzter Tag der Spur, je
 * einen Tag Puffer. Der Server filtert nach dem Aufnahmetag in Ortszeit,
 * die Spur steht in UTC — der Puffer faengt den Zonenunterschied und die
 * Tour ueber Mitternacht ab. Null, wenn die Spur keine Zeiten hat.
 */
fun spurTage(track: GpxTrack): Pair<String, String>? {
    val epochs = track.zeitIndex.epochs
    if (epochs.isEmpty()) return null
    fun tag(epoch: Long, versatzTage: Long) =
        Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).toLocalDate().plusDays(versatzTage).toString()
    return tag(epochs.first(), -1) to tag(epochs.last(), 1)
}

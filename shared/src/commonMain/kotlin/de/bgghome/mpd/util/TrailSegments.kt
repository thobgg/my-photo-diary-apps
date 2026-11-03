package de.bgghome.mpd.util

import de.bgghome.mpd.api.TrailPoint
import de.bgghome.mpd.api.TrailVisit

/*
 * Der Tagesweg als Kette aus Wegen und Orten (08.09.2026).
 *
 * Rueckmeldung: „jetzt muesste man noch den Knaeuel wegkriegen." Im Stillstand
 * meldet das Handy weiter, jeder Fix streut um 10 bis 30 Meter, und die
 * Karte zeichnete aus hunderten solcher Punkte ein Linienknaeuel um den
 * Aufenthaltsort. Die Rohpunkte bleiben im Store (Leitplanke „Angebot vor
 * Nutzung") — nur GEZEICHNET werden sie nicht mehr: Punkte, die zeitlich
 * in das Fenster eines erkannten Aufenthalts fallen, verschwinden aus der
 * Linie, der Aufenthalt bleibt sein Marker. Der Weg davor endet am Ort,
 * der Weg danach beginnt dort — die Linie dockt am Marker an.
 *
 * Reine Funktion ohne Android, damit sie getestet werden kann.
 */

/** Ein gezeichneter Punkt: Breite, Laenge. */
typealias LatLon = Pair<Double, Double>

/**
 * Zerlegt die Punkte eines Tages in Linienabschnitte.
 *
 * Getrennt wird — wie bisher — bei Quellenwechsel und ab [gapSeconds]
 * Abstand. Neu: Punkte innerhalb eines Aufenthalts (start_ts..end_ts,
 * einschliesslich) kommen nicht in die Linie; an ihrer
 * Stelle steht einmal die Position des Aufenthalts, als Ende des
 * ankommenden und Anfang des abgehenden Abschnitts. Ohne Aufenthalte
 * (keine Ableitung fuer den Tag) bleibt alles wie vorher.
 *
 * Abschnitte mit weniger als zwei Punkten werden verworfen.
 *
 * Der Aufenthalt wird NUR ueber die Zeit zugeordnet, nicht ueber die
 * Quelle: die eigene Erkennung des Servers legt Aufenthalte mit source
 * „mpd" an, die Punkte darunter tragen „owntracks" — ein Quellenvergleich
 * haette nie gegriffen (Hinweis der Server-Seite, 08.09.2026; das Web
 * macht es seit 2576bc7 genauso).
 */
internal fun tagesAbschnitte(
    points: List<TrailPoint>,
    visits: List<TrailVisit>,
    gapSeconds: Long = 30 * 60,
): List<List<LatLon>> {
    val out = mutableListOf<List<LatLon>>()
    var current = mutableListOf<LatLon>()
    var lastTst = 0L
    var lastSource: String? = null
    /** Der Aufenthalt, in dem der vorige Punkt lag — null = unterwegs. */
    var inVisit: TrailVisit? = null

    fun flush() {
        if (current.size >= 2) out += current
        current = mutableListOf()
    }

    for (point in points) {
        val visit = visits.firstOrNull { point.tst in it.startTs..it.endTs }
        if (visit != null) {
            if (inVisit !== visit) {
                // Ankunft: der Weg endet am Ort, nicht am letzten Streu-Fix.
                if (current.isNotEmpty() && (lastSource == null || lastSource == point.source)) {
                    current += visit.lat to visit.lon
                }
                flush()
                inVisit = visit
            }
            lastTst = point.tst
            lastSource = point.source
            continue
        }

        val broken = lastSource != null &&
            (point.source != lastSource || point.tst - lastTst > gapSeconds)
        if (broken) flush()
        if (inVisit != null) {
            // Abfahrt: der Weg beginnt am Ort.
            if (!broken || current.isEmpty()) current += inVisit!!.lat to inVisit!!.lon
            inVisit = null
        }
        current += point.lat to point.lon
        lastTst = point.tst
        lastSource = point.source
    }
    flush()
    return out
}

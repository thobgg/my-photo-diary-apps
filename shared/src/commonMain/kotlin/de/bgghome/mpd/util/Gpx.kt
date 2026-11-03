package de.bgghome.mpd.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPX-Auswertung — Port von _parseGpxXml/_extractPoints/_parseGpxDesc aus
 * frontend/js/album-render.js. Gleiche Statistik, gleiche Fallbacks.
 */

data class GpxPoint(val lat: Double, val lon: Double, val ele: Double?, val time: String?)

data class GpxTrack(
    val points: List<GpxPoint>,
    val distKm: Double,
    val ascent: Double,
    val descent: Double,
    val hasEle: Boolean,
    val desc: String,
    /** "H:MM h" oder "M min" aus den Zeitstempeln, sonst null. */
    val duration: String?,
) {
    /** Zeitstempel EINMAL geparst, fuer [positionAt] — siehe dort. */
    val zeitIndex: ZeitIndex by lazy { ZeitIndex.aus(points) }

    /** Ausgeduennte Punkte fuers Zeichnen — siehe [ausduennen]. */
    val anzeigePunkte: List<GpxPoint> by lazy { ausduennen(points) }
}

/** Statistik-Chips wie im Web: berechnet bevorzugt, desc als Fallback. */
data class TourStat(val icon: String, val value: String)

fun parseGpx(xmlText: String): GpxTrack {
    val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
    parser.setInput(xmlText.reader())

    // ROUTEN ZAEHLEN AUCH (15.09.2026). Der Parser kannte nur <trkpt>, also
    // aufgezeichnete Spuren. Eine GPX kann dieselbe Strecke aber als ROUTE
    // fuehren, <rte> mit <rtept> — geplant statt gegangen, ohne Zeitstempel
    // und oft ohne Hoehen. Solche Dateien wurden still leer geparst: keine
    // Punkte, keine Karte, keine Meldung. Thomas fiel es an einer
    // rekonstruierten Radtour von 1992 auf (29 rtept, kein einziges trkpt).
    //
    // Gesammelt wird in ZWEI Listen und am Ende entschieden. Wer beides in
    // eine schuettet, verbindet bei Dateien mit trk UND rte die Spur mit
    // der Planung zu einer Zickzack-Linie.
    val trkPoints = mutableListOf<GpxPoint>()
    val rtePoints = mutableListOf<GpxPoint>()
    var desc = ""
    var inMetadata = false
    var inTrkpt = false
    var inRtept = false
    var lat = 0.0
    var lon = 0.0
    var ele: Double? = null
    var time: String? = null

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "metadata" -> inMetadata = true
                "trkpt", "rtept" -> {
                    if (parser.name == "trkpt") inTrkpt = true else inRtept = true
                    lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                    lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                    ele = null
                    time = null
                }
                "ele" -> if (inTrkpt || inRtept) ele = parser.nextText().toDoubleOrNull()
                "time" -> if (inTrkpt || inRtept) time = parser.nextText()
                "desc" -> if (inMetadata && desc.isEmpty()) desc = parser.nextText()
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "metadata" -> inMetadata = false
                "trkpt" -> {
                    inTrkpt = false
                    if (!lat.isNaN() && !lon.isNaN()) trkPoints += GpxPoint(lat, lon, ele, time)
                }
                "rtept" -> {
                    inRtept = false
                    if (!lat.isNaN() && !lon.isNaN()) rtePoints += GpxPoint(lat, lon, ele, time)
                }
            }
        }
        event = parser.next()
    }

    // Die Spur gewinnt, wenn es eine gibt — sie ist der tatsaechliche Weg.
    val points = trkPoints.ifEmpty { rtePoints }

    var distKm = 0.0
    var ascent = 0.0
    var descent = 0.0
    var hasEle = false
    for (i in 1 until points.size) {
        distKm += haversineKm(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        val e0 = points[i - 1].ele
        val e1 = points[i].ele
        if (e0 != null && e1 != null) {
            hasEle = true
            val diff = e1 - e0
            if (diff > 0) ascent += diff else descent += abs(diff)
        }
    }

    val times = points.mapNotNull { it.time }
    val duration = if (times.size >= 2) parseDuration(times.first(), times.last()) else null

    return GpxTrack(points, distKm, ascent, descent, hasEle, desc, duration)
}

fun tourStats(track: GpxTrack): List<TourStat> {
    val descStats = parseGpxDesc(track.desc)
    val chips = mutableListOf<TourStat>()
    when {
        track.distKm > 0.05 -> chips += TourStat("📏", "%.1f km".format(track.distKm))
        descStats["distance"] != null -> chips += TourStat("📏", descStats["distance"]!!)
    }
    when {
        track.duration != null -> chips += TourStat("⏱", track.duration)
        descStats["duration"] != null -> chips += TourStat("⏱", descStats["duration"]!!)
    }
    when {
        track.hasEle && track.ascent > 5 -> chips += TourStat("⬆", "${track.ascent.toInt()} m")
        descStats["elevation"] != null -> chips += TourStat("⬆", descStats["elevation"]!!)
    }
    // ABSTIEG, aber nur wenn er etwas erzaehlt (15.09.2026, aus dem
    // Server-Seite uebernommen). Er wurde laengst berechnet und nie gezeigt.
    // Bei einer Rundtour ist er fast gleich dem Aufstieg — zwei nahezu
    // identische Zahlen nebeneinander sind keine Auskunft, sondern Rauschen.
    // Erst ein deutlicher Unterschied heisst etwas: dass es netto bergab
    // oder bergauf ging. Zehn Prozent Abweichung, mindestens 20 Meter.
    if (track.hasEle && track.descent > 20) {
        val unterschied = kotlin.math.abs(track.descent - track.ascent)
        if (unterschied > 20 && unterschied > track.ascent * 0.1) {
            chips += TourStat("⬇", "${track.descent.toInt()} m")
        }
    }
    descStats["calories"]?.let { chips += TourStat("🔥", it) }
    descStats["hr"]?.let { chips += TourStat("❤️", it) }
    return chips
}

// Einmal kompiliert (29.09.2026): tourStats laeuft bei jeder Recomposition
// des Tour-Elements, die Regexe wurden dabei jedes Mal neu gebaut.
private val RX_DISTANZ = Regex("""Distanz:\s*([\d.,]+)\s*km""")
private val RX_ZEIT = Regex("""Zeit:\s*(\d+)h\s*(\d+)min""")
private val RX_HOEHE = Regex("""Höhe max:\s*([\d.,]+)\s*m""")
private val RX_KALORIEN = Regex("""Kalorien:\s*([\d.,]+)\s*kcal""")
private val RX_HR = Regex("""Ø HR:\s*([\d.,]+)\s*bpm""")

private fun parseGpxDesc(desc: String): Map<String, String> {
    if (desc.isBlank()) return emptyMap()
    val stats = mutableMapOf<String, String>()
    fun num(rx: Regex): Double? = rx.find(desc)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
    num(RX_DISTANZ)?.let { stats["distance"] = "%.1f km".format(it) }
    RX_ZEIT.find(desc)?.let {
        stats["duration"] = "${it.groupValues[1]}:${it.groupValues[2].padStart(2, '0')} h"
    }
    num(RX_HOEHE)?.let { stats["elevation"] = "${it.toInt()} m" }
    num(RX_KALORIEN)?.let { stats["calories"] = "${it.toInt()} kcal" }
    num(RX_HR)?.let { stats["hr"] = "${it.toInt()} bpm" }
    return stats
}

private fun parseDuration(startIso: String, endIso: String): String? = try {
    val diff = (Instant.parse(endIso).epochSecond - Instant.parse(startIso).epochSecond)
    if (diff <= 0) null
    else {
        val h = diff / 3600
        val m = (diff % 3600) / 60
        if (h > 0) "$h:${m.toString().padStart(2, '0')} h" else "$m min"
    }
} catch (e: Exception) {
    null
}

/**
 * Foto auf dem Track verorten (Feature „Fotos auf der Tour", 03.09.2026):
 * MPD-Dateinamen tragen den Aufnahmezeitpunkt (YYYY-MM-DD_HH-MM-SS…),
 * GPX-Punkte tragen ISO-Zeiten — dazwischen wird linear interpoliert.
 * Zeitzonen-Ehrlichkeit: Dateinamen sind Lokalzeit, GPX ist UTC; wir
 * rechnen mit der Geraete-Zeitzone. Fuer Aufnahmen in der Heimatzone
 * stimmt das; Fernreisen koennen um die Zonendifferenz verrutschen.
 */
private val FILENAME_TS = Regex("""(\d{4})-(\d{2})-(\d{2})[_ ](\d{2})-(\d{2})-(\d{2})""")

fun filenameEpochSeconds(filename: String): Long? {
    val m = FILENAME_TS.find(filename) ?: return null
    val (y, mo, d, h, min, s) = m.destructured
    return try {
        java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), min.toInt(), s.toInt())
            .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
    } catch (e: Exception) {
        null
    }
}

data class TrackPosition(val lat: Double, val lon: Double)

/**
 * Position zum Zeitpunkt [epochSeconds] auf dem Track — linear zwischen
 * den umgebenden Punkten interpoliert; bis 10 min vor/nach dem Track
 * wird auf den Endpunkt geklemmt, sonst null (Foto gehoert nicht zur Tour).
 *
 * LUECKEN IN DER AUFZEICHNUNG (16.09.2026). Bis hierher wurde zwischen
 * zwei Punkten linear interpoliert, egal wie weit sie ZEITLICH
 * auseinanderlagen. War die Aufzeichnung eine Stunde aus und man dabei
 * woanders, landete ein Foto aus dieser Stunde auf der geraden Linie
 * zwischen dem letzten Punkt davor und dem ersten danach — an einem Ort,
 * an dem man nie war. Und zwar unauffaellig: das Foto steht ja auf der
 * Spur, es sieht richtig aus.
 *
 * Zwei Faelle sehen gleich aus und muessen getrennt werden, die Distanz
 * entscheidet:
 *
 *  - Lange Pause, Punkte NAH beieinander: man ist stehengeblieben, das
 *    Café, die Rast. Der Ort ist klar, das Foto kommt dorthin.
 *  - Lange Pause, Punkte WEIT auseinander: die Aufzeichnung war aus und
 *    man ist woanders hin. Wo das Foto entstand, weiss die Spur nicht —
 *    also wird es NICHT gesetzt. Ein fehlendes Pin ist ehrlich, ein
 *    falsch platziertes luegt.
 */
fun positionAt(track: GpxTrack, epochSeconds: Long): TrackPosition? {
    // O(FOTOS x PUNKTE) WAR DER ANR (29.09.2026, Vorgabe: „App reagiert
    // nicht" beim Scrollen in Alben mit Touren). Bis hierher parste jeder
    // Aufruf ALLE Zeitstempel des Tracks neu (Instant.parse je Punkt), und
    // zwar bevor feststand, ob das Foto ueberhaupt in den Zeitraum faellt —
    // je Foto des Albums, je Tour, auf dem Main-Thread. Bei einem Album
    // mit 300 Fotos und einer Spur mit 20.000 Punkten sind das sechs
    // Millionen Instant.parse. Jetzt: Zeitstempel einmal je Track in
    // [GpxTrack.zeitIndex], darin binaer suchen.
    val idx = track.zeitIndex
    val n = idx.epochs.size
    if (n < 2) return null
    val slack = 600L
    val firstT = idx.epochs[0]
    val lastT = idx.epochs[n - 1]
    if (epochSeconds < firstT - slack || epochSeconds > lastT + slack) return null
    if (epochSeconds <= firstT) return idx.points[0].let { TrackPosition(it.lat, it.lon) }
    if (epochSeconds >= lastT) return idx.points[n - 1].let { TrackPosition(it.lat, it.lon) }
    // firstT < t < lastT, also liegt der erste Index >= t in 1..n-1.
    var i = idx.ersterAb(epochSeconds)
    if (epochSeconds !in idx.epochs[i - 1]..idx.epochs[i]) {
        // Zeiten nicht aufsteigend (zusammengeklebte Dateien): wie frueher
        // linear das erste passende Paar nehmen.
        i = (1 until n).firstOrNull { epochSeconds in idx.epochs[it - 1]..idx.epochs[it] } ?: return null
    }
    val t1 = idx.epochs[i - 1]
    val t2 = idx.epochs[i]
    val p1 = idx.points[i - 1]
    val p2 = idx.points[i]
    if (t2 - t1 > LUECKE_SEKUNDEN) {
        val meter = haversineKm(p1.lat, p1.lon, p2.lat, p2.lon) * 1000
        return if (meter <= STILLSTAND_METER) TrackPosition(p1.lat, p1.lon) else null
    }
    val f = if (t2 == t1) 0.0 else (epochSeconds - t1).toDouble() / (t2 - t1)
    return TrackPosition(
        p1.lat + (p2.lat - p1.lat) * f,
        p1.lon + (p2.lon - p1.lon) * f,
    )
}

/**
 * Die Zeitstempel eines Tracks als Sekunden, parallel zu den Punkten, die
 * einen lesbaren Zeitstempel haben (Punkte ohne Zeit fehlen hier — wie
 * frueher in der Liste `timed`).
 */
class ZeitIndex(val epochs: LongArray, val points: List<GpxPoint>) {
    /** Erster Index i mit epochs[i] >= [t], sonst epochs.size. */
    fun ersterAb(t: Long): Int {
        var lo = 0
        var hi = epochs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (epochs[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun aus(points: List<GpxPoint>): ZeitIndex {
            val epochs = ArrayList<Long>(points.size)
            val mitZeit = ArrayList<GpxPoint>(points.size)
            for (p in points) {
                val t = p.time ?: continue
                val s = runCatching { Instant.parse(t).epochSecond }.getOrNull() ?: continue
                epochs += s
                mitZeit += p
            }
            return ZeitIndex(epochs.toLongArray(), mitZeit)
        }
    }
}

/**
 * Ausduennen fuers ZEICHNEN (29.09.2026). Eine Aufzeichnung im Sekundentakt
 * hat zehntausende Punkte; die Karte zeichnet die Spur zweimal (weisse
 * Unterlage, Magenta) und bei jedem Bild neu. Douglas-Peucker mit
 * [ANZEIGE_TOLERANZ_METER]: weg faellt nur, was auf der Karte keinen
 * sichtbaren Unterschied macht. Distanz, Hoehenmeter, Dauer und
 * [positionAt] rechnen weiter mit ALLEN Punkten. Kurze Spuren (bis [ab]
 * Punkte) bleiben, wie sie sind.
 */
internal const val ANZEIGE_TOLERANZ_METER = 2.0

internal fun ausduennen(
    points: List<GpxPoint>,
    toleranzMeter: Double = ANZEIGE_TOLERANZ_METER,
    ab: Int = 500,
): List<GpxPoint> {
    val n = points.size
    if (n <= ab) return points
    // Ebene Naeherung reicht fuer Meter-Abstaende: Breite in Meter je Grad,
    // Laenge mit cos(mittlere Breite) gestaucht.
    val meterProGrad = 111_320.0
    val mittlereBreite = points.sumOf { it.lat } / n
    val kx = cos(Math.toRadians(mittlereBreite)) * meterProGrad
    val xs = DoubleArray(n) { points[it].lon * kx }
    val ys = DoubleArray(n) { points[it].lat * meterProGrad }
    val behalten = BooleanArray(n)
    behalten[0] = true
    behalten[n - 1] = true
    val stapel = ArrayDeque<IntArray>()
    stapel.addLast(intArrayOf(0, n - 1))
    while (stapel.isNotEmpty()) {
        val (a, b) = stapel.removeLast()
        if (b - a < 2) continue
        val ax = xs[a]
        val ay = ys[a]
        val dx = xs[b] - ax
        val dy = ys[b] - ay
        val len2 = dx * dx + dy * dy
        var maxD = 0.0
        var maxI = -1
        for (i in a + 1 until b) {
            // Abstand zur STRECKE a-b, nicht zur Geraden: bei einer Schleife
            // liegen a und b nah beieinander, die Gerade sagt dann nichts.
            val f = if (len2 == 0.0) 0.0 else (((xs[i] - ax) * dx + (ys[i] - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val ex = xs[i] - (ax + f * dx)
            val ey = ys[i] - (ay + f * dy)
            val d = sqrt(ex * ex + ey * ey)
            if (d > maxD) {
                maxD = d
                maxI = i
            }
        }
        if (maxD > toleranzMeter) {
            behalten[maxI] = true
            stapel.addLast(intArrayOf(a, maxI))
            stapel.addLast(intArrayOf(maxI, b))
        }
    }
    return points.filterIndexed { i, _ -> behalten[i] }
}

/**
 * Ab wann zwei Spurpunkte eine LUECKE sind: fuenf Minuten. Eine laufende
 * Aufzeichnung legt Punkte im Sekunden- bis Minutentakt ab, auch sparsam
 * eingestellte Geraete bleiben deutlich darunter.
 */
internal const val LUECKE_SEKUNDEN = 300L

/** Innerhalb dieser Entfernung gilt eine Luecke als Stillstand. */
internal const val STILLSTAND_METER = 200.0

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

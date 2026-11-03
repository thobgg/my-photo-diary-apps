package de.bgghome.mpd

import de.bgghome.mpd.util.parseGpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Eine GPX kann dieselbe Strecke als Spur (<trk>/<trkpt>, aufgezeichnet)
 * oder als Route (<rte>/<rtept>, geplant) fuehren. Der Parser kannte bis
 * zum 15.09.2026 nur die Spur und gab bei einer reinen Route eine leere
 * Punktliste zurueck — die Karte blieb leer, ohne Fehlermeldung.
 *
 * Aufgefallen an einer rekonstruierten Radtour von 1992: GPX 1.1, 29
 * rtept, kein trkpt, keine Zeitstempel, keine Hoehen.
 */
class GpxRouteTest {

    private fun rte(vararg paare: Pair<Double, Double>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">""")
        append("""<metadata><name>Tour</name></metadata><rte>""")
        paare.forEach { (lat, lon) -> append("""<rtept lat="$lat" lon="$lon"><name>P</name></rtept>""") }
        append("</rte></gpx>")
    }

    @Test
    fun `eine reine Route liefert Punkte`() {
        val track = parseGpx(rte(52.5 to 13.4, 52.6 to 13.5, 52.7 to 13.6))
        assertEquals(3, track.points.size)
        assertTrue("Distanz sollte berechnet sein", track.distKm > 1.0)
    }

    @Test
    fun `ohne Zeitstempel gibt es keine Dauer, aber Punkte`() {
        val track = parseGpx(rte(52.5 to 13.4, 52.6 to 13.5))
        assertEquals(null, track.duration)
        assertEquals(2, track.points.size)
        assertTrue("ohne ele kein Hoehenprofil", !track.hasEle)
    }

    @Test
    fun `hat die Datei beides, gewinnt die aufgezeichnete Spur`() {
        val beides = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
            <rte><rtept lat="10.0" lon="10.0"/><rtept lat="10.1" lon="10.1"/></rte>
            <trk><trkseg>
              <trkpt lat="52.5" lon="13.4"/><trkpt lat="52.6" lon="13.5"/><trkpt lat="52.7" lon="13.6"/>
            </trkseg></trk></gpx>"""
        val track = parseGpx(beides)
        assertEquals("die drei Spurpunkte, nicht die zwei Routenpunkte", 3, track.points.size)
        assertEquals(52.5, track.points.first().lat, 0.0001)
    }
}

/*
 * Der Abstieg wurde laengst berechnet und nie gezeigt (bis 15.09.2026).
 * Gezeigt wird er nur, wenn er sich deutlich vom Aufstieg unterscheidet —
 * bei einer Rundtour stuenden sonst zwei fast gleiche Zahlen nebeneinander.
 */
class GpxAbstiegTest {

    private fun trk(vararg hoehen: Double) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""")
        hoehen.forEachIndexed { i, ele ->
            append("""<trkpt lat="${47.0 + i * 0.01}" lon="11.0"><ele>$ele</ele></trkpt>""")
        }
        append("</trkseg></trk></gpx>")
    }

    private fun abstiegGezeigt(xml: String) =
        de.bgghome.mpd.util.tourStats(de.bgghome.mpd.util.parseGpx(xml)).any { it.icon == "⬇" }

    @Test
    fun `Rundtour zeigt keinen Abstieg`() {
        // rauf 400, runter 400 — die Zahl waere dieselbe wie der Aufstieg
        assertTrue(!abstiegGezeigt(trk(500.0, 900.0, 500.0)))
    }

    @Test
    fun `eine Abfahrt zeigt den Abstieg`() {
        // fast nur bergab: 50 rauf, 800 runter
        assertTrue(abstiegGezeigt(trk(1000.0, 1050.0, 250.0)))
    }

    @Test
    fun `ohne Hoehen kein Abstieg`() {
        val ohne = """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="47.0" lon="11.0"/><trkpt lat="47.1" lon="11.0"/>
            </trkseg></trk></gpx>"""
        assertTrue(!abstiegGezeigt(ohne))
    }
}

/*
 * Fotos auf der Tourkarte werden anhand der Aufnahmezeit zwischen zwei
 * Spurpunkte gelegt. Bis zum 16.09.2026 auch dann, wenn die Aufzeichnung
 * dazwischen lange aus war — das Foto landete auf einer geraden Linie an
 * einem Ort, an dem man nie war.
 */
class GpxLueckenTest {

    /** Drei Punkte mit frei waehlbaren Zeiten (Sekunden ab einem Nullpunkt). */
    private fun spur(vararg punkte: Triple<Double, Double, Long>): de.bgghome.mpd.util.GpxTrack {
        val null0 = 1_780_000_000L
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""")
            punkte.forEach { (lat, lon, t) ->
                val zeit = java.time.Instant.ofEpochSecond(null0 + t).toString()
                append("""<trkpt lat="$lat" lon="$lon"><time>$zeit</time></trkpt>""")
            }
            append("</trkseg></trk></gpx>")
        }
        return de.bgghome.mpd.util.parseGpx(xml)
    }

    private val null0 = 1_780_000_000L

    @Test
    fun `waehrend laufender Aufzeichnung wird interpoliert`() {
        // zwei Punkte, 60 s auseinander, rund 1,1 km — normale Radtour
        val t = spur(Triple(47.00, 11.0, 0), Triple(47.01, 11.0, 60))
        val pos = de.bgghome.mpd.util.positionAt(t, null0 + 30)
        assertTrue("sollte gesetzt werden", pos != null)
        assertEquals("genau dazwischen", 47.005, pos!!.lat, 0.0001)
    }

    @Test
    fun `lange Pause mit Ortswechsel setzt das Foto nicht`() {
        // eine Stunde Luecke, dazwischen 11 km — Aufzeichnung war aus
        val t = spur(Triple(47.00, 11.0, 0), Triple(47.10, 11.0, 3600))
        assertEquals(null, de.bgghome.mpd.util.positionAt(t, null0 + 1800))
    }

    @Test
    fun `lange Pause am selben Ort setzt das Foto dorthin`() {
        // eine Stunde im Café, die Punkte liegen 50 m auseinander
        val t = spur(Triple(47.0000, 11.0, 0), Triple(47.00045, 11.0, 3600))
        val pos = de.bgghome.mpd.util.positionAt(t, null0 + 1800)
        assertTrue("Stillstand: der Ort ist klar", pos != null)
        assertEquals(47.0000, pos!!.lat, 0.0001)
    }

    // --- Zeit-Index (29.09.2026, ANR beim Scrollen): positionAt parst nicht
    // mehr je Foto alle Zeitstempel, sondern sucht binaer im einmal gebauten
    // Index. Die Ergebnisse muessen denen der alten Schleife entsprechen.

    private fun langeSpur(n: Int, sekundenSchritt: Int = 10) =
        spur(*Array(n) { i -> Triple(47.0 + i * 0.0001, 11.0, (i * sekundenSchritt).toLong()) })

    @Test
    fun `binaere Suche trifft dasselbe Paar wie die Schleife`() {
        val t = langeSpur(2000)
        // mitten in einem Intervall: Punkt 1234 bei 12340 s, 1235 bei 12350 s
        val pos = de.bgghome.mpd.util.positionAt(t, null0 + 12345)
        assertTrue(pos != null)
        assertEquals(47.0 + 1234.5 * 0.0001, pos!!.lat, 1e-9)
        // genau auf einem Punkt
        val exakt = de.bgghome.mpd.util.positionAt(t, null0 + 5000)
        assertEquals(47.0 + 500 * 0.0001, exakt!!.lat, 1e-9)
        // Anfang und Ende samt Toleranz von 10 min
        assertEquals(47.0, de.bgghome.mpd.util.positionAt(t, null0 - 599)!!.lat, 1e-9)
        assertEquals(null, de.bgghome.mpd.util.positionAt(t, null0 - 601))
        assertEquals(47.0 + 1999 * 0.0001, de.bgghome.mpd.util.positionAt(t, null0 + 19990 + 599)!!.lat, 1e-9)
        assertEquals(null, de.bgghome.mpd.util.positionAt(t, null0 + 19990 + 601))
    }

    @Test
    fun `Punkte ohne Zeitstempel stoeren den Index nicht`() {
        val xml = buildString {
            append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""")
            append("""<trkpt lat="47.00" lon="11.0"><time>${java.time.Instant.ofEpochSecond(null0)}</time></trkpt>""")
            append("""<trkpt lat="48.00" lon="11.0"></trkpt>""")
            append("""<trkpt lat="47.01" lon="11.0"><time>${java.time.Instant.ofEpochSecond(null0 + 60)}</time></trkpt>""")
            append("</trkseg></trk></gpx>")
        }
        val t = de.bgghome.mpd.util.parseGpx(xml)
        assertEquals(2, t.zeitIndex.epochs.size)
        assertEquals(47.005, de.bgghome.mpd.util.positionAt(t, null0 + 30)!!.lat, 0.0001)
    }

    @Test
    fun `Zeitindex wird nur einmal gebaut`() {
        val t = langeSpur(50)
        assertTrue(t.zeitIndex === t.zeitIndex)
    }

    // --- Ausduennen fuers Zeichnen: Douglas-Peucker mit 2 m Toleranz.

    @Test
    fun `kurze Spuren bleiben unveraendert`() {
        val t = langeSpur(100)
        assertTrue(t.anzeigePunkte === t.points)
    }

    @Test
    fun `eine gerade Linie schrumpft auf zwei Punkte`() {
        val t = langeSpur(2000)
        assertEquals(2, t.anzeigePunkte.size)
        assertEquals(t.points.first(), t.anzeigePunkte.first())
        assertEquals(t.points.last(), t.anzeigePunkte.last())
    }

    @Test
    fun `eine Ecke bleibt erhalten`() {
        // 600 Punkte nach Norden, dann 600 nach Osten
        val p = ArrayList<Triple<Double, Double, Long>>()
        repeat(600) { i -> p += Triple(47.0 + i * 0.0001, 11.0, i.toLong()) }
        repeat(600) { i -> p += Triple(47.0 + 599 * 0.0001, 11.0 + (i + 1) * 0.0001, 600L + i) }
        val t = spur(*p.toTypedArray())
        assertEquals(3, t.anzeigePunkte.size)
        assertEquals(47.0 + 599 * 0.0001, t.anzeigePunkte[1].lat, 1e-9)
    }

    @Test
    fun `ein Zickzack von 50 m Amplitude bleibt vollstaendig`() {
        // 0.00045 Grad Breite sind rund 50 m — weit ueber der Toleranz
        val t = spur(*Array(1000) { i -> Triple(47.0 + (i % 2) * 0.00045, 11.0 + i * 0.001, i.toLong()) })
        assertEquals(1000, t.anzeigePunkte.size)
    }
}

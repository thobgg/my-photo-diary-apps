package de.bgghome.mpd

import de.bgghome.mpd.util.FotoOrt
import de.bgghome.mpd.util.TourFoto
import de.bgghome.mpd.util.filenameEpochSeconds
import de.bgghome.mpd.util.fotoOrtSchluessel
import de.bgghome.mpd.util.parseGpx
import de.bgghome.mpd.util.spurTage
import de.bgghome.mpd.util.tourFotoPositionen
import de.bgghome.mpd.util.zonenVersatz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/*
 * Fotos auf der Tour-Karte (05.11.2026). Befund von Madeira: Alle Fotos
 * hatten Koordinaten, keins stand auf der Karte, weil die Uhrzeit aus dem
 * Dateinamen als Zeit der Geraetezone gelesen wird und die Spur in UTC
 * rechnet. Der erste Fix (Koordinate gilt, Uhr egal) brachte „photo von
 * mehreren tage" auf die Karte. Diese Tests halten die zweite Fassung fest:
 *  - die Koordinaten verraten den Zonenversatz, danach zaehlt wieder die
 *    Zeit der Tour;
 *  - ohne Koordinaten greift der alte Weg UNVERAENDERT (Versatz 0);
 *  - Fotos anderer Tage bleiben weg, auch mit Koordinate;
 *  - ohne Uhrzeit im Namen und ohne Koordinate wird kein Foto erfunden.
 */
class TourFotosTest {

    /**
     * Spur von (50, 8.00) nach (50, 8.02), knapp 1,5 km in einer Stunde,
     * ein Punkt alle vier Minuten (dichter als die Luecken-Regel von
     * positionAt, und die Punkte liegen unter 100 m auseinander, damit ein
     * Foto auf der Spur einen Zeugen-Punkt in Reichweite hat). Die Spurzeiten
     * sind [mitte] ± 30 min plus [spurVersatz] — damit laesst sich die
     * Fremdzone nachstellen: Auf Madeira ist die Spur (UTC) eine Stunde
     * SPAETER als die Foto-Uhrzeit, wie die App sie liest (deutsche Zeit).
     * [mitte] ist die Epoche eines Dateinamens, damit die Zeitzone des
     * Testrechners keine Rolle spielt.
     */
    private fun spur(mitte: Long, spurVersatz: Long = 0): String {
        fun iso(e: Long) = Instant.ofEpochSecond(e).toString()
        val punkte = (0..15).joinToString("") { i ->
            val lon = 8.0 + 0.02 * i / 15.0
            """<trkpt lat="50.0" lon="$lon"><time>${iso(mitte - 1800 + i * 240 + spurVersatz)}</time></trkpt>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            $punkte</trkseg></trk></gpx>"""
    }

    private val datei = "2022-03-07_10-30-00.jpg"
    private val mitte = filenameEpochSeconds(datei)!!
    private val heimat = parseGpx(spur(mitte))
    /** Spur eine Stunde spaeter als die Foto-Uhren: der Madeira-Fall. */
    private val madeira = parseGpx(spur(mitte, 3600))

    /** Ort auf der Spur bei Minute [min] nach Spurbeginn (lon 8.00 .. 8.02 ueber 60 min). */
    private fun aufSpur(min: Int) = FotoOrt(50.0, 8.0 + 0.02 * min / 60.0)

    @Test
    fun heimatOhneKoordinatenWieBisher() {
        val pos = tourFotoPositionen(heimat, listOf(TourFoto(datei, null)))
        assertNotNull(pos[0])
        assertEquals(50.0, pos[0]!!.lat, 1e-9)
        assertEquals(8.01, pos[0]!!.lon, 1e-6) // Mitte der Spur
    }

    @Test
    fun heimatMitKoordinateStehtTrotzdemAufDerSpur() {
        // EXIF-Koordinate 100 m neben der Spur (Handy-GPS): die Spur gewinnt
        val pos = tourFotoPositionen(heimat, listOf(TourFoto(datei, FotoOrt(50.001, 8.01))))
        assertEquals(50.0, pos[0]!!.lat, 1e-9)
        assertEquals(8.01, pos[0]!!.lon, 1e-6)
    }

    @Test
    fun inDerAufzeichnungspauseGiltDieKoordinate() {
        // Spur mit Loch von Minute 20 bis 40 (ueber 5 min, Punkte 475 m
        // auseinander): positionAt sagt dort ehrlich null — mit Koordinate
        // steht das Foto an der Koordinate, ohne bleibt es weg.
        val mitLoch = parseGpx(buildString {
            fun iso(e: Long) = Instant.ofEpochSecond(e).toString()
            append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""")
            for (i in 0..15) {
                val minute = i * 4
                if (minute in 21..39) continue
                append("""<trkpt lat="50.0" lon="${8.0 + 0.02 * minute / 60.0}"><time>${iso(mitte - 1800 + minute * 60)}</time></trkpt>""")
            }
            append("</trkseg></trk></gpx>")
        })
        val abseits = FotoOrt(50.002, 8.01)
        val pos = tourFotoPositionen(mitLoch, listOf(TourFoto(datei, abseits), TourFoto(datei, null)))
        assertEquals(50.002, pos[0]!!.lat, 1e-9)
        assertNull(pos[1])
    }

    @Test
    fun madeiraOhneKoordinatenFehltWieVorher() {
        // Spur gelesen 11:00–12:00, Foto 10:00: eine Stunde davor, faellt
        // raus — genau der Madeira-Befund ohne Koordinaten.
        val pos = tourFotoPositionen(madeira, listOf(TourFoto("2022-03-07_10-00-00.jpg", null)))
        assertNull(pos[0])
    }

    @Test
    fun madeiraKoordinatenVerratenDenVersatz() {
        val fotos = listOf(
            TourFoto("2022-03-07_10-10-00.jpg", aufSpur(10)),
            TourFoto("2022-03-07_10-40-00.jpg", aufSpur(40)),
        )
        assertEquals(-3600L, zonenVersatz(madeira, fotos))
    }

    @Test
    fun madeiraFotosStehenMitVersatzAufDerKarte() {
        val fotos = listOf(
            TourFoto("2022-03-07_10-10-00.jpg", aufSpur(10)),
            TourFoto("2022-03-07_10-40-00.jpg", aufSpur(40)),
            // ohne Koordinate, aber in der Tourzeit: ueber die korrigierte Uhr auf die Spur
            TourFoto("2022-03-07_10-30-00.jpg", null),
            // Vortag mit Koordinate am selben Ort: gehoert nicht dazu
            TourFoto("2022-03-06_10-20-00.jpg", aufSpur(20)),
            // ohne Uhrzeit im Namen
            TourFoto("IMG_0001.jpg", aufSpur(20)),
        )
        val pos = tourFotoPositionen(madeira, fotos)
        assertNotNull(pos[0]); assertNotNull(pos[1])
        assertNotNull(pos[2])
        assertEquals(8.01, pos[2]!!.lon, 1e-6) // 10:30 + 1 h Versatz = Mitte der Spur
        assertNull(pos[3])
        assertNull(pos[4])
    }

    @Test
    fun vorDerAufzeichnungMitKoordinateNebenDerSpur() {
        // Madeira: eine Stunde vor dem Start, Koordinate auf der Spur → dazu;
        // dieselbe Stunde mit Koordinate 2 km abseits (Hotel) → weg
        val pos = tourFotoPositionen(heimat, listOf(
            TourFoto("2022-03-07_09-20-00.jpg", aufSpur(35)), // nicht auf einer Halbstunden-Position, sonst wird es Zeuge
            TourFoto("2022-03-07_09-20-00.jpg", FotoOrt(50.02, 8.01)),
            TourFoto("2022-03-07_09-20-00.jpg", null),
        ))
        assertEquals(8.0 + 0.02 * 35 / 60.0, pos[0]!!.lon, 1e-6)
        assertNull(pos[1])
        assertNull(pos[2])
    }

    @Test
    fun fotoVomNaechstenTagBleibtWegAuchMitKoordinate() {
        val pos = tourFotoPositionen(heimat, listOf(TourFoto("2022-03-08_10-30-00.jpg", aufSpur(30))))
        assertNull(pos[0])
    }

    @Test
    fun zeugenAbseitsDerSpurZaehlenNicht() {
        // Koordinate 5 km neben der Spur: kein Zeuge, Versatz bleibt 0
        val fotos = listOf(TourFoto("2022-03-07_10-10-00.jpg", FotoOrt(50.05, 8.1)))
        assertEquals(0L, zonenVersatz(madeira, fotos))
    }

    @Test
    fun uneinigeZeugenErgebenKeinenVersatz() {
        // drei Zeugen, die je einen anderen Versatz stuetzen: kein eindeutiger Sieger
        val fotos = listOf(
            TourFoto("2022-03-07_10-10-00.jpg", aufSpur(10)),
            TourFoto("2022-03-07_12-10-00.jpg", aufSpur(10)),
            TourFoto("2022-03-07_14-10-00.jpg", aufSpur(10)),
        )
        assertEquals(0L, zonenVersatz(madeira, fotos))
    }

    @Test
    fun hinUndZurueckVerwirrtDenVersatzNicht() {
        // Spur geht bis Minute 30 hin und denselben Weg zurueck; das Foto bei
        // Minute 10 liegt raeumlich auch bei Minute 50 — von der Zeit her
        // gedacht passt nur der Versatz von einer Stunde.
        val hinZurueck = parseGpx(buildString {
            fun iso(e: Long) = Instant.ofEpochSecond(e).toString()
            append("""<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>""")
            for (i in 0..15) {
                val minute = i * 4
                val lon = 8.0 + 0.02 * (if (minute <= 30) minute else 60 - minute) / 60.0
                append("""<trkpt lat="50.0" lon="$lon"><time>${iso(mitte - 1800 + i * 240 + 3600)}</time></trkpt>""")
            }
            append("</trkseg></trk></gpx>")
        })
        val fotos = listOf(
            TourFoto("2022-03-07_10-10-00.jpg", aufSpur(10)),
            TourFoto("2022-03-07_10-20-00.jpg", aufSpur(20)),
            TourFoto("2022-03-07_10-50-00.jpg", aufSpur(10)), // Rueckweg, gleicher Ort wie Minute 10
        )
        assertEquals(-3600L, zonenVersatz(hinZurueck, fotos))
        assertEquals(3, tourFotoPositionen(hinZurueck, fotos).count { it != null })
    }

    @Test
    fun kaputteKoordinateFaelltAufDieUhrzeitZurueck() {
        val pos = tourFotoPositionen(heimat, listOf(TourFoto(datei, FotoOrt(Double.NaN, 8.0))))
        assertEquals(50.0, pos[0]!!.lat, 1e-9)
    }

    @Test
    fun spurTageMitEinemTagPuffer() {
        val (von, bis) = spurTage(heimat)!!
        val t = java.time.LocalDate.parse(Instant.ofEpochSecond(mitte).toString().take(10))
        assertEquals(t.minusDays(1).toString(), von)
        assertEquals(t.plusDays(1).toString(), bis)
    }

    @Test
    fun spurOhneZeitenHatKeinenZeitraum() {
        val ohne = parseGpx(
            """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
            <trkpt lat="50.0" lon="8.0"/><trkpt lat="50.0" lon="9.0"/></trkseg></trk></gpx>""",
        )
        assertNull(spurTage(ohne))
        assertEquals(listOf(null), tourFotoPositionen(ohne, listOf(TourFoto(datei, aufSpur(30)))))
    }

    @Test
    fun schluesselIstBereichOrdnerDatei() {
        assertEquals("shared/2022-madeira/a.jpg", fotoOrtSchluessel("shared", "2022-madeira", "a.jpg"))
    }
}

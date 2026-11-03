package de.bgghome.mpd

import de.bgghome.mpd.api.TrailPoint
import de.bgghome.mpd.api.TrailVisit
import de.bgghome.mpd.util.tagesAbschnitte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Der Tagesweg ohne Knaeuel: Punkte im Fenster eines Aufenthalts werden
 * nicht gezeichnet, der Aufenthalt selbst dockt die Wege an. Die Regel
 * hier ist reine Rechnung — ein Fehler waere auf der Karte nur „sieht
 * komisch aus", deshalb steht sie unter Test.
 */
class TrailSegmentsTest {

    private fun p(t: Long, lat: Double, lon: Double, src: String = "mpd") =
        TrailPoint(source = src, tst = t, lat = lat, lon = lon)

    private fun v(from: Long, to: Long, lat: Double, lon: Double, src: String = "mpd") =
        TrailVisit(source = src, startTs = from, endTs = to, lat = lat, lon = lon)

    @Test fun ohneAufenthalteBleibtAllesWieVorher() {
        val pts = listOf(p(0, 1.0, 1.0), p(60, 1.1, 1.1), p(120, 1.2, 1.2))
        val segs = tagesAbschnitte(pts, emptyList())
        assertEquals(1, segs.size)
        assertEquals(3, segs[0].size)
    }

    @Test fun streuungImAufenthaltVerschwindetDerOrtBleibt() {
        // Weg hin (0..120), Aufenthalt 200..800 mit sechs Streu-Fixes,
        // Weg weiter (900..1020).
        val pts = listOf(
            p(0, 1.0, 1.0), p(60, 1.1, 1.1), p(120, 1.2, 1.2),
            p(200, 2.001, 2.0), p(300, 2.0, 2.002), p(400, 1.999, 2.0),
            p(500, 2.0, 1.998), p(600, 2.001, 2.001), p(800, 2.0, 2.0),
            p(900, 3.0, 3.0), p(960, 3.1, 3.1), p(1020, 3.2, 3.2),
        )
        val segs = tagesAbschnitte(pts, listOf(v(200, 800, 2.0, 2.0)))
        assertEquals(2, segs.size)
        // Ankommender Weg: drei Punkte + der Ort als Ende
        assertEquals(4, segs[0].size)
        assertEquals(2.0 to 2.0, segs[0].last())
        // Abgehender Weg: der Ort als Anfang + drei Punkte
        assertEquals(4, segs[1].size)
        assertEquals(2.0 to 2.0, segs[1].first())
        // Kein Streu-Fix in irgendeiner Linie
        assertTrue(segs.flatten().none { it.first in 1.99..2.0011 && it != (2.0 to 2.0) })
    }

    @Test fun tagBeginntImAufenthalt() {
        val pts = listOf(p(0, 2.0, 2.0), p(100, 2.001, 2.0), p(400, 3.0, 3.0), p(460, 3.1, 3.1))
        val segs = tagesAbschnitte(pts, listOf(v(0, 200, 2.0, 2.0)))
        assertEquals(1, segs.size)
        assertEquals(2.0 to 2.0, segs[0].first())
        assertEquals(3, segs[0].size)
    }

    @Test fun tagEndetImAufenthalt() {
        val pts = listOf(p(0, 1.0, 1.0), p(60, 1.1, 1.1), p(300, 2.0, 2.0), p(400, 2.001, 2.0))
        val segs = tagesAbschnitte(pts, listOf(v(300, 900, 2.0, 2.0)))
        assertEquals(1, segs.size)
        assertEquals(2.0 to 2.0, segs[0].last())
        assertEquals(3, segs[0].size)
    }

    @Test fun aufenthaltZaehltUnabhaengigVonDerQuelle() {
        // Die eigene Erkennung des Servers legt Aufenthalte als source
        // „mpd" an, die Punkte darunter heissen „owntracks". Zugeordnet
        // wird nur ueber die Zeit — sonst griffe die Regel nie (Hinweis
        // Server-Chat 08.09.2026). Ankommender Weg 0..60, dann Aufenthalt
        // 100..200 (Streuung raus), abgehender Weg ab 240.
        val pts = listOf(
            p(0, 1.0, 1.0), p(60, 1.1, 1.1),
            p(100, 5.01, 5.0), p(150, 5.0, 5.01), p(200, 4.99, 5.0),
            p(240, 6.0, 6.0), p(300, 6.1, 6.1),
        )
        val segs = tagesAbschnitte(pts, listOf(v(100, 200, 5.0, 5.0, src = "mpd")))
        assertEquals(2, segs.size)
        // Streuung 5.01/4.99 ist weg, beide Abschnitte enden/beginnen am Ort 5.0/5.0
        assertEquals(5.0 to 5.0, segs[0].last())
        assertEquals(5.0 to 5.0, segs[1].first())
    }

    @Test fun lueckeUndQuellenwechselTrennenWeiter() {
        val pts = listOf(
            p(0, 1.0, 1.0), p(60, 1.1, 1.1),
            p(60 + 31 * 60, 1.5, 1.5), p(60 + 32 * 60, 1.6, 1.6),
            p(60 + 33 * 60, 1.7, 1.7, src = "google"), p(60 + 34 * 60, 1.8, 1.8, src = "google"),
        )
        val segs = tagesAbschnitte(pts, emptyList())
        assertEquals(3, segs.size)
    }
}

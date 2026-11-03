package de.bgghome.mpd

import de.bgghome.mpd.data.Stempel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Die Rundreise, an der der gemischte Zeitstrahl haengt: UploadWorker
 * benennt eine Aufnahme nach ihrem Zeitpunkt (Stempel.datei), LocalMedia
 * traegt denselben Zeitpunkt als ISO-Datum in den Strom (Stempel.iso), und
 * der Vergleich rechnet das ISO-Datum wieder in den Dateinamen um
 * (Stempel.vonIso). Bis 08.09.2026 waren das drei getrennte Formatierungen
 * in drei Dateien — hier steht der Test, der ein Auseinanderdriften sofort
 * rot macht.
 */
class StempelTest {

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    @Test fun dateinameUndIsoDatumBeschreibenDenselbenMoment() {
        val t = ms(2026, 6, 21, 11, 24, 30)
        assertEquals("2026-06-21T11:24:30", Stempel.iso(t))
        assertEquals("2026-06-21_11-24-30", Stempel.datei(t))
        // Der Vergleich im Strom: ISO → Dateiname muss den Upload-Namen treffen.
        assertEquals(Stempel.datei(t), Stempel.vonIso(Stempel.iso(t)))
    }

    @Test fun fuehrendeNullenBleibenInBeidenSchreibweisen() {
        val t = ms(2026, 1, 5, 0, 7, 9)
        assertEquals("2026-01-05T00:07:09", Stempel.iso(t))
        assertEquals("2026-01-05_00-07-09", Stempel.datei(t))
    }

    @Test fun durchlaufOrdnerIstDieNackteJahreszahl() {
        assertEquals("2026", Stempel.jahr(Stempel.datei(ms(2026, 12, 31, 23, 59, 59))))
    }

    @Test fun zuKurzesDatumBleibtUnveraendert() {
        // Lieber kein Treffer als ein zufaelliger.
        assertEquals("2026-06-21", Stempel.vonIso("2026-06-21"))
    }
}

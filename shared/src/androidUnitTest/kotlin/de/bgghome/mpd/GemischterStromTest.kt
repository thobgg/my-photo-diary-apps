package de.bgghome.mpd

import de.bgghome.mpd.data.LocalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/*
 * Der gemischte Zeitstrahl steht und faellt damit, ob eine Aufnahme, die
 * auf dem Geraet UND auf der NAS liegt, als dieselbe erkannt wird. Wird sie
 * es nicht, steht sie doppelt da; wird sie faelschlich erkannt, fehlt eine
 * ungesicherte Aufnahme in der Uebersicht — und DAS merkt niemand.
 *
 * Der schwierigste Schluessel ist der Sekundenstempel: `UploadWorker`
 * benennt beim Hochladen um, aus `20260621_112430.jpg` wird
 * `2026-06-21_11-24-30-t.jpg`. Der Originalname traegt also nicht, dieser
 * Stempel schon — auch dann noch, wenn das Ledger fehlt, etwa nach einer
 * Neuinstallation.
 */
class GemischterStromTest {

    @Test fun stempelEntsprichtDemNamensschemaDesUploads() {
        // So baut UploadWorker den Namen: stamp + "-" + Kuerzel + Endung.
        assertEquals("2026-06-21_11-24-30", LocalMedia.stampOf("2026-06-21T11:24:30"))
    }

    @Test fun stempelPasstAufDenServerDateinamen() {
        // Der Vergleich im Zusammenbau nimmt die ersten 19 Zeichen des
        // Server-Dateinamens. Beides muss zusammenpassen, sonst erscheint
        // jede gesicherte Aufnahme ein zweites Mal.
        val serverName = "2026-06-21_11-24-30-t.jpg"
        assertEquals(LocalMedia.stampOf("2026-06-21T11:24:30"), serverName.take(19))
    }

    @Test fun eineSekundeUnterschiedIstEinAndererStempel() {
        // Zwei Aufnahmen in derselben Sekunde waeren nicht unterscheidbar —
        // eine Sekunde Abstand muss reichen.
        assertNotEquals(
            LocalMedia.stampOf("2026-06-21T11:24:30"),
            LocalMedia.stampOf("2026-06-21T11:24:31"),
        )
    }

    @Test fun unvollstaendigesDatumBleibtUnveraendert() {
        // Lieber gar kein Treffer als ein falscher: ein zu kurzer Wert darf
        // nicht zufaellig auf einen Server-Namen passen.
        assertEquals("2026-06-21", LocalMedia.stampOf("2026-06-21"))
    }

    @Test fun mitternachtUndEinstelligeWerteBehaltenDieNullen() {
        // Fuehrende Nullen sind der klassische Formatierungsfehler — ohne
        // sie waere der Stempel um ein Zeichen kuerzer und traefe nie.
        assertEquals("2026-01-05_00-07-09", LocalMedia.stampOf("2026-01-05T00:07:09"))
    }
}

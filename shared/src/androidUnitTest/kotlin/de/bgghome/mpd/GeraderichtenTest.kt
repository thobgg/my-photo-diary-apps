package de.bgghome.mpd

import de.bgghome.mpd.data.LocalPhotoEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Geraderichten schneidet das Bild auf das groesste Rechteck zu, das nach
 * der Drehung noch vollstaendig darin liegt. Rechnet die App anders als der
 * Server, sieht dasselbe Foto vor und nach dem Sichern verschieden aus —
 * und zwar nicht auffaellig falsch, sondern nur ein bisschen anders
 * beschnitten. Genau die Sorte Fehler, die man erst Monate spaeter bemerkt.
 *
 * Deshalb hier die Eckwerte, die sich ohne Bild pruefen lassen.
 */
class GeraderichtenTest {

    @Test fun ohneDrehungBleibtDasGanzeBild() {
        val (w, h) = LocalPhotoEdit.largestInscribed(4000f, 3000f, 0.0)
        assertEquals(4000f, w, 0.5f)
        assertEquals(3000f, h, 0.5f)
    }

    @Test fun einGradKostetNurWenig() {
        // Der Alltagsfall: ein leicht schiefer Horizont. Es darf spuerbar
        // etwas wegfallen, aber nicht die halbe Aufnahme.
        val (w, h) = LocalPhotoEdit.largestInscribed(4000f, 3000f, Math.toRadians(1.0))
        assertTrue("Breite zu klein: $w", w > 3800f)
        assertTrue("Hoehe zu klein: $h", h > 2850f)
        assertTrue("Nichts beschnitten — die Drehung wurde ignoriert", w < 4000f)
    }

    @Test fun ergebnisPasstImmerInsBild() {
        // Die eigentliche Zusicherung: Das zugeschnittene Rechteck darf nie
        // groesser sein als das gedrehte Bild, sonst entstuenden schwarze
        // Ecken. Ueber den ganzen erlaubten Bereich (±45°) geprueft.
        for (grad in -45..45) {
            val rad = Math.toRadians(grad.toDouble())
            val (w, h) = LocalPhotoEdit.largestInscribed(4000f, 3000f, rad)
            val diagonaleBreite = 4000f * Math.abs(Math.cos(rad)).toFloat() +
                3000f * Math.abs(Math.sin(rad)).toFloat()
            val diagonaleHoehe = 4000f * Math.abs(Math.sin(rad)).toFloat() +
                3000f * Math.abs(Math.cos(rad)).toFloat()
            assertTrue("$grad°: Breite $w passt nicht in $diagonaleBreite", w <= diagonaleBreite + 1f)
            assertTrue("$grad°: Hoehe $h passt nicht in $diagonaleHoehe", h <= diagonaleHoehe + 1f)
            assertTrue("$grad°: Breite nicht positiv ($w)", w > 0f)
            assertTrue("$grad°: Hoehe nicht positiv ($h)", h > 0f)
        }
    }

    @Test fun drehungInBeideRichtungenErgibtDasselbe() {
        // +3° und -3° muessen gleich viel kosten; alles andere waere ein
        // Vorzeichenfehler.
        val (wPlus, hPlus) = LocalPhotoEdit.largestInscribed(4000f, 3000f, Math.toRadians(3.0))
        val (wMinus, hMinus) = LocalPhotoEdit.largestInscribed(4000f, 3000f, Math.toRadians(-3.0))
        assertEquals(wPlus, wMinus, 0.5f)
        assertEquals(hPlus, hMinus, 0.5f)
    }

    @Test fun hochformatVerhaeltSichWieQuerformat() {
        // Dieselbe Aufnahme hochkant darf nicht anders beschnitten werden.
        val (wQuer, hQuer) = LocalPhotoEdit.largestInscribed(4000f, 3000f, Math.toRadians(5.0))
        val (wHoch, hHoch) = LocalPhotoEdit.largestInscribed(3000f, 4000f, Math.toRadians(5.0))
        assertEquals(wQuer, hHoch, 1f)
        assertEquals(hQuer, wHoch, 1f)
    }
}

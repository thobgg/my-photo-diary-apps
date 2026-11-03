package de.bgghome.mpd

import de.bgghome.mpd.data.Tonwerte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Die Tonwert-Rechnung gegen Pillow (07.09.2026).
 *
 * Diese Tests sind der Grund, warum die Regler ueberhaupt freigegeben
 * werden konnten. Bis heute stand im Code, Pillow und Android rechneten
 * verschieden und dasselbe Foto saehe deshalb vor und nach dem Sichern
 * anders aus. Das stimmte fuer Androids fertige Bausteine — nicht fuer
 * eine nachgebaute Rechnung. Nur: „nachgebaut" ist eine Behauptung,
 * solange sie niemand nachrechnet.
 *
 * Die Sollwerte stammen aus Pillows Quelltext, nicht aus einem Lauf:
 *   ImageEnhance.Brightness  blend(schwarz, bild, f)
 *   ImageEnhance.Contrast    blend(grauflaeche(m), bild, f), m = mittleres L
 *   ImageEnhance.Color       blend(graubild, bild, f)
 *   Image.blend              out = a + f·(b-a), UINT8-Cast (abschneiden),
 *                            bei f ausserhalb 0..1 vorher geklemmt
 *   Convert rgb2l            L = (r·299 + g·587 + b·114) / 1000, Ganzzahl
 *
 * Die gefaehrliche Richtung ist hier nicht „falsch", sondern „fast
 * richtig": ein Fehler von zwei, drei Stufen faellt an keinem einzelnen
 * Bild auf und macht trotzdem jedes bearbeitete Foto anders, je nachdem
 * wo es lag.
 */
class TonwerteTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    // ── Graustufe ──────────────────────────────────────────────────────

    @Test fun grauBenutztDie601Gewichte() {
        // Reines Gruen: 587/1000 — mit BT.709 (ColorMatrix.setToSaturation)
        // waeren es 182. Der Unterschied ist der ganze Punkt.
        // Reines Rot: 255·299/1000 = 76. Mit BT.709 waeren es 55, mit
        // gleichen Gewichten 85 — die Zahl unterscheidet die Formeln.
        assertEquals(76, Tonwerte.grau(255, 0, 0))
        assertEquals(149, Tonwerte.grau(0, 255, 0))
        assertEquals(29, Tonwerte.grau(0, 0, 255))
        assertEquals(255, Tonwerte.grau(255, 255, 255))
        assertEquals(0, Tonwerte.grau(0, 0, 0))
    }

    @Test fun grauSchneidetAbUndRundetNicht() {
        // (1·299 + 1·587 + 1·114)/1000 = 1000/1000 = 1 … und bei 0,999?
        // 0·299 + 1·587 + 3·114 = 929 → 0, nicht 1.
        assertEquals(0, Tonwerte.grau(0, 1, 3))
    }

    // ── Mischen ────────────────────────────────────────────────────────

    @Test fun mischenSchneidetAb() {
        // 100 + 0,5·(201-100) = 150,5 → 150. Gerundet waeren es 151.
        assertEquals(150, Tonwerte.mische(100, 201, 0.5f))
    }

    @Test fun mischenKlemmtInBeideRichtungen() {
        assertEquals(255, Tonwerte.mische(0, 200, 2f))
        assertEquals(0, Tonwerte.mische(200, 100, 3f))
    }

    // ── Helligkeit ─────────────────────────────────────────────────────

    @Test fun helligkeitIstEineMultiplikation() {
        // Pillow: blend(schwarz, bild, f) = f · bild
        val p = Tonwerte.pixel(rgb(100, 100, 100), helligkeit = 50, kontrast = 0, saettigung = 0, mittel = 128)
        assertEquals(150, r(p))
    }

    @Test fun helligkeitLaeuftNichtUeber() {
        val p = Tonwerte.pixel(rgb(200, 200, 200), helligkeit = 100, kontrast = 0, saettigung = 0, mittel = 128)
        assertEquals(255, r(p))
    }

    // ── Kontrast ───────────────────────────────────────────────────────

    @Test fun kontrastDrehtSichUmDenBildmittelwert() {
        // DER Unterschied zur CSS-Formel. Mittelwert 100, Kontrast +50:
        //   Pillow: 100 + 1,5·(150-100) = 175
        //   CSS:    128 + 1,5·(150-128) = 161
        val p = Tonwerte.pixel(rgb(150, 150, 150), helligkeit = 0, kontrast = 50, saettigung = 0, mittel = 100)
        assertEquals(175, r(p))
    }

    @Test fun kontrastNullLaesstDasBildInRuhe() {
        // Auch mit einem abwegigen Mittelwert: bei 0 wird gar nicht
        // gerechnet, so wie der Server „if contrast:" prueft.
        val p = Tonwerte.pixel(rgb(150, 60, 200), helligkeit = 0, kontrast = 0, saettigung = 0, mittel = 7)
        assertEquals(150, r(p)); assertEquals(60, g(p)); assertEquals(200, b(p))
    }

    // ── Saettigung ─────────────────────────────────────────────────────

    @Test fun saettigungMinus100ErgibtDieGraustufe() {
        val p = Tonwerte.pixel(rgb(200, 100, 50), helligkeit = 0, kontrast = 0, saettigung = -100, mittel = 128)
        val l = Tonwerte.grau(200, 100, 50)
        assertEquals(l, r(p)); assertEquals(l, g(p)); assertEquals(l, b(p))
    }

    @Test fun saettigungGehtVomPixelgrauAusNichtVomBildmittel() {
        // Pillows Color mischt gegen das GRAUBILD, also je Pixel — nicht
        // gegen eine Flaeche wie der Kontrast.
        val hell = Tonwerte.pixel(rgb(200, 100, 50), 0, 0, -50, mittel = 10)
        val dunkel = Tonwerte.pixel(rgb(200, 100, 50), 0, 0, -50, mittel = 250)
        assertEquals("Der Mittelwert darf die Saettigung nicht beruehren", hell, dunkel)
    }

    // ── Reihenfolge ────────────────────────────────────────────────────

    @Test fun reihenfolgeIstHelligkeitKontrastSaettigung() {
        // Der Server rechnet in dieser Folge (core/photo_edit.py). Wer
        // sie vertauscht, bekommt fuer dieselben Regler ein anderes Bild.
        // Helligkeit +100 auf 60 → 120; Kontrast +100 um 100 → 140.
        val p = Tonwerte.pixel(rgb(60, 60, 60), helligkeit = 100, kontrast = 100, saettigung = 0, mittel = 100)
        assertEquals(140, r(p))
    }

    @Test fun mittelwertRundetWiePillow() {
        // ImageStat.mean → int(mean + 0.5)
        assertEquals(100, Tonwerte.mittelwert(1004, 10))   // 100,4 → 100
        assertEquals(101, Tonwerte.mittelwert(1005, 10))   // 100,5 → 101
        assertEquals(128, Tonwerte.mittelwert(0, 0))
    }

    @Test fun alleReglerAufNullAendernNichts() {
        // Der haeufigste Fall ueberhaupt: der Nutzer dreht nur am
        // Zuschnitt. Kein Pixel darf sich bewegen.
        for (v in listOf(rgb(0, 0, 0), rgb(255, 255, 255), rgb(37, 190, 91))) {
            assertEquals(v, Tonwerte.pixel(v, 0, 0, 0, 128))
        }
    }

    @Test fun alphaBleibtErhalten() {
        val halb = (0x80 shl 24) or (100 shl 16) or (100 shl 8) or 100
        val p = Tonwerte.pixel(halb, 50, 0, 0, 128)
        assertEquals(0x80, (p shr 24) and 0xFF)
    }

    @Test fun keinWertVerlaesstDenBereich() {
        // Ueber den ganzen Reglerbereich: nichts unter 0, nichts ueber 255.
        for (v in 0..255 step 17) {
            for (regler in listOf(-100, -50, 0, 50, 100)) {
                val p = Tonwerte.pixel(rgb(v, v, v), regler, regler, regler, 128)
                assertTrue("$v/$regler ergibt ${r(p)}", r(p) in 0..255)
            }
        }
    }
}

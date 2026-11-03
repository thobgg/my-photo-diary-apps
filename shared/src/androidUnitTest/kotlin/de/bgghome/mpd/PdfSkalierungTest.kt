package de.bgghome.mpd

import de.bgghome.mpd.ui.seitenSkalierung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Bis zum 15.09.2026 zeichnete der PDF-Betrachter jede Seite auf feste
 * 1600 px Breite und zog sie danach auf die Fensterbreite. Ein Kassenbon
 * (80 mm) stand damit so breit da wie ein A4-Blatt — die Seitengröße kam
 * an keiner der beiden Stellen vor. Der Befund kam von der Server-Seite,
 * der denselben Fehler im Web hatte.
 *
 * Maße in PDF-Punkten (1/72 Zoll): A4 = 595 × 842, Bon 80 × 200 mm
 * = 227 × 566. Gerät: S25 Ultra, 1440 × 3120 px bei Dichte 3.75.
 */
class PdfSkalierungTest {

    private val handyB = 1440f
    private val handyH = 3120f
    private val dichte = 3.75f

    @Test
    fun `ein Bon wird nicht so breit wie A4`() {
        val bon = seitenSkalierung(227, 566, handyB, handyH, dichte)
        val a4 = seitenSkalierung(595, 842, handyB, handyH, dichte)
        val bonBreite = 227 * bon
        val a4Breite = 595 * a4
        assertTrue(
            "Bon ($bonBreite px) muss schmaler bleiben als A4 ($a4Breite px)",
            bonBreite < a4Breite * 0.95f,
        )
    }

    @Test
    fun `nichts wird ueber die Originalgroesse hinaus vergroessert`() {
        val original = dichte * 160f / 72f
        // Ein kleines Etikett, 40 x 30 mm = 113 x 85 pt.
        //
        // Bewusst SO klein: Bei Dichte 3.75 ist der 1440-px-Schirm physisch
        // nur rund 61 mm breit. Alles, was breiter gedruckt ist — auch eine
        // Visitenkarte mit 85 mm —, passt ohnehin nicht und wird von der
        // Fensterbreite begrenzt. Die Obergrenze Originalgröße greift am
        // Handy also nur bei wirklich kleinen Belegen. Am Tablet, das
        // physisch größer ist, greift sie öfter.
        val etikett = seitenSkalierung(113, 85, handyB, handyH, dichte)
        assertEquals("Originalgröße ist die Obergrenze", original, etikett, 0.001f)
        assertTrue("und passt damit in die Breite", 113 * etikett <= handyB)
    }

    @Test
    fun `der Bon passt jetzt ganz in die Hoehe`() {
        // Das war der eigentliche Gewinn: vorher wurde er auf 1600 px
        // Breite gezeichnet und war damit 3989 px hoch — man musste
        // scrollen. Jetzt begrenzt die Fensterhöhe.
        val bon = seitenSkalierung(227, 566, handyB, handyH, dichte)
        assertTrue("Höhe passt", 566 * bon <= handyH + 0.5f)
        assertTrue("Breite bleibt darunter", 227 * bon < handyB)
    }

    @Test
    fun `eine grosse Seite passt in das Fenster`() {
        val a4 = seitenSkalierung(595, 842, handyB, handyH, dichte)
        assertTrue("Breite passt", 595 * a4 <= handyB + 0.5f)
        assertTrue("Höhe passt", 842 * a4 <= handyH + 0.5f)
    }

    @Test
    fun `unsinnige Seitenmasse ergeben Faktor eins`() {
        assertEquals(1f, seitenSkalierung(0, 842, handyB, handyH, dichte), 0.0f)
        assertEquals(1f, seitenSkalierung(595, -3, handyB, handyH, dichte), 0.0f)
    }

    @Test
    fun `am Tablet quer passt A4 ganz`() {
        // Tab S8 Ultra quer: 2960 x 1848 px bei Dichte 2.0
        val f = seitenSkalierung(595, 842, 2960f, 1848f, 2.0f)
        assertTrue("Höhe war vorher der Engpass", 842 * f <= 1848f + 0.5f)
        assertTrue("und es bleibt lesbar groß", 595 * f > 1000f)
    }
}

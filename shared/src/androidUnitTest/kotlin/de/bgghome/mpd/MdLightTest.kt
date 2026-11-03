package de.bgghome.mpd

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import de.bgghome.mpd.ui.mdLight
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Markdown-light (PDX v1.5): nur *kursiv* und **fett**. Die Faelle spiegeln
 * die Spec-Regeln — insbesondere: unvollstaendige Marker bleiben woertlich.
 */
class MdLightTest {

    private fun boldRanges(src: String) = mdLight(src).spanStyles
        .filter { it.item.fontWeight == FontWeight.Bold }
        .map { Triple(it.start, it.end, null) }

    private fun italicRanges(src: String) = mdLight(src).spanStyles
        .filter { it.item.fontStyle == FontStyle.Italic }
        .map { Triple(it.start, it.end, null) }

    @Test fun plainTextBleibtPlain() {
        val r = mdLight("Endlich auf der Insel.")
        assertEquals("Endlich auf der Insel.", r.text)
        assertEquals(0, r.spanStyles.size)
    }

    @Test fun fettEntferntSterneUndSetztSpan() {
        val r = mdLight("Hier **fetter** Text")
        assertEquals("Hier fetter Text", r.text)
        assertEquals(listOf(Triple(5, 11, null)), boldRanges("Hier **fetter** Text"))
    }

    @Test fun kursivEntferntSterne() {
        val r = mdLight("Hier *kursiver* Text")
        assertEquals("Hier kursiver Text", r.text)
        assertEquals(listOf(Triple(5, 13, null)), italicRanges("Hier *kursiver* Text"))
    }

    /** Spec §text: KEINE Verschachtelung — exakt das Web-Tokenizer-Verhalten
     *  (album-render.js): innere *…* gewinnen, Rest-Sterne bleiben stehen. */
    @Test fun keineVerschachtelungWieImWeb() {
        val r = mdLight("**ver*schach*telt**")
        assertEquals("*verschachtelt*", r.text)
        assertEquals(0, boldRanges("**ver*schach*telt**").size)
        assertEquals(2, italicRanges("**ver*schach*telt**").size)
    }

    @Test fun kursivNichtUeberZeilenumbruch() {
        val r = mdLight("*eins\nzwei*")
        assertEquals("*eins\nzwei*", r.text)
        assertEquals(0, r.spanStyles.size)
    }

    @Test fun fettDarfZeilenumbruchEnthalten() {
        val r = mdLight("**eins\nzwei**")
        assertEquals("eins\nzwei", r.text)
        assertEquals(1, boldRanges("**eins\nzwei**").size)
    }

    @Test fun einsamerSternBleibtWoertlich() {
        val r = mdLight("ein einsamer * Stern")
        assertEquals("ein einsamer * Stern", r.text)
        assertEquals(0, r.spanStyles.size)
    }

    @Test fun unvollstaendigesFettBleibtWoertlich() {
        val r = mdLight("kaputt **ohne Ende")
        assertEquals("kaputt **ohne Ende", r.text)
        assertEquals(0, r.spanStyles.size)
    }

    @Test fun leereMarkerBleibenWoertlich() {
        assertEquals("****", mdLight("****").text)
        assertEquals("**", mdLight("**").text)
    }
}

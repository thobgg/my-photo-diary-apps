package de.bgghome.mpd

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die Texte kommen seit 22.09.2026 aus Compose-Ressourcen statt aus
 * Android-R. Android hat Escapes (\n, \') und Platzhalter (%1$d) selbst
 * aufgeloest; hier wird festgehalten, dass die Compose-Ressourcen auf dem
 * Desktop dasselbe tun — sonst staenden auf dem Desktop Backslashes im Text.
 */
class ComposeRessourcenTest {
    @Test fun zeilenumbruchUndApostroph() {
        val s = Texte.t(Res.string.search_hint)
        assertTrue(s.contains('\n'), "\\n nicht aufgeloest: $s")
        assertTrue(s.contains("geht's"), "\\' nicht aufgeloest: $s")
        assertTrue(!s.contains('\\'), "Backslash im Text: $s")
    }

    @Test fun platzhalter() {
        val s = Texte.t(Res.string.vm_album_created_x, "Urlaub")
        assertTrue(s.contains("Urlaub"), "Platzhalter nicht ersetzt: $s")
        assertTrue(!s.contains("%1"), "Platzhalter stehen geblieben: $s")
    }

    @Test fun mengenform() {
        val eins = Texte.plural(Res.plurals.backup_rejected_n, 1)
        val viele = Texte.plural(Res.plurals.backup_rejected_n, 3)
        assertTrue(viele.contains("3"), "Zahl fehlt: $viele")
        assertTrue(eins != viele, "Einzahl und Mehrzahl gleich: $eins")
    }

    @Test fun unbekanntBleibtLesbar() {
        // Kein Absturz ohne Ressource — der Schluessel kommt zurueck.
        assertEquals("#vm_saved", "#" + Res.string.vm_saved.key)
    }
}

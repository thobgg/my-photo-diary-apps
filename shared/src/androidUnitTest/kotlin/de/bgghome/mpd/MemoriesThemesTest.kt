package de.bgghome.mpd

import de.bgghome.mpd.state.MEMORIES_THEMES
import de.bgghome.mpd.ui.MEMORIES_THEME_LABELS
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Die Farbwelten stehen an zwei Stellen: die REIHENFOLGE im Zustand
 * (MEMORIES_THEMES, danach schaltet die Palette weiter), die
 * BESCHRIFTUNGEN in der Oberflaeche (MEMORIES_THEME_LABELS, daraus baut
 * sich die Liste in den Einstellungen).
 *
 * Am 13.09.2026 liefen sie auseinander: die neue Pinnwand war nur
 * beschriftet, aber nicht in der Reihenfolge — die Palette schaltete
 * durch die alten sechs und erreichte sie nie. Am Geraet sah es aus, als
 * gaebe es das Theme gar nicht. Dieser Test faengt genau das ab.
 */
class MemoriesThemesTest {

    @Test
    fun `jede Farbwelt ist beschriftet und erreichbar`() {
        assertEquals(
            "Reihenfolge und Beschriftungen muessen dieselben Schluessel tragen",
            MEMORIES_THEMES.toSet(),
            MEMORIES_THEME_LABELS.keys.toSet(),
        )
    }

    @Test
    fun `die Reihenfolge enthaelt keine Dubletten`() {
        assertEquals(MEMORIES_THEMES.size, MEMORIES_THEMES.toSet().size)
    }
}

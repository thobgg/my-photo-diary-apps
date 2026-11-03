package de.bgghome.mpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * Deutsch und Englisch muessen dieselben Schluessel tragen — und je
 * Schluessel dieselben Platzhalter (%1$s, %1$d, …). Fehlt ein Schluessel
 * in values-en, faellt Android still auf Deutsch zurueck; ein fehlender
 * Platzhalter wirft erst beim Nutzer. Beides faellt hier beim Bauen auf.
 *
 * Gedeckt sind alle strings_*.xml — auch die der anderen Bereiche
 * (Album, Module), sobald sie zusammengefuehrt sind. Die Basisdatei
 * strings.xml (app_name) braucht keine Uebersetzung.
 */
class I18nKeysTest {

    private val res: File = listOf("src/commonMain/composeResources", "shared/src/commonMain/composeResources")
        .map { File(it) }.first { it.isDirectory }

    /** name → Menge der Platzhalter, fuer <string> und <plurals>. */
    private fun keys(dir: File): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>()
        val files = dir.listFiles { f -> f.name.startsWith("strings_") && f.name.endsWith(".xml") }
            ?: return out
        val string = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val plurals = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val ph = Regex("""%\d\$[sd]""")
        for (f in files) {
            val src = f.readText()
            for (m in string.findAll(src)) out[m.groupValues[1]] = ph.findAll(m.groupValues[2]).map { it.value }.toSet()
            for (m in plurals.findAll(src)) out["plurals:" + m.groupValues[1]] =
                ph.findAll(m.groupValues[2]).map { it.value }.toSet() + setOf("%d").filter { m.groupValues[2].contains("%d") }
        }
        return out
    }

    @Test fun englischHatJedenDeutschenSchluessel() {
        val de = keys(File(res, "values"))
        val en = keys(File(res, "values-en"))
        assertTrue("keine strings_*.xml gefunden", de.isNotEmpty())
        val fehltEn = de.keys - en.keys
        val fehltDe = en.keys - de.keys
        assertEquals("fehlt in values-en: $fehltEn", emptySet<String>(), fehltEn)
        assertEquals("fehlt in values (de): $fehltDe", emptySet<String>(), fehltDe)
    }

    @Test fun platzhalterStimmenJeSchluesselUeberein() {
        val de = keys(File(res, "values"))
        val en = keys(File(res, "values-en"))
        val abweichend = de.filter { (k, v) -> en[k] != null && en[k] != v }.keys
        assertEquals("Platzhalter weichen ab: $abweichend", emptySet<String>(), abweichend)
    }
}

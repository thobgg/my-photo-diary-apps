package de.bgghome.mpd

import de.bgghome.mpd.api.WhoAmI
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * /api/whoami — der Vertrag steht in docs/API_CHANGES.md (04.09.2026).
 *
 * Warum ueberhaupt ein Test dafuer: ein Vertipper im @SerialName faellt
 * nirgends auf. Das Feld bliebe still `false`, der erzwungene Wechsel wuerde
 * nie erscheinen, und ein Konto mit Startpasswort saehe bei jeder Aktion nur
 * eine 403 — genau der Fehler, den dieser Code beheben soll.
 */
class WhoAmITest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(src: String) = json.decodeFromString(WhoAmI.serializer(), src)

    @Test fun flagWirdGelesen() {
        val who = parse(
            """{"user":"tester","role":"editor","must_change_password":true}"""
        )
        assertTrue(who.mustChangePassword)
        assertEquals("tester", who.user)
    }

    @Test fun flagFalsch() {
        assertFalse(
            parse("""{"user":"t","role":"admin","must_change_password":false}""")
                .mustChangePassword
        )
    }

    /** Aeltere Server (vor 2.0.215) kennen das Feld nicht — nicht stolpern. */
    @Test fun fehlendesFeldGiltAlsFalsch() {
        val who = parse("""{"user":"t","role":"admin","modules":["timeline"]}""")
        assertFalse(who.mustChangePassword)
        assertEquals(listOf("timeline"), who.modules)
    }

    /** Unbekannte Felder kuenftiger Server duerfen nicht zum Absturz fuehren. */
    @Test fun unbekannteFelderStoerenNicht() {
        val who = parse("""{"user":"t","role":"viewer","kommt_spaeter":{"a":1}}""")
        assertEquals("viewer", who.role)
        assertFalse(who.isDemo)
    }
}

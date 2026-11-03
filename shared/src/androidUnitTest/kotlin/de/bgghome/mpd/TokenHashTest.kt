package de.bgghome.mpd

import de.bgghome.mpd.util.tokenHash
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Der lokal gemerkte Klartext-Token findet seinen Listeneintrag nur ueber
 * `sha256(token)` — denselben Wert, den der Server als `token_hash` fuehrt.
 *
 * Weicht die Berechnung ab, passiert nichts Sichtbares: es gaebe einfach nie
 * die Zusatzaktionen an bestehenden Links, und man suchte den Fehler in der
 * Oberflaeche. Deshalb sind die Erwartungswerte hier NICHT von Kotlin
 * erzeugt, sondern mit Pythons hashlib gerechnet — also mit demselben
 * Werkzeug wie im Server (`backend/core/shares.py:_hash`).
 */
class TokenHashTest {

    /** Ein realistischer Token, wie ihn secrets.token_urlsafe(32) liefert. */
    @Test fun entsprichtDemServerHash() {
        assertEquals(
            "589954b156ef3b4b4ba47738fb804003a70d8ccdbf46bdb238f74fa1d6aeef5c",
            tokenHash("u7Fq3ZkPl9xR2sVbN4mTgH8jW1cY6dA0eK5oI3pQzXs"),
        )
    }

    /** Der leere String — Randfall, aber der Wert ist eindeutig bekannt. */
    @Test fun leererStringStimmtAuch() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            tokenHash(""),
        )
    }

    /** UTF-8 muss es sein, nicht die Plattform-Kodierung. */
    @Test fun umlauteWerdenAlsUtf8Gehasht() {
        assertEquals(
            "6e1a426378d741448d6a17faece76693b6f1b9ba7323104f9968f0331d2f02a8",
            tokenHash("Grüße-üöä"),
        )
    }

    /** Kleinbuchstaben, 64 Zeichen — Pythons hexdigest-Form. */
    @Test fun formIstKleingeschriebenUnd64Zeichen() {
        val h = tokenHash("beliebig")
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
        assertEquals(true, h.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

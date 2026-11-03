package de.bgghome.mpd

import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.apiUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/*
 * Der eine Adressbauer der API (08.09.2026). Vorher gab es zwei Regeln:
 * 48 Endpunkte kodierten jedes Segment ueber OkHttp, 17 klebten die
 * Adresse als String zusammen — ein Albumname mit Schraegstrich oder
 * Umlaut haette dort still eine andere Route getroffen. Hier steht, was
 * fuer alle gilt.
 */
class ApiUrlTest {

    @Test fun segmenteWerdenEinzelnKodiert() {
        val url = apiUrl(
            "https://mpd.example.org",
            listOf("album", "shared", "2024 - Gran Canaria/Süden", "Bild #1.jpg"),
        )
        assertEquals(
            "https://mpd.example.org/api/album/shared/2024%20-%20Gran%20Canaria%2FS%C3%BCden/Bild%20%231.jpg",
            url.toString(),
        )
    }

    /*
     * Pfadsegmente nie mit URLEncoder (26.09.2026): der kodiert fuers
     * Formular, Leerzeichen wird "+", und im Pfad nimmt der Server "+"
     * woertlich. Jede Komoot-Tour ("Entlang der Gose Elbe") war 404.
     * Leerzeichen -> %20, "+" bleibt "+" (im Pfad unverfaelscht), "%" -> %25.
     */
    @Test fun tourMitLeerzeichenPlusProzentUmlaut() {
        assertEquals(
            "https://mpd.example.org/api/tours/2021-01-09_Entlang%20der%20Gose%20Elbe-303408490.gpx",
            apiUrl("https://mpd.example.org", listOf("tours", "2021-01-09_Entlang der Gose Elbe-303408490.gpx")).toString(),
        )
        assertEquals(
            "https://mpd.example.org/api/tours/a%20b.gpx",
            apiUrl("https://mpd.example.org", listOf("tours", "a b.gpx")).toString(),
        )
        assertEquals(
            "https://mpd.example.org/api/tours/R%C3%BCgen+Usedom%20100%25.gpx",
            apiUrl("https://mpd.example.org", listOf("tours", "Rügen+Usedom 100%.gpx")).toString(),
        )
    }

    @Test fun dokumentPfadKodiertAlbumUndDatei() {
        assertEquals(
            "/api/document/shared/2024%20-%20Gran%20Canaria/Plan%20A+B%20%C3%BCber%20100%25.pdf",
            MpdApi.documentPath("shared", "2024 - Gran Canaria", "Plan A+B über 100%.pdf"),
        )
    }

    @Test fun queryWirdKodiertUndNullWeggelassen() {
        val url = apiUrl(
            "https://mpd.example.org",
            listOf("search"),
            mapOf("q" to "Grüße & Küsse", "cursor" to null, "limit" to "50"),
        )
        assertEquals(
            "https://mpd.example.org/api/search?q=Gr%C3%BC%C3%9Fe%20%26%20K%C3%BCsse&limit=50",
            url.toString(),
        )
    }

    /** Reverse-Proxy unter einem Unterpfad — das Praefix muss erhalten bleiben. */
    @Test fun unterpfadDerBasisadresseBleibt() {
        val url = apiUrl("https://nas.local:8443/mpd", listOf("whoami"))
        assertEquals("https://nas.local:8443/mpd/api/whoami", url.toString())
    }

    @Test fun ungueltigeAdresseWirftIOException() {
        assertThrows(IOException::class.java) { apiUrl("nicht-eine-adresse", listOf("whoami")) }
    }
}

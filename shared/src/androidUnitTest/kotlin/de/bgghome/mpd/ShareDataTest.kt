package de.bgghome.mpd

import de.bgghome.mpd.api.ContribCreated
import de.bgghome.mpd.api.ContribListResponse
import de.bgghome.mpd.api.ShareListResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Die Share-/Contrib-Antworten tragen `time.time()`-Werte — REAL mit
 * Nachkommastellen. Ein als Long deklariertes Feld bricht beim ersten
 * echten Datensatz mit "Unexpected symbol in numeric literal", und zwar
 * nicht beim Bauen, sondern beim Nutzer. ContribCreated.expires_at war bis
 * 08.09.2026 genau so deklariert, direkt unter dem Kommentar, der davor
 * warnt. Dieser Test fuettert jede Antwort mit einem Kommawert.
 */
class ShareDataTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun contribCreateVertraegtKommazeit() {
        val c = json.decodeFromString(
            ContribCreated.serializer(),
            """{"token":"abc","url":"/contrib/abc","space":"shared",
                "folder_name":"2026 - Feier","expires_at":1757954400.123456,
                "max_files":500}""",
        )
        assertEquals(1757954400.123456, c.expiresAt!!, 1e-6)
        assertEquals("abc", c.token)
    }

    @Test fun contribListVertraegtKommazeit() {
        val list = json.decodeFromString(
            ContribListResponse.serializer(),
            """{"contribs":[{"token_hash":"h","space":"personal","folder_name":"x",
                "created_at":1757000000.5,"expires_at":1757954400.25,
                "max_files":10,"uploaded":3}]}""",
        )
        assertEquals(1757954400.25, list.contribs.single().expiresAt!!, 1e-6)
    }

    @Test fun shareListVertraegtKommazeitUndUnbefristet() {
        val list = json.decodeFromString(
            ShareListResponse.serializer(),
            """{"shares":[
                {"token_hash":"a","created_at":1757000000.75,"expires_at":null},
                {"token_hash":"b","created_at":1757000001.0,"last_seen_at":1757000002.5,
                 "expires_at":1757954400.0,"file":"2026-06-21_11-24-30-t.jpg"}
            ]}""",
        )
        assertNull(list.shares[0].expiresAt)
        assertEquals("2026-06-21_11-24-30-t.jpg", list.shares[1].file)
    }
}

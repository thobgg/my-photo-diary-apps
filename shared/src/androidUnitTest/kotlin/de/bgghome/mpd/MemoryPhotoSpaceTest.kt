package de.bgghome.mpd

import de.bgghome.mpd.api.MemoryPhoto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * Aus welchem Bestand kommt ein Erinnerungsfoto (26.09.2026)? Bei der
 * Quelle „beides" mischt ein Jahresblock Familien- und eigene Fotos; die
 * App muss das Album im richtigen Space oeffnen. Bis dahin stand dort fest
 * "shared" — mit „Meine Alben" fuehrte jeder Tipp ins Leere.
 */
class MemoryPhotoSpaceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun photo(s: String) = json.decodeFromString(MemoryPhoto.serializer(), s)

    @Test fun neuerServerSagtEsDirekt() {
        assertEquals("personal", photo("""{"album":"a","filename":"f.jpg","thumbnail_url":"/x?size=m","space":"personal"}""").space)
        assertEquals("shared", photo("""{"album":"a","filename":"f.jpg","thumbnail_url":"/x?size=m&space=personal","space":"shared"}""").space)
    }

    @Test fun alterServerAusDerThumbnailAdresse() {
        assertEquals("personal", photo("""{"album":"a","filename":"f.jpg","thumbnail_url":"/api/memories-thumb/a/f.jpg?size=m&v=1&space=personal"}""").space)
        assertEquals("shared", photo("""{"album":"a","filename":"f.jpg","thumbnail_url":"/api/memories-thumb/a/f.jpg?size=m&v=1"}""").space)
    }
}

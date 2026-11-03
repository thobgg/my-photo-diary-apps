package de.bgghome.mpd

import de.bgghome.mpd.api.ToursResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * GET /api/tours seit Server 3.0.488 — Beispiel woertlich aus
 * docs/API_CHANGES.md (29.09.2026). Der Server liefert dist und hr_avg als
 * Text ("23.4", "131"); die App liest sie als Zahl. Bricht das, steht die
 * ganze Tourenliste leer da, nicht nur ein Feld.
 */
class TourListTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(src: String) = json.decodeFromString(ToursResponse.serializer(), src).tours

    @Test fun neueFelderWerdenGelesen() {
        val t = parse(
            """{"tours":[{"filename": "2024-05-01_cycle.gpx", "folder": "2024", "kind": "durchlauf",
             "date": "2024-05-01", "label": "🚴 Radtour", "title": null,
             "km": 23.41, "dist": "23.4", "duration_s": 4980, "duration": "1:23 h",
             "points": 1830, "start": "2024-05-01T07:02:11+00:00",
             "hr_avg": "131", "hr_max": "160",
             "used_in": [{"album": "2024 - Gardasee", "title": "Gardasee"}],
             "stub": null}]}"""
        ).single()
        assertEquals("2024/2024-05-01_cycle.gpx", t.key)
        assertEquals(23.4, t.dist!!, 0.001)
        assertEquals(131, t.hrAvg)
        assertEquals(1830, t.points)
        assertEquals("Gardasee", t.usedIn.single().label)
        assertNull(t.stub)
    }

    @Test fun offenerStummel() {
        val t = parse(
            """{"tours":[{"filename":"x.gpx","folder":"2023","kind":"durchlauf",
             "used_in":[],"stub":"strecke"}]}"""
        ).single()
        assertTrue(t.usedIn.isEmpty())
        assertEquals("strecke", t.stub)
    }

    @Test fun alterServerOhneOrdner() {
        val t = parse("""{"tours":[{"filename":"a b.gpx","dist":12.5}]}""").single()
        assertEquals("", t.folder)
        assertTrue(t.usedIn.isEmpty())
    }
}

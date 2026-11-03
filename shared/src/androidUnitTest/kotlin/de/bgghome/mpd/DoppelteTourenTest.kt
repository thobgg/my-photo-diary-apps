package de.bgghome.mpd

import de.bgghome.mpd.api.TourListEntry
import de.bgghome.mpd.ui.doppelteTouren
import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * „Doppelt?" im Tour-Dialog (11.10.2026): dieselbe Unternehmung von zwei
 * Geraeten (Huawei-Uhr, Komoot). Merkmal ist die ueberlappende Zeit, nicht
 * der Name — die beiden starten selten in derselben Sekunde.
 */
class DoppelteTourenTest {
    private fun t(name: String, start: String?, sek: Long?) =
        TourListEntry(filename = name, folder = "2024", start = start, durationS = sek)

    @Test fun ueberlappendeAufzeichnungen() {
        val d = doppelteTouren(listOf(
            t("huawei.gpx", "2024-05-01T07:02:11+02:00", 4980),
            t("komoot.gpx", "2024-05-01T07:05:40+02:00", 4700),
            t("abend.gpx", "2024-05-01T18:00:00+02:00", 1800),
        ))
        assertEquals(setOf("2024/huawei.gpx", "2024/komoot.gpx"), d)
    }

    @Test fun nacheinanderIstNichtDoppelt() {
        val d = doppelteTouren(listOf(
            t("hin.gpx", "2024-05-01T07:00:00Z", 3600),
            t("rueck.gpx", "2024-05-01T08:00:00Z", 3600),
        ))
        assertEquals(emptySet<String>(), d)
    }

    @Test fun ohneZeitKeinUrteil() {
        assertEquals(emptySet<String>(), doppelteTouren(listOf(
            t("a.gpx", null, 100), t("b.gpx", "2024-05-01T07:00:00Z", null),
        )))
    }
}

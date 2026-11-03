package de.bgghome.mpd

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.api.moduleKeyForPath
import de.bgghome.mpd.ui.moduleFree
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Core und Plus (19.09.2026): Ein reiner „My Photo Diary Core" meldet in
 * /api/whoami ehrlich `"modules": []`. Die App las das bis dahin als
 * „alter Server, alles frei" und zeigte Menuepunkte ins Leere. Diese Tests
 * halten die drei Faelle auseinander: Feld fehlt, leer, gefuellt.
 */
class ModulFreiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun who(src: String) = json.decodeFromString(WhoAmI.serializer(), src)

    @Test fun fehlendesFeldIstNull() {
        assertNull(who("""{"user":"t","role":"admin"}""").modules)
    }

    @Test fun leereListeBleibtLeer() {
        assertEquals(emptyList<String>(), who("""{"user":"t","role":"admin","modules":[]}""").modules)
    }

    @Test fun altserverAllesFrei() {
        assertTrue(moduleFree("tours", null, emptyList()))
    }

    @Test fun coreNichtsFrei() {
        for (k in listOf("tours", "trails", "search", "stats")) {
            assertFalse(k, moduleFree(k, emptyList(), emptyList()))
        }
    }

    @Test fun zeitstrahlImmerFrei() {
        assertTrue(moduleFree("timeline", emptyList(), listOf("timeline")))
    }

    /** Kern seit Server 20.09.2026 — fehlt in `modules`, darf kein Schloss haben. */
    @Test fun memoriesImmerFrei() {
        assertTrue(moduleFree("memories", listOf("tours", "trails", "search", "stats"), emptyList()))
        assertTrue(moduleFree("memories", emptyList(), emptyList()))
    }

    @Test fun genauDieListe() {
        assertTrue(moduleFree("stats", listOf("stats"), emptyList()))
        assertFalse(moduleFree("tours", listOf("stats"), emptyList()))
    }

    @Test fun fehlenderEndpunktSchlaegtWhoami() {
        assertFalse(moduleFree("stats", listOf("stats"), listOf("stats")))
        assertFalse(moduleFree("stats", null, listOf("stats")))
    }

    @Test fun routeFehltNurBeiNacktemNotFound() {
        assertTrue(MpdHttpException(404, "Not Found").routeMissing)
        assertFalse(MpdHttpException(404, "Datei nicht gefunden: a.gpx").routeMissing)
        assertFalse(MpdHttpException(403, "Not Found").routeMissing)
    }

    @Test fun pfadZuModul() {
        assertNull(moduleKeyForPath("/api/memories"))
        assertNull(moduleKeyForPath("/api/memories-thumb/x/y.jpg"))
        assertEquals("trails", moduleKeyForPath("/mpd/api/trails/day"))
        assertEquals("tours", moduleKeyForPath("/api/tours/a%20b.gpx"))
        assertNull(moduleKeyForPath("/api/gpx/shared/Album/a.gpx"))
        assertNull(moduleKeyForPath("/api/albums"))
        assertNull(moduleKeyForPath("/"))
    }
}

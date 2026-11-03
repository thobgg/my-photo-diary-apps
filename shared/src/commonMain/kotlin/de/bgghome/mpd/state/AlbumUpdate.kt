package de.bgghome.mpd.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Der Body fuer POST …/update und die Konflikt-Basis — EINE Stelle fuer
 * alle Schreibwege (Autosave, Cover-Patch von der Kachel, Favoriten).
 * Aus dem ViewModel herausgehoben (08.09.2026, Struktur-Audit Stufe 2,
 * Block 3), damit FavoritesState dieselbe Fassung nutzt, ohne ans
 * ViewModel gekoppelt zu sein.
 */

/**
 * Berechnete Felder der GET-Antwort, die nie zum Server zurueckgehen
 * (API_CHANGES.md „Albumgestaltung"). Der Server wuerde sie per
 * Whitelist filtern; wir verlassen uns nicht darauf.
 */
private val computedFields =
    setOf("thumbnails", "original", "document_url", "error", "source_missing")

/** Ganzes album-Objekt wie im Web, Elemente ohne die berechneten Felder. */
internal fun updateBody(meta: JsonObject, elements: List<JsonObject>): JsonObject =
    buildJsonObject {
        put("meta", meta)
        put("elements", JsonArray(elements.map { e ->
            JsonObject(e.filterKeys { it !in computedFields })
        }))
    }

/** meta.modified der geladenen Fassung — die Basis fuer X-MPD-Base-Modified. */
internal fun modifiedOf(raw: JsonObject): String? =
    raw["meta"]?.jsonObject?.get("modified")?.jsonPrimitive?.contentOrNull

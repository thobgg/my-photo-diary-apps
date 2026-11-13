package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl

// Modul Trails (/api/trails/…).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

// ==== Modul „trails": Standorthistorie (docs/API_CHANGES.md, 04.09.) ====
// Alle Routen sind Modul-gated (403 ohne „trails") und im Demo gesperrt.
// Zeitraeume immer entweder date+tz (Kalendertag in Lokalzeit) oder
// from/to in Unix-Sekunden.

private fun MpdApi.trailsUrl(
    baseUrl: String, vararg tail: String, query: Map<String, String?> = emptyMap(),
): HttpUrl = api(baseUrl, "trails", *tail, query = query)

/**
 * GET /api/photos/geo — Ortsangaben der Fotos eines Zeitraums, gebuendelt.
 * [from]/[to] sind Aufnahmetage YYYY-MM-DD (Ortszeit, einschliesslich).
 * Limit auf dem Servermaximum; `truncated` bleibt in der Antwort sichtbar.
 */
suspend fun MpdApi.photosGeo(
    baseUrl: String, space: String, from: String, to: String,
): PhotoGeoResponse = withContext(Dispatchers.IO) {
    val url = api(
        baseUrl, "photos", "geo",
        query = mapOf("space" to space, "from" to from, "to" to to, "limit" to "50000"),
    )
    decode(PhotoGeoResponse.serializer(), get(url))
}

/** GET /api/trails/status — Umfang des eigenen Speichers. */
suspend fun MpdApi.trailsStatus(baseUrl: String): TrailStatus = withContext(Dispatchers.IO) {
    decode(TrailStatus.serializer(), get(trailsUrl(baseUrl, "status")))
}

/** GET /api/trails/day — Punkte (ausgeduennt), Besuche und Wege eines Tages. */
suspend fun MpdApi.trailsDay(
    baseUrl: String, date: String, tzMinutes: Int, thin: Int = 2000,
): TrailDay = withContext(Dispatchers.IO) {
    val url = trailsUrl(
        baseUrl, "day",
        query = mapOf("date" to date, "tz" to tzMinutes.toString(), "thin" to thin.toString()),
    )
    decode(TrailDay.serializer(), get(url))
}

/** GET /api/trails/days — Tag → Punktzahl, Futter fuer den Kalender. */
suspend fun MpdApi.trailsDays(
    baseUrl: String, year: Int, month: Int?, tzMinutes: Int,
): Map<String, Int> = withContext(Dispatchers.IO) {
    val url = trailsUrl(
        baseUrl, "days",
        query = mapOf(
            "year" to year.toString(),
            "month" to month?.toString(),
            "tz" to tzMinutes.toString(),
        ),
    )
    decode(TrailDays.serializer(), get(url)).days
}

/** GET /api/trails/places — Orte nach Besuchshaeufigkeit. */
suspend fun MpdApi.trailsPlaces(baseUrl: String, limit: Int = 100): List<TrailPlace> =
    withContext(Dispatchers.IO) {
        val url = trailsUrl(baseUrl, "places", query = mapOf("limit" to limit.toString()))
        decode(TrailPlacesResponse.serializer(), get(url)).places
    }

/**
 * PUT /api/trails/places/name — eigener Ortsname („Zuhause") fuer den
 * Ort (source, place_ref); gilt fuer alle Besuche dort, auch kuenftige,
 * der OSM-Nachtlauf ueberschreibt ihn nie. `name` leer/null nimmt ihn
 * zurueck. 404 = kein Besuch mit dieser Kennung. Vertrag 08.09.2026.
 */
suspend fun MpdApi.setTrailsPlaceName(
    baseUrl: String, source: String, placeRef: String, name: String?,
): TrailPlaceName = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("source", JsonPrimitive(source))
        put("place_ref", JsonPrimitive(placeRef))
        put("name", name?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    decode(TrailPlaceName.serializer(), put(trailsUrl(baseUrl, "places", "name"), jsonBody(body)))
}

/** GET /api/trails/heatmap — Rasterzaehlung fuer die Weltkarte.
 *  `year = null` heisst „alles" und braucht dann from/to. */
suspend fun MpdApi.trailsHeatmap(
    baseUrl: String, year: Int?, from: Long? = null, to: Long? = null, cell: Double = 0.01,
): TrailHeatmap = withContext(Dispatchers.IO) {
    val range = if (year != null) {
        mapOf("year" to year.toString())
    } else {
        mapOf(
            "from" to (from ?: 0L).toString(),
            "to" to (to ?: System.currentTimeMillis() / 1000).toString(),
        )
    }
    val url = trailsUrl(baseUrl, "heatmap", query = range + ("cell" to cell.toString()))
    // Bis 200.000 Zellen — der Standard-Lesetimeout ist dafuer zu knapp.
    decode(TrailHeatmap.serializer(), get(url, patientClient))
}

/** POST /api/trails/derive — Besuche und Wege eines Tages jetzt ableiten
 *  (sonst macht das der Nachtjob). Ohne `date` laeuft der Nachtjob. */
suspend fun MpdApi.trailsDerive(
    baseUrl: String, date: String? = null, tzMinutes: Int = 0,
): TrailDeriveResponse = withContext(Dispatchers.IO) {
    val url = trailsUrl(
        baseUrl, "derive",
        query = if (date != null) mapOf("date" to date, "tz" to tzMinutes.toString()) else emptyMap(),
    )
    // Ableiten laeuft ueber Rohpunkte — bei einem vollen Tag dauert das
    // (vorher 120 s; longRunClient wartet laenger, das schadet nicht).
    decode(TrailDeriveResponse.serializer(), post(url, http = longRunClient))
}

/** GET /api/trails/devices — Aufzeichnungsgeraete (OwnTracks). */
suspend fun MpdApi.trailsDevices(baseUrl: String): List<TrailDevice> = withContext(Dispatchers.IO) {
    decode(TrailDevicesResponse.serializer(), get(trailsUrl(baseUrl, "devices"))).devices
}

/** POST /api/trails/devices — Geraet anlegen bzw. Token erneuern.
 *  Der Klartext-Token steht NUR in dieser Antwort. */
suspend fun MpdApi.trailsCreateDevice(
    baseUrl: String, deviceId: String, name: String,
): TrailDeviceCreated = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("device_id", JsonPrimitive(deviceId))
        put("name", JsonPrimitive(name))
    })
    decode(TrailDeviceCreated.serializer(), post(trailsUrl(baseUrl, "devices"), body))
}

/** DELETE /api/trails/devices/{id} — Token weg, die Punkte bleiben. */
suspend fun MpdApi.trailsDeleteDevice(baseUrl: String, deviceId: String): Unit =
    withContext(Dispatchers.IO) {
        delete(trailsUrl(baseUrl, "devices", deviceId))
        Unit
    }

/** GET /api/trails/sources — welche Quelle deckt welchen Zeitraum ab. */
suspend fun MpdApi.trailsSources(baseUrl: String): List<TrailSource> = withContext(Dispatchers.IO) {
    decode(TrailSourcesResponse.serializer(), get(trailsUrl(baseUrl, "sources"))).sources
}

/**
 * POST /api/trails/sources/delete — endgueltig. Mit `from`/`to` fallen nur
 * die abgeleiteten Besuche und Wege im Fenster (Umstiegsfall), ohne
 * Zeitfenster faellt die Quelle komplett samt Rohpunkten, als Praefix
 * ("google" trifft auch "google:h1").
 */
suspend fun MpdApi.trailsDeleteSource(
    baseUrl: String, source: String, from: Long? = null, to: Long? = null,
): Unit = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("source", JsonPrimitive(source))
        // Der Server verlangt die Wiederholung als Absicherung.
        put("confirm", JsonPrimitive(source))
        if (from != null && to != null) {
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
        }
    })
    // Vorher 180 s eigener Client; longRunClient wartet laenger.
    post(trailsUrl(baseUrl, "sources", "delete"), body, longRunClient)
    Unit
}

/** GET /api/trails/archive — das naechtliche Archiv auf der Platte. */
suspend fun MpdApi.trailsArchive(baseUrl: String): TrailArchiveResponse = withContext(Dispatchers.IO) {
    decode(TrailArchiveResponse.serializer(), get(trailsUrl(baseUrl, "archive")))
}

/** POST /api/trails/export?all=1 — Archiv vollstaendig neu bauen.
 *  Laeuft bei grossen Bestaenden Minuten. */
suspend fun MpdApi.trailsRebuildArchive(baseUrl: String, all: Boolean = true): Int =
    withContext(Dispatchers.IO) {
        val url = trailsUrl(baseUrl, "export", query = mapOf("all" to if (all) "1" else "0"))
        json.parseToJsonElement(post(url, http = longRunClient)).jsonObject["count"]
            ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    }

/** Adresse einer Archivdatei — zum Herunterladen ueber downloadStream(). */
fun MpdApi.trailsArchiveFileUrl(baseUrl: String, name: String): String =
    trailsUrl(baseUrl, "archive", name).toString()

/** Adresse des Gesamtarchivs als ZIP (Zeitraum optional, YYYY-MM). */
fun MpdApi.trailsArchiveZipUrl(baseUrl: String, from: String? = null, to: String? = null): String =
    trailsUrl(baseUrl, "archive.zip", query = mapOf("from" to from, "to" to to)).toString()

/** Adresse eines Exports (GeoJSON/GPX) fuer einen Kalendertag. */
fun MpdApi.trailsExportDayUrl(
    baseUrl: String, date: String, tzMinutes: Int, format: String,
): String = trailsUrl(
    baseUrl, "export",
    query = mapOf("format" to format, "date" to date, "tz" to tzMinutes.toString()),
).toString()

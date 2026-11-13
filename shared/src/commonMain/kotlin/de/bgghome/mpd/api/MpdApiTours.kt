package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Modul Tours (/api/tours, /api/gpx).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

/** GET /api/tours — globale Tourenliste (Modul „tours"; Demo → 403). */
suspend fun MpdApi.tours(baseUrl: String): List<TourListEntry> = withContext(Dispatchers.IO) {
    decode(ToursResponse.serializer(), get(api(baseUrl, "tours"))).tours
}

/** GET /api/gpx-files/{space}/{album} — GPX-Dateien im Albumordner. */
suspend fun MpdApi.gpxFiles(baseUrl: String, space: String, album: String): List<String> =
    withContext(Dispatchers.IO) {
        decode(GpxFilesResponse.serializer(), get(api(baseUrl, "gpx-files", space, album))).files
    }

/** Adresse der GPX: mit Ordner (seit 3.0.488 eindeutig), sonst der alte
 *  Weg ueber den Namen allein. */
fun MpdApi.tourFileUrl(baseUrl: String, tour: TourListEntry): String =
    (if (tour.folder.isNotEmpty()) api(baseUrl, "tours", "file", tour.folder, tour.filename)
    else api(baseUrl, "tours", tour.filename)).toString()

/**
 * POST /api/tours/place — Tour in ein Album legen. Offen → verschoben;
 * liegt sie schon in einem Album → bleibt dort, [TourPlaced.sourceAlbum]
 * gesetzt. [append] = Server haengt das Tour-Element selbst ans Album
 * (so macht es die Tours-Seite des Webs); ohne baut der Aufrufer es.
 */
suspend fun MpdApi.placeTour(
    baseUrl: String, tour: TourListEntry, album: String, space: String, append: Boolean,
): TourPlaced = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("folder", JsonPrimitive(tour.folder))
        put("filename", JsonPrimitive(tour.filename))
        put("album", JsonPrimitive(album))
        put("space", JsonPrimitive(space))
        put("append", JsonPrimitive(append))
    })
    val o = json.parseToJsonElement(post(api(baseUrl, "tours", "place"), body)).jsonObject
    TourPlaced(
        file = o["file"]?.jsonPrimitive?.contentOrNull ?: tour.filename,
        sourceAlbum = o["source_album"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
        action = o["action"]?.jsonPrimitive?.contentOrNull,
    )
}

/** DELETE /api/tours/file/{folder}/{filename} — in den Papierkorb, nur
 *  Admin. 409 (Text nennt die Alben), solange ein Album sie benutzt.
 *  Liefert, ob der Papierkorb gegriffen hat. */
suspend fun MpdApi.deleteTour(baseUrl: String, tour: TourListEntry): Boolean =
    deleteTourFile(baseUrl, tour.folder, tour.filename)

/** Dasselbe fuer eine GPX im Albumordner (Tour-Kachel, 13.11.2026):
 *  [folder] ist der Albumordner im Familienbestand. */
suspend fun MpdApi.deleteTourFile(baseUrl: String, folder: String, filename: String): Boolean =
    withContext(Dispatchers.IO) {
        val text = delete(api(baseUrl, "tours", "file", folder, filename))
        runCatching {
            json.parseToJsonElement(text).jsonObject["recycled"]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
    }

/** GET /api/gpx/{space}/{album}/{file} — die GPX eines Tour-Elements
 *  (Kern, kein Modul-Gate; [album] ist bei source_album der Quellordner). */
fun MpdApi.gpxUrl(baseUrl: String, space: String, album: String, file: String): String =
    api(baseUrl, "gpx", space, album, file).toString()

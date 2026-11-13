package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl

// Fotos: EXIF, Bearbeiten, Umbenennen, Zuruecksetzen (/api/photo/…).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

/** `/api/photo/{space}/{album}/{file}/<tail…>` */
private fun MpdApi.photoUrl(
    baseUrl: String, space: String, album: String, file: String, vararg tail: String,
): HttpUrl = api(baseUrl, "photo", space, album, file, *tail)

/** GET /api/photo/…/exif — kuratierte EXIF-Felder des Servers. */
suspend fun MpdApi.exif(baseUrl: String, space: String, album: String, file: String): ExifData =
    withContext(Dispatchers.IO) {
        decode(ExifData.serializer(), get(photoUrl(baseUrl, space, album, file, "exif")))
    }

/** POST /api/photo/…/edit — destruktiv mit rollendem .bak-Backup.
 *  Liefert den Backup-Namen (Statuszeile wie im Web-Editor). */
suspend fun MpdApi.editPhoto(
    baseUrl: String, space: String, album: String, file: String,
    params: PhotoEditParams,
): String? = withContext(Dispatchers.IO) {
    val body = jsonBody(json.encodeToString(PhotoEditParams.serializer(), params))
    val text = post(photoUrl(baseUrl, space, album, file, "edit"), body)
    runCatching {
        json.parseToJsonElement(text).jsonObject["backup"]?.jsonPrimitive?.content
    }.getOrNull()
}

/** POST /api/photo/…/rename/preview — Zielname + Kollisionsflag (dry-run). */
suspend fun MpdApi.renamePreview(
    baseUrl: String, space: String, album: String, file: String, newStem: String,
): Pair<String, Boolean> = withContext(Dispatchers.IO) {
    val body = jsonBody("""{"new_stem":${JsonPrimitive(newStem)}}""")
    val obj = json.parseToJsonElement(
        post(photoUrl(baseUrl, space, album, file, "rename", "preview"), body),
    ).jsonObject
    (obj["final_name"]?.jsonPrimitive?.content ?: file) to
        (obj["collision"]?.jsonPrimitive?.content == "true")
}

/** POST /api/photo/…/rename — ISO-Umbenennen; liefert den neuen Namen. */
suspend fun MpdApi.renamePhoto(
    baseUrl: String, space: String, album: String, file: String, newStem: String,
): String? = withContext(Dispatchers.IO) {
    val body = jsonBody("""{"new_stem":${JsonPrimitive(newStem)}}""")
    val obj = json.parseToJsonElement(
        post(photoUrl(baseUrl, space, album, file, "rename"), body),
    ).jsonObject
    obj["renamed"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "false" && it.isNotEmpty() }
}

/**
 * POST /api/photo/…/exif — Datum/GPS (und optional neuer Name) schreiben
 * (save_metadata: .bak + EXIF-Rewrite). Liefert den neuen Dateinamen,
 * wenn dabei umbenannt wurde, sonst null.
 */
suspend fun MpdApi.saveExif(
    baseUrl: String, space: String, album: String, file: String,
    datetime: String?, lat: Double?, lon: Double?, alt: Double?, newStem: String?,
): String? = withContext(Dispatchers.IO) {
    val payload = buildJsonObject {
        datetime?.let { put("datetime", JsonPrimitive(it)) }
        if (lat != null && lon != null) {
            put("gps", buildJsonObject {
                put("lat", JsonPrimitive(lat))
                put("lon", JsonPrimitive(lon))
                alt?.let { put("alt", JsonPrimitive(it)) }
            })
        }
        newStem?.let { put("new_stem", JsonPrimitive(it)) }
    }
    val obj = json.parseToJsonElement(
        post(photoUrl(baseUrl, space, album, file, "exif"), jsonBody(payload)),
    ).jsonObject
    obj["renamed"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "false" && it.isNotEmpty() }
}

/** DELETE /api/photo/…/edit — Original aus dem .bak wiederherstellen. */
suspend fun MpdApi.resetPhoto(baseUrl: String, space: String, album: String, file: String): Unit =
    withContext(Dispatchers.IO) {
        delete(photoUrl(baseUrl, space, album, file, "edit"))
        Unit
    }

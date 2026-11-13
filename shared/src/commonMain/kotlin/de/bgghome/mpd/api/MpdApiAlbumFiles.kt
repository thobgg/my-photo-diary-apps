package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException
import de.bgghome.mpd.state.Texte

// Dateien im Albumordner: Cover, Papierkorb, Loeschen, Hochladen, Einsortieren, Anlegen.
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

/** POST …/cover-from-source — referenziertes Foto als cover.<ext> in den
 *  eigenen Ordner kopieren; Antwort traegt den neuen Dateinamen. */
suspend fun MpdApi.coverFromSource(
    baseUrl: String, space: String, album: String,
    sourceAlbum: String, file: String,
): String = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("source_album", JsonPrimitive(sourceAlbum))
        put("file", JsonPrimitive(file))
    })
    val response = json.parseToJsonElement(
        post(api(baseUrl, "album", space, album, "cover-from-source"), body),
    ).jsonObject
    response["thumbnail"]?.jsonPrimitive?.contentOrNull
        ?: throw IOException(Texte.t(Res.string.api_err_no_thumbnail))
}

/** GET /api/recycle-status/{space} — hat der Space einen #recycle-Ordner?
 *  null = nicht ermittelbar (Web setzt dann den neutralen Warnhinweis). */
suspend fun MpdApi.recycleStatus(baseUrl: String, space: String): Boolean? =
    withContext(Dispatchers.IO) {
        runCatching {
            json.parseToJsonElement(get(api(baseUrl, "recycle-status", space)))
                .jsonObject["recycled"]?.jsonPrimitive?.content == "true"
        }.getOrNull()
    }

/**
 * DELETE /api/album/{space}/{album}/file — Datei, Thumbnails und
 * .bak-Versionen physisch loeschen (Web: deletePhotoFile).
 * Liefert was_thumbnail: die Datei war das Album-Cover.
 *
 * @param albumContext true = der Aufruf kommt aus einer Album-Ansicht,
 *   wo Loeschen der vorgesehene Weg ist (Album-Editor, Kachel-Menue,
 *   Lightbox AUS dem Album). Ohne das Feld schuetzt der Server jede
 *   Datei, die in einem Ordner mit album.json liegt — 403
 *   `album.delete_in_album_folder`. Zeitstrahl und Timeline-Lightbox
 *   setzen es deshalb NIE.
 *
 *   Heisst absichtlich nicht `from_album`: das gibt es in dieser API
 *   schon bei importFile, dort als Ordnername. Zwei Felder gleichen
 *   Namens mit verschiedener Bedeutung waeren eine Falle fuer den, der
 *   es in einem halben Jahr liest.
 *
 * Unabhaengig davon lehnt der Server mit 409
 * `album.delete_used_elsewhere` ab, wenn ein ANDERES Album per
 * source_album auf die Datei zeigt — das ist nicht ueberstimmbar.
 */
/**
 * @property wasThumbnail Die Datei war das Deckblatt des Albums.
 * @property removedFromJson Der Server hat den Eintrag SELBST aus der
 *   album.json genommen und `meta.modified` neu gestempelt. Dann darf
 *   die App nicht noch einmal speichern — sie liefe mit dem Stempel von
 *   vor der Loeschung in den Konfliktschutz (Befund 12.09.2026).
 */
data class DeleteResult(
    val wasThumbnail: Boolean,
    val removedFromJson: Boolean,
)

suspend fun MpdApi.deleteFile(
    baseUrl: String,
    space: String,
    album: String,
    filename: String,
    albumContext: Boolean = false,
): DeleteResult =
    withContext(Dispatchers.IO) {
        val payload = """{"filename":${JsonPrimitive(filename)},""" +
            """"album_context":$albumContext}"""
        val text = delete(api(baseUrl, "album", space, album, "file"), jsonBody(payload))
        fun flag(name: String) = runCatching {
            json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.content == "true"
        }.getOrDefault(false)
        DeleteResult(wasThumbnail = flag("was_thumbnail"), removedFromJson = flag("removed_from_json"))
    }

/**
 * PUT /api/album/{space}/{album}/file/{filename} — Kamera-Upload,
 * roher Body (kein Multipart). 409 = Datei existiert (Aufrufer haengt
 * z.B. "-2" an), 413 = ueber 2 GB, 415 = falsche Endung.
 */
suspend fun MpdApi.uploadFile(
    baseUrl: String,
    space: String,
    album: String,
    filename: String,
    file: java.io.File,
): Unit = withContext(Dispatchers.IO) {
    val mime = when (filename.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "webp" -> "image/webp"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
    put(api(baseUrl, "album", space, album, "file", filename), file.asRequestBody(mime.toMediaType()))
    Unit
}

/**
 * POST /api/album/{space}/{album}/import-file — Einsortieren: Datei
 * zieht physisch aus dem Durchlauf ins Album um (auch quer ueber
 * Spaces), Element wird atomar angehaengt. 409 = im Ziel vorhanden
 * oder noch im Quell-Album referenziert.
 */
suspend fun MpdApi.importFile(
    baseUrl: String,
    targetSpace: String,
    targetAlbum: String,
    fromSpace: String,
    fromAlbum: String,
    filename: String,
): ImportResult = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("from_space", JsonPrimitive(fromSpace))
        put("from_album", JsonPrimitive(fromAlbum))
        put("filename", JsonPrimitive(filename))
    }
    // Seit 4ee1b4e (Server, 08.09.2026, API_CHANGES „Einsortieren:
    // Namenskollision ist kein 409 mehr"): liegt im Ziel schon eine
    // Datei gleichen Namens, antwortet der Server 200 — bytegleich mit
    // duplicate=true (Quelle in den Papierkorb), sonst renamed_from +
    // neuem filename. Der 409 „Quelle noch referenziert" bleibt.
    val raw = runCatching {
        json.parseToJsonElement(
            post(api(baseUrl, "album", targetSpace, targetAlbum, "import-file"), jsonBody(body)),
        ).jsonObject
    }.getOrNull()
    ImportResult(
        duplicate = raw?.get("duplicate")?.jsonPrimitive?.booleanOrNull == true,
        renamedFrom = raw?.get("renamed_from")?.jsonPrimitive?.contentOrNull,
        filename = raw?.get("filename")?.jsonPrimitive?.contentOrNull,
        adopted = raw?.get("adopted")?.jsonPrimitive?.booleanOrNull == true,
    )
}

/** GET /api/albums/without-json — der Ordner-Scan kann bei vielen
 *  Ordnern dauern, darum geduldiger Timeout. */
suspend fun MpdApi.albumsWithoutJson(baseUrl: String): FoldersResponse =
    withContext(Dispatchers.IO) {
        decode(FoldersResponse.serializer(), get(api(baseUrl, "albums", "without-json"), patientClient))
    }

/** GET /api/album/files?space&folder — sortiert nach Aufnahmezeit. */
suspend fun MpdApi.albumFiles(baseUrl: String, space: String, folder: String): AlbumFilesResponse =
    withContext(Dispatchers.IO) {
        decode(
            AlbumFilesResponse.serializer(),
            get(api(baseUrl, "album", "files", query = mapOf("space" to space, "folder" to folder))),
        )
    }

/**
 * POST /api/album/create — Album fuer einen bestehenden Ordner.
 *
 * GEDULDIGER CLIENT (12.09.2026). Der Server baut dabei die album.json
 * aus dem GANZEN Ordner: jede Datei wird angefasst, die Aufnahmezeit
 * gelesen, danach sortiert. Bei einem gewachsenen Jahresordner ist der
 * Standard-Lesetimeout von 10 Sekunden dafuer zu knapp — die Anfrage
 * brach ab, waehrend der Server ruhig weiterarbeitete und fertig
 * anlegte. Die App sah nur den Netzfehler und zeigte wieder die
 * Deckblatt-Wahl.
 *
 * 12.09.2026: „ich hatte Deckblatt gewaehlt, wieder Auswahl
 * Deckblatt … dann mache ich Abbrechen, und irgendwann mal taucht das
 * neue Album doch auf." Genau das — der Abbruch war unsererseits.
 *
 * Der Ordner-SCAN (albumsWithoutJson) hatte den geduldigen Client
 * laengst, das Anlegen war schlicht vergessen worden.
 */
suspend fun MpdApi.createAlbum(
    baseUrl: String,
    space: String,
    folderName: String,
    thumbnail: String,
): Unit =
    withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("space", JsonPrimitive(space))
            put("folder_name", JsonPrimitive(folderName))
            put("thumbnail", JsonPrimitive(thumbnail))
        }
        post(api(baseUrl, "album", "create"), jsonBody(body), patientClient)
        Unit
    }

/** POST /api/album/create-empty — legt Ordner + minimales album.json
 *  an (PDX v1.4, leere Alben sind formatgueltig). */
suspend fun MpdApi.createEmptyAlbum(baseUrl: String, space: String, folderName: String): Unit =
    withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("space", JsonPrimitive(space))
            put("folder_name", JsonPrimitive(folderName))
        }
        post(api(baseUrl, "album", "create-empty"), jsonBody(body))
        Unit
    }

package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Zeitstrahl und Durchlauf (/api/timeline, /api/durchlauf, unassigned).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

/** GET /api/timeline — chronologische Medienliste, Cursor-Paging.
 *  403 = Modul „timeline" nicht freigeschaltet. */
/**
 * Wieviel liegt im Durchlauf? Genau der Aufruf, den das Web macht
 * (index.html, _loadDurchlaufCount): ein Bestand, `limit=1`, und die
 * Antwort interessiert nur wegen `total`.
 *
 * Bewusst KEINE eigene Zaehlung im Client. Am 08.09.2026 stand hier
 * erst eine — sie zaehlte jede Serverdatei ohne album.json und war
 * damit falsch, weil der Server drei Ordnerarten unterscheidet und
 * die dritte nicht mitzaehlt. „Keine Server-Logik in der App
 * nachbauen" (CLAUDE.md); die Zahl kann der Server einfach sagen.
 */
suspend fun MpdApi.durchlaufTotal(baseUrl: String, space: String): Int =
    withContext(Dispatchers.IO) {
        decode(
            TimelineResponse.serializer(),
            get(
                api(
                    baseUrl, "timeline",
                    query = mapOf(
                        "space" to space,
                        "referenced" to "false",
                        "folder" to "durchlauf,album",
                        "limit" to "1",
                    ),
                ),
            ),
        ).total
    }

suspend fun MpdApi.timeline(baseUrl: String, cursor: String?, limit: Int = 200): TimelineResponse =
    withContext(Dispatchers.IO) {
        decode(
            TimelineResponse.serializer(),
            get(api(baseUrl, "timeline", query = mapOf("limit" to limit.toString(), "cursor" to cursor))),
        )
    }

/** GET …/unassigned-photos: Ordnerdateien, die noch in keinem Element
 *  referenziert sind — Futter fuer den Foto/Video-Picker. */
suspend fun MpdApi.unassignedMedia(baseUrl: String, space: String, album: String): List<UnassignedFile> =
    unassignedFiles(baseUrl, space, album, "photos")

/** GET …/unassigned-{photos|audio|documents} — gleiche Struktur,
 *  nur andere Endung. */
suspend fun MpdApi.unassignedFiles(
    baseUrl: String, space: String, album: String, kind: String,
): List<UnassignedFile> = withContext(Dispatchers.IO) {
    decode(
        UnassignedResponse.serializer(),
        get(api(baseUrl, "album", space, album, "unassigned-$kind")),
    ).unassigned
}

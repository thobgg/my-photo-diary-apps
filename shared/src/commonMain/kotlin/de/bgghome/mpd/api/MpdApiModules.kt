package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

// Module Memories, Suche, Stats (/api/memories, /api/search, /api/stats).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

/** GET /api/memories[?date=MM-DD] — „Vor X Jahren" (Shared-Scan). */
/**
 * [limit] = hoechstens so viele je Jahr, in der Rangfolge des Servers
 * (Tagesabschnitte, dann Ort; deterministisch je Tag — Vertrag
 * 08.09.2026 „Memories: Auswahl statt Flut"). null = alles, wie vorher.
 */
suspend fun MpdApi.memories(baseUrl: String, date: String? = null, limit: Int? = null): MemoriesResponse =
    withContext(Dispatchers.IO) {
        val url = api(
            baseUrl, "memories",
            query = mapOf("date" to date, "limit" to limit?.toString()),
        )
        // Kalter Cache: der Server scannt live (~12 s gemessen am
        // 03.09.) — der 10-s-Default-Timeout brach ab und die App
        // meldete faelschlich „Server nicht erreichbar".
        decode(MemoriesResponse.serializer(), get(url, patientClient))
    }

/** GET /api/search — null, solange der Index gebaut wird (202). */
suspend fun MpdApi.search(
    baseUrl: String,
    q: String,
    space: String = "all",
    limit: Int = 50,
): SearchResponse? = withContext(Dispatchers.IO) {
    val url = api(
        baseUrl, "search",
        query = mapOf("q" to q, "space" to space, "limit" to limit.toString()),
    )
    val (code, text) = runWithCode(Request.Builder().url(url).build())
    if (code == 202) null
    else decode(SearchResponse.serializer(), text)
}

/** GET /api/stats — null, solange der Hintergrund-Scan laeuft (202). */
suspend fun MpdApi.stats(baseUrl: String, space: String = "both"): StatsResponse? =
    withContext(Dispatchers.IO) {
        val url = api(baseUrl, "stats", query = mapOf("space" to space))
        val (code, text) = runWithCode(Request.Builder().url(url).build())
        if (code == 202) null
        else decode(StatsResponse.serializer(), text)
    }

/** GET /api/stats/geo — je Space ein Paket (Weltkarte, Laender). 202 =
 *  der Server geocodiert noch → null; 503 = kein MAPTILER_KEY
 *  (MpdHttpException mit dem Servertext). */
suspend fun MpdApi.statsGeo(baseUrl: String, space: String = "both"): Map<String, StatsGeo>? =
    withContext(Dispatchers.IO) {
        val url = api(baseUrl, "stats", "geo", query = mapOf("space" to space))
        val (code, text) = runWithCode(Request.Builder().url(url).build())
        if (code == 202) null
        else json.parseToJsonElement(text).jsonObject.mapValues { (_, v) ->
            json.decodeFromJsonElement(StatsGeo.serializer(), v)
        }
    }

/** GET /api/stats/text — je Space ein TextStats-Paket. */
suspend fun MpdApi.statsText(baseUrl: String, space: String = "both"): Map<String, TextStats> =
    withContext(Dispatchers.IO) {
        val root = json.parseToJsonElement(
            get(api(baseUrl, "stats", "text", query = mapOf("space" to space))),
        ).jsonObject
        root.mapValues { (_, v) ->
            json.decodeFromJsonElement(TextStats.serializer(), v)
        }
    }

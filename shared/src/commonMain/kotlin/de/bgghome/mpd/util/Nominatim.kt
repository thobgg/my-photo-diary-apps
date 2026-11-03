package de.bgghome.mpd.util

import de.bgghome.mpd.api.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Ortssuche ueber Nominatim — wie mapSearchPlace im Web (limit=1).
 *
 * Bis 08.09.2026 stand das zweimal im Code (Karten-Dialog der
 * Albumgestaltung, GPS-Nadel im EXIF-Editor), je mit einem frischen
 * `OkHttpClient()` ohne Timeout, mit zwei verschiedenen User-Agents und
 * zwei handgeschriebenen Parsern. Jetzt einmal.
 *
 * Der Client ist BEWUSST nicht der API-Client der App: der traegt den
 * Bearer-Header und den Sitzungs-Cookie, und beides darf nie an einen
 * Fremddienst gehen. Nominatims Nutzungsbedingungen verlangen einen
 * identifizierenden User-Agent — der Paketname reicht dafuer.
 */
object Nominatim {

    data class Treffer(val lat: Double, val lon: Double, val label: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Erster Treffer oder null (auch bei Netzfehler — die Aufrufer melden
     *  „nicht gefunden", mehr gibt es dem Nutzer hier nicht zu sagen). */
    suspend fun suche(query: String): Treffer? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        runCatching {
            val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "1")
                .addQueryParameter("q", q)
                .build()
            val request = Request.Builder().url(url)
                .header("Accept-Language", "de")
                .header("User-Agent", "de.bgghome.mpd (My Photo Diary, Android)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val hit = json.parseToJsonElement(response.body?.string().orEmpty())
                    .jsonArray.firstOrNull()?.jsonObject ?: return@use null
                val lat = hit["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val lon = hit["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
                if (lat == null || lon == null) return@use null
                Treffer(
                    lat = lat,
                    lon = lon,
                    label = hit["display_name"]?.jsonPrimitive?.content
                        ?.substringBefore(",").orEmpty(),
                )
            }
        }.getOrNull()
    }
}

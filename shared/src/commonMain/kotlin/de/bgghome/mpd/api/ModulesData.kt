package de.bgghome.mpd.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Antwortformen der Module „search" und „stats" (GET /api/search,
 * GET /api/stats) — Felder wie im jeweiligen Router; Unbekanntes wird
 * vom gemeinsamen Json-Objekt ignoriert.
 */

@Serializable
data class SearchResult(
    val type: String = "",              // album | text | link
    val space: String = "",
    val album: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    val year: Int? = null,
    val text: String = "",
    val snippet: String = "",
    val score: Double = 0.0,
    /** Kontext-Vorschaubild: Dateiname im Albumordner (kann fehlen). */
    @SerialName("context_photo") val contextPhoto: String? = null,
    val thumbnail: String? = null,
    @SerialName("element_id") val elementId: String? = null,
    val style: String? = null,
)

@Serializable
data class SearchResponse(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val count: Int = 0,
    val total: Int = 0,
)

@Serializable
data class StatsResponse(
    @SerialName("total_photos") val totalPhotos: Long = 0,
    @SerialName("total_size_mb") val totalSizeMb: Double = 0.0,
    /** Jahr ("2016") → Anzahl. */
    @SerialName("photos_per_year") val photosPerYear: Map<String, Long> = emptyMap(),
    /** Monat ("1".."12") → Anzahl. */
    @SerialName("photos_per_month") val photosPerMonth: Map<String, Long> = emptyMap(),
    /** "Winter"/"Frühling"/"Sommer"/"Herbst" → Anzahl. */
    @SerialName("season_counts") val seasonCounts: Map<String, Long> = emptyMap(),
    /** Wochentag "0"(Mo).."6"(So) → Anzahl. */
    @SerialName("weekday_counts") val weekdayCounts: Map<String, Long> = emptyMap(),
    /** Stunde "0".."23" → Anzahl. */
    @SerialName("hour_counts") val hourCounts: Map<String, Long> = emptyMap(),
    /** Kameramodell → Anzahl. */
    val devices: Map<String, Long> = emptyMap(),
    @SerialName("gps_coverage_pct") val gpsCoveragePct: Map<String, Double> = emptyMap(),
    @SerialName("total_size_mb_per_year") val sizeMbPerYear: Map<String, Double> = emptyMap(),
    /** Jahr → (Monat "1".."12" → Anzahl) — Heatmap Monat/Jahr. */
    @SerialName("photos_per_year_month")
    val photosPerYearMonth: Map<String, Map<String, Long>> = emptyMap(),
    /** Jahr → Ø Dateigroesse in MB. */
    @SerialName("avg_filesize_mb_per_year")
    val avgFilesizeMbPerYear: Map<String, Double> = emptyMap(),
    @SerialName("albums_summary") val albumsSummary: List<AlbumStatSummary> = emptyList(),
    val spaces: List<String> = emptyList(),
    /** Jahr → ("KW_Wochentag" mit Wochentag 0=Mo → Anzahl) — Tag/Woche-Ansicht
     *  des Webs (openDrilldown), seit 13.11.2026 auch in der App. */
    @SerialName("photos_per_year_week")
    val photosPerYearWeek: Map<String, Map<String, Long>> = emptyMap(),
)

/* -- /api/stats/geo: je Space ein Paket; die App fuehrt zusammen -------- */

/** Ein geocodierter Ort (5-km-Cluster, Koordinate auf 3 Stellen). */
@Serializable
data class GeoStatPoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val count: Long = 0,
    val country: String = "",
)

@Serializable
data class StatsGeo(
    @SerialName("total_gps_photos") val totalGpsPhotos: Long = 0,
    @SerialName("cluster_count") val clusterCount: Long = 0,
    @SerialName("country_counts") val countryCounts: Map<String, Long> = emptyMap(),
    @SerialName("map_points") val mapPoints: List<GeoStatPoint> = emptyList(),
) {
    /** Shared + Personal additiv, wie der Web-Merge. */
    operator fun plus(o: StatsGeo): StatsGeo = StatsGeo(
        totalGpsPhotos = totalGpsPhotos + o.totalGpsPhotos,
        clusterCount = clusterCount + o.clusterCount,
        countryCounts = (countryCounts.keys + o.countryCounts.keys)
            .associateWith { (countryCounts[it] ?: 0L) + (o.countryCounts[it] ?: 0L) },
        mapPoints = mapPoints + o.mapPoints,
    )
}

@Serializable
data class AlbumStatSummary(
    val name: String = "",
    @SerialName("photo_count") val photoCount: Long = 0,
    @SerialName("total_size_mb") val totalSizeMb: Double = 0.0,
)

/* -- /api/stats/text: je Space ein Paket; die App fuehrt zusammen ------- */

@Serializable
data class TextStats(
    @SerialName("total_text_elements") val totalTextElements: Long = 0,
    @SerialName("total_words") val totalWords: Long = 0,
    @SerialName("total_chars") val totalChars: Long = 0,
    @SerialName("total_albums_with_text") val totalAlbumsWithText: Long = 0,
    @SerialName("style_counts") val styleCounts: Map<String, Long> = emptyMap(),
    /** Jahr → {elements, words}. */
    @SerialName("per_year") val perYear: Map<String, TextYear> = emptyMap(),
    @SerialName("top_by_text") val topByText: List<TextAlbum> = emptyList(),
    @SerialName("top_by_score") val topByScore: List<TextAlbum> = emptyList(),
)

@Serializable
data class TextYear(val elements: Long = 0, val words: Long = 0)

@Serializable
data class TextAlbum(
    val album: String = "",
    val title: String = "",
    val year: Int? = null,
    val elements: Long = 0,
    val words: Long = 0,
    val photos: Long = 0,
    /** Texte je Photo — der „Tagebuch-Score" des Webs. */
    val score: Double = 0.0,
)

/* -- /api/memories: „Vor X Jahren" (Scan nur Shared-Space) ------------- */

@Serializable
data class MemoriesResponse(
    val date: String = "",
    /** z.B. "2. September". */
    val label: String = "",
    val years: List<MemoryYear> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class MemoryYear(
    val year: Int = 0,
    @SerialName("years_ago") val yearsAgo: Int = 0,
    val photos: List<MemoryPhoto> = emptyList(),
    /** Volle Zahl des Tages in diesem Jahr — `photos` ist seit dem
     *  08.09.2026 eine Auswahl (Server: „Memories: Auswahl statt Flut").
     *  0 auf aelteren Servern: dann ist photos alles, was es gibt. */
    val total: Int = 0,
)

@Serializable
data class MemoryPhoto(
    val filename: String = "",
    val album: String = "",
    /** Relativer Thumb-Pfad (/api/memories-thumb/…?size=m). */
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    /** Reverse-Geocoding-Ort („Tübingen, DE") — leer ohne GPS/Key. */
    val location: String = "",
    /** „HH:MM" aus Dateiname oder EXIF, null wenn unbekannt (seit 08.09.2026). */
    val time: String? = null,
    /** Bestand des Albums, seit Server 26.09.2026 (Quelle „beides" mischt
     *  Familien- und eigene Fotos). Aeltere Server: nur in thumbnail_url. */
    @SerialName("space") val spaceField: String? = null,
) {
    val space: String
        get() = spaceField
            ?: if ("space=personal" in thumbnailUrl) "personal" else "shared"
}

/* -- /api/notify: Benachrichtigungen (Ersatz fuer ntfy) ---------------- */

@Serializable
data class NotifyMessage(
    val id: Long = 0,
    /** Unix-Zeit — kommt vom Server als Kommazahl (time.time()). */
    @SerialName("created_at") val createdAt: Double = 0.0,
    /** z.B. "memories". */
    val type: String = "",
    val title: String = "",
    val body: String = "",
    /** deep_link, date, photos, years_ago … je nach type. */
    val payload: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class NotifyPullResponse(
    val notifications: List<NotifyMessage> = emptyList(),
    val cursor: Long = 0,
)

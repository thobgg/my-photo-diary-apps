package de.bgghome.mpd.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Modul „Trails" — Standorthistorie im MPD-Server (docs/API_CHANGES.md,
 * 04.09.2026, Phasen 1-6). Anders als bei allen anderen Modulen gibt es
 * KEIN Web-Frontend als Vorlage: trails.html ist der alte GPX-Ordner-
 * Betrachter, mpd-trail.html spricht noch Dawarich an. Die App ist damit
 * der erste Client des neuen Speichers — Vorlage ist die API-Doku.
 *
 * Eine SQLite je Nutzer: jeder sieht nur die eigene Historie, auch der
 * Admin. Alle Zeiten sind Unix-Sekunden UTC; `tz_offset` (Minuten) sagt,
 * in welcher Zone der Punkt aufgezeichnet wurde — bei Google-Punkten
 * gesetzt, bei OwnTracks null (dann gilt die Zone des Geraets).
 */

@Serializable
data class TrailPoint(
    val id: Long = 0,
    val source: String = "",
    val device: String? = null,
    val tst: Long = 0,
    @SerialName("tz_offset") val tzOffset: Int? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Double? = null,
    /** Genauigkeit in Metern (OwnTracks `acc`). */
    val acc: Double? = null,
    val vacc: Double? = null,
    /** Geschwindigkeit in km/h (OwnTracks `vel`). */
    val vel: Double? = null,
    val cog: Double? = null,
    val batt: Int? = null,
    val conn: String? = null,
    /** OwnTracks-Ausloeser bzw. bei Google path|visit|activity. */
    val trigger: String? = null,
    val motion: String? = null,
    val country: String? = null,
    val city: String? = null,
)

/** Aufenthalt: „hier war ich von … bis …". */
@Serializable
data class TrailVisit(
    val id: Long = 0,
    val source: String = "",
    @SerialName("start_ts") val startTs: Long = 0,
    @SerialName("end_ts") val endTs: Long = 0,
    @SerialName("tz_offset") val tzOffset: Int? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** Google: placeId; mpd: 100-m-Rasterschluessel. */
    @SerialName("place_ref") val placeRef: String? = null,
    /** Name aus dem Geocoder — Google-Besuche haben keinen. */
    @SerialName("place_name") val placeName: String? = null,
    val city: String? = null,
    val country: String? = null,
    /** Google: HOME/WORK/SEARCHED_ADDRESS/UNKNOWN. */
    val semantic: String? = null,
    val probability: Double? = null,
    /** true = eigener Ortsname gesetzt (Vertrag 08.09.2026, PUT
     *  /api/trails/places/name); place_name traegt dann ihn. Aeltere Server
     *  liefern das Feld nicht → false. */
    val custom: Boolean = false,
)

/** Weg zwischen zwei Aufenthalten. */
@Serializable
data class TrailActivity(
    val id: Long = 0,
    val source: String = "",
    @SerialName("start_ts") val startTs: Long = 0,
    @SerialName("end_ts") val endTs: Long = 0,
    @SerialName("tz_offset") val tzOffset: Int? = null,
    @SerialName("start_lat") val startLat: Double? = null,
    @SerialName("start_lon") val startLon: Double? = null,
    @SerialName("end_lat") val endLat: Double? = null,
    @SerialName("end_lon") val endLon: Double? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    /** WALKING, IN_PASSENGER_VEHICLE … — bei Dawarich-Tracks eine Zahl. */
    val type: String? = null,
    val probability: Double? = null,
)

/** GET /api/trails/day — „Wo war ich?" in einem Aufruf. */
@Serializable
data class TrailDay(
    val date: String = "",
    val tz: Int = 0,
    val from: Long = 0,
    val to: Long = 0,
    val points: List<TrailPoint> = emptyList(),
    @SerialName("points_total") val pointsTotal: Int = 0,
    val thinned: Boolean = false,
    val visits: List<TrailVisit> = emptyList(),
    val activities: List<TrailActivity> = emptyList(),
)

/** GET /api/trails/days — Kalender-Futter: Tag → Punktzahl. */
@Serializable
data class TrailDays(val days: Map<String, Int> = emptyMap())

/** GET /api/trails/places — Orte nach Besuchshaeufigkeit. */
@Serializable
data class TrailPlace(
    val source: String = "",
    @SerialName("place_ref") val placeRef: String? = null,
    /** Anzahl Besuche. */
    val n: Int = 0,
    /** Summe der Aufenthaltsdauer in Sekunden. */
    val seconds: Long = 0,
    @SerialName("first_ts") val firstTs: Long? = null,
    @SerialName("last_ts") val lastTs: Long? = null,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    val country: String? = null,
    /** true = eigener Ortsname (siehe TrailVisit.custom). */
    val custom: Boolean = false,
)

/** Antwort auf PUT /api/trails/places/name. */
@Serializable
data class TrailPlaceName(
    val name: String? = null,
    val custom: Boolean = false,
    @SerialName("osm_name") val osmName: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class TrailPlacesResponse(val places: List<TrailPlace> = emptyList())

/** GET /api/trails/heatmap — Rasterzaehlung [lat, lon, n] je Zelle. */
@Serializable
data class TrailHeatmap(
    /** Zellgroesse in Grad (0,01 ≈ 1 km). */
    val cell: Double = 0.01,
    val cells: List<List<Double>> = emptyList(),
    val total: Long = 0,
    val truncated: Boolean = false,
)

/** Ein Aufzeichnungsgeraet (OwnTracks). `created`/`last_seen` sind
 *  Unix-Sekunden als Kommazahl (REAL in der Tabelle). */
@Serializable
data class TrailDevice(
    @SerialName("device_id") val deviceId: String = "",
    val name: String? = null,
    @SerialName("has_token") val hasToken: Boolean = false,
    val created: Double? = null,
    @SerialName("last_seen") val lastSeen: Double? = null,
    /** Zeitstempel des letzten Punktes dieses Geraets. */
    @SerialName("last_tst") val lastTst: Long? = null,
)

@Serializable
data class TrailDevicesResponse(val devices: List<TrailDevice> = emptyList())

/** Antwort auf POST /api/trails/devices — `token` gibt es NUR hier. */
@Serializable
data class TrailDeviceCreated(
    @SerialName("device_id") val deviceId: String = "",
    val name: String? = null,
    val token: String = "",
    @SerialName("ingest_url") val ingestUrl: String = "/api/trails/ingest",
    val hint: String = "",
)

/** GET /api/trails/status — Umfang des Speichers. */
@Serializable
data class TrailStatus(
    val exists: Boolean = false,
    val points: Long = 0,
    val events: Long = 0,
    val visits: Long = 0,
    val activities: Long = 0,
    val segments: Long = 0,
    @SerialName("first_tst") val firstTst: Long? = null,
    @SerialName("last_tst") val lastTst: Long? = null,
    /** Quelle → Punkte, z.B. {"owntracks": 812, "google": 401_233}. */
    val sources: Map<String, Long> = emptyMap(),
    val devices: List<TrailDevice> = emptyList(),
)

/** GET /api/trails/sources — was liegt im Speicher, je Quelle. */
@Serializable
data class TrailSource(
    val source: String = "",
    val points: Long = 0,
    val visits: Long = 0,
    val activities: Long = 0,
    val segments: Long = 0,
    val places: Long = 0,
    @SerialName("first_ts") val firstTs: Long? = null,
    @SerialName("last_ts") val lastTs: Long? = null,
)

@Serializable
data class TrailSourcesResponse(
    val user: String = "",
    val sources: List<TrailSource> = emptyList(),
)

/** Eine Datei im naechtlichen Archiv (Mitnahme, Phase 6). */
@Serializable
data class TrailArchiveFile(
    val name: String = "",
    /** raw | derived | places. */
    val kind: String = "",
    /** "2026-09" — bei places.jsonl null. */
    val month: String? = null,
    val bytes: Long = 0,
    val mtime: Long = 0,
)

@Serializable
data class TrailArchiveResponse(
    val user: String = "",
    val files: List<TrailArchiveFile> = emptyList(),
    val bytes: Long = 0,
)

/** POST /api/trails/derive — Nachtjob von Hand. */
@Serializable
data class TrailDeriveResponse(
    val derived: Boolean = false,
    /** Nur wenn nichts zu tun war. */
    val reason: String? = null,
    val points: Long = 0,
    val visits: Long = 0,
    val activities: Long = 0,
    val removed: Long = 0,
)

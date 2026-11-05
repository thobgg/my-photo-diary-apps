package de.bgghome.mpd.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/photos/geo — die Koordinaten vieler Fotos in einem Aufruf
 * (Server seit 09/2026, Quelle ist der Statistik-Cache je Album; ein Foto,
 * das seit dem letzten Stats-Lauf dazukam, fehlt hier bis zum naechsten).
 */
@Serializable
data class PhotoGeoEntry(
    val space: String = "",
    val album: String = "",
    val file: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val dt: String? = null,
)

@Serializable
data class PhotoGeoResponse(
    val photos: List<PhotoGeoEntry> = emptyList(),
    val count: Int = 0,
    val total: Int = 0,
    val truncated: Boolean = false,
    val stand: String? = null,
    @SerialName("albums_unscanned") val albumsUnscanned: Int = 0,
)

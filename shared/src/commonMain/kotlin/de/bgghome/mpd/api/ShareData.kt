package de.bgghome.mpd.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Teilen — Besitzer-Seite (docs/API_CHANGES.md, Vertraege vom 03./04.09.2026).
 *
 * Konzept des Servers: die App baut NUR die Besitzer-Seite (Links
 * erzeugen, sehen, widerrufen). Empfaenger oeffnen den Magic-Link im
 * Browser, der Token-Viewer bleibt bewusst Web — Gaeste brauchen nie
 * eine App. Dasselbe gilt fuer Beisteuern-Links.
 *
 * ACHTUNG Zeitstempel: die Share-Tabellen speichern `created_at`,
 * `last_seen_at` und `expires_at` als REAL — das sind `time.time()`-Werte
 * MIT Nachkommastellen. Als `Long` deklariert bricht die Antwort beim
 * Dezimalpunkt ("Unexpected symbol in numeric literal"), und zwar erst
 * beim ersten echten Datensatz. Faustregel fuer diese API: alles, was aus
 * `time.time()` kommt, ist `Double`; nur ausdrueckliche INTEGER-Spalten
 * (die Trails-Zeitstempel) sind `Long`.
 *
 * Der KLARTEXT-Token steht nur in der Antwort auf create; die Liste
 * kennt bloss den Hash. Alles, was den Klartext braucht (E-Mail
 * verschicken, Link weitergeben), geht also nur unmittelbar nach dem
 * Anlegen — im Web ist es genauso geloest.
 */

/** Ein aktiver Share-Link, wie ihn `GET /api/share/list` beschreibt. */
@Serializable
data class ShareEntry(
    @SerialName("token_hash") val tokenHash: String = "",
    @SerialName("created_at") val createdAt: Double? = null,
    /** Wann der Link zuletzt geoeffnet wurde; null = noch nie. */
    @SerialName("last_seen_at") val lastSeenAt: Double? = null,
    val theme: String? = null,
    /** Unix-Zeit oder null = unbefristet. */
    @SerialName("expires_at") val expiresAt: Double? = null,
    /** Dateiname bei Einzelfoto-Shares (Teilen v3); null = ganzes Album. */
    val file: String? = null,
)

@Serializable
data class ShareListResponse(val shares: List<ShareEntry> = emptyList())

/** Antwort auf `POST /api/share/create` — `token` gibt es NUR hier. */
@Serializable
data class ShareCreated(
    val token: String = "",
    @SerialName("expires_days") val expiresDays: Int? = null,
    val file: String? = null,
)

/** Ein Beisteuern-Link (Gast-Upload in einen Sammelordner). */
@Serializable
data class ContribEntry(
    @SerialName("token_hash") val tokenHash: String = "",
    val space: String = "",
    @SerialName("folder_name") val folderName: String = "",
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("expires_at") val expiresAt: Double? = null,
    @SerialName("max_files") val maxFiles: Int = 0,
    /** Wie viele Dateien Gaeste schon abgeliefert haben. */
    val uploaded: Int = 0,
)

@Serializable
data class ContribListResponse(val contribs: List<ContribEntry> = emptyList())

/** Antwort auf `POST /api/contrib/create` — `token` gibt es NUR hier. */
@Serializable
data class ContribCreated(
    val token: String = "",
    /** Relativ, z.B. "/contrib/<token>". */
    val url: String = "",
    val space: String = "",
    @SerialName("folder_name") val folderName: String = "",
    /** Double wie in [ContribEntry]: der Server schreibt `time.time()`. */
    @SerialName("expires_at") val expiresAt: Double? = null,
    @SerialName("max_files") val maxFiles: Int = 0,
)

/** Adressbuch fuer den E-Mail-Versand (`GET /api/contacts`). */
@Serializable
data class ShareContact(
    val email: String = "",
    val name: String? = null,
    @SerialName("last_used_at") val lastUsedAt: Double? = null,
)

@Serializable
data class ContactsResponse(val contacts: List<ShareContact> = emptyList())

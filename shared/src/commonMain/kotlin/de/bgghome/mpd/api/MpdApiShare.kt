package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Teilen und Beisteuern, Besitzer-Seite (/api/share, /api/contrib, /api/contacts).
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

// ==== Teilen: Besitzer-Seite (Vertraege 03./04.09.2026) ================
// Klartext-Token nur in der create-Antwort; die Liste kennt Hashes.
// Der Gast-Viewer bleibt Web — hier entsteht nur die Verwaltung.

/** GET /api/share/list — aktive Links EINES Albums (Metadaten). */
suspend fun MpdApi.shareList(baseUrl: String, space: String, album: String): List<ShareEntry> =
    withContext(Dispatchers.IO) {
        val url = api(
            baseUrl, "share", "list",
            query = mapOf("space" to space, "album_name" to album),
        )
        decode(ShareListResponse.serializer(), get(url)).shares
    }

/**
 * POST /api/share/create — neuer Link. `file` gesetzt = Einzelfoto-Share
 * (Teilen v3), sonst das ganze Album. `expiresDays` null = unbefristet.
 * Der zurueckgegebene Token ist der EINZIGE Klartext, den es je gibt.
 */
suspend fun MpdApi.shareCreate(
    baseUrl: String,
    space: String,
    album: String,
    file: String? = null,
    expiresDays: Int? = null,
): ShareCreated = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("space", JsonPrimitive(space))
        put("album_name", JsonPrimitive(album))
        file?.let { put("file", JsonPrimitive(it)) }
        expiresDays?.let { put("expires_days", JsonPrimitive(it)) }
    })
    decode(ShareCreated.serializer(), post(api(baseUrl, "share", "create"), body))
}

/** POST /api/share/revoke — ueber den Hash aus der Liste. */
suspend fun MpdApi.shareRevoke(baseUrl: String, tokenHash: String): Unit =
    withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject { put("token_hash", JsonPrimitive(tokenHash)) })
        post(api(baseUrl, "share", "revoke"), body)
        Unit
    }

/** POST /api/share/send-email — braucht den KLARTEXT-Token, geht also
 *  nur unmittelbar nach dem Anlegen (im Web ebenso). */
suspend fun MpdApi.shareSendEmail(
    baseUrl: String,
    token: String,
    toEmail: String,
    toName: String? = null,
    personalMessage: String? = null,
): Unit = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("token", JsonPrimitive(token))
        put("to_email", JsonPrimitive(toEmail))
        toName?.takeIf { it.isNotBlank() }?.let { put("to_name", JsonPrimitive(it)) }
        personalMessage?.takeIf { it.isNotBlank() }
            ?.let { put("personal_message", JsonPrimitive(it)) }
    })
    // SMTP kann dauern — der Standard-Lesetimeout ist dafuer zu knapp.
    post(api(baseUrl, "share", "send-email"), body, patientClient)
    Unit
}

/** GET /api/contacts — Adressbuch fuer den E-Mail-Versand. */
suspend fun MpdApi.contacts(baseUrl: String): List<ShareContact> = withContext(Dispatchers.IO) {
    decode(ContactsResponse.serializer(), get(api(baseUrl, "contacts"))).contacts
}

/** GET /api/contrib/list — eigene Beisteuern-Links, ueber alle Ordner. */
suspend fun MpdApi.contribList(baseUrl: String): List<ContribEntry> = withContext(Dispatchers.IO) {
    decode(ContribListResponse.serializer(), get(api(baseUrl, "contrib", "list"))).contribs
}

/**
 * POST /api/contrib/create — Sammelordner anlegen und Gastlink dazu.
 * Ziel darf KEIN Album sein (Server: 400) — Gaeste liefern Rohmaterial,
 * einsortiert wird danach ueber den Zeitstrahl.
 */
suspend fun MpdApi.contribCreate(
    baseUrl: String,
    space: String,
    folderName: String,
    expiresDays: Int,
    maxFiles: Int,
): ContribCreated = withContext(Dispatchers.IO) {
    val body = jsonBody(buildJsonObject {
        put("space", JsonPrimitive(space))
        put("folder_name", JsonPrimitive(folderName))
        put("expires_days", JsonPrimitive(expiresDays))
        put("max_files", JsonPrimitive(maxFiles))
    })
    decode(ContribCreated.serializer(), post(api(baseUrl, "contrib", "create"), body))
}

/** POST /api/contrib/revoke — ueber den Hash aus der Liste. */
suspend fun MpdApi.contribRevoke(baseUrl: String, tokenHash: String): Unit =
    withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject { put("token_hash", JsonPrimitive(tokenHash)) })
        post(api(baseUrl, "contrib", "revoke"), body)
        Unit
    }

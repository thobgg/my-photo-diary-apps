package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// Benachrichtigungen, Benutzerverwaltung, Einstellungen.
// Erweiterungen von MpdApi, herausgeloest aus MpdApi.kt (13.11.2026).

// -- Benachrichtigungen (/api/notify, Ersatz fuer ntfy) -----------------

/** PUT /api/notify/devices/{id} — idempotent, bei jedem Lauf erlaubt. */
suspend fun MpdApi.registerNotifyDevice(baseUrl: String, deviceId: String, name: String): Unit =
    withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("name", JsonPrimitive(name))
            put("platform", JsonPrimitive("android"))
            put("transport", JsonPrimitive("poll"))
        })
        put(api(baseUrl, "notify", "devices", deviceId), body)
        Unit
    }

/** GET /api/admin/users — Nutzerliste (admin-only). */
suspend fun MpdApi.adminUsers(baseUrl: String): AdminUsersResponse =
    withContext(Dispatchers.IO) {
        decode(AdminUsersResponse.serializer(), get(api(baseUrl, "admin", "users")))
    }

/** POST /api/admin/users — anlegen (username, password ≥12, role, display_name?). */
suspend fun MpdApi.adminCreateUser(baseUrl: String, body: JsonObject): Unit =
    withContext(Dispatchers.IO) {
        post(api(baseUrl, "admin", "users"), jsonBody(body))
        Unit
    }

/** PATCH /api/admin/users/{u} — display_name | role | password (Reset kickt Sessions). */
suspend fun MpdApi.adminPatchUser(baseUrl: String, username: String, body: JsonObject): Unit =
    withContext(Dispatchers.IO) {
        patch(api(baseUrl, "admin", "users", username), jsonBody(body))
        Unit
    }

/** DELETE /api/admin/users/{u} — Server schuetzt selbst/letzten Admin. */
suspend fun MpdApi.adminDeleteUser(baseUrl: String, username: String): Unit =
    withContext(Dispatchers.IO) {
        delete(api(baseUrl, "admin", "users", username))
        Unit
    }

/** GET /api/settings/me — persoenliche Einstellungen des Nutzers. */
suspend fun MpdApi.userSettings(baseUrl: String): UserSettingsData =
    withContext(Dispatchers.IO) {
        decode(UserSettingsData.serializer(), get(api(baseUrl, "settings", "me")))
    }

/** PUT /api/settings/me — z.B. {"memories_scope": "personal"}. */
suspend fun MpdApi.updateUserSettings(baseUrl: String, body: JsonObject): UserSettingsData =
    withContext(Dispatchers.IO) {
        decode(UserSettingsData.serializer(), put(api(baseUrl, "settings", "me"), jsonBody(body)))
    }

/** GET /api/settings — lesbar fuer alle Angemeldeten. */
suspend fun MpdApi.appSettings(baseUrl: String): AppSettingsData =
    withContext(Dispatchers.IO) {
        decode(AppSettingsData.serializer(), get(api(baseUrl, "settings")))
    }

/** POST /api/settings — Admin; nur die uebergebenen Schluessel aendern. */
suspend fun MpdApi.updateAppSettings(baseUrl: String, updates: JsonObject): AppSettingsData =
    withContext(Dispatchers.IO) {
        decode(AppSettingsData.serializer(), post(api(baseUrl, "settings"), jsonBody(updates)))
    }

/** GET /api/notify/pull — hoechstens 100 je Antwort, aufsteigend nach id.
 *  since>0: ab diesem Cursor (Blaettern: solange 100 kommen, mit der
 *  hoechsten id weiterfragen). since=0: unabhaengig vom Ack-Cursor; ab
 *  Server 3.0.502 die NEUESTEN 100 der 30-Tage-Aufbewahrung, davor die
 *  aeltesten (API_CHANGES 13.10.2026). wait>0 haelt die Antwort serverseitig bis zu N
 *  Sekunden offen (Long-Poll, Servermaximum 25) — dafuer bekommt der
 *  Call einen entsprechend verlaengerten Read-Timeout. */
suspend fun MpdApi.notifyPull(
    baseUrl: String,
    deviceId: String,
    since: Long? = null,
    wait: Int = 0,
): NotifyPullResponse =
    withContext(Dispatchers.IO) {
        val url = api(
            baseUrl, "notify", "pull",
            query = mapOf(
                "device_id" to deviceId,
                "since" to since?.toString(),
                "wait" to wait.takeIf { it > 0 }?.toString(),
            ),
        )
        // Long-Poll: der Server haelt bis zu 25 s offen, patientClient
        // wartet 60 s — vorher wait+10 als eigener Client je Aufruf.
        val http = if (wait > 0) patientClient else client
        decode(NotifyPullResponse.serializer(), get(url, http))
    }

/** POST /api/notify/ack — setzt den Server-Cursor des Geraets. */
suspend fun MpdApi.notifyAck(baseUrl: String, deviceId: String, lastId: Long): Unit =
    withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("device_id", JsonPrimitive(deviceId))
            put("last_id", JsonPrimitive(lastId))
        })
        post(api(baseUrl, "notify", "ack"), body)
        Unit
    }

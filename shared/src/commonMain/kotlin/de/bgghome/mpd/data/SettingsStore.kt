package de.bgghome.mpd.data

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Persistente Einstellungen der App: Serveradresse, die eine vom Nutzer
 * bewusst vertraute Zertifikats-Signatur (TOFU) und der Sitzungs-Cookie.
 *
 * Keine feste Serveradresse im Code — jede Installation zeigt auf die NAS
 * des jeweiligen Nutzers (siehe STAND.md, Gesamtziel).
 *
 * Der Cookie liegt in app-privaten SharedPreferences. EncryptedSharedPreferences
 * ist von Google deprecated; app-privater Speicher ist fuer einen Session-Cookie
 * der uebliche Rahmen (gleiches Schutzniveau wie der Cookie-Store des WebView).
 *
 * Seit der Aufteilung (22.09.2026) liegt diese Klasse in commonMain und
 * kennt Android nicht mehr: sie schreibt in eine [Ablage], die je Plattform
 * anders aussieht (Android: SharedPreferences, Desktop: java.util.prefs).
 * Die Schluessel und Vorgaben sind unveraendert, bestehende Installationen
 * finden ihre Einstellungen wieder.
 */
class SettingsStore(private val prefs: Ablage) {

    /** Basis-URL ohne abschliessenden Schraegstrich, z.B. https://192.168.1.2:5001 */
    var baseUrl: String?
        get() = prefs.getString("base_url", null)
        set(value) { prefs.putString("base_url", value) }

    /**
     * SHA-256-Fingerprint (Hex, ohne Trennzeichen, Grossbuchstaben) des einen
     * Server-Zertifikats, dem der Nutzer beim Einrichten ausdruecklich vertraut
     * hat. null = nur System-CAs gelten.
     */
    var trustedCertSha256: String?
        get() = prefs.getString("trusted_cert_sha256", null)
        set(value) { prefs.putString("trusted_cert_sha256", value) }

    fun saveSession(cookieValue: String, expiresAt: Long) {
        prefs.putString("session_cookie", cookieValue)
        prefs.putLong("session_expires_at", expiresAt)
    }

    /** Cookie-Wert und Ablaufzeit, oder null wenn keiner (mehr) da ist. */
    fun session(): Pair<String, Long>? {
        val value = prefs.getString("session_cookie", null) ?: return null
        val expiresAt = prefs.getLong("session_expires_at", 0L)
        return value to expiresAt
    }

    fun clearSession() {
        prefs.remove("session_cookie"); prefs.remove("session_expires_at")
        prefs.remove("bearer_token"); prefs.remove("bearer_expires_at")
    }

    // -- Kamera-Backup (Geraeteordner → Durchlauf; ersetzt Synology Photos) --

    /** Backup DCIM/Camera aktiv? */
    var backupEnabled: Boolean
        get() = prefs.getBoolean("backup_enabled", false)
        set(value) { prefs.putBoolean("backup_enabled", value) }

    /** Ziel-Space des Backups: true = Personal („Privat"), false = Shared. */
    var backupPrivat: Boolean
        get() = prefs.getBoolean("backup_privat", false)
        set(value) { prefs.putBoolean("backup_privat", value) }

    /** Aktivierungszeitpunkt (ms) — gesichert wird nur, was DANACH in den
     *  MediaStore kam; der Bestand davor gehoert der alten Loesung. */
    var backupSince: Long
        get() = prefs.getLong("backup_since", 0L)
        set(value) { prefs.putLong("backup_since", value) }

    /** Upload nur ueber WLAN (unmetered)? Default ja — Videos! */
    var backupWifiOnly: Boolean
        get() = prefs.getBoolean("backup_wifi_only", true)
        set(value) { prefs.putBoolean("backup_wifi_only", value) }

    /** Zu sichernde Geraeteordner (BUCKET_DISPLAY_NAME); Default: Camera. */
    var backupBuckets: Set<String>
        get() = prefs.getStringSet("backup_buckets", setOf("Camera"))
        set(value) { prefs.putStringSet("backup_buckets", value) }

    /**
     * Geraeteordner, die im Zeitstrahl NICHT erscheinen sollen. Gespeichert
     * wird der Ausschluss, nicht die Auswahl: so bleibt „alles sichtbar" der
     * Standard, auch wenn spaeter ein neuer Ordner auf dem Geraet auftaucht
     * — neue Aufnahmen sollen ja zu sehen sein, nicht stillschweigend fehlen.
     */
    var timelineHiddenBuckets: Set<String>
        get() = prefs.getStringSet("timeline_hidden_buckets", emptySet())
        set(value) { prefs.putStringSet("timeline_hidden_buckets", value) }

    /**
     * Desktop: Ordner auf diesem PC, deren Fotos im Zeitstrahl erscheinen
     * (absolute Pfade). Leer ist der Standard — anders als am Handy gibt es
     * keinen Kameraordner, und ein Bilderordner am PC ist oft das ganze
     * Archiv oder ein Spiegel der NAS. Wer einen Ordner sehen will, waehlt
     * ihn aus (Vorgabe, 04.10.2026).
     */
    var lokaleOrdner: Set<String>
        get() = prefs.getStringSet("local_folders", emptySet())
        set(value) { prefs.putStringSet("local_folders", value) }

    /** Letzter Backup-Lauf (ms, 0 = nie) + Zaehler hochgeladener Dateien. */
    var backupLastRun: Long
        get() = prefs.getLong("backup_last_run", 0L)
        set(value) { prefs.putLong("backup_last_run", value) }
    var backupUploaded: Long
        get() = prefs.getLong("backup_uploaded", 0L)
        set(value) { prefs.putLong("backup_uploaded", value) }

    /** Angemeldeter Nutzername — Quelle fuers Personenkuerzel im Dateinamen,
     *  auch im Hintergrund-Worker ohne laufende Sitzung. */
    var lastUser: String?
        get() = prefs.getString("last_user", null)
        set(value) { prefs.putString("last_user", value) }

    /** Gewaehltes Theme (MpdThemeId.name); null = Default (Kacheln, wie Web). */
    var themeName: String?
        get() = prefs.getString("theme", null)
        set(value) { prefs.putString("theme", value) }

    // -- Bearer-Token (Server seit 01.09.2026; gleicher Session-Token,
    //    zweiter Transportweg — Header gewinnt serverseitig vor Cookie) ----

    fun saveBearer(token: String, expiresAt: Long) {
        prefs.putString("bearer_token", token)
        prefs.putLong("bearer_expires_at", expiresAt)
    }

    /** Gueltiger Bearer-Token oder null (alter Server liefert keinen). */
    fun bearerToken(): String? {
        val token = prefs.getString("bearer_token", null) ?: return null
        if (prefs.getLong("bearer_expires_at", 0L) <= System.currentTimeMillis()) return null
        return token
    }

    /** Farbwelt des Erinnerungen-Schirms ("auto" = folgt dem App-Theme). */
    var memoriesTheme: String
        get() = prefs.getString("memories_theme", "auto") ?: "auto"
        set(value) { prefs.putString("memories_theme", value) }

    // -- Benachrichtigungen (/api/notify) -----------------------------------

    /** Stabile App-Geraete-ID fuer die Notify-Registrierung (einmal erzeugt). */
    val notifyDeviceId: String
        get() = prefs.getString("notify_device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            prefs.putString("notify_device_id", id)
            id
        }

    /** Timeline-Look: "dunkel" (Default), "hell", "auto" (folgt App-Theme). */
    var timelineTheme: String
        get() = prefs.getString("timeline_theme", "dunkel") ?: "dunkel"
        set(value) { prefs.putString("timeline_theme", value) }

    /** Zuletzt gewaehlter Start-Tab (0 = Alben, 1 = Zeitstrahl) — die App
     *  startet dort, wo man zuletzt war („MPD im Zeitstrahl
     *  starten", 03.09.). */
    /** App-Sprache („de"/„en"), null = Systemsprache. Nur der Rueckfall
     *  fuer Android < 13; ab 13 verwaltet das System die Pro-App-Sprache
     *  (AppLanguage.kt). */
    var appLanguage: String?
        get() = prefs.getString("app_language", null)
        set(value) { prefs.putString("app_language", value) }

    /** Aufzeichnungsstufe fuer OwnTracks: „sparsam" | „normal" | „genau"
     *  (08.09.2026). Geht in den Setup-Link neuer Geraete und in
     *  den Nachsende-Knopf. */
    var trailsRecordingLevel: String
        get() = prefs.getString("trails_recording_level", "normal") ?: "normal"
        set(value) { prefs.putString("trails_recording_level", value) }

    var homeTab: Int
        get() = prefs.getInt("home_tab", 0)
        set(value) { prefs.putInt("home_tab", value) }

    /** Hoechste bereits ANGEZEIGTE Notify-id — Dedupe, falls das Ack
     *  nach dem Anzeigen scheitert (Server wuerde erneut liefern). */
    var notifyLastShownId: Long
        get() = prefs.getLong("notify_last_shown_id", 0L)
        set(value) { prefs.putLong("notify_last_shown_id", value) }

    /**
     * Hoechste Notify-id, die der Vordergrund-Banner schon gesehen hat —
     * getrennt von [notifyLastShownId], weil den nur der NotifyWorker
     * fortschreibt. Der bricht ab, wenn Android der App keine
     * Benachrichtigungen erlaubt; dann blieb der Zaehler stehen, und der
     * Banner zeigte bei JEDEM Start und jeder Rueckkehr dieselbe alte
     * Meldung (Vorgabe, 11.10.2026: „immer wenn mpd gestartet oder man war
     * kurz in anderer App … kam wieder die Meldung zu Kies").
     */
    var notifyBannerSeenId: Long
        get() = prefs.getLong("notify_banner_seen_id", 0L)
        set(value) { prefs.putLong("notify_banner_seen_id", value) }

    // -- Gedaechtnis fuer eigene Teilen-/Beisteuern-Links -------------------
    //
    // Der Server liefert den Klartext-Token NUR in der Antwort auf `create`
    // und speichert selbst bloss dessen Hash. Ohne dieses Gedaechtnis kann
    // man an einem bestehenden Link genau eines tun: ihn widerrufen — den
    // QR-Code oder den Link selbst bekommt man nie wieder zu sehen.
    //
    // Bewusste Abweichung vom Server, am 05.09.2026 abgewogen:
    // Der Token liegt hier in app-privaten Einstellungen (andere Apps kommen
    // nicht heran; ein gerootetes Geraet oder ein Geraete-Backup schon). Am
    // Server aendert das nichts, der kennt weiterhin nur den Hash — wird ER
    // kompromittiert, bleiben die Links unrekonstruierbar. Und es ist
    // derselbe Token, der ohnehin per WhatsApp verschickt wird.
    //
    // Es ist ein Gedaechtnis DIESES Geraets, keine Synchronisation: was am
    // Handy angelegt wurde, kennt das Tablet nicht.

    private val tokenSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun tokenMap(key: String): MutableMap<String, String> {
        val roh = prefs.getString(key, null) ?: return mutableMapOf()
        return runCatching {
            kotlinx.serialization.json.Json
                .decodeFromString(tokenSerializer, roh).toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun saveTokenMap(key: String, map: Map<String, String>) {
        prefs.putString(key, kotlinx.serialization.json.Json.encodeToString(tokenSerializer, map))
    }

    /** Merkt den Klartext-Token zu einem Link. [hash] ist der `token_hash`
     *  aus der Liste, ueber den beides wieder zusammenfindet. */
    fun rememberToken(art: LinkArt, hash: String, token: String) {
        if (hash.isBlank() || token.isBlank()) return
        val map = tokenMap(art.key)
        map[hash] = token
        saveTokenMap(art.key, map)
    }

    /** Klartext-Token zu einem Link, oder null wenn dieses Geraet ihn nicht
     *  angelegt hat (oder er vergessen wurde). */
    fun tokenFor(art: LinkArt, hash: String): String? = tokenMap(art.key)[hash]

    /** Nach Widerruf: der Token hat hier nichts mehr zu suchen. */
    fun forgetToken(art: LinkArt, hash: String) {
        val map = tokenMap(art.key)
        if (map.remove(hash) != null) saveTokenMap(art.key, map)
    }

    /**
     * Wirft alles weg, was nicht mehr in [lebendeHashes] steht — abgelaufene
     * oder anderswo widerrufene Links. Sonst waechst der Speicher still, und
     * es lagern Token zu Links, die es gar nicht mehr gibt.
     */
    fun pruneTokens(art: LinkArt, lebendeHashes: Collection<String>) {
        val map = tokenMap(art.key)
        val vorher = map.size
        map.keys.retainAll(lebendeHashes.toSet())
        if (map.size != vorher) saveTokenMap(art.key, map)
    }

    enum class LinkArt(val key: String) {
        SHARE("known_share_tokens"),
        CONTRIB("known_contrib_tokens"),
    }
}

/**
 * Schluessel-Wert-Ablage, hinter der die Plattform steckt. Bewusst so schmal
 * wie SharedPreferences, damit die Android-Fassung eine reine Weiterleitung
 * ist und die Schluessel bestehender Installationen weitergelten.
 */
interface Ablage {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putStringSet(key: String, value: Set<String>)
    fun remove(key: String)
}

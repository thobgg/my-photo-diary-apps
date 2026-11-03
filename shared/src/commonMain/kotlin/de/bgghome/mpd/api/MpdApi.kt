package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import de.bgghome.mpd.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.DateFormat
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import de.bgghome.mpd.state.Texte

// ---------------------------------------------------------------------------
// Datentypen der Endpunkte (backend/main.py, Stand 01.09.2026)
// ---------------------------------------------------------------------------

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    /** Seit 01.09.2026: Server liefert dann zusaetzlich token + expires_in.
     *  Aeltere Server ignorieren das Feld einfach — Cookie-Fallback. */
    @SerialName("want_token") val wantToken: Boolean = true,
)

@Serializable
data class LoginResponse(
    val ok: Boolean = false,
    val token: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

/** meta.thumbnail_crop — normalisierter Cover-Ausschnitt (0..1), wie im
 *  Web (_toNormalized): x/y = linke obere Ecke, w/h = Kantenlaengen. */
@Serializable
data class ThumbCrop(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
)

@Serializable
data class AlbumSummary(
    val name: String,
    val space: String,
    val title: String = "",
    val year: Int = 0,
    val thumbnail: String = "",
    @SerialName("thumbnail_crop") val thumbnailCrop: ThumbCrop? = null,
    @SerialName("photo_count") val photoCount: Int = 0,
    @SerialName("video_count") val videoCount: Int = 0,
)

@Serializable
data class AlbumsResponse(val albums: List<AlbumSummary> = emptyList())

@Serializable
data class Thumbnails(
    val sm: String = "",
    val m: String = "",
    val xl: String = "",
    /** 4096 px — liefert der Server erst seit 01.09.2026 (additiv); leer = alt. */
    val full: String = "",
)

@Serializable
data class Resolution(val width: Int = 0, val height: Int = 0)

@Serializable
data class MapPin(val lat: Double = 0.0, val lon: Double = 0.0, val label: String? = null)

/**
 * Ein PDX-Element. Feldbelegung je nach type (photo, video, text, separator,
 * link, audio, document, map, tour) — Referenz ist frontend/js/album-render.js
 * im MPD-Repo. Unbekannte Typen werden wie im Web still uebersprungen.
 */
@Serializable
data class PdxElement(
    val id: String = "",
    val type: String = "",
    val file: String? = null,
    val text: String? = null,
    /** Text: default/info/quote/note/heading · Separator: line/bold/dashed/dots/space */
    val style: String? = null,
    /** Zitat: Quellenangabe (PDX v1.5) · Tour: Herkunft (komoot …), informativ. */
    val source: String? = null,
    val label: String? = null,
    val title: String? = null,
    val url: String? = null,
    val note: String? = null,
    val locked: Boolean = false,
    val resolution: Resolution? = null,
    val blurhash: String? = null,
    /** Relative URLs (/api/thumbnail/…), vom Server bereits URL-codiert. */
    val thumbnails: Thumbnails? = null,
    /** Relative Download-URL des Originals. */
    val original: String? = null,
    @SerialName("document_url") val documentUrl: String? = null,
    @SerialName("source_album") val sourceAlbum: String? = null,
    /**
     * Darf MPD diese Datei ueberschreiben? Nur die FORMATfrage — ob
     * dieses Konto es darf, steht in `rights`/`read_only`, und der Stift
     * braucht beides (Server 3.0.283, docs/API_CHANGES.md 07.09.2026).
     *
     * Vorgabe true: ein Server ohne dieses Feld kennt auch die neuen
     * Formate nicht, dort ist jedes gelistete Foto bearbeitbar. Falsch
     * herum waere der Stift bei alten Staenden ueberall verschwunden.
     *
     * false heisst nie „kaputt", nur „anzeigen ja, ueberschreiben nein".
     */
    @SerialName("editable_format") val editableFormat: Boolean = true,
    // map-Element
    val pins: List<MapPin> = emptyList(),
    /** Kartengrundlage: "osm" (CARTO Voyager) oder "topo" (OpenTopoMap). */
    val layer: String? = null,
    // tour-Element
    @SerialName("type_hint") val typeHint: String? = null,
    @SerialName("map_layer") val mapLayer: String? = null,
    @SerialName("show_elevation") val showElevation: Boolean = true,
    val error: String? = null,
) {
    /** Seitenverhaeltnis wie im Web: unbekannt → 1.5. */
    val aspectRatio: Float
        get() = resolution?.takeIf { it.width > 0 && it.height > 0 }
            ?.let { it.width.toFloat() / it.height } ?: 1.5f
}

@Serializable
data class AlbumMeta(
    val title: String = "",
    val year: Int = 0,
    @SerialName("show_locked") val showLocked: Boolean = true,
)

@Serializable
data class AlbumContent(
    val meta: AlbumMeta = AlbumMeta(),
    val elements: List<PdxElement> = emptyList(),
    /** Space des Nutzers nur lesbar? Default true = im Zweifel nicht schreiben. */
    @SerialName("read_only") val readOnly: Boolean = true,
)

/**
 * Album typisiert PLUS rohes JSON. Beim Speichern wird das PDX unveraendert
 * durchgereicht bis auf die editierten Felder — die typisierten Modelle
 * verwerfen unbekannte Felder (ignoreUnknownKeys), deshalb bleibt das
 * Original-JSON parallel erhalten und ist die Quelle fuer den Update-Body.
 */
data class AlbumEnvelope(val content: AlbumContent, val raw: JsonObject)

/** Antwort von POST …/import-file (Vertrag 08.09.2026). */
data class ImportResult(
    /** Bytegleich lag es schon im Ziel; die Quelle ging in den Papierkorb. */
    val duplicate: Boolean = false,
    /** Gleicher Name, anderer Inhalt: Originalname, im Ziel als [filename]. */
    val renamedFrom: String? = null,
    val filename: String? = null,
    /** Quelle war der Zielordner selbst: nichts verschoben, nur das
     *  Element angehaengt (Server c0e193b, nach dem Selbst-Import-Vorfall
     *  vom 08.09.2026, bei dem die Datei im Papierkorb landete). */
    val adopted: Boolean = false,
)

/** GET /api/albums/without-json — Ordner mit Medien, aber ohne Album. */
@Serializable
data class FolderCandidate(
    @SerialName("folder_name") val folderName: String,
    val space: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
    @SerialName("first_photo") val firstPhoto: String? = null,
)

@Serializable
data class FoldersResponse(val folders: List<FolderCandidate> = emptyList())

/** GET /api/album/files — Dateiliste eines Ordners (Deckblatt-Wahl). */
@Serializable
data class AlbumFileEntry(val filename: String, val thumbnail: String = "")

@Serializable
data class AlbumFilesResponse(val files: List<AlbumFileEntry> = emptyList())

/** GET /api/admin/users (routers/users_admin.py) — ohne pw_hash. */
@Serializable
data class AdminUser(
    val username: String,
    val role: String = "viewer",
    @SerialName("personal_path") val personalPath: String = "",
    @SerialName("is_demo") val isDemo: Boolean = false,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("has_password") val hasPassword: Boolean = true,
)

@Serializable
data class AdminUsersResponse(
    /** Login-Name des Aufrufers — der darf sich nicht selbst loeschen. */
    val self: String = "",
    val users: List<AdminUser> = emptyList(),
)

/** GET/PUT /api/settings/me — persoenliche Einstellungen (03.09.2026). */
@Serializable
data class UserSettingsData(
    /** "shared" (Familienalben, Default) oder "personal" (eigene Alben). */
    @SerialName("memories_scope") val memoriesScope: String = "shared",
)

/** GET/POST /api/settings — globale Schalter (routers/settings.py). */
@Serializable
data class AppSettingsData(
    @SerialName("share_enabled") val shareEnabled: Boolean = true,
    @SerialName("demo_enabled") val demoEnabled: Boolean = true,
    @SerialName("memories_push_enabled") val memoriesPushEnabled: Boolean = true,
    @SerialName("companion_provider") val companionProvider: String = "anthropic",
    /** Nur fuer Admins geliefert: maskierter Key ("sk-…xy12") oder "". */
    @SerialName("companion_api_key_hint") val companionApiKeyHint: String? = null,
)

@Serializable
data class WhoAmI(
    val user: String,
    val role: String,
    @SerialName("is_demo") val isDemo: Boolean = false,
    /**
     * Freigeschaltete Plus-Module. `null` = das Feld fehlt (Server vor dem
     * 13.07.2026) → alles frei; `[]` = KEINS frei. Bis 19.09.2026 stand hier
     * `emptyList()` als Vorgabe, damit waren beide Faelle dasselbe — und ein
     * reiner Core, der ehrlich `[]` meldet, zeigte alle Module ohne Schloss.
     * Das Web liest es genauso (`Array.isArray(mods) ? mods : null`).
     */
    val modules: List<String>? = null,
    // Steht das Flag, antwortet jede API ausser whoami/logout/change-password
    // mit 403. Aeltere Server kennen das Feld nicht -> Vorgabe false.
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    /**
     * Was dieses Konto darf, je Bestand plus „instance" — als NAMEN
     * (see, contribute, curate, edit, share, contrib_link …). Seit
     * Server 3.0.272.
     *
     * Ein Recht, das nicht in der Liste steht, ist nicht erteilt. Nie auf
     * die Anwesenheit eines unbekannten Namens pruefen — neue Rechte
     * tauchen einfach auf, weggefallene verschwinden, und der Vertrag
     * bricht daran nicht.
     *
     * NUR FUER DIE OBERFLAECHE: Durchgesetzt wird an jedem Endpunkt
     * einzeln. Ein hier fehlendes Recht schuetzt nichts — es verhindert
     * nur den vergeblichen Griff.
     *
     * Fehlt das Feld (aelterer Server), ist die Karte leer; dann faellt
     * die App auf ihre alten Annahmen zurueck.
     */
    val rights: Map<String, List<String>> = emptyMap(),
    /**
     * Oeffentliche Adresse des Servers (`MPD_BASE_URL`), ohne Schraegstrich
     * am Ende — fuer Links, die BEI ANDEREN aufgehen muessen: QR-Codes,
     * Weitergeben, Kopieren.
     *
     * **Fehlt, wenn der Betreiber sie nicht konfiguriert hat** (seit
     * 05.09.2026 gibt es dafuer keinen Vorgabewert mehr — der zeigte vorher
     * auf einen fremden Server). Dann faellt die App auf die eingetragene
     * Adresse zurueck, so wie vorher.
     */
    @SerialName("base_url") val baseUrl: String? = null,
)

@Serializable
data class ChangePasswordRequest(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

// EXIF-Antwort von GET /api/photo/…/exif (extract_exif in routers/media.py)
@Serializable
data class ExifCamera(val make: String? = null, val model: String? = null)

@Serializable
data class ExifSettings(
    @SerialName("focal_length") val focalLength: String? = null,
    val aperture: String? = null,
    @SerialName("shutter_speed") val shutterSpeed: String? = null,
    val iso: Int? = null,
)

@Serializable
data class ExifGps(val lat: Double? = null, val lon: Double? = null, val altitude: Int? = null)

@Serializable
data class ExifData(
    val datetime: String? = null,
    val camera: ExifCamera? = null,
    val lens: String? = null,
    val settings: ExifSettings? = null,
    val gps: ExifGps? = null,
    val description: String? = null,
    val error: String? = null,
)

/** Normierter Zuschnitt 0..1 — bezogen aufs Bild NACH Drehung/Spiegelung/
 *  Geraderichten (Reihenfolge in core/photo_edit.py). */
@Serializable
data class CropBox(val x: Float, val y: Float, val w: Float, val h: Float)

/** Body von POST /api/photo/…/edit (EditRequest in routers/photo_edit.py). */
@Serializable
data class PhotoEditParams(
    val rotation: Int = 0,
    val flip: Boolean = false,
    @SerialName("flip_v") val flipV: Boolean = false,
    val straighten: Float = 0f,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val crop: CropBox? = null,
) {
    val isNoOp: Boolean
        get() = rotation == 0 && !flip && !flipV && straighten == 0f &&
            brightness == 0 && contrast == 0 && saturation == 0 && crop == null
}

// GET /api/timeline (docs/API_CHANGES.md, 01.09.2026)
@Serializable
data class TimelineItem(
    val date: String = "",
    @SerialName("date_source") val dateSource: String = "",
    val space: String = "",
    val album: String = "",
    val file: String = "",
    val type: String = "photo",
    /** In irgendeinem album.json referenziert? false = unkuratierte Ordnerdatei. */
    val referenced: Boolean = true,
    /**
     * Ordnerart des Servers (core/timeline_index.py, seit 08.09.2026):
     *
     *   "album"     — der Ordner hat eine album.json
     *   "durchlauf" — nackte Jahreszahl ohne album.json, der Sammelordner
     *   "folder"    — weder noch: ein kopierter Ordner, der auf
     *                 „Album hinzufuegen" wartet
     *
     * Wichtig fuer den Durchlauf-Zaehler: die dritte Art zaehlt NICHT
     * mit. Server-Kommentar dazu: „die dritte Art ist ein fertiges
     * Album, kein Rest" — im Web erscheint so ein Ordner in der
     * Uebersicht als Album, seine Dateien sind also keine offene Arbeit.
     *
     * Voreinstellung "album" wie beim Server (routers/timeline.py:119):
     * ein alter Server ohne das Feld soll nichts in den Zaehler spuelen.
     */
    val folder: String = "album",
    /** true = jede Referenz der Datei ist gesperrt (Anzeige entscheidet die App). */
    val locked: Boolean = false,
    val resolution: Resolution? = null,
    val thumbnails: Thumbnails? = null,
    val original: String? = null,
    /** Formatfrage fuer den Stift — siehe PdxElement.editableFormat. */
    @SerialName("editable_format") val editableFormat: Boolean = true,
    /** Sofort-Platzhalter (Server 2.0.178); Videos haben keinen. */
    val blurhash: String? = null,
    /**
     * Nur fuer Aufnahmen, die (auch) auf dem Geraet liegen: die
     * content://-Adresse. Gesetzt heisst „noch nicht auf der NAS" — was
     * gesichert ist, kommt als Server-Eintrag in den Strom, nicht doppelt.
     * Kein API-Feld, der Server kennt es nicht.
     */
    @kotlinx.serialization.Transient val localUri: String? = null,
    /** "i"/"v" + MediaStore-id, Schluessel des Backup-Ledgers. */
    @kotlinx.serialization.Transient val localKey: String = "",
    /** Dateigroesse in Bytes — nur fuer Geraete-Aufnahmen (MediaStore.SIZE),
     *  fuer „Speicherplatz freigeben" (08.09.2026). 0 = unbekannt. */
    @kotlinx.serialization.Transient val size: Long = 0,
)

@Serializable
data class TimelineResponse(
    val items: List<TimelineItem> = emptyList(),
    val count: Int = 0,
    /** Treffer NACH den Filtern, VOR dem Cursor — die Zahl fuer Zaehler. */
    val total: Int = 0,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class UnassignedFile(val file: String, val type: String = "photo")

@Serializable
data class TourListEntry(
    val filename: String,
    val date: String = "",
    val label: String = "",
    val sport: String = "",
    val dist: Double? = null,
    val duration: String? = null,
    @SerialName("hr_avg") val hrAvg: Int? = null,
    @SerialName("hr_max") val hrMax: Int? = null,
    @SerialName("size_kb") val sizeKb: Double? = null,
    /** Ordner, in dem die GPX liegt (Jahr, Album oder alter Sammelordner
     *  `tours`), seit Server 3.0.488. Der Dateiname allein ist nicht mehr
     *  eindeutig — Schluessel ist [key]. Leer = alter Server. */
    val folder: String = "",
    /** `durchlauf` | `album` | `ordner` | `tours` (alter Sammelordner). */
    val kind: String = "",
    val title: String? = null,
    val points: Int? = null,
    /** Alben, deren Tour-Element auf genau diese Datei zeigt. Leer = offen;
     *  dann (und nur dann) darf der Admin sie in den Papierkorb legen. */
    @SerialName("used_in") val usedIn: List<TourUse> = emptyList(),
    /** null oder Grund: `unlesbar` | `punkte` | `strecke` | `dauer`. */
    val stub: String? = null,
    /** Erster Zeitstempel der Spur (ISO mit Zone) — fuer „Doppelt?". */
    val start: String? = null,
    /** Dauer in Sekunden aus den Trackpunkten. */
    @SerialName("duration_s") val durationS: Long? = null,
) {
    val key: String get() = "$folder/$filename"
}

@Serializable
data class TourUse(val album: String, val title: String? = null) {
    val label: String get() = title?.takeIf { it.isNotBlank() } ?: album
}

/** Antwort von POST /api/tours/place: [file] kann vom Namen abweichen
 *  (`_1`), [sourceAlbum] gesetzt heisst „liegt in einem anderen Album, das
 *  Element muss darauf zeigen". */
data class TourPlaced(val file: String, val sourceAlbum: String?, val action: String?)

@Serializable
data class ToursResponse(val tours: List<TourListEntry> = emptyList())

@Serializable
data class GpxFilesResponse(val files: List<String> = emptyList())

@Serializable
data class BackupEntry(val filename: String, val size: Long = 0, val created: String = "")

@Serializable
data class BackupsResponse(val backups: List<BackupEntry> = emptyList())

@Serializable
data class UnassignedResponse(val unassigned: List<UnassignedFile> = emptyList())

/**
 * HTTP-Fehler mit dem "detail"-Text des Backends (lokalisierte Meldung).
 *
 * `code` ist der HTTP-Status, `errorCode` das maschinenlesbare `code`-Feld
 * aus dem Fehlerkoerper — bisher nur `"must_change_password"` (403), siehe
 * API_CHANGES.md vom 04.09.2026. Die beiden heissen im Backend gleich, hier
 * bewusst nicht: sonst verwechselt man 403 mit dem Grund fuer die 403.
 */
class MpdHttpException(
    val code: Int,
    val detail: String,
    val errorCode: String? = null,
) : IOException(detail) {
    /**
     * Die Route gibt es auf diesem Server gar nicht: FastAPIs eigene 404
     * mit dem nackten englischen „Not Found". Eigene 404 des Servers tragen
     * immer einen uebersetzten Satz. Passiert, wenn ein Plus-Modul nicht
     * installiert ist (reiner Core, 19.09.2026) — dann ist es gesperrt,
     * nicht kaputt.
     */
    val routeMissing: Boolean get() = code == 404 && detail == "Not Found"
}

/** Plus-Module mit eigenem Router (Server: core/module_keys.py MODULE_KEYS).
 *  memories gehoert seit Server 20.09.2026 zum Kern. */
internal val PLUS_MODULE_KEYS = setOf("tours", "trails", "search", "stats")

/**
 * Zu welchem Plus-Modul gehoert dieser Pfad? Das Segment nach `api`, ohne
 * Anhang (`memories-thumb` → memories); ein Unterpfad vor `/api` (Reverse-
 * Proxy) stoert nicht. `null` fuer alles, was zum Kern gehoert.
 */
internal fun moduleKeyForPath(encodedPath: String): String? {
    val segs = encodedPath.split('/').filter { it.isNotEmpty() }
    val i = segs.indexOf("api")
    if (i < 0 || i + 1 >= segs.size) return null
    return segs[i + 1].substringBefore('-').takeIf { it in PLUS_MODULE_KEYS }
}

/** Backend-Kennung fuer „dieses Konto muss erst sein Passwort wechseln". */
const val ERR_MUST_CHANGE_PASSWORD = "must_change_password"

/** Anzeige-Daten fuer die Vertrauensabfrage beim ersten Kontakt. */
data class CertInfo(
    val sha256: String,          // Hex ohne Trennzeichen, fuer den Vergleich
    val fingerprintDisplay: String, // AB:CD:… fuer die Anzeige
    val subject: String,
    val validUntil: String,
)

/**
 * `<baseUrl>/api/<segments…>?<query>` — der eine Adressbauer der API.
 * Jedes Segment wird einzeln kodiert (Schraegstrich, Umlaut, Raute im
 * Albumnamen bleiben ein Segment), ein Unterpfad in der Basisadresse
 * (Reverse-Proxy) bleibt erhalten, Query-Werte mit null fallen weg.
 * Paketweit sichtbar, damit der Test ihn ohne Android erreicht.
 */
internal fun apiUrl(
    baseUrl: String,
    segments: List<String>,
    query: Map<String, String?> = emptyMap(),
): HttpUrl =
    (baseUrl.toHttpUrlOrNull() ?: throw IOException(Texte.t(Res.string.api_err_invalid_address)))
        .newBuilder()
        .addPathSegment("api")
        .apply { segments.forEach { addPathSegment(it) } }
        .apply { query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }
        .build()

// ---------------------------------------------------------------------------
// Client
// ---------------------------------------------------------------------------

/**
 * Duenner Client um die MPD-REST-API. Fuehrt den mpd_session-Cookie
 * (persistent ueber SettingsStore) und akzeptiert neben den System-CAs genau
 * ein vom Nutzer ausdruecklich bestaetigtes Zertifikat (TOFU) — kein
 * pauschales Abschalten der Pruefung.
 */
class MpdApi(private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Wird gerufen, sobald irgendein Aufruf mit `code: "must_change_password"`
     * abgelehnt wird. Das ViewModel haengt sich hier ein und schaltet auf den
     * Wechsel-Bildschirm — sonst saehe der Nutzer nur eine 403-Meldung, und
     * zwar bei jeder einzelnen Aktion.
     */
    var onMustChangePassword: (() -> Unit)? = null

    /**
     * Wird gerufen, wenn ein Plus-Modul-Endpunkt auf diesem Server fehlt
     * ([MpdHttpException.routeMissing]) — mit dem Modul-Key. Rueckfall fuer
     * den Fall, dass whoami.modules nicht stimmt: das ViewModel sperrt das
     * Modul dann fuer den Rest der Sitzung.
     */
    var onModuleMissing: ((String) -> Unit)? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val session = cookies.firstOrNull { it.name == "mpd_session" } ?: return
            if (session.expiresAt <= System.currentTimeMillis()) {
                settings.clearSession() // delete_cookie beim Logout
            } else {
                settings.saveSession(session.value, session.expiresAt)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val (value, expiresAt) = settings.session() ?: return emptyList()
            if (expiresAt <= System.currentTimeMillis()) return emptyList()
            return listOf(
                Cookie.Builder()
                    .name("mpd_session").value(value)
                    .domain(url.host).path("/")
                    .build()
            )
        }
    }

    private val systemTrust: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    // Liest settings.trustedCertSha256 bei jeder Pruefung neu, damit ein
    // frisch bestaetigtes Zertifikat ohne Client-Neubau greift.
    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTrust.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTrust.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                if (sha256Hex(chain[0].encoded) != settings.trustedCertSha256) throw e
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust.acceptedIssuers
    }

    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // Verbindungsaufbau: 15 s statt der 10 s Voreinstellung — ueber
            // DDNS und Mobilfunk ist der erste Handshake gern langsamer als
            // im LAN. Lesen bleibt bei 10 s; wer laenger braucht, nimmt
            // patientClient oder longRunClient.
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            // Bearer-Header, sobald ein Token da ist — gewinnt serverseitig
            // vor dem Cookie; ohne Token traegt der CookieJar die Sitzung.
            .addInterceptor { chain ->
                // Die Sprache der APP mitschicken — der Server lokalisiert
                // seine Fehlertexte danach (de/en, i18n-Vertrag vom 03.09.).
                // Seit dem 08.09.2026 spricht die App selbst Deutsch und
                // Englisch (res/values, res/values-en); Locale.getDefault()
                // ist die App-Sprache, auch wenn sie ab Android 13 je App in
                // den Systemeinstellungen gesetzt wurde. Alles andere faellt
                // auf Deutsch zurueck, wie die App selbst.
                val lang = java.util.Locale.getDefault().language
                    .takeIf { it == "de" || it == "en" } ?: "de"
                val token = settings.bearerToken()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .header("Accept-Language", lang)
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request().newBuilder()
                        .header("Accept-Language", lang)
                        .build()
                }
                chain.proceed(request)
            }
            .sslSocketFactory(ssl.socketFactory, trustManager)
            // Selbstsignierte Zertifikate tragen selten den LAN-Hostnamen —
            // das bestaetigte Zertifikat gilt deshalb auch als Hostnachweis.
            .hostnameVerifier { host, sslSession ->
                defaultVerifier.verify(host, sslSession) || run {
                    val cert = sslSession.peerCertificates.firstOrNull() as? X509Certificate
                    cert != null && sha256Hex(cert.encoded) == settings.trustedCertSha256
                }
            }
            .build()
    }

    // -- Anfrage-Bausteine ----------------------------------------------------
    //
    // Bis 08.09.2026 stand an 48 Endpunkten derselbe Block
    // `baseUrl.toHttpUrlOrNull() ?: throw …` + newBuilder + addPathSegment,
    // und 17 weitere bauten die Adresse per String-Konkatenation
    // ("$baseUrl/api/…") — ohne Kodierung, ohne Pruefung. Zwei Regeln fuer
    // dieselbe Frage. Jetzt: EIN Adressbauer (apiUrl, mit Test) und vier
    // Kurzformen fuer die Methoden, die run()/fail() umschliessen. Ein neuer
    // Endpunkt kostet damit drei Zeilen.

    private val jsonType = "application/json".toMediaType()
    private fun jsonBody(obj: JsonObject) = obj.toString().toRequestBody(jsonType)
    private fun jsonBody(text: String) = text.toRequestBody(jsonType)
    private fun emptyBody() = ByteArray(0).toRequestBody()

    /** `/api/<segments…>?query` — jedes Segment einzeln kodiert. */
    private fun api(
        baseUrl: String, vararg segments: String, query: Map<String, String?> = emptyMap(),
    ): HttpUrl = apiUrl(baseUrl, segments.toList(), query)

    private fun get(url: HttpUrl, http: OkHttpClient = client): String =
        run(Request.Builder().url(url).build(), http)
    private fun post(url: HttpUrl, body: RequestBody = emptyBody(), http: OkHttpClient = client): String =
        run(Request.Builder().url(url).post(body).build(), http)
    private fun put(url: HttpUrl, body: RequestBody): String =
        run(Request.Builder().url(url).put(body).build())
    private fun patch(url: HttpUrl, body: RequestBody): String =
        run(Request.Builder().url(url).patch(body).build())
    private fun delete(url: HttpUrl, body: RequestBody? = null): String =
        run(Request.Builder().url(url).delete(body).build())

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        json.decodeFromString(serializer, text)

    // -- Endpunkte ----------------------------------------------------------

    suspend fun login(baseUrl: String, username: String, password: String): Unit =
        withContext(Dispatchers.IO) {
            val body = jsonBody(
                json.encodeToString(LoginRequest.serializer(), LoginRequest(username, password)),
            )
            val response = decode(LoginResponse.serializer(), post(api(baseUrl, "login"), body))
            response.token?.let { token ->
                settings.saveBearer(token, System.currentTimeMillis() + response.expiresIn * 1000)
            }
        }

    /**
     * Auth-Header fuer externe HTTP-Stacks (ExoPlayer): Bearer bevorzugt,
     * sonst der Sitzungs-Cookie.
     */
    fun authHeaders(): Map<String, String> {
        settings.bearerToken()?.let { return mapOf("Authorization" to "Bearer $it") }
        settings.session()?.let { (value, expiresAt) ->
            if (expiresAt > System.currentTimeMillis()) return mapOf("Cookie" to "mpd_session=$value")
        }
        return emptyMap()
    }

    suspend fun whoami(baseUrl: String): WhoAmI = withContext(Dispatchers.IO) {
        decode(WhoAmI.serializer(), get(api(baseUrl, "whoami")))
    }

    /**
     * Passwort wechseln. Steht das `must_change_password`-Flag, ist das einer
     * der wenigen erlaubten Aufrufe und loescht das Flag; sonst das normale
     * „Passwort aendern" aus den Einstellungen (jedem Konto offen).
     *
     * 401 altes Passwort falsch · 400 kuerzer als 12 Zeichen oder identisch
     * zum alten · 403 Demo-Konto · 429 Ratelimit (zaehlt auf die IP).
     */
    suspend fun changePassword(baseUrl: String, oldPassword: String, newPassword: String) =
        withContext(Dispatchers.IO) {
            val body = jsonBody(
                json.encodeToString(
                    ChangePasswordRequest.serializer(),
                    ChangePasswordRequest(oldPassword, newPassword),
                ),
            )
            post(api(baseUrl, "account", "change-password"), body)
            Unit
        }

    suspend fun albums(baseUrl: String): List<AlbumSummary> = withContext(Dispatchers.IO) {
        decode(AlbumsResponse.serializer(), get(api(baseUrl, "albums"))).albums
    }

    suspend fun album(baseUrl: String, space: String, name: String): AlbumEnvelope =
        withContext(Dispatchers.IO) {
            val raw = json.parseToJsonElement(get(api(baseUrl, "album", space, name))).jsonObject
            AlbumEnvelope(json.decodeFromJsonElement(AlbumContent.serializer(), raw), raw)
        }

    /**
     * POST /api/album/{space}/{album}/update — Body ist das ganze album-Objekt
     * (wie saveAlbumData in album-save.js). Der Server bereinigt die Elemente
     * selbst per Whitelist (angereicherte Felder wie thumbnails/original
     * fliegen dort raus) und setzt version + meta.modified.
     *
     * [baseModified] = meta.modified der Fassung, auf der die Aenderungen
     * beruhen. Wenn gesetzt, geht der Header X-MPD-Base-Modified mit —
     * der Server (Konfliktschutz, Auftrag 02.09.2026) antwortet 409, falls
     * das Album inzwischen anders modifiziert wurde. Ohne Header (oder auf
     * altem Server) gilt Last-Write-Wins wie im Web. Rueckgabe: Antworttext,
     * daraus liest der Aufrufer das neue modified.
     */
    suspend fun updateAlbum(
        baseUrl: String, space: String, name: String, body: JsonObject,
        baseModified: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(api(baseUrl, "album", space, name, "update"))
            .post(jsonBody(body))
            .apply { baseModified?.let { header("X-MPD-Base-Modified", it) } }
            .build()
        run(request)
    }

    /** GET …/backups — die .mpd_backups des Albums, neueste zuerst. */
    suspend fun listBackups(baseUrl: String, space: String, name: String): List<BackupEntry> =
        withContext(Dispatchers.IO) {
            decode(
                BackupsResponse.serializer(),
                get(api(baseUrl, "album", space, name, "backups")),
            ).backups
        }

    /** POST …/restore — Stand zurueckholen; der Server sichert vorher den
     *  aktuellen Stand (current_backup). */
    suspend fun restoreBackup(baseUrl: String, space: String, name: String, backupFilename: String) {
        withContext(Dispatchers.IO) {
            post(
                api(
                    baseUrl, "album", space, name, "restore",
                    query = mapOf("backup_filename" to backupFilename),
                ),
            )
        }
    }

    /** DELETE /api/album/… — loescht NUR album.json (Overlay-Modell); liefert
     *  den Backup-Dateinamen fuers Rueckgaengig. */
    suspend fun deleteAlbum(baseUrl: String, space: String, name: String): String? =
        withContext(Dispatchers.IO) {
            val text = delete(api(baseUrl, "album", space, name))
            json.parseToJsonElement(text).jsonObject["backup"]?.jsonPrimitive?.contentOrNull
        }

    /** POST …/undo-delete — geloeschtes Album aus dem Backup zurueckholen. */
    suspend fun undoDeleteAlbum(baseUrl: String, space: String, name: String, backupFilename: String) {
        withContext(Dispatchers.IO) {
            post(
                api(
                    baseUrl, "album", space, name, "undo-delete",
                    query = mapOf("backup_filename" to backupFilename),
                ),
            )
        }
    }

    /** Coil laedt Thumbnails ueber denselben Client (Cookie + Zertifikatslogik). */
    fun httpClient(): OkHttpClient = client

    // Zwei geduldigere Fassungen desselben Clients (gleicher Pool, gleiche
    // Cookies, gleiches Zertifikat) — bis 08.09.2026 baute jeder der neun
    // Aufrufer, der laenger warten musste, per newBuilder() seine eigene
    // Instanz je Aufruf. Wer 60 s braucht (Live-Scan, SMTP, Heatmap), nimmt
    // patientClient; wer Minuten braucht (Archiv, Downloads, Loeschen einer
    // Quelle), longRunClient.
    private val patientClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private val longRunClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(15, java.util.concurrent.TimeUnit.MINUTES).build()
    }

    /** Textdatei (z.B. GPX) mit Sitzungs-Cookie laden. */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        run(Request.Builder().url(url).build())
    }

    /**
     * Nur den ANFANG einer Textdatei holen, per HTTP-Range.
     *
     * Fuer die Beschriftung einer Textdatei im Album braucht die App deren
     * erste nicht-leere Zeile (16.09.2026: „Der Titel soll auch
     * nicht der Dateiname sein, nur Fallback, sondern erste Zeile txt oder
     * manuell"). Den ganzen Text dafuer zu laden widerspraeche der Regel,
     * erst beim Aufklappen zu holen — ein paar hundert Byte tun das nicht.
     *
     * Beantwortet der Server kein 206, kommt die ganze Datei; dann schneidet
     * der Aufrufer selbst. Das ist der Preis dafuer, ohne Vertragsaenderung
     * auszukommen — schoener waere die erste Zeile im Element, das waere
     * ein Abruf statt zehn bei einem Album voller Textdateien.
     */
    suspend fun fetchTextHead(url: String, bytes: Int = 512): String =
        withContext(Dispatchers.IO) {
            val text = run(
                Request.Builder().url(url)
                    .header("Range", "bytes=0-${bytes - 1}")
                    .build(),
            )
            if (text.length > bytes) text.take(bytes) else text
        }

    /**
     * Laedt eine Datei (das Foto-Original) in den App-Cache und liefert sie
     * zurueck. Seit dem Server-ETag (01.09.2026, sha1(pfad+mtime_ns) mit
     * Cache-Control: no-cache) wird ein vorhandener Cache-Treffer per
     * If-None-Match revalidiert — 304 kostet im LAN fast nichts, und nach
     * einem Foto-Edit kommt sofort die frische Datei. Ohne gemerkten ETag
     * (Altbestand) wird neu geladen.
     *
     * @param suffix Dateiendung mit Punkt, die die Cache-Datei tragen soll.
     *   Klingt nach Kosmetik, ist keine: Der Name aus dem SHA-256 hatte gar
     *   keine Endung, und `FileProvider.getType()` leitet den MIME-Typ genau
     *   daraus ab. Eine so weitergegebene Datei meldete sich der
     *   empfangenden App als `application/octet-stream` — manche glauben
     *   dem Typ im Intent, manche fragen den Resolver, und die zweiten
     *   bekamen ein Bild als Dokument angeboten (07.09.2026).
     */
    suspend fun downloadToCache(
        dir: java.io.File,
        url: String,
        suffix: String = "",
    ): java.io.File =
        withContext(Dispatchers.IO) {
            val name = de.bgghome.mpd.util.sha256Hex(url.toByteArray()) + suffix
            val target = java.io.File(dir, name)
            val etagFile = java.io.File(dir, "$name.etag")
            val cachedEtag = etagFile.takeIf { target.length() > 0 && it.isFile }
                ?.readText()?.takeIf { it.isNotBlank() }

            val requestBuilder = Request.Builder().url(url)
            cachedEtag?.let { requestBuilder.header("If-None-Match", it) }

            dir.mkdirs()
            val tmp = java.io.File(dir, "$name.part")
            // Ein Abbruch mitten im Download (Netz weg, Lightbox zu, Prozess
            // tot) liess bis 08.09.2026 die halbe .part-Datei liegen — bei
            // Originalen zweistellige Megabyte, und niemand raeumte sie je
            // weg. Jetzt: bei jedem Fehler loeschen, und ein Rest aus einem
            // frueheren Lauf wird beim Start ohnehin ueberschrieben.
            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code == 304) return@withContext target
                    // Kein fail(): der Koerper ist hier die Nutzlast (Datei-Stream),
                    // ihn zu lesen wuerde den Download verbrauchen.
                    if (!response.isSuccessful) {
                        throw MpdHttpException(response.code, "HTTP ${response.code}")
                    }
                    tmp.outputStream().use { out ->
                        response.body?.byteStream()?.copyTo(out)
                            ?: throw IOException(Texte.t(Res.string.api_err_empty_response))
                    }
                    response.header("ETag")?.let { etagFile.writeText(it) } ?: etagFile.delete()
                }
                if (!tmp.renameTo(target)) throw IOException(Texte.t(Res.string.api_err_cache_unwritable))
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            target
        }

    /** `/api/photo/{space}/{album}/{file}/<tail…>` */
    private fun photoUrl(
        baseUrl: String, space: String, album: String, file: String, vararg tail: String,
    ): HttpUrl = api(baseUrl, "photo", space, album, file, *tail)

    /** GET /api/photo/…/exif — kuratierte EXIF-Felder des Servers. */
    /** GET /api/companion/status → enabled? Fehlt die Route (Kern ohne
     *  Companion) oder scheitert sie, gilt aus. */
    suspend fun companionStatus(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            json.parseToJsonElement(get(api(baseUrl, "companion", "status")))
                .jsonObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
        }.getOrDefault(false)
    }

    /** POST /api/companion/suggest {text} → bis zu drei Formulierungen.
     *  503 = nicht konfiguriert, 413 = Text zu lang (MpdHttpException). */
    suspend fun companionSuggest(baseUrl: String, text: String): List<String> = withContext(Dispatchers.IO) {
        val body = jsonBody(JsonObject(mapOf("text" to JsonPrimitive(text))))
        val antwort = post(api(baseUrl, "companion", "suggest"), body)
        json.parseToJsonElement(antwort).jsonObject["suggestions"]?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.takeIf { t -> t.isNotBlank() } }
            .orEmpty()
    }

    suspend fun exif(baseUrl: String, space: String, album: String, file: String): ExifData =
        withContext(Dispatchers.IO) {
            decode(ExifData.serializer(), get(photoUrl(baseUrl, space, album, file, "exif")))
        }

    /** POST /api/photo/…/edit — destruktiv mit rollendem .bak-Backup.
     *  Liefert den Backup-Namen (Statuszeile wie im Web-Editor). */
    suspend fun editPhoto(
        baseUrl: String, space: String, album: String, file: String,
        params: PhotoEditParams,
    ): String? = withContext(Dispatchers.IO) {
        val body = jsonBody(json.encodeToString(PhotoEditParams.serializer(), params))
        val text = post(photoUrl(baseUrl, space, album, file, "edit"), body)
        runCatching {
            json.parseToJsonElement(text).jsonObject["backup"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    /** POST /api/photo/…/rename/preview — Zielname + Kollisionsflag (dry-run). */
    suspend fun renamePreview(
        baseUrl: String, space: String, album: String, file: String, newStem: String,
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val body = jsonBody("""{"new_stem":${JsonPrimitive(newStem)}}""")
        val obj = json.parseToJsonElement(
            post(photoUrl(baseUrl, space, album, file, "rename", "preview"), body),
        ).jsonObject
        (obj["final_name"]?.jsonPrimitive?.content ?: file) to
            (obj["collision"]?.jsonPrimitive?.content == "true")
    }

    /** POST /api/photo/…/rename — ISO-Umbenennen; liefert den neuen Namen. */
    suspend fun renamePhoto(
        baseUrl: String, space: String, album: String, file: String, newStem: String,
    ): String? = withContext(Dispatchers.IO) {
        val body = jsonBody("""{"new_stem":${JsonPrimitive(newStem)}}""")
        val obj = json.parseToJsonElement(
            post(photoUrl(baseUrl, space, album, file, "rename"), body),
        ).jsonObject
        obj["renamed"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "false" && it.isNotEmpty() }
    }

    /**
     * POST /api/photo/…/exif — Datum/GPS (und optional neuer Name) schreiben
     * (save_metadata: .bak + EXIF-Rewrite). Liefert den neuen Dateinamen,
     * wenn dabei umbenannt wurde, sonst null.
     */
    suspend fun saveExif(
        baseUrl: String, space: String, album: String, file: String,
        datetime: String?, lat: Double?, lon: Double?, alt: Double?, newStem: String?,
    ): String? = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            datetime?.let { put("datetime", JsonPrimitive(it)) }
            if (lat != null && lon != null) {
                put("gps", buildJsonObject {
                    put("lat", JsonPrimitive(lat))
                    put("lon", JsonPrimitive(lon))
                    alt?.let { put("alt", JsonPrimitive(it)) }
                })
            }
            newStem?.let { put("new_stem", JsonPrimitive(it)) }
        }
        val obj = json.parseToJsonElement(
            post(photoUrl(baseUrl, space, album, file, "exif"), jsonBody(payload)),
        ).jsonObject
        obj["renamed"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "false" && it.isNotEmpty() }
    }

    /** DELETE /api/photo/…/edit — Original aus dem .bak wiederherstellen. */
    suspend fun resetPhoto(baseUrl: String, space: String, album: String, file: String): Unit =
        withContext(Dispatchers.IO) {
            delete(photoUrl(baseUrl, space, album, file, "edit"))
            Unit
        }

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
    suspend fun durchlaufTotal(baseUrl: String, space: String): Int =
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

    suspend fun timeline(baseUrl: String, cursor: String?, limit: Int = 200): TimelineResponse =
        withContext(Dispatchers.IO) {
            decode(
                TimelineResponse.serializer(),
                get(api(baseUrl, "timeline", query = mapOf("limit" to limit.toString(), "cursor" to cursor))),
            )
        }

    /** GET …/unassigned-photos: Ordnerdateien, die noch in keinem Element
     *  referenziert sind — Futter fuer den Foto/Video-Picker. */
    suspend fun unassignedMedia(baseUrl: String, space: String, album: String): List<UnassignedFile> =
        unassignedFiles(baseUrl, space, album, "photos")

    /** GET …/unassigned-{photos|audio|documents} — gleiche Struktur,
     *  nur andere Endung. */
    suspend fun unassignedFiles(
        baseUrl: String, space: String, album: String, kind: String,
    ): List<UnassignedFile> = withContext(Dispatchers.IO) {
        decode(
            UnassignedResponse.serializer(),
            get(api(baseUrl, "album", space, album, "unassigned-$kind")),
        ).unassigned
    }

    /** GET /api/tours — globale Tourenliste (Modul „tours"; Demo → 403). */
    suspend fun tours(baseUrl: String): List<TourListEntry> = withContext(Dispatchers.IO) {
        decode(ToursResponse.serializer(), get(api(baseUrl, "tours"))).tours
    }

    /** GET /api/gpx-files/{space}/{album} — GPX-Dateien im Albumordner. */
    suspend fun gpxFiles(baseUrl: String, space: String, album: String): List<String> =
        withContext(Dispatchers.IO) {
            decode(GpxFilesResponse.serializer(), get(api(baseUrl, "gpx-files", space, album))).files
        }

    /** Adresse der GPX: mit Ordner (seit 3.0.488 eindeutig), sonst der alte
     *  Weg ueber den Namen allein. */
    fun tourFileUrl(baseUrl: String, tour: TourListEntry): String =
        (if (tour.folder.isNotEmpty()) api(baseUrl, "tours", "file", tour.folder, tour.filename)
        else api(baseUrl, "tours", tour.filename)).toString()

    /**
     * POST /api/tours/place — Tour in ein Album legen. Offen → verschoben;
     * liegt sie schon in einem Album → bleibt dort, [TourPlaced.sourceAlbum]
     * gesetzt. [append] = Server haengt das Tour-Element selbst ans Album
     * (so macht es die Tours-Seite des Webs); ohne baut der Aufrufer es.
     */
    suspend fun placeTour(
        baseUrl: String, tour: TourListEntry, album: String, space: String, append: Boolean,
    ): TourPlaced = withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("folder", JsonPrimitive(tour.folder))
            put("filename", JsonPrimitive(tour.filename))
            put("album", JsonPrimitive(album))
            put("space", JsonPrimitive(space))
            put("append", JsonPrimitive(append))
        })
        val o = json.parseToJsonElement(post(api(baseUrl, "tours", "place"), body)).jsonObject
        TourPlaced(
            file = o["file"]?.jsonPrimitive?.contentOrNull ?: tour.filename,
            sourceAlbum = o["source_album"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            action = o["action"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** DELETE /api/tours/file/{folder}/{filename} — in den Papierkorb, nur
     *  Admin. 409 (Text nennt die Alben), solange ein Album sie benutzt.
     *  Liefert, ob der Papierkorb gegriffen hat. */
    suspend fun deleteTour(baseUrl: String, tour: TourListEntry): Boolean =
        withContext(Dispatchers.IO) {
            val text = delete(api(baseUrl, "tours", "file", tour.folder, tour.filename))
            runCatching {
                json.parseToJsonElement(text).jsonObject["recycled"]?.jsonPrimitive?.content == "true"
            }.getOrDefault(false)
        }

    /** POST …/cover-from-source — referenziertes Foto als cover.<ext> in den
     *  eigenen Ordner kopieren; Antwort traegt den neuen Dateinamen. */
    suspend fun coverFromSource(
        baseUrl: String, space: String, album: String,
        sourceAlbum: String, file: String,
    ): String = withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("source_album", JsonPrimitive(sourceAlbum))
            put("file", JsonPrimitive(file))
        })
        val response = json.parseToJsonElement(
            post(api(baseUrl, "album", space, album, "cover-from-source"), body),
        ).jsonObject
        response["thumbnail"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException(Texte.t(Res.string.api_err_no_thumbnail))
    }

    /** GET /api/recycle-status/{space} — hat der Space einen #recycle-Ordner?
     *  null = nicht ermittelbar (Web setzt dann den neutralen Warnhinweis). */
    suspend fun recycleStatus(baseUrl: String, space: String): Boolean? =
        withContext(Dispatchers.IO) {
            runCatching {
                json.parseToJsonElement(get(api(baseUrl, "recycle-status", space)))
                    .jsonObject["recycled"]?.jsonPrimitive?.content == "true"
            }.getOrNull()
        }

    /**
     * DELETE /api/album/{space}/{album}/file — Datei, Thumbnails und
     * .bak-Versionen physisch loeschen (Web: deletePhotoFile).
     * Liefert was_thumbnail: die Datei war das Album-Cover.
     *
     * @param albumContext true = der Aufruf kommt aus einer Album-Ansicht,
     *   wo Loeschen der vorgesehene Weg ist (Album-Editor, Kachel-Menue,
     *   Lightbox AUS dem Album). Ohne das Feld schuetzt der Server jede
     *   Datei, die in einem Ordner mit album.json liegt — 403
     *   `album.delete_in_album_folder`. Zeitstrahl und Timeline-Lightbox
     *   setzen es deshalb NIE.
     *
     *   Heisst absichtlich nicht `from_album`: das gibt es in dieser API
     *   schon bei importFile, dort als Ordnername. Zwei Felder gleichen
     *   Namens mit verschiedener Bedeutung waeren eine Falle fuer den, der
     *   es in einem halben Jahr liest.
     *
     * Unabhaengig davon lehnt der Server mit 409
     * `album.delete_used_elsewhere` ab, wenn ein ANDERES Album per
     * source_album auf die Datei zeigt — das ist nicht ueberstimmbar.
     */
    /**
     * @property wasThumbnail Die Datei war das Deckblatt des Albums.
     * @property removedFromJson Der Server hat den Eintrag SELBST aus der
     *   album.json genommen und `meta.modified` neu gestempelt. Dann darf
     *   die App nicht noch einmal speichern — sie liefe mit dem Stempel von
     *   vor der Loeschung in den Konfliktschutz (Befund 12.09.2026).
     */
    data class DeleteResult(
        val wasThumbnail: Boolean,
        val removedFromJson: Boolean,
    )

    suspend fun deleteFile(
        baseUrl: String,
        space: String,
        album: String,
        filename: String,
        albumContext: Boolean = false,
    ): DeleteResult =
        withContext(Dispatchers.IO) {
            val payload = """{"filename":${JsonPrimitive(filename)},""" +
                """"album_context":$albumContext}"""
            val text = delete(api(baseUrl, "album", space, album, "file"), jsonBody(payload))
            fun flag(name: String) = runCatching {
                json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.content == "true"
            }.getOrDefault(false)
            DeleteResult(wasThumbnail = flag("was_thumbnail"), removedFromJson = flag("removed_from_json"))
        }

    suspend fun logout(baseUrl: String): Unit = withContext(Dispatchers.IO) {
        try {
            post(api(baseUrl, "logout"))
        } finally {
            settings.clearSession() // lokal immer abmelden, auch wenn der Server nicht erreichbar ist
        }
    }

    /**
     * PUT /api/album/{space}/{album}/file/{filename} — Kamera-Upload,
     * roher Body (kein Multipart). 409 = Datei existiert (Aufrufer haengt
     * z.B. "-2" an), 413 = ueber 2 GB, 415 = falsche Endung.
     */
    suspend fun uploadFile(
        baseUrl: String,
        space: String,
        album: String,
        filename: String,
        file: java.io.File,
    ): Unit = withContext(Dispatchers.IO) {
        val mime = when (filename.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
        put(api(baseUrl, "album", space, album, "file", filename), file.asRequestBody(mime.toMediaType()))
        Unit
    }

    /**
     * POST /api/album/{space}/{album}/import-file — Einsortieren: Datei
     * zieht physisch aus dem Durchlauf ins Album um (auch quer ueber
     * Spaces), Element wird atomar angehaengt. 409 = im Ziel vorhanden
     * oder noch im Quell-Album referenziert.
     */
    suspend fun importFile(
        baseUrl: String,
        targetSpace: String,
        targetAlbum: String,
        fromSpace: String,
        fromAlbum: String,
        filename: String,
    ): ImportResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("from_space", JsonPrimitive(fromSpace))
            put("from_album", JsonPrimitive(fromAlbum))
            put("filename", JsonPrimitive(filename))
        }
        // Seit 4ee1b4e (Server, 08.09.2026, API_CHANGES „Einsortieren:
        // Namenskollision ist kein 409 mehr"): liegt im Ziel schon eine
        // Datei gleichen Namens, antwortet der Server 200 — bytegleich mit
        // duplicate=true (Quelle in den Papierkorb), sonst renamed_from +
        // neuem filename. Der 409 „Quelle noch referenziert" bleibt.
        val raw = runCatching {
            json.parseToJsonElement(
                post(api(baseUrl, "album", targetSpace, targetAlbum, "import-file"), jsonBody(body)),
            ).jsonObject
        }.getOrNull()
        ImportResult(
            duplicate = raw?.get("duplicate")?.jsonPrimitive?.booleanOrNull == true,
            renamedFrom = raw?.get("renamed_from")?.jsonPrimitive?.contentOrNull,
            filename = raw?.get("filename")?.jsonPrimitive?.contentOrNull,
            adopted = raw?.get("adopted")?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** GET /api/albums/without-json — der Ordner-Scan kann bei vielen
     *  Ordnern dauern, darum geduldiger Timeout. */
    suspend fun albumsWithoutJson(baseUrl: String): FoldersResponse =
        withContext(Dispatchers.IO) {
            decode(FoldersResponse.serializer(), get(api(baseUrl, "albums", "without-json"), patientClient))
        }

    /** GET /api/album/files?space&folder — sortiert nach Aufnahmezeit. */
    suspend fun albumFiles(baseUrl: String, space: String, folder: String): AlbumFilesResponse =
        withContext(Dispatchers.IO) {
            decode(
                AlbumFilesResponse.serializer(),
                get(api(baseUrl, "album", "files", query = mapOf("space" to space, "folder" to folder))),
            )
        }

    /**
     * POST /api/album/create — Album fuer einen bestehenden Ordner.
     *
     * GEDULDIGER CLIENT (12.09.2026). Der Server baut dabei die album.json
     * aus dem GANZEN Ordner: jede Datei wird angefasst, die Aufnahmezeit
     * gelesen, danach sortiert. Bei einem gewachsenen Jahresordner ist der
     * Standard-Lesetimeout von 10 Sekunden dafuer zu knapp — die Anfrage
     * brach ab, waehrend der Server ruhig weiterarbeitete und fertig
     * anlegte. Die App sah nur den Netzfehler und zeigte wieder die
     * Deckblatt-Wahl.
     *
     * 12.09.2026: „ich hatte Deckblatt gewaehlt, wieder Auswahl
     * Deckblatt … dann mache ich Abbrechen, und irgendwann mal taucht das
     * neue Album doch auf." Genau das — der Abbruch war unsererseits.
     *
     * Der Ordner-SCAN (albumsWithoutJson) hatte den geduldigen Client
     * laengst, das Anlegen war schlicht vergessen worden.
     */
    suspend fun createAlbum(
        baseUrl: String,
        space: String,
        folderName: String,
        thumbnail: String,
    ): Unit =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("space", JsonPrimitive(space))
                put("folder_name", JsonPrimitive(folderName))
                put("thumbnail", JsonPrimitive(thumbnail))
            }
            post(api(baseUrl, "album", "create"), jsonBody(body), patientClient)
            Unit
        }

    /** POST /api/album/create-empty — legt Ordner + minimales album.json
     *  an (PDX v1.4, leere Alben sind formatgueltig). */
    suspend fun createEmptyAlbum(baseUrl: String, space: String, folderName: String): Unit =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("space", JsonPrimitive(space))
                put("folder_name", JsonPrimitive(folderName))
            }
            post(api(baseUrl, "album", "create-empty"), jsonBody(body))
            Unit
        }

    // -- Benachrichtigungen (/api/notify, Ersatz fuer ntfy) -----------------

    /** PUT /api/notify/devices/{id} — idempotent, bei jedem Lauf erlaubt. */
    suspend fun registerNotifyDevice(baseUrl: String, deviceId: String, name: String): Unit =
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
    suspend fun adminUsers(baseUrl: String): AdminUsersResponse =
        withContext(Dispatchers.IO) {
            decode(AdminUsersResponse.serializer(), get(api(baseUrl, "admin", "users")))
        }

    /** POST /api/admin/users — anlegen (username, password ≥12, role, display_name?). */
    suspend fun adminCreateUser(baseUrl: String, body: JsonObject): Unit =
        withContext(Dispatchers.IO) {
            post(api(baseUrl, "admin", "users"), jsonBody(body))
            Unit
        }

    /** PATCH /api/admin/users/{u} — display_name | role | password (Reset kickt Sessions). */
    suspend fun adminPatchUser(baseUrl: String, username: String, body: JsonObject): Unit =
        withContext(Dispatchers.IO) {
            patch(api(baseUrl, "admin", "users", username), jsonBody(body))
            Unit
        }

    /** DELETE /api/admin/users/{u} — Server schuetzt selbst/letzten Admin. */
    suspend fun adminDeleteUser(baseUrl: String, username: String): Unit =
        withContext(Dispatchers.IO) {
            delete(api(baseUrl, "admin", "users", username))
            Unit
        }

    /** GET /api/settings/me — persoenliche Einstellungen des Nutzers. */
    suspend fun userSettings(baseUrl: String): UserSettingsData =
        withContext(Dispatchers.IO) {
            decode(UserSettingsData.serializer(), get(api(baseUrl, "settings", "me")))
        }

    /** PUT /api/settings/me — z.B. {"memories_scope": "personal"}. */
    suspend fun updateUserSettings(baseUrl: String, body: JsonObject): UserSettingsData =
        withContext(Dispatchers.IO) {
            decode(UserSettingsData.serializer(), put(api(baseUrl, "settings", "me"), jsonBody(body)))
        }

    /** GET /api/settings — lesbar fuer alle Angemeldeten. */
    suspend fun appSettings(baseUrl: String): AppSettingsData =
        withContext(Dispatchers.IO) {
            decode(AppSettingsData.serializer(), get(api(baseUrl, "settings")))
        }

    /** POST /api/settings — Admin; nur die uebergebenen Schluessel aendern. */
    suspend fun updateAppSettings(baseUrl: String, updates: JsonObject): AppSettingsData =
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
    suspend fun notifyPull(
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
    suspend fun notifyAck(baseUrl: String, deviceId: String, lastId: Long): Unit =
        withContext(Dispatchers.IO) {
            val body = jsonBody(buildJsonObject {
                put("device_id", JsonPrimitive(deviceId))
                put("last_id", JsonPrimitive(lastId))
            })
            post(api(baseUrl, "notify", "ack"), body)
            Unit
        }

    /** GET /api/memories[?date=MM-DD] — „Vor X Jahren" (Shared-Scan). */
    /**
     * [limit] = hoechstens so viele je Jahr, in der Rangfolge des Servers
     * (Tagesabschnitte, dann Ort; deterministisch je Tag — Vertrag
     * 08.09.2026 „Memories: Auswahl statt Flut"). null = alles, wie vorher.
     */
    suspend fun memories(baseUrl: String, date: String? = null, limit: Int? = null): MemoriesResponse =
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
    suspend fun search(
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
    suspend fun stats(baseUrl: String, space: String = "both"): StatsResponse? =
        withContext(Dispatchers.IO) {
            val url = api(baseUrl, "stats", query = mapOf("space" to space))
            val (code, text) = runWithCode(Request.Builder().url(url).build())
            if (code == 202) null
            else decode(StatsResponse.serializer(), text)
        }

    /** GET /api/stats/text — je Space ein TextStats-Paket. */
    suspend fun statsText(baseUrl: String, space: String = "both"): Map<String, TextStats> =
        withContext(Dispatchers.IO) {
            val root = json.parseToJsonElement(
                get(api(baseUrl, "stats", "text", query = mapOf("space" to space))),
            ).jsonObject
            root.mapValues { (_, v) ->
                json.decodeFromJsonElement(TextStats.serializer(), v)
            }
        }


    // ==== Modul „trails": Standorthistorie (docs/API_CHANGES.md, 04.09.) ====
    // Alle Routen sind Modul-gated (403 ohne „trails") und im Demo gesperrt.
    // Zeitraeume immer entweder date+tz (Kalendertag in Lokalzeit) oder
    // from/to in Unix-Sekunden.

    private fun trailsUrl(
        baseUrl: String, vararg tail: String, query: Map<String, String?> = emptyMap(),
    ): HttpUrl = api(baseUrl, "trails", *tail, query = query)

    /** GET /api/trails/status — Umfang des eigenen Speichers. */
    suspend fun trailsStatus(baseUrl: String): TrailStatus = withContext(Dispatchers.IO) {
        decode(TrailStatus.serializer(), get(trailsUrl(baseUrl, "status")))
    }

    /** GET /api/trails/day — Punkte (ausgeduennt), Besuche und Wege eines Tages. */
    suspend fun trailsDay(
        baseUrl: String, date: String, tzMinutes: Int, thin: Int = 2000,
    ): TrailDay = withContext(Dispatchers.IO) {
        val url = trailsUrl(
            baseUrl, "day",
            query = mapOf("date" to date, "tz" to tzMinutes.toString(), "thin" to thin.toString()),
        )
        decode(TrailDay.serializer(), get(url))
    }

    /** GET /api/trails/days — Tag → Punktzahl, Futter fuer den Kalender. */
    suspend fun trailsDays(
        baseUrl: String, year: Int, month: Int?, tzMinutes: Int,
    ): Map<String, Int> = withContext(Dispatchers.IO) {
        val url = trailsUrl(
            baseUrl, "days",
            query = mapOf(
                "year" to year.toString(),
                "month" to month?.toString(),
                "tz" to tzMinutes.toString(),
            ),
        )
        decode(TrailDays.serializer(), get(url)).days
    }

    /** GET /api/trails/places — Orte nach Besuchshaeufigkeit. */
    suspend fun trailsPlaces(baseUrl: String, limit: Int = 100): List<TrailPlace> =
        withContext(Dispatchers.IO) {
            val url = trailsUrl(baseUrl, "places", query = mapOf("limit" to limit.toString()))
            decode(TrailPlacesResponse.serializer(), get(url)).places
        }

    /**
     * PUT /api/trails/places/name — eigener Ortsname („Zuhause") fuer den
     * Ort (source, place_ref); gilt fuer alle Besuche dort, auch kuenftige,
     * der OSM-Nachtlauf ueberschreibt ihn nie. `name` leer/null nimmt ihn
     * zurueck. 404 = kein Besuch mit dieser Kennung. Vertrag 08.09.2026.
     */
    suspend fun setTrailsPlaceName(
        baseUrl: String, source: String, placeRef: String, name: String?,
    ): TrailPlaceName = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("source", JsonPrimitive(source))
            put("place_ref", JsonPrimitive(placeRef))
            put("name", name?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        decode(TrailPlaceName.serializer(), put(trailsUrl(baseUrl, "places", "name"), jsonBody(body)))
    }

    /** GET /api/trails/heatmap — Rasterzaehlung fuer die Weltkarte.
     *  `year = null` heisst „alles" und braucht dann from/to. */
    suspend fun trailsHeatmap(
        baseUrl: String, year: Int?, from: Long? = null, to: Long? = null, cell: Double = 0.01,
    ): TrailHeatmap = withContext(Dispatchers.IO) {
        val range = if (year != null) {
            mapOf("year" to year.toString())
        } else {
            mapOf(
                "from" to (from ?: 0L).toString(),
                "to" to (to ?: System.currentTimeMillis() / 1000).toString(),
            )
        }
        val url = trailsUrl(baseUrl, "heatmap", query = range + ("cell" to cell.toString()))
        // Bis 200.000 Zellen — der Standard-Lesetimeout ist dafuer zu knapp.
        decode(TrailHeatmap.serializer(), get(url, patientClient))
    }

    /** POST /api/trails/derive — Besuche und Wege eines Tages jetzt ableiten
     *  (sonst macht das der Nachtjob). Ohne `date` laeuft der Nachtjob. */
    suspend fun trailsDerive(
        baseUrl: String, date: String? = null, tzMinutes: Int = 0,
    ): TrailDeriveResponse = withContext(Dispatchers.IO) {
        val url = trailsUrl(
            baseUrl, "derive",
            query = if (date != null) mapOf("date" to date, "tz" to tzMinutes.toString()) else emptyMap(),
        )
        // Ableiten laeuft ueber Rohpunkte — bei einem vollen Tag dauert das
        // (vorher 120 s; longRunClient wartet laenger, das schadet nicht).
        decode(TrailDeriveResponse.serializer(), post(url, http = longRunClient))
    }

    /** GET /api/trails/devices — Aufzeichnungsgeraete (OwnTracks). */
    suspend fun trailsDevices(baseUrl: String): List<TrailDevice> = withContext(Dispatchers.IO) {
        decode(TrailDevicesResponse.serializer(), get(trailsUrl(baseUrl, "devices"))).devices
    }

    /** POST /api/trails/devices — Geraet anlegen bzw. Token erneuern.
     *  Der Klartext-Token steht NUR in dieser Antwort. */
    suspend fun trailsCreateDevice(
        baseUrl: String, deviceId: String, name: String,
    ): TrailDeviceCreated = withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("device_id", JsonPrimitive(deviceId))
            put("name", JsonPrimitive(name))
        })
        decode(TrailDeviceCreated.serializer(), post(trailsUrl(baseUrl, "devices"), body))
    }

    /** DELETE /api/trails/devices/{id} — Token weg, die Punkte bleiben. */
    suspend fun trailsDeleteDevice(baseUrl: String, deviceId: String): Unit =
        withContext(Dispatchers.IO) {
            delete(trailsUrl(baseUrl, "devices", deviceId))
            Unit
        }

    /** GET /api/trails/sources — welche Quelle deckt welchen Zeitraum ab. */
    suspend fun trailsSources(baseUrl: String): List<TrailSource> = withContext(Dispatchers.IO) {
        decode(TrailSourcesResponse.serializer(), get(trailsUrl(baseUrl, "sources"))).sources
    }

    /**
     * POST /api/trails/sources/delete — endgueltig. Mit `from`/`to` fallen nur
     * die abgeleiteten Besuche und Wege im Fenster (Umstiegsfall), ohne
     * Zeitfenster faellt die Quelle komplett samt Rohpunkten, als Praefix
     * ("google" trifft auch "google:h1").
     */
    suspend fun trailsDeleteSource(
        baseUrl: String, source: String, from: Long? = null, to: Long? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val body = jsonBody(buildJsonObject {
            put("source", JsonPrimitive(source))
            // Der Server verlangt die Wiederholung als Absicherung.
            put("confirm", JsonPrimitive(source))
            if (from != null && to != null) {
                put("from", JsonPrimitive(from))
                put("to", JsonPrimitive(to))
            }
        })
        // Vorher 180 s eigener Client; longRunClient wartet laenger.
        post(trailsUrl(baseUrl, "sources", "delete"), body, longRunClient)
        Unit
    }

    /** GET /api/trails/archive — das naechtliche Archiv auf der Platte. */
    suspend fun trailsArchive(baseUrl: String): TrailArchiveResponse = withContext(Dispatchers.IO) {
        decode(TrailArchiveResponse.serializer(), get(trailsUrl(baseUrl, "archive")))
    }

    /** POST /api/trails/export?all=1 — Archiv vollstaendig neu bauen.
     *  Laeuft bei grossen Bestaenden Minuten. */
    suspend fun trailsRebuildArchive(baseUrl: String, all: Boolean = true): Int =
        withContext(Dispatchers.IO) {
            val url = trailsUrl(baseUrl, "export", query = mapOf("all" to if (all) "1" else "0"))
            json.parseToJsonElement(post(url, http = longRunClient)).jsonObject["count"]
                ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        }

    /** Adresse einer Archivdatei — zum Herunterladen ueber downloadStream(). */
    fun trailsArchiveFileUrl(baseUrl: String, name: String): String =
        trailsUrl(baseUrl, "archive", name).toString()

    /** Adresse des Gesamtarchivs als ZIP (Zeitraum optional, YYYY-MM). */
    fun trailsArchiveZipUrl(baseUrl: String, from: String? = null, to: String? = null): String =
        trailsUrl(baseUrl, "archive.zip", query = mapOf("from" to from, "to" to to)).toString()

    /** Adresse eines Exports (GeoJSON/GPX) fuer einen Kalendertag. */
    fun trailsExportDayUrl(
        baseUrl: String, date: String, tzMinutes: Int, format: String,
    ): String = trailsUrl(
        baseUrl, "export",
        query = mapOf("format" to format, "date" to date, "tz" to tzMinutes.toString()),
    ).toString()

    /**
     * Datei vom Server in einen Ausgabestrom schieben — fuer die Mitnahme
     * (Archiv, Export). Bewusst NICHT ueber den DownloadManager: der kennt
     * unseren Zertifikatsspeicher nicht und muesste den Bearer-Token
     * mitbekommen. Liefert die Zahl der Bytes.
     */
    suspend fun downloadStream(url: String, sink: java.io.OutputStream): Long =
        withContext(Dispatchers.IO) {
            longRunClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                // Kein fail(): Streaming-Download, der Koerper ist die Nutzlast.
                if (!response.isSuccessful) {
                    throw MpdHttpException(response.code, "HTTP ${response.code}")
                }
                sink.use { out ->
                    response.body?.byteStream()?.copyTo(out) ?: throw IOException(Texte.t(Res.string.api_err_empty_response))
                }
            }
        }


    // ==== Teilen: Besitzer-Seite (Vertraege 03./04.09.2026) ================
    // Klartext-Token nur in der create-Antwort; die Liste kennt Hashes.
    // Der Gast-Viewer bleibt Web — hier entsteht nur die Verwaltung.

    /** GET /api/share/list — aktive Links EINES Albums (Metadaten). */
    suspend fun shareList(baseUrl: String, space: String, album: String): List<ShareEntry> =
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
    suspend fun shareCreate(
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
    suspend fun shareRevoke(baseUrl: String, tokenHash: String): Unit =
        withContext(Dispatchers.IO) {
            val body = jsonBody(buildJsonObject { put("token_hash", JsonPrimitive(tokenHash)) })
            post(api(baseUrl, "share", "revoke"), body)
            Unit
        }

    /** POST /api/share/send-email — braucht den KLARTEXT-Token, geht also
     *  nur unmittelbar nach dem Anlegen (im Web ebenso). */
    suspend fun shareSendEmail(
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
    suspend fun contacts(baseUrl: String): List<ShareContact> = withContext(Dispatchers.IO) {
        decode(ContactsResponse.serializer(), get(api(baseUrl, "contacts"))).contacts
    }

    /** GET /api/contrib/list — eigene Beisteuern-Links, ueber alle Ordner. */
    suspend fun contribList(baseUrl: String): List<ContribEntry> = withContext(Dispatchers.IO) {
        decode(ContribListResponse.serializer(), get(api(baseUrl, "contrib", "list"))).contribs
    }

    /**
     * POST /api/contrib/create — Sammelordner anlegen und Gastlink dazu.
     * Ziel darf KEIN Album sein (Server: 400) — Gaeste liefern Rohmaterial,
     * einsortiert wird danach ueber den Zeitstrahl.
     */
    suspend fun contribCreate(
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
    suspend fun contribRevoke(baseUrl: String, tokenHash: String): Unit =
        withContext(Dispatchers.IO) {
            val body = jsonBody(buildJsonObject { put("token_hash", JsonPrimitive(tokenHash)) })
            post(api(baseUrl, "contrib", "revoke"), body)
            Unit
        }

    /**
     * Wertet einen Fehlerkoerper aus und wirft. Einzige Stelle, an der eine
     * `MpdHttpException` entsteht — dadurch greift auch die Meldung an
     * [onMustChangePassword] fuer *jeden* Aufruf der App, nicht nur fuer
     * Login und whoami.
     */
    private fun fail(status: Int, text: String, request: Request): Nothing {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val field = { name: String ->
            runCatching { obj?.get(name)?.jsonPrimitive?.content }.getOrNull()
        }
        val errorCode = field("code")
        if (errorCode == ERR_MUST_CHANGE_PASSWORD) onMustChangePassword?.invoke()
        val e = MpdHttpException(status, field("detail") ?: "HTTP $status", errorCode)
        if (e.routeMissing) {
            moduleKeyForPath(request.url.encodedPath)?.let { onModuleMissing?.invoke(it) }
        }
        throw e
    }

    /** Wie run(), gibt aber den Status mit — fuer 202-Vertraege
     *  (Suchindex/Stats-Scan laufen gerade). */
    private fun runWithCode(request: Request, http: OkHttpClient = client): Pair<Int, String> {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(response.code, text, request)
            return response.code to text
        }
    }

    private fun run(request: Request, http: OkHttpClient = client): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) fail(response.code, text, request)
            return text
        }
    }

    // -- Zertifikat fuer die Vertrauensabfrage holen ------------------------

    /**
     * Baut nur den TLS-Handshake auf (es werden keine Daten gesendet) und
     * liefert das Server-Zertifikat zur Anzeige in der Vertrauensabfrage.
     */
    suspend fun fetchServerCert(baseUrl: String): CertInfo = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrlOrNull()
            ?: throw IOException(Texte.t(Res.string.api_err_invalid_address_x, baseUrl))
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        (ssl.socketFactory.createSocket(url.host, url.port) as SSLSocket).use { socket ->
            socket.soTimeout = 10_000
            socket.startHandshake()
            val cert = socket.session.peerCertificates.first() as X509Certificate
            val hex = sha256Hex(cert.encoded)
            CertInfo(
                sha256 = hex,
                fingerprintDisplay = hex.chunked(2).joinToString(":"),
                subject = cert.subjectX500Principal.name,
                validUntil = DateFormat.getDateInstance().format(cert.notAfter),
            )
        }
    }

    companion object {
        /** Zertifikats-Fingerprint: GROSS, wie gespeichert und angezeigt. */
        private fun sha256Hex(data: ByteArray): String =
            de.bgghome.mpd.util.sha256Hex(data, uppercase = true)

        private fun encodedUrl(vararg segments: String): HttpUrl =
            HttpUrl.Builder().scheme("http").host("x")
                .apply { segments.forEach { addPathSegment(it) } }
                .build()

        /** Relativer Thumbnail-Pfad, URL-codiert wie routers/albums.py. */
        fun thumbnailPath(space: String, album: String, file: String, size: String): String {
            val url = encodedUrl("api", "thumbnail", space, album, file)
            return "${url.encodedPath}?size=$size"
        }

        /** Relativer Original-Download-Pfad wie routers/albums.py. */
        fun originalPath(space: String, album: String, file: String): String =
            encodedUrl("api", "photo", space, album, file, "download").encodedPath

        /** FFmpeg-Video-Thumb — /api/thumbnail liefert fuer Videos 415. */
        fun videoThumbPath(space: String, album: String, file: String): String =
            encodedUrl("api", "video-thumb", space, album, file).encodedPath

        /**
         * PDF im Album. Frueher per URLEncoder zusammengeklebt — der kodiert
         * fuers Formular, aus Leerzeichen wurde "+", das der Server im Pfad
         * woertlich nimmt: jedes Album mit Leerzeichen im Namen lief ins 404.
         */
        fun documentPath(space: String, album: String, file: String): String =
            encodedUrl("api", "document", space, album, file).encodedPath
    }
}

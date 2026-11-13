package de.bgghome.mpd.api

import de.bgghome.mpd.shared.res.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie

// Datentypen der Endpunkte (Antwort- und Anfrageformen), Felder wie im
// jeweiligen Router. Herausgeloest aus MpdApi.kt (13.11.2026).

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
    /** Foto oder Video: ein Element, hinter dem eine Mediendatei im
     *  Albumordner steht — die Kacheln mit Menue, Schloss und Papierkorb. */
    val istDatei: Boolean get() = type == "photo" || type == "video"

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
    /** Bytes; additiv seit 13.11.2026, aeltere Server lassen es weg. */
    @SerialName("file_size") val fileSize: Long? = null,
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

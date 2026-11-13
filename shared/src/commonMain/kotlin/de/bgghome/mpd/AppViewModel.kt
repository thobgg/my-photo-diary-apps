package de.bgghome.mpd

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.CertInfo
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.state.modifiedOf
import de.bgghome.mpd.state.moduleLockedText
import de.bgghome.mpd.state.serverUnreachable
import de.bgghome.mpd.state.updateBody
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.net.ssl.SSLException
import de.bgghome.mpd.api.albumsWithoutJson
import de.bgghome.mpd.api.appSettings
import de.bgghome.mpd.api.coverFromSource
import de.bgghome.mpd.api.createAlbum
import de.bgghome.mpd.api.createEmptyAlbum
import de.bgghome.mpd.api.deleteFile
import de.bgghome.mpd.api.deleteTourFile
import de.bgghome.mpd.api.durchlaufTotal
import de.bgghome.mpd.api.importFile
import de.bgghome.mpd.api.notifyPull
import de.bgghome.mpd.api.photosGeo
import de.bgghome.mpd.api.recycleStatus
import de.bgghome.mpd.api.timeline
import de.bgghome.mpd.api.updateAppSettings
import de.bgghome.mpd.api.updateUserSettings
import de.bgghome.mpd.api.userSettings


data class OpenAlbum(
    val summary: AlbumSummary,
    val title: String,
    /** Alle sichtbaren PDX-Elemente in Originalreihenfolge. */
    val elements: List<de.bgghome.mpd.api.PdxElement>,
    /** Nur die Fotos — die Lightbox-Reihenfolge (wie allImages im Web). */
    val photos: List<Photo>,
    /** Element-id → Index in [photos], fuer den Klick im Album. */
    val photoIndexById: Map<String, Int>,
    /** meta.show_locked — Gesperrte im View-Modus zeigen? (Edit zeigt immer.) */
    val showLocked: Boolean = true,
    /** Zaehler der Kopfzeile — wie im Web auf der UNgefilterten Liste. */
    val mediaCount: Int,
    val textCount: Int,
    /** read_only der Album-API: true = kein Bearbeiten-Umschalter. */
    val readOnly: Boolean,
    /**
     * Rohes JSON fuer den Speicher-Roundtrip: die typisierten Modelle
     * verwerfen unbekannte Felder, der Update-Body entsteht deshalb aus
     * diesen Originalen (nur editierte Felder werden ersetzt). rawElements
     * ist UNgefiltert — auch bei show_locked=false verschwindet beim
     * Speichern kein gesperrtes Element.
     */
    val rawMeta: JsonObject,
    val rawElements: List<JsonObject>,
    /**
     * meta.modified der geladenen Fassung — Basis fuer den opt-in-
     * Konfliktschutz (X-MPD-Base-Modified beim Update, 409 bei Abweichung).
     * null = kein Schutz (alter Server oder bewusstes Ueberschreiben).
     */
    val baseModified: String? = null,
)

/** Speicherstatus der Albumgestaltung — wie updateSaveIndicator im Web. */
enum class SaveState { Idle, Unsaved, Saving, Saved, Error }

sealed interface AppState {
    /** Beim Start: gespeicherte Sitzung wird geprueft. */
    data object Checking : AppState

    /** Einrichtung/Anmeldung noetig. */
    data object Setup : AppState

    /**
     * Angemeldet, aber der Server verlangt einen Passwortwechsel
     * (`must_change_password`). Ein eigener Zustand statt eines Dialogs ueber
     * dem Home-Screen, weil in diesem Zustand *jeder* andere Aufruf mit 403
     * antwortet — eine Albenliste im Hintergrund waere nur eine Fehlerwand.
     */
    data class MustChangePassword(val who: WhoAmI) : AppState

    data class LoggedIn(val who: WhoAmI) : AppState
}

/**
 * Der gemeinsame Kern fuer Android und Desktop (Aufteilung 22.09.2026).
 * Was Android-Typen braucht (Context, Uri, WorkManager, MediaStore), holt
 * er ueber [Plattform]; was Android-Typen in der Signatur traegt, steht in
 * der Unterklasse AndroidAppViewModel (androidMain).
 */
open class AppViewModel(val plattform: Plattform) : ViewModel() {

    internal val settings = plattform.settings
    val api = MpdApi(settings)

    /** Text aus den Ressourcen fuer Code ohne Composable-Kontext (i18n 08.09.2026). */
    fun t(res: StringResource, vararg args: Any?): String = Texte.t(res, *args)

    /** Mengenform; die Zahl ist immer auch das %d-Argument. */
    fun plural(res: PluralStringResource, n: Int, vararg args: Any?): String = Texte.plural(res, n, *args)

    init {
        // Android: liegengebliebene Uploads anstossen, Kamera-Beobachter,
        // Benachrichtigungen (sofort + 15-min-Wachlauf). Desktop: nichts.
        plattform.starteHintergrund(settings.backupEnabled)
    }

    // -- Einstellungen-Screen (Web: Einstellungen-Modal im Hamburger) -------

    var settingsOpen by mutableStateOf(false)
    /** „Handy verbinden": QR mit mpd://setup?url=… (Web: verbinden.html). */
    var connectOpen by mutableStateOf(false)
    var themePickerOpen by mutableStateOf(false)
    var appSettings by mutableStateOf<de.bgghome.mpd.api.AppSettingsData?>(null)

    fun loadAppSettings() {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            runCatching { api.appSettings(base) }.onSuccess { appSettings = it }
        }
    }

    /** Teil-Update (nur die uebergebenen Schluessel); onDone(null) = ok. */
    fun updateAppSettings(
        updates: kotlinx.serialization.json.JsonObject,
        onDone: (String?) -> Unit = {},
    ) {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            try {
                appSettings = api.updateAppSettings(base, updates)
                onDone(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onDone(e.message ?: t(Res.string.vm_err_save_failed))
            }
        }
    }

    // -- Kachel-Aktionen (Web: alb-kebab auf der Albumkarte) ----------------

    /** Ziel-Album der Kachel-Dialoge (null = zu). */
    var tileCoverAlbum by mutableStateOf<AlbumSummary?>(null)
    var tileRenameAlbum by mutableStateOf<AlbumSummary?>(null)
    var tileCropAlbum by mutableStateOf<AlbumSummary?>(null)
    var tileDeleteAlbum by mutableStateOf<AlbumSummary?>(null)

    /** Teilen-Dialog der Kachel bzw. der Albumansicht (null = zu). */
    var tileShareAlbum by mutableStateOf<AlbumSummary?>(null)

    /** Einzelfoto-Share aus der Lightbox (Teilen v3): space/album/datei. */
    var photoShare by mutableStateOf<Triple<String, String, String>?>(null)

    /** Beisteuern-Verwaltung offen (Hamburger „Werkzeuge → Beisteuern"). */
    var contribOpen by mutableStateOf(false)

    /** Album-Meta von der Kachel aus aendern (Web: _fetchAlbumForEdit +
     *  _saveAlbum): album.json laden, Felder setzen bzw. mit null-Wert
     *  entfernen, zurueckschreiben, Uebersicht neu laden. Die Elemente
     *  gehen unveraendert durch. */
    /**
     * Ein einzelnes Feld der album.json setzen (Titel, Deckblatt, Sperre).
     *
     * KEIN KONFLIKTDIALOG HIER (12.09.2026: „das Projekt ist
     * langsam zu komplex … was heisst dann z. B. ueberschreiben?"). Wer
     * genau ein Feld setzt, hat nichts zu entscheiden: aendert jemand
     * gleichzeitig ein anderes Feld, ist die richtige Antwort immer
     * dieselbe — frischen Stand holen, das eigene Feld erneut setzen,
     * schreiben. Eine Frage zu stellen, auf die es nur eine sinnvolle
     * Antwort gibt, ist keine Vorsicht, sondern Arbeit ohne Ertrag.
     *
     * Der Dialog bleibt an der einen Stelle, wo er wirklich etwas fragt:
     * beim Bearbeiten-Modus, der viele ungespeicherte Aenderungen haelt
     * und sie nicht von allein mit einer fremden Fassung mischen kann.
     *
     * Dasselbe Muster faehrt der Favoriten-Stern seit dem 08.09.2026
     * (state/FavoritesState.kt) — dort mit einem Riegel, weil zwei schnelle
     * Tipps sonst denselben Stand laden. Hier genuegt die Wiederholung:
     * Titel und Deckblatt tippt niemand zweimal in derselben Sekunde.
     */
    private fun patchAlbumMeta(
        summary: AlbumSummary,
        patch: Map<String, kotlinx.serialization.json.JsonElement?>,
        onDone: (String?) -> Unit,
    ) {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            try {
                try {
                    patchOnce(base, summary, patch)
                } catch (e: MpdHttpException) {
                    // Jemand war schneller. Der Wunsch gilt trotzdem:
                    // einmal still mit frischem Stand wiederholen.
                    if (e.code == 409) patchOnce(base, summary, patch) else throw e
                }
                albums = null
                loadAlbums()
                onDone(null)
            } catch (e: MpdHttpException) {
                onDone(e.detail)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onDone(e.message ?: t(Res.string.vm_err_save_failed))
            }
        }
    }

    /** Ein Durchlauf: laden, Feld setzen, speichern mit Konflikt-Basis. */
    private suspend fun patchOnce(
        base: String,
        summary: AlbumSummary,
        patch: Map<String, kotlinx.serialization.json.JsonElement?>,
    ) {
        val env = api.album(base, summary.space, summary.name)
        val meta = env.raw["meta"]?.jsonObject ?: buildJsonObject {}
        var newMeta = meta
        for ((key, value) in patch) {
            newMeta = if (value == null) {
                JsonObject(newMeta - key)
            } else {
                JsonObject(newMeta + (key to value))
            }
        }
        val elements = env.raw["elements"]?.jsonArray
            ?.map { it.jsonObject } ?: emptyList()
        // Wie save(): gefilterter Body und die geladene Fassung als
        // Konflikt-Basis. Das Fenster ist klein (Laden und Schreiben
        // liegen Millisekunden auseinander), aber ohne den Header
        // ueberschriebe der Patch still, was jemand gerade im Web
        // oder am anderen Geraet geaendert hat.
        api.updateAlbum(
            base, summary.space, summary.name,
            updateBody(newMeta, elements),
            baseModified = modifiedOf(env.raw),
        )
    }

    /** Deckblatt: ein alter Zuschnitt passt nicht zum neuen Bild → weg. */
    fun setCoverFromTile(summary: AlbumSummary, filename: String, onDone: (String?) -> Unit) =
        patchAlbumMeta(
            summary,
            mapOf("thumbnail" to JsonPrimitive(filename), "thumbnail_crop" to null),
            onDone,
        )

    /** Umbenennen aendert wie im Web nur meta.title, nie den Ordner. */
    fun renameAlbumFromTile(summary: AlbumSummary, title: String, onDone: (String?) -> Unit) =
        patchAlbumMeta(summary, mapOf("title" to JsonPrimitive(title.trim())), onDone)

    /** Cover-Ausschnitt (Lupe) — normalisiert wie im Web. */
    fun setCoverCrop(
        summary: AlbumSummary,
        crop: de.bgghome.mpd.api.ThumbCrop?,
        onDone: (String?) -> Unit,
    ) = patchAlbumMeta(
        summary,
        mapOf(
            "thumbnail_crop" to crop?.let {
                buildJsonObject {
                    put("x", JsonPrimitive(it.x))
                    put("y", JsonPrimitive(it.y))
                    put("w", JsonPrimitive(it.w))
                    put("h", JsonPrimitive(it.h))
                }
            },
        ),
        onDone,
    )

    /** Loeschen von der Kachel — gleiche Mechanik wie im Album (nur
     *  album.json weg, Backup fuer den Rueckgaengig-Toast). */
    fun deleteAlbumFromTile(summary: AlbumSummary, onError: (String) -> Unit) {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            try {
                val backup = api.deleteAlbum(base, summary.space, summary.name)
                pendingUndoDelete = backup?.let {
                    DeletedAlbum(
                        summary.space, summary.name,
                        summary.title.ifBlank { summary.name }, it,
                    )
                }
                albums = null
                loadAlbums()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onError(e.message ?: t(Res.string.vm_err_delete_failed))
            }
        }
    }

    // -- Neues Album (Web: Create-Dialog der Startseite) --------------------

    var createAlbumOpen by mutableStateOf(false)
    var folderCandidates by mutableStateOf<List<de.bgghome.mpd.api.FolderCandidate>?>(null)
    var folderCandidatesError by mutableStateOf<String?>(null)

    fun loadFolderCandidates() {
        val base = settings.baseUrl ?: return
        folderCandidates = null
        folderCandidatesError = null
        viewModelScope.launch {
            try {
                folderCandidates = api.albumsWithoutJson(base).folders
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                folderCandidatesError = e.message ?: t(Res.string.vm_err_scan_failed)
            }
        }
    }

    /**
     * Album aus einem bestehenden Ordner anlegen. Danach steht man in der
     * Albenuebersicht, das neue Album ist dort.
     *
     * DIE DECKBLATT-SCHLEIFE (12.09.2026: „nach Auswahl Deckblatt,
     * Rueckkehr, fuehrt immer zur erneuten Auswahl und nicht zurueck zur
     * Albenuebersicht") entstand hier: der try-Block umfasste auch alles
     * NACH dem Anlegen. Stolperte das Nachladen oder das Oeffnen des neuen
     * Albums, meldete der catch einen Fehler — obwohl das Album laengst
     * angelegt war. Der Dialog blieb offen und zeigte wieder die
     * Deckblatt-Wahl. Der zweite Versuch traf dann auf ein bereits
     * bestehendes Album und scheiterte endgueltig: aus einem Aussetzer
     * wurde eine Sackgasse, aus der nur Abbrechen herausfuehrte.
     *
     * Jetzt steht nur der eine Aufruf im try, der schiefgehen DARF.
     *
     * DER DIALOG IST BEIM AUFRUF SCHON ZU (12.09.2026: „eigentlich
     * sollte man doch nach Deckblattauswahl in die Albenuebersicht
     * zurueckkommen, man wird ja eher selten gleich mehrere anlegen
     * wollen"). Das Anlegen kann eine Minute dauern — so lange jemanden
     * vor einem Wartekreis festzuhalten, waere Preis-Arbeit. Er landet
     * sofort in der Uebersicht, das Album erscheint, sobald es fertig ist,
     * und eine Snackbar sagt es. Nur ein echter Fehlschlag meldet sich, und
     * zwar ebenfalls dort, nicht in einem Dialog, den es nicht mehr gibt.
     */
    fun createAlbumFromFolder(
        space: String,
        folderName: String,
        thumbnail: String,
        onDone: (String?) -> Unit,
    ) {
        val base = settings.baseUrl
        if (base == null) {
            // Ohne Adresse gab es hier ein nacktes return: onDone kam nie,
            // und der Dialog blieb auf „Erstelle Album …" stehen.
            onDone(t(Res.string.vm_err_create_failed))
            return
        }
        viewModelScope.launch {
            try {
                api.createAlbum(base, space, folderName, thumbnail)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // BRICHT DIE LEITUNG AB, HEISST DAS NICHT, DASS NICHTS
                // PASSIERT IST (12.09.2026). Der Server arbeitet weiter und
                // legt fertig an — Thomas sah deshalb erst einen Fehler und
                // „irgendwann mal" das Album doch. Also nachsehen, statt zu
                // behaupten: steht es in der Liste, war es ein Erfolg.
                val entstanden = e !is MpdHttpException && runCatching {
                    api.albums(base).any { it.space == space && it.name == folderName }
                }.getOrDefault(false)
                if (!entstanden) {
                    onDone(e.message ?: t(Res.string.vm_err_create_failed))
                    return@launch
                }
            }
            // Ab hier ist das Album da. Was jetzt noch schiefgeht, darf den
            // Anlegen-Vorgang nicht mehr als gescheitert darstellen.
            onDone(null)
            albums = null
            loadAlbums()
            meldung(t(Res.string.vm_album_created_x, folderName))
        }
    }

    /** Leeres Album (neuer Ordner) anlegen und oeffnen. */
    fun createEmptyAlbumUi(space: String, name: String, onDone: (String?) -> Unit) {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            try {
                api.createEmptyAlbum(base, space, name)
                albums = null
                loadAlbums()
                createAlbumOpen = false
                openAlbum(AlbumSummary(name = name, space = space))
                onDone(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onDone(e.message ?: t(Res.string.vm_err_create_failed))
            }
        }
    }

    // -- Persoenliche Einstellungen (GET/PUT /api/settings/me) --------------

    var userSettings by mutableStateOf<de.bgghome.mpd.api.UserSettingsData?>(null)

    fun loadUserSettings() {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            runCatching { api.userSettings(base) }.onSuccess { userSettings = it }
        }
    }

    fun setMemoriesScope(scope: String, onDone: (String?) -> Unit = {}) {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            try {
                userSettings = api.updateUserSettings(
                    base,
                    kotlinx.serialization.json.buildJsonObject {
                        put("memories_scope", kotlinx.serialization.json.JsonPrimitive(scope))
                    },
                )
                // Frisch geladene Erinnerungen passen nicht mehr zur Quelle —
                // gleich neu holen, die Pinnwand kann offen sein.
                memories.data = null
                memories.load()
                onDone(null)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onDone(e.message ?: t(Res.string.err_generic))
            }
        }
    }

    // -- Benutzerverwaltung (admin-only, Web: „Benutzer"-Modal) -------------

    /** Liste und Aufruf-Rahmen liegen in state/UsersState.kt. */
    val users = de.bgghome.mpd.state.UsersState(api, settings, viewModelScope)

    // -- Waechter-Banner bei geoeffneter App --------------------------------

    /** Titel/Text des zuletzt eingetroffenen Alarms — null = kein Banner. */
    var alertBanner by mutableStateOf<Pair<String, String>?>(null)

    /**
     * Vordergrund-Long-Poll: sofortige Zustellung, solange die App sichtbar
     * ist (MainActivity bindet die Schleife per repeatOnLifecycle an
     * STARTED — im Hintergrund uebernimmt wieder der 15-min-Worker).
     * Alarme erscheinen zusaetzlich als In-App-Banner; die Systemmeldung
     * samt Dedupe/Ack-Buchfuehrung macht weiterhin der NotifyWorker (kick),
     * damit es genau EINEN Buchhalter gibt.
     */
    suspend fun foregroundNotifyLoop() {
        val baseUrl = settings.baseUrl ?: return
        val deviceId = settings.notifyDeviceId
        // Eigener, gespeicherter Zaehler des Banners (11.10.2026) — der des
        // NotifyWorkers bleibt stehen, wenn Android Benachrichtigungen
        // verbietet, und dann kam dieselbe Meldung bei jedem Oeffnen wieder.
        var cursor = maxOf(settings.notifyLastShownId, settings.notifyBannerSeenId)
        // Nach Neuinstallation/Neuanmeldung steht der Zaehler auf 0 — der
        // erste Durchlauf setzt nur die Basislinie, sonst poppt ein laengst
        // gelaufener Alarm als Banner auf („was mit xy", 03.09.).
        // Ebenso beim ersten Lauf mit dem eigenen Banner-Zaehler: sonst kaeme
        // die alte Kies-Meldung nach dem Update noch ein letztes Mal.
        var baseline = cursor == 0L || settings.notifyBannerSeenId == 0L
        while (true) {
            try {
                val page = api.notifyPull(baseUrl, deviceId, since = cursor, wait = 25)
                val fresh = page.notifications.filter { it.id > cursor }
                if (fresh.isNotEmpty()) {
                    cursor = fresh.maxOf { it.id }
                    settings.notifyBannerSeenId = cursor
                    if (!baseline) {
                        fresh.lastOrNull { it.type == "alert" }?.let { msg ->
                            alertBanner = msg.title.ifBlank { "My Photo Diary" } to msg.body
                        }
                        plattform.kickNotify()
                    }
                }
                baseline = false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    // -- Kamera-Backup-Einstellungen (Dialog im Hamburger) ------------------

    /** Basis-URL fuer alle Bild-Requests; erst nach der Anmeldung gesetzt. */
    val baseUrl: String get() = settings.baseUrl.orEmpty()

    // -- Theme (persistiert wie localStorage 'mpd-theme' im Web) ------------

    /** OwnTracks-Aufzeichnungsstufe, siehe TrailsDevices (08.09.2026). */
    var trailsRecordingLevel by mutableStateOf(settings.trailsRecordingLevel)
        private set

    fun waehleAufzeichnung(level: String) {
        trailsRecordingLevel = level
        settings.trailsRecordingLevel = level
    }

    var themeId by mutableStateOf(
        de.bgghome.mpd.ui.theme.MpdThemeId.fromName(settings.themeName)
    )
        private set

    fun setTheme(id: de.bgghome.mpd.ui.theme.MpdThemeId) {
        themeId = id
        settings.themeName = id.name
    }

    // -- Space-Filter der Albenliste (Hamburger „Anzeige", wie im Web) ------

    /** null = alle Spaces; sonst "personal"/"shared". Gilt fuer Albenliste
     *  UND Zeitstrahl — bis 10.10.2026 nur fuer die Alben („wenn
     *  man bei Timeline von Ansicht Geteilt auf Persönlich umstellt, wird
     *  trotzdem alles angezeigt"). */
    private var _spaceFilter by mutableStateOf<String?>(null)
    var spaceFilter: String?
        get() = _spaceFilter
        set(value) {
            if (value == _spaceFilter) return
            _spaceFilter = value
            rebuildTimelineFeed()
        }

    /** Cover-Wechsler-Dialog offen (Einstieg: MetaEditRow im Edit-Modus). */
    var coverPickerOpen by mutableStateOf(false)

    /** Tours-Modul offen (Hamburger „Entdecken → Tours"). */
    var toursOpen by mutableStateOf(false)

    /** Trails-Modul offen (Hamburger „Entdecken → Trails"). */
    var trailsOpen by mutableStateOf(false)

    /** Tag, den der Trails-Screen beim Oeffnen zeigt (ISO YYYY-MM-DD);
     *  null = heute. Damit kann ein Aufruf von aussen — Lightbox-Foto,
     *  Zeitstrahl-Tag — direkt im richtigen Tag landen. */
    var trailsDate by mutableStateOf<String?>(null)

    /** „Wo war ich?" — Trails am gegebenen Tag oeffnen. */
    fun openTrailsAt(date: String?) {
        trailsDate = date
        tryOpenModule("trails") { trailsOpen = true }
    }

    // -- Modul „search" (GET /api/search) -----------------------------------

    /** Suchfeld, Bereich, Treffer und die Suche selbst liegen in
     *  state/SearchState.kt; hier bleibt, was ein Treffer beim Antippen
     *  tut — das haengt am offenen Album. */
    val search = de.bgghome.mpd.state.SearchState(api, { baseUrl }, viewModelScope)

    /** Treffer antippen: Album oeffnen und zum Element springen. */
    var pendingScrollElementId by mutableStateOf<String?>(null)

    fun openAlbumFromSearch(space: String, album: String, elementId: String?) {
        pendingScrollElementId = elementId
        search.open = false
        openAlbum(de.bgghome.mpd.api.AlbumSummary(name = album, space = space))
    }

    // ================= Upload/Backup (Modell 02.09.2026, abends) =========
    // Eigene MPD-Kamera gibt es nicht mehr (Samsung-UI unschlagbar) —
    // fotografiert wird normal, das Geraeteordner-Backup (UploadWorker)
    // holt neue Aufnahmen der gewaehlten Ordner in den Durchlauf.

    /** Letzter Upload-Fehler (Worker) — Zeitstrahl zeigt ihn als Snackbar. */
    var uploadError by mutableStateOf<String?>(null)

    /** Die Fehler-Pollschleife nach einem Anstoss — immer nur eine. Vorher
     *  startete jeder kickUpload() eine neue, und drei Anstoesse hintereinander
     *  liessen drei Schleifen zwei Minuten lang nebeneinander laufen. */
    private var uploadPollJob: Job? = null

    fun kickUpload() {
        plattform.kickUpload()
        uploadPollJob?.cancel()
        uploadPollJob = viewModelScope.launch {
            repeat(40) {
                delay(3000)
                plattform.holeUploadFehler()?.let {
                    uploadError = it
                    return@launch
                }
            }
        }
    }

    // ================= Kuratieren im Zeitstrahl (Modell 02.09.2026) ======
    // Filter „Unkuratiert" (referenced=false) → Mehrfachauswahl → genau
    // zwei Tasten: Einsortieren nach… (import-file, chronologisch) oder
    // Loeschen (auch Videos). 403 = C+-Rechte („nur der Kurator kann…").

    var timelineUncuratedOnly by mutableStateOf(false)
    val timelineSelection =
        androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()
    var curateBusy by mutableStateOf(false)
    var curateStatus by mutableStateOf<String?>(null)

    private fun sameItem(
        a: de.bgghome.mpd.api.TimelineItem,
        b: de.bgghome.mpd.api.TimelineItem,
    ) = a.space == b.space && a.album == b.album && a.file == b.file

    fun isSelected(item: de.bgghome.mpd.api.TimelineItem) =
        timelineSelection.any { sameItem(it, item) }

    /**
     * Markieren — auch Aufnahmen vom Geraet (06.09.2026: „kann man
     * nicht in der Timeline Fotos die nur auf Gerät sind löschen?").
     *
     * Was mit ihnen geht, entscheiden die Aktionen einzeln: wegwerfen ja,
     * einsortieren nein. Auf der NAS haben sie weder Space noch Album, ein
     * Server-Aufruf ginge mit leerem Space hinaus.
     */
    fun toggleSelect(item: de.bgghome.mpd.api.TimelineItem) {
        // Wer weder wegwerfen noch einsortieren darf, soll nicht erst
        // markieren und dann am 403 haengenbleiben.
        if (item.localUri == null && !canContribute(item.space)) {
            curateStatus = t(Res.string.vm_shared_curator_only)
            return
        }
        val existing = timelineSelection.indexOfFirst { sameItem(it, item) }
        if (existing >= 0) timelineSelection.removeAt(existing)
        else timelineSelection.add(item)
    }

    fun clearSelection() = timelineSelection.clear()

    /** Einsortieren: je Foto ein atomarer Aufruf, chronologisch aufsteigend
     *  — dann stimmt die Grundreihenfolge im Zielalbum. */
    fun importSelectionTo(targetSpace: String, targetAlbum: String) {
        if (curateBusy) return
        // Geraete-Aufnahmen kann der Server nicht einsortieren, er hat sie
        // nicht. Erst sichern (das Kamera-Backup erledigt es), dann kuratieren.
        val all = timelineSelection.sortedBy { it.date }
        val batch = all.filter { it.localUri == null }
        val skipped = all.size - batch.size
        if (batch.isEmpty()) {
            curateStatus = t(Res.string.vm_device_only_backup_first)
            clearSelection()
            return
        }
        if (skipped > 0) {
            curateStatus = t(Res.string.vm_skipped_unsaved_n, skipped)
        }
        curateBusy = true
        viewModelScope.launch {
            var ok = 0
            var schonDa = 0
            var umbenannt = 0
            var adoptiert = 0
            var firstError: String? = null
            for (item in batch) {
                try {
                    val r = api.importFile(
                        baseUrl, targetSpace, targetAlbum,
                        item.space, item.album, item.file,
                    )
                    ok++
                    if (r.duplicate) schonDa++
                    if (r.renamedFrom != null) umbenannt++
                    if (r.adopted) adoptiert++
                } catch (e: MpdHttpException) {
                    if (firstError == null) firstError = e.detail
                } catch (e: IOException) {
                    if (firstError == null) firstError = serverUnreachable()
                    break
                }
            }
            curateBusy = false
            // Namenskollisionen sind seit dem 08.09. kein Fehler mehr
            // (Server 4ee1b4e): bytegleich → lag schon dort, Quelle im
            // Papierkorb; anderer Inhalt → unter neuem Namen. Beides
            // ehrlich dazusagen, sonst wundert man sich ueber „_1".
            val zusatz = listOfNotNull(
                schonDa.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_dup_n, it) },
                umbenannt.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_renamed_n, it) },
                adoptiert.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_adopted_n, it) },
            ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
            curateStatus = if (firstError == null) {
                t(Res.string.vm_imported_to, ok, targetAlbum, zusatz)
            } else {
                t(Res.string.vm_imported_partial, ok, batch.size, zusatz, firstError)
            }
            clearSelection()
            loadTimeline(refresh = true)
        }
    }

    /**
     * Papierkorb-Status je Space fuer die Loesch-Rueckfrage der Timeline.
     *
     * Der Papierkorb ist eine Eigenschaft der FREIGABE: der Server prueft
     * `<space>/#recycle` (album_files.py), also ob auf der DSM-Freigabe
     * der Papierkorb eingeschaltet ist (06.09.2026). Das kann je
     * Space verschieden sein — Shared hat ihn vielleicht, das Home nicht.
     * Eine Auswahl quer ueber beide haette also zwei Schicksale, und ein
     * einzelnes recycleActive wuerde fuer die Haelfte luegen.
     */
    val recycleBySpace = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun loadRecycleStatusForSpaces(spaces: Set<String>) {
        for (space in spaces) {
            if (space.isBlank() || recycleBySpace.containsKey(space)) continue
            viewModelScope.launch {
                runCatching { api.recycleStatus(baseUrl, space) }
                    // null = der Server sagt es nicht; dann bleibt der
                    // Eintrag offen und der Dialog wird nicht konkret.
                    .onSuccess { answer -> answer?.let { recycleBySpace[space] = it } }
            }
        }
    }

    /**
     * Darf diese Datei aus der Timeline geloescht werden?
     *
     * 06.09.2026, ausdruecklich: „im Timeline darf NIE Medien
     * geloescht werden koennen, die in einem Albumordner mit album.json
     * sind." Das ist strenger als „nicht referenziert" — in einem
     * Albumordner kann eine Datei liegen, die (noch) in keiner album.json
     * steht; die waere sonst loeschbar gewesen.
     *
     * /api/albums listet genau die Ordner mit album.json (albums.py: wer
     * keine hat, wird uebersprungen). Die Albenliste ist also die Antwort
     * auf die Frage — solange sie da ist. Ist sie es nicht, gilt die Datei
     * als geschuetzt: bei „nie" ist der sichere Standard der einzige.
     *
     * Zusaetzlich bleibt die Referenz-Pruefung: eine Datei im Durchlauf
     * kann per source_album in einem Album haengen, ohne dort zu liegen.
     * Die Regel selbst steht in Regeln.darfLoeschen; hier nur der Zustand.
     */
    fun isDeletable(item: de.bgghome.mpd.api.TimelineItem): Boolean {
        // Im Zeitstrahl geht die Geraete-Aufnahme einen eigenen Weg (den
        // Android-Papierkorb), deshalb hier ausdruecklich false statt der
        // Regel — die Auswahl behandelt beide Faelle getrennt.
        if (item.localUri != null) return false
        return de.bgghome.mpd.data.Regeln.darfLoeschen(
            nurAufGeraet = false,
            referenziert = item.referenced,
            ausAlbum = false,
            space = item.space,
            album = item.album,
            darfBeitragen = canContribute(item.space),
            albumOrdner = albumOrdner,
        )
    }

    // -- Rechte (Spiegel von routers/deps.py) -------------------------------
    //
    // Die App bot bisher alles an und liess den Server 403 sagen: ein
    // Betrachter konnte markieren, den Papierkorb druecken und bekam einen
    // Fehler. Was die Rolle nicht erlaubt, soll gar nicht erst dastehen.
    //
    // Bewusst nachgebaut statt erraten — dieselben zwei Stufen wie
    // deps.py, damit die App nicht strenger und nicht lockerer ist:
    //   kuratieren  = is_readonly()  → Alben gestalten, Dateien loeschen
    //   beitragen   = can_contribute() → hochladen, einsortieren, Ausschuss
    // Weicht der Server ab, entscheidet er: seine 403 bleibt die Wahrheit,
    // die App verhindert nur den vergeblichen Griff.

    private val role: String get() = (state as? AppState.LoggedIn)?.who?.role ?: "viewer"
    private val isDemo: Boolean get() = (state as? AppState.LoggedIn)?.who?.isDemo ?: false

    // Die Regeln selbst stehen in data/Regeln.kt — dort sind sie ohne
    // Geraet pruefbar. Hier liegt nur der Zustand, den sie brauchen.

    /**
     * Rechte-Karte des Servers (`rights` in whoami, seit 3.0.272). Leer bei
     * aelteren Servern — dann greift der Rueckfall auf die Rollenregeln.
     */
    private val rights: Map<String, List<String>>
        get() = (state as? AppState.LoggedIn)?.who?.rights.orEmpty()

    /**
     * Hat das Konto dieses Recht in diesem Bestand?
     *
     * Ein Recht, das nicht in der Liste steht, ist nicht erteilt — nie auf
     * unbekannte Namen pruefen. Kennt der Server das Feld nicht, faellt es
     * auf den Nachbau zurueck; der ist schlechter, aber besser als nichts.
     */
    fun hasRight(space: String, recht: String): Boolean {
        val karte = rights
        if (karte.isEmpty()) return fallbackRight(space, recht)
        return recht in karte[space].orEmpty()
    }

    private fun fallbackRight(space: String, recht: String): Boolean = when (recht) {
        "curate", "edit" -> de.bgghome.mpd.data.Regeln.darfKuratieren(role, isDemo, space)
        "contribute" -> de.bgghome.mpd.data.Regeln.darfBeitragen(role, isDemo, space)
        "share", "contrib_link" -> de.bgghome.mpd.data.Regeln.darfKuratieren(role, isDemo, space)
        "see" -> !isDemo || space == "personal"
        else -> false
    }

    /** Darf kuratieren: Alben gestalten, Dateien loeschen. */
    fun canCurate(space: String): Boolean = hasRight(space, "curate")

    /** Darf beitragen: hochladen, einsortieren, Ausschuss wegwerfen. */
    fun canContribute(space: String): Boolean = hasRight(space, "contribute")

    /** Darf teilen — eigene Regel, folgt keiner der beiden Stufen. */
    fun canShare(space: String): Boolean = hasRight(space, "share")

    /** Darf Beisteuern-Links anlegen — haengt am Kurator-Recht. */
    fun canContribLink(space: String): Boolean = hasRight(space, "contrib_link")

    /** Ordner MIT album.json — /api/albums listet genau diese. */
    private val albumOrdner: Set<Pair<String, String>>?
        get() = albums?.map { it.space to it.name }?.toSet()

    /** Kurzform fuer die Oberflaeche: darf der Nutzer irgendwo beitragen? */
    val mayCurateAnywhere: Boolean
        get() = canCurate("shared") || canCurate("personal")

    /**
     * Dieselbe Frage fuer die Lightbox, wo ein Photo vorliegt statt eines
     * TimelineItem. Die Vollbildansicht ist der Ort, an dem man „das ist
     * Ausschuss" tatsaechlich entscheidet (06.09.2026) — die Regel
     * muss dort dieselbe sein wie im Raster, sonst hat man zwei Wahrheiten.
     */
    fun isDeletable(p: Photo, fromAlbum: Boolean = false): Boolean =
        de.bgghome.mpd.data.Regeln.darfLoeschen(
            nurAufGeraet = p.isLocal,
            referenziert = p.referenced,
            ausAlbum = fromAlbum,
            space = p.space,
            album = p.album,
            darfBeitragen = canContribute(p.space),
            albumOrdner = albumOrdner,
        )

    /**
     * Einzelnes Foto aus der Lightbox wegwerfen. Geraete-Aufnahmen gehen
     * ueber den Android-Papierkorb (die UI holt pendingTrash ab), alles
     * andere ueber den Server.
     */
    fun deleteSinglePhoto(p: Photo, fromAlbum: Boolean = false, onDone: () -> Unit) {
        // Der Kontext MUSS mit: ohne ihn scheiterte das Loeschen aus dem
        // Album an der eigenen Regel („gehoert zu einem Album"), also genau
        // dort, wo es erlaubt sein soll. Am 06.09.: „in der Lightbox
        // loescht es scheinbar gar nicht."
        if (!isDeletable(p, fromAlbum)) {
            curateStatus = t(Res.string.vm_belongs_to_album_delete_there)
            return
        }
        if (p.isLocal) {
            wegwerfenLokal(listOf(p.originalUrl))
            onDone()
            return
        }
        viewModelScope.launch {
            try {
                // Aus dem Album heraus ist Loeschen der vorgesehene Weg —
                // ohne dieses Feld schuetzt der Server die Datei mit 403.
                api.deleteFile(baseUrl, p.space, p.album, p.file, albumContext = fromAlbum)
                curateStatus = t(Res.string.vm_deleted)
                onDone()
                loadTimeline(refresh = true)
                // Und das offene Album: sonst steht die Datei dort weiter,
                // obwohl sie weg ist. Bisher wurde nur der Zeitstrahl
                // aufgefrischt — deshalb „geloescht, aber immer noch zu
                // sehen" (06.09.). Der Album-Editor braucht das
                // nicht, er nimmt das Element selbst aus der Liste
                // (removeElement); aus der Lightbox heraus gibt es kein
                // Element-Handle, also wird neu geladen.
                openAlbum?.summary?.let { openAlbum(it) }
            } catch (e: MpdHttpException) {
                curateStatus = explain(e)
                rechteNachladenNach(e)
            } catch (e: IOException) {
                curateStatus = serverUnreachable()
            }
        }
    }

    /**
     * Nach einem 403 die Rechte neu holen: Aendert ein Verwalter eine Rolle,
     * ist die App bis zum naechsten whoami veraltet — und bietet dann
     * entweder zu viel an (Knopf laeuft in den Fehler) oder zu wenig
     * (Funktion fehlt grundlos).
     */
    private fun rechteNachladenNach(e: MpdHttpException) {
        if (e.code != 403) return
        viewModelScope.launch {
            runCatching {
                val frisch = api.whoami(baseUrl)
                if (state is AppState.LoggedIn) state = AppState.LoggedIn(frisch)
            }
        }
    }

    /**
     * Server-Fehler in einen Satz uebersetzen, den man lesen kann. Der
     * Server liefert bei 403 zwar schon deutschen Text, aber er erklaert
     * die Rolle nicht — und ein leeres detail landete bisher als „null"
     * in der Meldung.
     *
     * Bewusst NICHT dasselbe wie `apiErrorText()` in ui/Bausteine.kt: das
     * nimmt bei 403 immer den Satz des Aufrufers (Modul-Hinweis), dieses
     * hier zuerst das `detail` des Servers und erst dann einen eigenen
     * Satz. Zwei Abbildungen, zwei Zwecke — Rechtefehler beim Kuratieren
     * gegen Modulsperren beim Oeffnen.
     */
    fun explain(e: MpdHttpException): String = when {
        e.detail.isNotBlank() -> e.detail
        e.code == 403 -> t(Res.string.vm_explain_403)
        e.code == 404 -> t(Res.string.vm_explain_404)
        e.code == 409 -> t(Res.string.vm_explain_409)
        else -> t(Res.string.vm_explain_other, e.code)
    }

    /** Grund, warum eine Datei bleibt — fuer die Meldung. */
    fun protectionReason(item: de.bgghome.mpd.api.TimelineItem): String? = when {
        item.localUri != null -> null
        !canContribute(item.space) -> t(Res.string.vm_reason_no_permission)
        item.referenced -> t(Res.string.vm_reason_in_album)
        albums == null -> t(Res.string.vm_reason_albums_not_loaded)
        albums.orEmpty().any { it.space == item.space && it.name == item.album } ->
            t(Res.string.vm_reason_in_album_folder)
        else -> null
    }

    /**
     * Loeschen (Ausschuss im Durchlauf): sequenziell, auch Videos.
     *
     * Einsortierte Dateien bleiben unangetastet — 06.09.2026: „es
     * darf nur löschbar sein wenn nicht in Album". Der Server erzwingt das
     * nur fuer Beitragende (album_files.py: contributor_only); ein Kurator
     * duerfte auch referenzierte Dateien loeschen, und dann klafft im Album
     * eine Luecke. Loeschen ist fuer den Ausschuss da, nicht zum Aufraeumen
     * fertiger Alben — also filtert die App vorher (isDeletable) und sagt,
     * was sie uebergangen hat.
     */
    fun deleteSelection() {
        if (curateBusy) return
        val all = timelineSelection.toList()
        // Geraete-Aufnahmen wandern nicht ueber den Server, sondern in den
        // Android-Papierkorb — 30 Tage wiederherstellbar, und das System
        // fragt selbst nach. Die UI holt sie hier ab.
        val local = all.mapNotNull { it.localUri }
        val batch = all.filter { isDeletable(it) }
        val kept = all.count { it.localUri == null && !isDeletable(it) }
        if (local.isNotEmpty()) wegwerfenLokal(local)
        if (batch.isEmpty()) {
            curateStatus = when {
                kept == 0 -> null // rein lokal: der Systemdialog uebernimmt
                kept == 1 -> t(Res.string.vm_kept_one)
                else -> t(Res.string.vm_kept_all_n, kept)
            }
            clearSelection()
            return
        }
        curateBusy = true
        viewModelScope.launch {
            var ok = 0
            var firstError: String? = null
            for (item in batch) {
                try {
                    // Zeitstrahl: ausdruecklich OHNE Album-Kontext. Was zu
                    // einem Album gehoert, wird dort geloescht, nicht hier.
                    api.deleteFile(baseUrl, item.space, item.album, item.file)
                    ok++
                } catch (e: MpdHttpException) {
                    if (firstError == null) firstError = e.detail
                } catch (e: IOException) {
                    if (firstError == null) firstError = serverUnreachable()
                    break
                }
            }
            curateBusy = false
            val keptNote = if (kept > 0) t(Res.string.vm_kept_note_n, kept) else ""
            curateStatus = if (firstError == null) t(Res.string.vm_deleted_n, ok, keptNote)
            else t(Res.string.vm_deleted_partial, ok, batch.size, firstError, keptNote)
            clearSelection()
            loadTimeline(refresh = true)
        }
    }

    // -- Favoriten (App-Extra 02.09.2026) ------------------------------------

    /** Stern-Zustand je Space und der Roundtrip ins favoriten-Album liegen
     *  in state/FavoritesState.kt. */
    val favorites = de.bgghome.mpd.state.FavoritesState(api, { baseUrl }, viewModelScope)

    // -- Modul „memories" (GET /api/memories) — seit 05.09. Vollbild-Ansicht
    //    (kein Tab): unten stehen nur die drei Grundtaetigkeiten, keine
    //    Module. Erreichbar ueber das Kopfmenue und das Erinnerungs-
    //    Karussell der Timeline.

    /** Pinnwand-Zustand, Blaettern und Farbwelt liegen in
     *  state/MemoriesState.kt; hier bleibt der Sprung ins Album. */
    val memories = de.bgghome.mpd.state.MemoriesState(api, { baseUrl }, settings, viewModelScope)

    /** Timeline-Look (dunkel/hell/auto) — Pille im Kopf zykelt. */
    var timelineTheme by mutableStateOf(settings.timelineTheme)
        private set

    fun cycleTimelineTheme() {
        val order = listOf("dunkel", "hell", "auto")
        timelineTheme = order[(order.indexOf(timelineTheme) + 1).mod(order.size)]
        settings.timelineTheme = timelineTheme
    }

    /**
     * Erinnerung antippen: Album oeffnen (im Bestand des Fotos), Foto in der Lightbox.
     * Die Pinnwand bleibt offen und kommt beim Zurueck wieder.
     *
     * `nurFoto`: ein Polaroid will das eine Bild gross sehen — schliesst
     * man die Lightbox, geht es direkt zurueck zur Pinnwand, nicht erst
     * ins Album. „noch X weitere" dagegen will ins Album; dort fuehrt erst
     * das Schliessen des Albums zurueck.
     *
     * BIS 26.09.2026 SAH MAN NICHTS: Album und Lightbox gingen auf, aber
     * die Pinnwand wird in beiden Hosts NACH ihnen gezeichnet und lag
     * darueber. Jetzt blendet der Host sie aus, solange
     * [albumAusErinnerung] gilt.
     */
    fun openMemory(photo: de.bgghome.mpd.api.MemoryPhoto, nurFoto: Boolean = true) {
        if (albumLoading) return
        albumAusErinnerung = true
        lightboxZurPinnwand = nurFoto
        // Bis 26.09.2026 stand hier fest "shared": mit der Quelle „Meine
        // Alben" fuehrte jeder Tipp ins Leere.
        openAlbum(AlbumSummary(name = photo.album, space = photo.space), showFile = photo.filename)
    }

    /** Ein aus der Pinnwand geoeffnetes Album liegt ueber ihr — der Host
     *  zeichnet die Pinnwand dann nicht. */
    var albumAusErinnerung by mutableStateOf(false)
        private set
    private var lightboxZurPinnwand = false

    /**
     * Erinnerungen als Vollbild zeigen — der Weg, den der Morgengruss
     * nimmt (Benachrichtigung → `mpd://memories`).
     *
     * ES MUSS DIESER EINE WEG SEIN, und nicht wieder ein Tab-Index:
     * der Deep-Link in MainActivity schaltete bis zum 24.09.2026 auf
     * `switchHomeTab(2)`. Das stimmte am 03.09., als Memories der dritte
     * Tab war — seit dem 05.09. sitzt dort die Suche, und Memories ist
     * eine Vollbild-Ansicht. Der Morgengruss fuehrte seither ins Leere
     * (24.09.2026: „kommt nur schwarzer Screen"). Ein Index in
     * einer Leiste, die sich aendern darf, ist kein Ziel.
     *
     * Geschlossen wird vorher alles, was darueber liegen wuerde: die
     * Pinnwand wird VOR Tours/Trails/Beisteuern/Statistik/Suche/
     * Einstellungen/Benutzer gezeichnet, ein offener Nachbar verdeckt sie
     * also vollstaendig — das waere wieder genau der schwarze Schirm.
     */
    fun zeigeErinnerungen(offset: Int = 0) {
        closeAlbum()
        closeTimelineLightbox()
        schliesseVollbildAnsichten()
        memories.open = true
        // Der Morgengruss meint den heutigen Tag, nicht den zuletzt
        // angesehenen: frisch laden statt auf den Vorrat zu vertrauen.
        memories.load(offset)
    }

    /** Alles, was ueber der Pinnwand liegt, zuklappen. Android haengt den
     *  Geraete-Papierkorb an (Unterklasse). */
    open fun schliesseVollbildAnsichten() {
        toursOpen = false
        trailsOpen = false
        contribOpen = false
        stats.open = false
        search.open = false
        settingsOpen = false
        users.open = false
    }

    // -- Modul „stats" (GET /api/stats) -------------------------------------

    /** Kopfzahlen, Text-Statistiken samt Merge und der Scan-Poll liegen in
     *  state/StatsState.kt. */
    val stats = de.bgghome.mpd.state.StatsState(api, { baseUrl }, viewModelScope)

    var state by mutableStateOf<AppState>(AppState.Checking)
        private set

    // Formularfelder der Einrichtung
    var serverUrl by mutableStateOf(settings.baseUrl ?: "")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Gesetzt, wenn der Server ein unbekanntes Zertifikat vorzeigt. */
    var pendingCert by mutableStateOf<CertInfo?>(null)
        private set

    init {
        // Greift fuer jeden Aufruf der App: laeuft irgendwo eine 403 mit
        // "must_change_password" auf, landen wir auf dem Wechsel-Bildschirm
        // statt in einer kryptischen Fehlermeldung.
        api.onMustChangePassword = {
            viewModelScope.launch {
                val current = state
                if (current !is AppState.MustChangePassword) {
                    val who = (current as? AppState.LoggedIn)?.who
                    if (who != null) state = AppState.MustChangePassword(who)
                }
            }
        }
        api.onModuleMissing = { key ->
            viewModelScope.launch { if (key !in missingModules) missingModules.add(key) }
        }
        checkStoredSession()
    }

    /**
     * Nach Anmeldung oder Sitzungspruefung: entweder normal weiter oder in
     * den erzwungenen Passwortwechsel.
     */
    private fun stateFor(who: WhoAmI): AppState =
        if (who.mustChangePassword) {
            AppState.MustChangePassword(who)
        } else {
            // Die Durchlauf-Zahl gehoert in den Kopf, und der steht auf
            // JEDEM Tab. Sie darf also nicht daran haengen, dass der
            // Zeitstrahl schon geladen wurde — genau daran ist die erste
            // Fassung gescheitert.
            AppState.LoggedIn(who).also { loadDurchlaufCount(who) }
        }

    /**
     * Holt die Durchlauf-Zahl vom Server — ein Aufruf je Bestand, in dem
     * das Konto beitragen darf, `limit=1`, gezaehlt wird serverseitig.
     * Dasselbe Vorgehen wie im Web (_loadDurchlaufCount).
     *
     * Scheitert der Aufruf, bleibt die alte Zahl stehen: eine Pille, die
     * bei jedem Netzhaenger auf 0 springt, meldet „alles erledigt" und
     * ist schlechter als eine, die kurz veraltet ist.
     */
    fun loadDurchlaufCount(who: WhoAmI? = (state as? AppState.LoggedIn)?.who) {
        val karte = who?.rights ?: return
        val spaces = listOf("shared", "personal")
            .filter { "contribute" in karte[it].orEmpty() }
        if (spaces.isEmpty()) {
            durchlaufCount = 0
            return
        }
        viewModelScope.launch {
            runCatching { spaces.sumOf { api.durchlaufTotal(baseUrl, it) } }
                .onSuccess { durchlaufCount = it }
        }
    }

    /** Beim App-Start: mit gespeichertem Cookie direkt anmelden, wenn moeglich. */
    private fun checkStoredSession() {
        val base = settings.baseUrl
        if (base == null || settings.session() == null) {
            state = AppState.Setup
            return
        }
        viewModelScope.launch {
            state = AppState.Checking
            try {
                state = stateFor(api.whoami(base).also {
                    settings.lastUser = it.user // Personenkuerzel fuer Worker
                })
            } catch (e: MpdHttpException) {
                // 401 = Sitzung abgelaufen — normale, stille Neuanmeldung
                if (e.code != 401) error = e.detail
                settings.clearSession()
                state = AppState.Setup
            } catch (e: IOException) {
                error = serverUnreachable(e)
                state = AppState.Setup
            }
        }
    }

    fun login() {
        val base = normalizeUrl(serverUrl) ?: run {
            error = t(Res.string.vm_err_invalid_server)
            return
        }
        serverUrl = base
        viewModelScope.launch {
            busy = true
            error = null
            try {
                api.login(base, username.trim(), password)
                settings.baseUrl = base
                password = ""
                vergissKontodaten()   // nie mit den Daten eines anderen Kontos starten
                state = stateFor(api.whoami(base).also {
                    settings.lastUser = it.user // Personenkuerzel fuer Worker
                })
            } catch (e: MpdHttpException) {
                error = e.detail
            } catch (e: SSLException) {
                // Unbekanntes Zertifikat: Fingerprint holen und ehrlich fragen,
                // statt die Pruefung still abzuschalten.
                try {
                    pendingCert = api.fetchServerCert(base)
                } catch (fetchError: Exception) {
                    error = t(Res.string.vm_err_tls_x, e.message)
                }
            } catch (e: IOException) {
                error = serverUnreachable(e)
            } finally {
                busy = false
            }
        }
    }

    /** Nutzer hat dem angezeigten Zertifikat vertraut → merken und erneut anmelden. */
    fun trustPendingCert() {
        settings.trustedCertSha256 = pendingCert?.sha256 ?: return
        pendingCert = null
        login()
    }

    fun dismissPendingCert() {
        pendingCert = null
    }

    // -- Alben + Lightbox ---------------------------------------------------

    var albums by mutableStateOf<List<AlbumSummary>?>(null)
        private set

    // SCROLLSTELLE DER ALBENUEBERSICHT (12.09.2026: „wenn aus Album
    // zurueck in Albenuebersicht, gleiche Stelle springen"). Sie muss im
    // ViewModel liegen, nicht im Bildschirm: die Uebersicht verlaesst beim
    // Oeffnen eines Albums die Komposition (MainActivity schaltet mit einem
    // `when` ueber openAlbum), damit stirbt jedes remember darin — auch ein
    // rememberSaveable, denn es haengt an keinem SaveableStateHolder.
    var albumsScrollIndex by mutableStateOf(0)
    var albumsScrollOffset by mutableStateOf(0)
    var albumsError by mutableStateOf<String?>(null)
        private set

    var openAlbum by mutableStateOf<OpenAlbum?>(null)
        private set
    var albumLoading by mutableStateOf(false)
        private set

    fun loadAlbums() {
        if (albums != null) return
        viewModelScope.launch {
            albumsError = null
            try {
                albums = api.albums(baseUrl)
            } catch (e: MpdHttpException) {
                // Status mit in die Meldung: der Text des Servers sagt nicht,
                // ob es ein Recht war (403), ein fehlender Bestand (404) oder
                // ein Serverfehler (500). Fruehere Diagnosezeile, jetzt
                // sichtbar statt protokolliert (16.09.2026).
                albumsError = t(Res.string.vm_err_http_x, e.code.toString(), e.detail)
            } catch (e: IOException) {
                albumsError = serverUnreachable(e)
            }
        }
    }

    fun openAlbum(summary: AlbumSummary, showFile: String? = null) {
        if (albumLoading) return
        viewModelScope.launch {
            albumLoading = true
            albumsError = null
            pendingSpace = summary.space
            try {
                val envelope = api.album(baseUrl, summary.space, summary.name)
                val content = envelope.content
                // elements bleibt UNgefiltert — die Sichtbarkeit gesperrter
                // Elemente entscheidet die Albumansicht beim Rendern (Web:
                // showLocked = editMode || meta.show_locked; frueher filterte
                // die App hier und Gesperrte "verschwanden" im Edit-Modus).
                val (photos, photoIndexById) =
                    buildPhotos(content.elements, summary.name, content.meta.showLocked)
                tourDaten.clear()
                fotoOrteCache.clear()
                openAlbum = OpenAlbum(
                    summary = summary,
                    title = content.meta.title.ifBlank { summary.name },
                    elements = content.elements,
                    showLocked = content.meta.showLocked,
                    photos = photos,
                    photoIndexById = photoIndexById,
                    mediaCount = content.elements.count { it.type == "photo" || it.type == "video" },
                    textCount = content.elements.count { it.type == "text" },
                    readOnly = content.readOnly,
                    baseModified = modifiedOf(envelope.raw),
                    rawMeta = envelope.raw["meta"]?.jsonObject ?: JsonObject(emptyMap()),
                    rawElements = envelope.raw["elements"]?.jsonArray
                        ?.map { it.jsonObject } ?: emptyList(),
                )
                // Sprung aus der Timeline: direkt zur Datei in der Lightbox
                showFile?.let { file ->
                    photos.indexOfFirst { it.file == file }.takeIf { it >= 0 }
                        ?.let { lightboxIndex = it }
                }
            } catch (e: MpdHttpException) {
                albumsError = e.detail
                albumAusErinnerung = false
                lightboxZurPinnwand = false
            } catch (e: IOException) {
                albumsError = serverUnreachable(e)
                albumAusErinnerung = false
                lightboxZurPinnwand = false
            } finally {
                albumLoading = false
            }
        }
        // Papierkorb-Status nebenbei holen — bestimmt nur den Hinweistext
        // der Loesch-Rueckfrage (Web: fetchRecycleStatus). Frisch je
        // Albumoeffnung, wie bisher; gehalten wird er seit 08.09.2026 nur
        // noch in recycleBySpace (vorher daneben ein zweites recycleActive
        // fuer den Album-Editor — zwei Wahrheiten fuer dieselbe Freigabe).
        recycleBySpace.remove(summary.space)
        loadRecycleStatusForSpaces(setOf(summary.space))
    }

    // -- Albumgestaltung (Schritt 4) ----------------------------------------
    // Verhalten wie album-edit.js/album-save.js: Aenderungen landen sofort im
    // Modell, gespeichert wird per Autosave 2 s nach der letzten Aenderung
    // (scheduleAutoSave) und spaetestens beim Verlassen des Edit-Modus.

    var isEditMode by mutableStateOf(false)
        private set

    /** id des Textelements, dessen Editor gerade offen ist, sonst null. */
    var editingElementId by mutableStateOf<String?>(null)
        private set

    var saveState by mutableStateOf(SaveState.Idle)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set

    private var autoSaveJob: Job? = null
    private var saveIdleJob: Job? = null

    // -- Rueckgaengig / Wiederherstellen (Web: undoStack, MAX_UNDO) --------
    //
    // Jede Aenderung im Bearbeiten-Modus legt den Album-Stand DAVOR ab
    // (OpenAlbum ist unveraenderlich, das Ablegen kostet nichts); Rueckgaengig
    // tauscht ihn zurueck und speichert wie jede andere Aenderung. Beim
    // Verlassen des Bearbeitens und beim Albumwechsel ist der Verlauf weg.
    private val undoStapel = ArrayDeque<OpenAlbum>()
    private val redoStapel = ArrayDeque<OpenAlbum>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun merkeUndo(vorher: OpenAlbum) {
        if (!isEditMode) return
        undoStapel.addLast(vorher)
        while (undoStapel.size > 50) undoStapel.removeFirst()
        redoStapel.clear()
        canUndo = true; canRedo = false
    }

    private fun verlaufLeeren() {
        undoStapel.clear(); redoStapel.clear()
        canUndo = false; canRedo = false
    }

    fun undo() {
        val jetzt = openAlbum ?: return
        val vorher = undoStapel.removeLastOrNull() ?: return
        redoStapel.addLast(jetzt)
        openAlbum = vorher
        canUndo = undoStapel.isNotEmpty(); canRedo = true
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    fun redo() {
        val jetzt = openAlbum ?: return
        val danach = redoStapel.removeLastOrNull() ?: return
        undoStapel.addLast(jetzt)
        openAlbum = danach
        canRedo = redoStapel.isNotEmpty(); canUndo = true
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    fun toggleEditMode() {
        if (isEditMode) {
            editingElementId = null
            isEditMode = false
            verlaufLeeren()
            // "Fertig" speichert — Autosave ist der Normalfall (Web ebenso).
            if (saveState == SaveState.Unsaved || saveState == SaveState.Error) saveNow()
        } else if (openAlbum?.readOnly == false) {
            isEditMode = true
            frischenStandHolen()
        }
    }

    /**
     * Den Serverstand holen, SOLANGE nichts zu verlieren ist.
     *
     * Warum das den Konfliktdialog fast abschafft (12.09.2026:
     * „aber Aenderungen werden doch immer sofort gespeichert"): Richtig,
     * zwei Sekunden nach der letzten Aenderung. Die Luecke ist nicht das
     * Speichern, sondern die Zeit, in der ein Album offen LIEGT. Beim
     * Oeffnen merkt sich die App den Stand von diesem Moment; aendert
     * jemand danach im Browser etwas, ist dieser Stand veraltet, und die
     * App weiss es nicht — sie zeigt sogar einen veralteten Inhalt. Die
     * naechste eigene Aenderung speichert dann gegen den alten Stand, und
     * der Server sagt zu Recht Nein. Zwischen beiden Handlungen koennen
     * Stunden liegen.
     *
     * Der richtige Zeitpunkt ist VOR der ersten Aenderung, nicht vor dem
     * Speichern. Vorher liegt nichts Eigenes vor, das Neuladen kostet also
     * nichts. Danach waere es gefaehrlich: die App wuerde mit frischem
     * Stempel ihre eigene, alte Elementliste hochschreiben und eine fremde
     * Ergaenzung stillschweigend loeschen — genau das automatische
     * Ueberschreiben, das der Schutz verhindern soll.
     *
     * Alternative — das Album sperren, solange es woanders offen
     * ist — waere teurer und haette ihn selbst am oeftesten getroffen: eine
     * Sperre muss jemand wieder wegraeumen, und ein zugeschlagener
     * Browser-Tab sagt dem Server nichts.
     */
    fun frischenStandHolen() {
        if (openAlbum == null) return
        if (saveState == SaveState.Unsaved || saveState == SaveState.Error) return
        if (autoSaveJob?.isActive == true) return
        reloadAlbum()
    }

    fun startTextEdit(id: String) {
        if (isEditMode) editingElementId = id
    }

    fun closeTextEdit() {
        editingElementId = null
    }

    /**
     * Ein Element in typisierter UND roher Liste aendern, dann Autosave —
     * gemeinsamer Unterbau aller Feld-Edits.
     */
    private fun updateElement(
        id: String,
        typed: (de.bgghome.mpd.api.PdxElement) -> de.bgghome.mpd.api.PdxElement,
        raw: (JsonObject) -> JsonObject,
    ) {
        val album = openAlbum ?: return
        openAlbum = album.copy(
            elements = album.elements.map { if (it.id == id) typed(it) else it },
            rawElements = album.rawElements.map {
                if (it["id"]?.jsonPrimitive?.contentOrNull == id) raw(it) else it
            },
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /** Wie handleTextChange im Web: trimmen, Modell aendern, Autosave planen. */
    fun commitTextEdit(id: String, newText: String) {
        val trimmed = newText.trim()
        val old = openAlbum?.elements?.find { it.id == id } ?: return
        if (old.text.orEmpty() == trimmed) return
        updateElement(
            id,
            typed = { it.copy(text = trimmed) },
            raw = { JsonObject(it + ("text" to JsonPrimitive(trimmed))) },
        )
    }

    /** Fuer „Rueckgaengig" nach dem Entfernen (Web: Undo-Toast). */
    private data class RemovedElement(
        val typed: de.bgghome.mpd.api.PdxElement,
        val raw: JsonObject,
        val typedIndex: Int,
        val rawIndex: Int,
    )

    private var lastRemoved: RemovedElement? = null

    /**
     * Element aus dem Album nehmen — Web removePhotoFromAlbum/deleteTextBlock:
     * bewusst OHNE Rueckfrage, die Absicherung ist „Rueckgaengig" (Snackbar,
     * im Web Toast + Undo-Stack). Die Datei bleibt liegen (Overlay-Prinzip).
     * Liefert den Anzeigenamen fuers Snackbar-Textchen, oder null.
     */
    /**
     * @param autoSave false, wenn der SERVER die album.json schon
     *   angepasst hat — dann ist hier nur die Anzeige nachzuziehen. Ein
     *   Speichern waere nicht nur unnoetig, es lief garantiert in den
     *   Konfliktschutz (Befund 12.09.2026, siehe performDeleteFile).
     */
    fun removeElement(id: String, autoSave: Boolean = true): String? {
        val album = openAlbum ?: return null
        val typedIndex = album.elements.indexOfFirst { it.id == id }
        val rawIndex = album.rawElements
            .indexOfFirst { it["id"]?.jsonPrimitive?.contentOrNull == id }
        if (typedIndex < 0 || rawIndex < 0) return null
        val typed = album.elements[typedIndex]
        lastRemoved = RemovedElement(typed, album.rawElements[rawIndex], typedIndex, rawIndex)

        val newTyped = album.elements.filterIndexed { i, _ -> i != typedIndex }
        val (photos, photoIndexById) = buildPhotos(newTyped)
        openAlbum = album.copy(
            elements = newTyped,
            rawElements = album.rawElements.filterIndexed { i, _ -> i != rawIndex },
            photos = photos,
            photoIndexById = photoIndexById,
            textCount = album.textCount - if (typed.type == "text") 1 else 0,
            mediaCount = album.mediaCount -
                if (typed.type == "photo" || typed.type == "video") 1 else 0,
        )
        if (editingElementId == id) editingElementId = null
        if (autoSave) {
            merkeUndo(album)
            saveState = SaveState.Unsaved
            scheduleAutoSave()
        }
        return typed.file ?: typed.label ?: typed.title
            ?: typed.text?.take(30)?.takeIf { it.isNotBlank() } ?: typed.type
    }

    /** „Rueckgaengig" der Snackbar: an alter Stelle wieder einsetzen. */
    fun undoRemove() {
        val removed = lastRemoved ?: return
        lastRemoved = null
        val album = openAlbum ?: return
        val newTyped = album.elements.toMutableList()
            .apply { add(removed.typedIndex.coerceAtMost(size), removed.typed) }
        val (photos, photoIndexById) = buildPhotos(newTyped)
        openAlbum = album.copy(
            elements = newTyped,
            rawElements = album.rawElements.toMutableList()
                .apply { add(removed.rawIndex.coerceAtMost(size), removed.raw) },
            photos = photos,
            photoIndexById = photoIndexById,
            textCount = album.textCount + if (removed.typed.type == "text") 1 else 0,
            mediaCount = album.mediaCount +
                if (removed.typed.type == "photo" || removed.typed.type == "video") 1 else 0,
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /**
     * Web toggleShowLocked: meta.show_locked kippen und neu rendern.
     * Bewusste Abweichung: die App speichert die Wahl sofort per Autosave —
     * im Web ueberlebt sie nur, wenn zufaellig noch etwas anderes speichert.
     */
    fun toggleShowLocked() {
        val album = openAlbum ?: return
        val newValue = !album.showLocked
        val (photos, photoIndexById) = buildPhotos(album.elements, showLocked = newValue)
        openAlbum = album.copy(
            showLocked = newValue,
            photos = photos,
            photoIndexById = photoIndexById,
            rawMeta = JsonObject(album.rawMeta + ("show_locked" to JsonPrimitive(newValue))),
        )
        if (!album.readOnly) {
            merkeUndo(album)
            saveState = SaveState.Unsaved
            scheduleAutoSave()
        }
    }

    /** Web toggleLocked: locked=true setzen, sonst Schluessel entfernen. */
    fun toggleLocked(id: String) {
        val old = openAlbum?.elements?.find { it.id == id } ?: return
        val nowLocked = !old.locked
        updateElement(
            id,
            typed = { it.copy(locked = nowLocked) },
            raw = {
                if (nowLocked) JsonObject(it + ("locked" to JsonPrimitive(true)))
                else JsonObject(it - "locked")
            },
        )
    }

    /** Trenner-Label — Web: separator-label-edit; leer entfernt den Schluessel. */
    fun setSeparatorLabel(id: String, label: String) {
        val trimmed = label.trim()
        val old = openAlbum?.elements?.find { it.id == id } ?: return
        if (old.label.orEmpty() == trimmed) return
        updateElement(
            id,
            typed = { it.copy(label = trimmed.ifEmpty { null }) },
            raw = {
                if (trimmed.isEmpty()) JsonObject(it - "label")
                else JsonObject(it + ("label" to JsonPrimitive(trimmed)))
            },
        )
    }

    /** Text-Stil — Web: handleStyleChange; "default" entfernt den Schluessel
     *  (der Server poppt style=="default" ohnehin). */
    fun setTextStyle(id: String, style: String) {
        updateElement(
            id,
            typed = { it.copy(style = style.takeIf { s -> s != "default" }) },
            raw = {
                if (style == "default") JsonObject(it - "style")
                else JsonObject(it + ("style" to JsonPrimitive(style)))
            },
        )
    }

    /** Quellenangabe am Zitat (PDX v1.5): leer entfernt den Schluessel. */
    fun setQuoteSource(id: String, source: String) {
        val trimmed = source.trim()
        val old = openAlbum?.elements?.find { it.id == id } ?: return
        if (old.source.orEmpty() == trimmed) return
        updateElement(
            id,
            typed = { it.copy(source = trimmed.ifEmpty { null }) },
            raw = {
                if (trimmed.isEmpty()) JsonObject(it - "source")
                else JsonObject(it + ("source" to JsonPrimitive(trimmed)))
            },
        )
    }

    /** Link dessen Dialog gerade zum Bearbeiten offen ist (Web: link-edit-btn). */
    var editingLinkId by mutableStateOf<String?>(null)

    /** Web submitLinkDialog im Bearbeiten-Zweig: url Pflicht, Rest optional. */
    fun updateLink(id: String, url: String, title: String, note: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val cleanTitle = title.trim()
        val cleanNote = note.trim()
        updateElement(
            id,
            typed = {
                it.copy(
                    url = cleanUrl,
                    title = cleanTitle.ifEmpty { null },
                    note = cleanNote.ifEmpty { null },
                )
            },
            raw = {
                val base = it - "title" - "note"
                var out = base + ("url" to JsonPrimitive(cleanUrl))
                if (cleanTitle.isNotEmpty()) out = out + ("title" to JsonPrimitive(cleanTitle))
                if (cleanNote.isNotEmpty()) out = out + ("note" to JsonPrimitive(cleanNote))
                JsonObject(out)
            },
        )
    }

    // -- Datei physisch loeschen (Web: deletePhotoFile, nur type=photo) -----

    /** Foto, dessen Loesch-Rueckfrage gerade offen ist. */
    var pendingDelete by mutableStateOf<de.bgghome.mpd.api.PdxElement?>(null)
        private set

    /** Ergebnismeldung des Loeschens (war-Cover-Hinweis oder Fehler). */
    var deleteNotice by mutableStateOf<String?>(null)
        private set

    fun requestDeleteFile(elem: de.bgghome.mpd.api.PdxElement) {
        pendingDelete = elem
    }

    /**
     * Tour-Kachel in den Papierkorb (13.11.2026): erst das Element heraus
     * und das Album speichern, DANN die GPX loeschen. Die Reihenfolge ist
     * Pflicht — der Server sperrt das Loeschen mit 409, solange ein Album
     * die Datei nennt (TOURS.md §4.2). Scheitert das Speichern, kommt das
     * Element zurueck und nichts ist geloescht. Kein „Rueckgaengig": die
     * Datei liegt im Papierkorb der Freigabe, nicht in der App.
     */
    private fun performDeleteTour(elem: de.bgghome.mpd.api.PdxElement) {
        val album = openAlbum ?: return
        val file = elem.file ?: return
        viewModelScope.launch {
            removeElement(elem.id, autoSave = false)
            if (!speichereSofort()) {
                undoRemove()
                meldung(t(Res.string.tour_save_failed))
                return@launch
            }
            lastRemoved = null
            try {
                val recycled = api.deleteTourFile(baseUrl, album.summary.name, file)
                meldung(
                    t(if (recycled) Res.string.tours_recycled else Res.string.tours_deleted_no_bin),
                    kurz = true,
                )
            } catch (e: Exception) {
                meldung(t(Res.string.tours_not_deleted, e.message ?: t(Res.string.err_generic)))
            }
        }
    }

    fun cancelDeleteFile() {
        pendingDelete = null
    }

    fun clearDeleteNotice() {
        deleteNotice = null
    }

    /** Nach der Rueckfrage: Datei loeschen, Element raus — OHNE Undo (Web:
     *  "file is physically gone, undo would be misleading"). */
    fun performDeleteFile() {
        val elem = pendingDelete ?: return
        pendingDelete = null
        if (elem.type == "tour") return performDeleteTour(elem)
        val album = openAlbum ?: return
        val file = elem.file ?: return
        viewModelScope.launch {
            try {
                val res =
                    api.deleteFile(
                        baseUrl, album.summary.space, album.summary.name, file,
                        albumContext = true, // der Album-Editor IST die Album-Ansicht
                    )
                // „ALBUM WURDE WOANDERS GEAENDERT" BEIM EIGENEN LOESCHEN
                // (12.09.2026: „immer doch diese dadellige Warnung,
                // wenn ich am Handy paar Bilder aus Album loesche").
                //
                // Woanders war niemand — es war die App selbst. Der Server
                // nimmt beim Loeschen den Eintrag SELBST aus der album.json
                // und stempelt meta.modified neu (bewusst so, seit
                // 06.09.2026, sonst holte ein spaeterer Save das geloeschte
                // Element still zurueck). Die App speicherte danach
                // trotzdem — mit dem Stempel von VOR der Loeschung. Der
                // Konfliktschutz sah zu Recht eine fremde Aenderung und
                // fragte. Jedes Mal, unvermeidlich.
                //
                // Hat der Server die Datei schon ausgetragen, ist hier nur
                // die Anzeige nachzuziehen. Der frische Stempel muss aber
                // mitkommen, sonst laeuft der NAECHSTE echte Save in
                // dasselbe Messer.
                if (res.removedFromJson) {
                    removeElement(elem.id, autoSave = false)
                    runCatching {
                        api.album(baseUrl, album.summary.space, album.summary.name)
                    }.getOrNull()?.let { env ->
                        openAlbum = openAlbum?.copy(baseModified = modifiedOf(env.raw))
                    }
                } else {
                    removeElement(elem.id)
                }
                lastRemoved = null // kein „Rueckgaengig" — die Datei ist weg
                if (res.wasThumbnail) {
                    deleteNotice =
                        t(Res.string.vm_cover_deleted_x, file)
                }
            } catch (e: MpdHttpException) {
                deleteNotice = t(Res.string.vm_err_delete_failed_x, e.detail)
            } catch (e: IOException) {
                deleteNotice = t(Res.string.vm_err_delete_failed_x, e.message)
            }
        }
    }

    /** Trenner-Stil — Web: changeSepStyle; "line" entfernt den Schluessel. */
    fun setSeparatorStyle(id: String, style: String) {
        updateElement(
            id,
            typed = { it.copy(style = style.takeIf { s -> s != "line" }) },
            raw = {
                if (style == "line") JsonObject(it - "style")
                else JsonObject(it + ("style" to JsonPrimitive(style)))
            },
        )
    }

    /** Web generateId(): max numerischer id + 1, vierstellig. */
    private fun generateId(album: OpenAlbum): String =
        (album.rawElements
            .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 1).toString().padStart(4, '0')

    /**
     * Neues Element hinter [afterId] einfuegen (null = Albumanfang — wie
     * splice(insertAfterIndex+1) im Web bei insertAfterIndex -1), in
     * typisierter UND roher Liste, dann Autosave.
     */
    private fun insertElement(
        afterId: String?,
        typedElem: de.bgghome.mpd.api.PdxElement,
        rawElem: JsonObject,
    ) {
        val album = openAlbum ?: return
        fun <T> List<T>.inserted(item: T, idOf: (T) -> String?): List<T> {
            val idx = if (afterId == null) -1 else indexOfFirst { idOf(it) == afterId }
            return toMutableList().apply { add(idx + 1, item) }
        }

        val newTyped = album.elements.inserted(typedElem) { it.id }
        val (photos, photoIndexById) = buildPhotos(newTyped)
        openAlbum = album.copy(
            elements = newTyped,
            rawElements = album.rawElements
                .inserted(rawElem) { it["id"]?.jsonPrimitive?.contentOrNull },
            photos = photos,
            photoIndexById = photoIndexById,
            textCount = album.textCount + if (typedElem.type == "text") 1 else 0,
            mediaCount = album.mediaCount +
                if (typedElem.type == "photo" || typedElem.type == "video") 1 else 0,
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /**
     * Web addPhotoToAlbum: Element ist nur {id, type, file} — resolution und
     * blurhash traegt der Server beim naechsten Album-GET nach
     * (_enrich_photo_meta). Die Thumbnail-/Original-URLs sind synthetisch
     * (gleiches Muster wie routers/albums.py), damit die Kachel sofort
     * rendert; bis zum Nachladen gilt Seitenverhaeltnis 1,5 wie im Web.
     */
    fun addMediaElement(afterId: String?, file: String, type: String): String? {
        val album = openAlbum ?: return null
        if (!isEditMode) return null
        val newId = generateId(album)
        val space = album.summary.space
        val name = album.summary.name
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = type, file = file,
                thumbnails = de.bgghome.mpd.api.Thumbnails(
                    sm = MpdApi.thumbnailPath(space, name, file, "sm"),
                    m = MpdApi.thumbnailPath(space, name, file, "m"),
                    xl = MpdApi.thumbnailPath(space, name, file, "xl"),
                    full = MpdApi.thumbnailPath(space, name, file, "full"),
                ),
                original = MpdApi.originalPath(space, name, file),
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive(type))
                put("file", JsonPrimitive(file))
            },
        )
        return newId
    }

    /** Web enableMetaEdit: leerer Titel faellt auf den alten zurueck. */
    fun setAlbumTitle(title: String) {
        val album = openAlbum ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == album.title) return
        openAlbum = album.copy(
            title = trimmed,
            rawMeta = JsonObject(album.rawMeta + ("title" to JsonPrimitive(trimmed))),
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /** Web enableMetaEdit: Jahr nur im Bereich 1800–2099 uebernehmen. */
    fun setAlbumYear(year: Int) {
        val album = openAlbum ?: return
        if (year < 1800 || year > 2099 || year == album.summary.year) return
        openAlbum = album.copy(
            summary = album.summary.copy(year = year),
            rawMeta = JsonObject(album.rawMeta + ("year" to JsonPrimitive(year))),
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /** Web insertTextAtScrollPosition: leerer Text, Editor direkt offen. */
    fun addTextElement(afterId: String?) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val newId = generateId(album)
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(id = newId, type = "text", text = ""),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(""))
            },
        )
        editingElementId = newId
    }

    /** Web insertSeparatorAtScrollPosition: label/style nur wenn gesetzt. */
    fun addSeparator(afterId: String?, label: String, style: String) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val newId = generateId(album)
        val trimmed = label.trim()
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = "separator",
                label = trimmed.ifEmpty { null },
                style = style.takeIf { it != "line" },
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("separator"))
                if (trimmed.isNotEmpty()) put("label", JsonPrimitive(trimmed))
                if (style != "line") put("style", JsonPrimitive(style))
            },
        )
    }

    /** Web insertLinkAtScrollPosition: url Pflicht, title/note optional. */
    fun addLink(afterId: String?, url: String, title: String, note: String) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        val newId = generateId(album)
        val cleanTitle = title.trim()
        val cleanNote = note.trim()
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = "link", url = cleanUrl,
                title = cleanTitle.ifEmpty { null },
                note = cleanNote.ifEmpty { null },
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("link"))
                put("url", JsonPrimitive(cleanUrl))
                if (cleanTitle.isNotEmpty()) put("title", JsonPrimitive(cleanTitle))
                if (cleanNote.isNotEmpty()) put("note", JsonPrimitive(cleanNote))
            },
        )
    }

    /** Web openAddAudioModal/openAddDocumentModal: Element {id, type, file}.
     *  Fuer Nicht-PDF-Dokumente synthetische Thumb-URLs wie bei Fotos, damit
     *  die Kachel sofort rendert; PDFs bekommen die document_url. */
    fun addFolderFile(afterId: String?, file: String, type: String) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val newId = generateId(album)
        val space = album.summary.space
        val name = album.summary.name
        val isPdf = file.lowercase().endsWith(".pdf")
        // Vorschau-Adressen sofort selbst bauen — wie bei Fotos aus anderen
        // Alben (addPhotoFromOtherAlbum). Bis 08.09.2026 bekamen nur
        // Dokumente welche; ein aus dem Ordner eingefuegtes Foto hatte
        // thumbnails == null, die Kachel zeichnete NICHTS, und auch nach
        // dem Speichern holte niemand die Serverdaten nach („jetzt
        // ist kein Bild mehr angezeigt im Album, wohl kein refresh"). Die
        // Aufloesung kennt erst der Server; refreshEnrichment() holt sie
        // nach dem naechsten Speichern still nach.
        val thumbs = when (type) {
            "photo", "document" -> if (isPdf) null else de.bgghome.mpd.api.Thumbnails(
                sm = MpdApi.thumbnailPath(space, name, file, "sm"),
                m = MpdApi.thumbnailPath(space, name, file, "m"),
                xl = MpdApi.thumbnailPath(space, name, file, "xl"),
                full = MpdApi.thumbnailPath(space, name, file, "full"),
            )
            // /api/thumbnail antwortet fuer Videos 415 — der FFmpeg-Thumb
            // liegt unter /api/video-thumb (wie im Zeitstrahl).
            "video" -> MpdApi.videoThumbPath(space, name, file).let {
                de.bgghome.mpd.api.Thumbnails(sm = it, m = it, xl = it)
            }
            else -> null
        }
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = type, file = file,
                thumbnails = thumbs,
                original = if (type == "photo") MpdApi.originalPath(space, name, file) else null,
                documentUrl = if (type == "document" && isPdf)
                    MpdApi.documentPath(space, name, file)
                else null,
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive(type))
                put("file", JsonPrimitive(file))
            },
        )
    }

    /** Web addTourToAlbum: nur {id, type, file} — Defaults gelten. */
    /** [sourceAlbum]: die GPX liegt in einem anderen Album (Antwort von
     *  /api/tours/place) — das Element zeigt dorthin, wie beim Foto. */
    fun addTour(afterId: String?, filename: String, sourceAlbum: String? = null) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val newId = generateId(album)
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = "tour", file = filename, sourceAlbum = sourceAlbum,
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("tour"))
                put("file", JsonPrimitive(filename))
                if (sourceAlbum != null) put("source_album", JsonPrimitive(sourceAlbum))
            },
        )
    }

    /** Tour-Elemente des offenen Albums, die auf genau diese Datei zeigen
     *  (Datei im Albumordner oder per source_album in einem anderen). */
    fun tourElementeFuer(folder: String, file: String): List<de.bgghome.mpd.api.PdxElement> {
        val album = openAlbum ?: return emptyList()
        return album.elements.filter {
            it.type == "tour" && it.file == file && (it.sourceAlbum ?: album.summary.name) == folder
        }
    }

    /** Tour-Element auf eine andere Datei umstellen — Platz, Titel und alle
     *  anderen Angaben bleiben (Tausch Huawei ↔ Komoot, 11.10.2026). */
    fun ersetzeTourDatei(id: String, file: String, sourceAlbum: String?) {
        updateElement(
            id,
            typed = { it.copy(file = file, sourceAlbum = sourceAlbum) },
            raw = { roh ->
                val neu = roh.toMutableMap()
                neu["file"] = JsonPrimitive(file)
                if (sourceAlbum != null) neu["source_album"] = JsonPrimitive(sourceAlbum)
                else neu.remove("source_album")
                JsonObject(neu)
            },
        )
    }

    /**
     * Sofort speichern und warten, ob es geklappt hat. Noetig, bevor eine
     * Tour geloescht wird, die eben aus dem Album genommen wurde: der
     * Server prueft used_in an der GESPEICHERTEN album.json und sagt sonst
     * 409.
     */
    suspend fun speichereSofort(): Boolean {
        autoSaveJob?.cancel()
        while (saveState == SaveState.Saving) delay(200)
        val album = openAlbum ?: return false
        save(album)
        return saveState == SaveState.Saved
    }

    /** Web insertMapFromDialog: pins [{lat,lon,label?}], title optional. */
    fun addMap(afterId: String?, title: String, pins: List<de.bgghome.mpd.api.MapPin>) {
        val album = openAlbum ?: return
        if (!isEditMode || pins.isEmpty()) return
        val newId = generateId(album)
        val cleanTitle = title.trim()
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = "map", pins = pins,
                title = cleanTitle.ifEmpty { null },
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("map"))
                put("pins", JsonArray(pins.map { p ->
                    buildJsonObject {
                        put("lat", JsonPrimitive(p.lat))
                        put("lon", JsonPrimitive(p.lon))
                        p.label?.takeIf { it.isNotBlank() }
                            ?.let { put("label", JsonPrimitive(it)) }
                    }
                }))
                if (cleanTitle.isNotEmpty()) put("title", JsonPrimitive(cleanTitle))
            },
        )
    }

    /** Mixed-Picker (PDX v1.4): Foto aus anderem Album per source_album. */
    fun addPhotoFromOtherAlbum(afterId: String?, sourceAlbum: String, file: String) {
        val album = openAlbum ?: return
        if (!isEditMode) return
        val newId = generateId(album)
        val space = album.summary.space
        insertElement(
            afterId,
            de.bgghome.mpd.api.PdxElement(
                id = newId, type = "photo", file = file, sourceAlbum = sourceAlbum,
                thumbnails = de.bgghome.mpd.api.Thumbnails(
                    sm = MpdApi.thumbnailPath(space, sourceAlbum, file, "sm"),
                    m = MpdApi.thumbnailPath(space, sourceAlbum, file, "m"),
                    xl = MpdApi.thumbnailPath(space, sourceAlbum, file, "xl"),
                    full = MpdApi.thumbnailPath(space, sourceAlbum, file, "full"),
                ),
                original = MpdApi.originalPath(space, sourceAlbum, file),
            ),
            buildJsonObject {
                put("id", JsonPrimitive(newId))
                put("type", JsonPrimitive("photo"))
                put("file", JsonPrimitive(file))
                put("source_album", JsonPrimitive(sourceAlbum))
            },
        )
    }

    /**
     * Cover setzen (Web openThumbnailModal): lokales Foto direkt in
     * meta.thumbnail; referenziertes erst per cover-from-source kopieren.
     */
    fun setAlbumCover(elem: de.bgghome.mpd.api.PdxElement, onDone: (String?) -> Unit) {
        val album = openAlbum ?: return
        val file = elem.file ?: return
        viewModelScope.launch {
            val thumbName = if (elem.sourceAlbum != null) {
                try {
                    api.coverFromSource(
                        baseUrl, album.summary.space, album.summary.name,
                        elem.sourceAlbum, file,
                    )
                } catch (e: Exception) {
                    onDone(e.message ?: t(Res.string.vm_err_copy_failed))
                    return@launch
                }
            } else file
            openAlbum = (openAlbum ?: return@launch).let { current ->
                current.copy(
                    summary = current.summary.copy(thumbnail = thumbName),
                    rawMeta = JsonObject(current.rawMeta + ("thumbnail" to JsonPrimitive(thumbName))),
                )
            }
            albums = null // Albenliste beim naechsten Besuch frisch laden (neues Cover)
            saveState = SaveState.Unsaved
            scheduleAutoSave()
            onDone(null)
        }
    }

    private fun scheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            delay(2000)
            save(openAlbum ?: return@launch)
        }
    }

    fun saveNow() {
        val album = openAlbum ?: return
        autoSaveJob?.cancel()
        viewModelScope.launch { save(album) }
    }

    /** Geloeschtes Album fuer die Rueckgaengig-Snackbar der Uebersicht. */
    data class DeletedAlbum(
        val space: String, val name: String, val title: String, val backup: String,
    )

    var pendingUndoDelete by mutableStateOf<DeletedAlbum?>(null)

    /** Web _confirmDeleteAlbum: loescht NUR album.json, merkt das Backup
     *  fuer den Rueckgaengig-Toast und kehrt zur Uebersicht zurueck. */
    fun deleteCurrentAlbum(onError: (String) -> Unit) {
        val album = openAlbum ?: return
        viewModelScope.launch {
            try {
                val backup = api.deleteAlbum(baseUrl, album.summary.space, album.summary.name)
                pendingUndoDelete = backup?.let {
                    DeletedAlbum(album.summary.space, album.summary.name, album.title, it)
                }
                closeAlbum()
                albums = null // Uebersicht frisch laden
            } catch (e: Exception) {
                onError(e.message ?: t(Res.string.vm_err_delete_failed))
            }
        }
    }

    /** Web _undoDeleteAlbum: Backup zurueckspielen, Liste neu laden. */
    fun undoDeleteAlbum(onError: (String) -> Unit) {
        val snap = pendingUndoDelete ?: return
        pendingUndoDelete = null
        viewModelScope.launch {
            try {
                api.undoDeleteAlbum(baseUrl, snap.space, snap.name, snap.backup)
                albums = null
                loadAlbums()
            } catch (e: Exception) {
                onError(e.message ?: t(Res.string.vm_err_restore_failed))
            }
        }
    }

    /** true = Konfliktdialog „Album wurde woanders geaendert" ist offen. */
    var conflictOpen by mutableStateOf(false)
        private set

    /** Antwort des Konfliktdialogs: neu laden (Aenderungen verwerfen) oder
     *  bewusst ueberschreiben (Basis leeren → Save ohne Konflikt-Header). */
    fun resolveConflict(reload: Boolean) {
        conflictOpen = false
        if (reload) {
            autoSaveJob?.cancel()
            saveState = SaveState.Idle
            reloadAlbum()
        } else {
            openAlbum = openAlbum?.copy(baseModified = null)
            saveNow()
        }
    }

    /** true = Rueckfrage „Album wirklich leer speichern?" ist offen. */
    var emptySaveConfirmOpen by mutableStateOf(false)
        private set
    private var emptySaveApproved = false

    /** Antwort der Leer-Speichern-Rueckfrage (Web saveAlbumData-Guard). */
    fun answerEmptySave(confirmed: Boolean) {
        emptySaveConfirmOpen = false
        if (confirmed) {
            emptySaveApproved = true
            saveNow()
        } else {
            saveState = SaveState.Unsaved
        }
    }

    private suspend fun save(album: OpenAlbum) {
        // Laeuft schon ein Save, in 2 s erneut versuchen statt still verwerfen.
        if (saveState == SaveState.Saving) {
            scheduleAutoSave()
            return
        }
        // Web saveAlbumData-Guard: alle Elemente entfernt → einmal ehrlich
        // nachfragen, statt das Drehbuch still zu leeren.
        if (album.rawElements.isEmpty() && !emptySaveApproved) {
            emptySaveConfirmOpen = true
            return
        }
        emptySaveApproved = false
        saveIdleJob?.cancel()
        saveState = SaveState.Saving
        saveError = null
        val body = updateBody(album.rawMeta, album.rawElements)
        try {
            val responseText = api.updateAlbum(
                baseUrl, album.summary.space, album.summary.name, body,
                baseModified = album.baseModified,
            )
            // Neues meta.modified als naechste Konflikt-Basis uebernehmen
            // (Vertrag: update-Antwort traegt "modified"; alter Server ohne
            // das Feld → Basis leeren, dann geht kein Header mehr mit und
            // es bleibt beim Last-Write-Wins).
            val newModified = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(responseText)
                    .jsonObject["modified"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            openAlbum = openAlbum?.let { current ->
                current.copy(
                    baseModified = newModified,
                    rawMeta = newModified?.let {
                        JsonObject(current.rawMeta + ("modified" to JsonPrimitive(it)))
                    } ?: current.rawMeta,
                )
            }
            saveState = SaveState.Saved
            saveIdleJob = viewModelScope.launch {
                delay(2000)
                if (saveState == SaveState.Saved) saveState = SaveState.Idle
            }
            refreshEnrichment()
        } catch (e: MpdHttpException) {
            if (e.code == 409) {
                // Album wurde woanders geaendert — ehrlicher Dialog statt
                // stillem Ueberschreiben (Entscheidung vom 02.09.2026).
                saveState = SaveState.Unsaved
                conflictOpen = true
                return
            }
            saveState = SaveState.Error
            saveError = e.detail
        } catch (e: IOException) {
            saveState = SaveState.Error
            saveError = serverUnreachable(e)
        }
    }

    /**
     * Nach dem Speichern die berechneten Felder des Servers nachholen —
     * thumbnails, original, resolution, blurhash, error — fuer Elemente, die
     * die App selbst eingefuegt hat und die deshalb nur {id, type, file}
     * kennen (08.09.2026). Ohne das stimmt bis zum naechsten Oeffnen des
     * Albums die Reihenhoehe nicht (Seitenverhaeltnis 1.5 statt echt) und
     * ein Foto ohne Vorschau-Adresse bleibt leer.
     *
     * Still und ohne den Bearbeiten-Modus zu verlassen: es wird nur in die
     * typisierten Elemente gemischt, die rohen bleiben, wie sie sind (die
     * berechneten Felder gehen ohnehin nie zum Server zurueck).
     */
    private suspend fun refreshEnrichment() {
        val album = openAlbum ?: return
        val needs = album.elements.any {
            (it.type == "photo" || it.type == "video") &&
                it.error == null && (it.thumbnails == null || it.resolution == null)
        }
        if (!needs) return
        val env = runCatching {
            api.album(baseUrl, album.summary.space, album.summary.name)
        }.getOrNull() ?: return
        val fresh = env.content.elements.associateBy { it.id }
        val current = openAlbum ?: return
        if (current.summary != album.summary) return
        val merged = current.elements.map { e ->
            val f = fresh[e.id] ?: return@map e
            e.copy(
                thumbnails = f.thumbnails ?: e.thumbnails,
                original = f.original ?: e.original,
                resolution = f.resolution ?: e.resolution,
                blurhash = f.blurhash ?: e.blurhash,
                error = f.error,
                editableFormat = f.editableFormat,
            )
        }
        if (merged == current.elements) return
        val (photos, photoIndexById) = buildPhotos(merged)
        openAlbum = current.copy(
            elements = merged, photos = photos, photoIndexById = photoIndexById,
        )
    }

    /** allImages des Webs: nur Fotos (ohne Videos, ohne Fehler) — Lightbox-Reihenfolge. */
    private fun buildPhotos(
        elements: List<de.bgghome.mpd.api.PdxElement>,
        albumName: String? = null,
        showLocked: Boolean = openAlbum?.showLocked ?: true,
    ): Pair<List<Photo>, Map<String, Int>> {
        val name = albumName ?: openAlbum?.summary?.name.orEmpty()
        val space = openAlbum?.summary?.space
        // Gesperrte Fotos gehoeren nur in die Lightbox, wenn show_locked es
        // erlaubt — wie allImages im Web (View-Modus-Sicht).
        val photoElements = elements.filter {
            it.type == "photo" && it.thumbnails != null && it.error == null &&
                it.original != null && (showLocked || !it.locked)
        }
        val photos = photoElements.map { e ->
            val t = e.thumbnails!!
            Photo(
                file = e.file.orEmpty(),
                space = space ?: pendingSpace.orEmpty(),
                album = e.sourceAlbum ?: name,
                mUrl = baseUrl + t.m,
                xlUrl = baseUrl + t.xl,
                fullUrl = t.full.takeIf { it.isNotBlank() }?.let { baseUrl + it },
                originalUrl = baseUrl + e.original!!,
                resolution = e.resolution,
                editableFormat = e.editableFormat,
            )
        }
        return photos to photoElements.withIndex().associate { (i, e) -> e.id to i }
    }

    /** Space des gerade ladenden Albums — buildPhotos laeuft VOR openAlbum=. */
    private var pendingSpace: String? = null

    /** Album frisch vom Server holen (nach Umbenennen/EXIF-Rewrite). */
    fun reloadAlbum() {
        openAlbum?.summary?.let { openAlbum(it) }
    }

    // -- Timeline (GET /api/timeline, Modul „timeline") ---------------------

    /** 0 = Alben, 1 = Zeitstrahl — Tab der Startansicht. */
    var homeTab by mutableStateOf(settings.homeTab)
        private set

    fun switchHomeTab(tab: Int) {
        homeTab = tab
        settings.homeTab = tab
    }

    // -- Modul-Entitlement (Schaufenster statt 403) -------------------------

    /** Modul-Key, dessen Schaufenster-Dialog offen ist (ui/ModuleShowcase). */
    var lockedModuleInfo by mutableStateOf<String?>(null)

    /**
     * Module, deren Endpunkte dieser Server gar nicht hat (FastAPIs nackte
     * 404, gemeldet ueber api.onModuleMissing). Rueckfall, falls
     * whoami.modules einmal nicht stimmt; gilt bis zum Abmelden.
     */
    private val missingModules = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * Freigeschaltet laut /api/whoami — siehe [moduleFree]. Die Wahrheit
     * erzwingt ohnehin der Server (require_module).
     */
    fun moduleAllowed(key: String): Boolean {
        val who = (state as? AppState.LoggedIn)?.who ?: return true
        return de.bgghome.mpd.ui.moduleFree(key, who.modules, missingModules)
    }

    /** Modul oeffnen oder — wenn gesperrt — das Schaufenster zeigen. */
    fun tryOpenModule(key: String, open: () -> Unit) {
        if (moduleAllowed(key)) open() else lockedModuleInfo = key
    }

    val timelineItems = androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()
    var timelineLoading by mutableStateOf(false)
        private set
    var timelineError by mutableStateOf<String?>(null)
        private set
    private var timelineCursor: String? = null
    private var timelineEnd = false
    private var timelineJob: Job? = null

    /**
     * Zeitstrahl komplett laden; [refresh] = von vorn. Cursor-Paging laut
     * Vertrag, aber EIFRIG bis zum Ende: Bei grossen Bibliotheken (Vorgabe:
     * 17.500+ Dateien) liegen die kuratierten Alben tausende Eintraege
     * unter den mtime-datierten Neuzugaengen — mit Nachladen-beim-Scrollen
     * waren sie praktisch unerreichbar und der Schnell-Scroller umspannte
     * nur das bereits Geladene. Metadaten sind klein (~5 MB gesamt bei
     * 500er-Seiten, dem Servermaximum); die Ansicht baut sich progressiv
     * pro Seite auf.
     */
    fun loadTimeline(refresh: Boolean = false) {
        if (refresh) {
            // Jedes Loeschen, Einsortieren und Sichern laeuft hier durch —
            // der guenstigste Ort, die Zahl im Kopf nachzuziehen.
            loadDurchlaufCount()
            // Ein laufender Lauf wird abgebrochen und von vorn begonnen.
            // Bis 08.09.2026 wurde ein refresh bei laufendem Laden still
            // verworfen — wer waehrend des eifrigen Erstladens (bei 17.500
            // Dateien Sekunden) loeschte oder einsortierte, sah danach
            // den alten Stand.
            timelineJob?.cancel()
            timelineItems.clear()
            timelineCursor = null
            timelineEnd = false
            timelineError = null
        } else if (timelineJob?.isActive == true) {
            return
        }
        if (timelineEnd) return
        timelineJob = viewModelScope.launch {
            timelineLoading = true
            try {
                do {
                    // Ein Wiederholungsversuch je Seite (11.09.2026
                    // unterwegs: „die Timeline war instabil, da kam mal
                    // Server nicht erreichbar"). Der Zeitstrahl holt bei
                    // 17.500 Dateien rund 36 Seiten am Stueck — ueber
                    // Mobilfunk genuegte bisher EIN Aussetzer, und der
                    // ganze Lauf war verloren, auch die 35 gelungenen.
                    val page = try {
                        api.timeline(baseUrl, timelineCursor, limit = 500)
                    } catch (e: IOException) {
                        delay(1500)
                        api.timeline(baseUrl, timelineCursor, limit = 500)
                    }
                    timelineItems.addAll(page.items)
                    timelineCursor = page.nextCursor
                    timelineEnd = page.nextCursor == null
                    rebuildTimelineFeed()
                } while (!timelineEnd)
                timelineError = null
            } catch (e: MpdHttpException) {
                timelineError = if (e.code == 403) {
                    moduleLockedText("Timeline")
                } else e.detail
            } catch (e: IOException) {
                // Der Teilstand BLEIBT stehen: timelineCursor zeigt auf die
                // letzte gelungene Seite, timelineEnd ist false — ein
                // spaeterer Aufruf macht genau dort weiter, statt von vorn.
                timelineError = serverUnreachable(e)
            } finally {
                // Ein abgebrochener Lauf darf den Nachfolger nicht auf
                // „fertig" setzen — der hat timelineLoading gerade selbst
                // gesetzt.
                if (isActive) {
                    timelineLoading = false
                    timelineLoadedAt = System.currentTimeMillis()
                }
            }
        }
    }

    // -- Was von aussen hereinkommt (06.09.2026) ----------------------------
    //
    // „Teilen mit MPD" aus einer beliebigen App, und Bilder ansehen. Das
    // Manifest kannte bis heute nur MAIN/LAUNCHER — fuer Android war die
    // App unsichtbar, obwohl sie eine Galerie sein soll.


    // -- Gemischter Strom: Server + Geraet (05.09.2026) ---------------------
    //
    // Thomas wollte den Google-Fotos-Strom: „lokale Aufnahmen sofort
    // sichtbar, mit Sicherungsstatus". Bis dahin war ein Foto unsichtbar,
    // bis das Backup es hochgeladen hatte.

    /** Alles vom Geraet, ungefiltert (siehe LocalMedia.scan). */
    val localItems = androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()

    /** Was der Zeitstrahl zeigt: Server + noch nicht gesicherte Aufnahmen. */
    val timelineFeed =
        androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()

    /**
     * Zaehlt bei jedem Neuaufbau des Stroms hoch — der Schluessel, an dem
     * der Zeitstrahl sein Raster neu rechnet. Bis 08.09.2026 hing das an
     * `timelineFeed.size`: wanderte nach einem Backup-Lauf eine Aufnahme
     * von „nur Geraet" zu „Server", blieb die Anzahl gleich, das Raster
     * veraltet, und die durchgestrichene Wolke stand weiter an einem Foto,
     * das laengst oben war.
     */
    var timelineFeedVersion by mutableStateOf(0)
        private set

    /**
     * Wie viel im Durchlauf liegt — die Zahl auf der Kopf-Pille.
     *
     * Sie kommt vom SERVER, aus demselben Aufruf, den das Web macht
     * (MpdApi.durchlaufTotal). Zwei Anlaeufe waren vorher falsch, und
     * beide Fehler sind es wert, hier zu stehen:
     *
     *  1. Erst zaehlte die App selbst, ueber jede Serverdatei ohne
     *     album.json. Der Server unterscheidet aber drei Ordnerarten und
     *     laesst die dritte („kopierter Ordner, wartet auf Album")
     *     draussen — unsere Zahl war groesser als die im Web.
     *     Vorgabe, 08.09.2026: „es sind da dann nur 311."
     *  2. Danach zaehlte sie richtig, aber ueber `timelineItems` — und
     *     die sind leer, solange man den Zeitstrahl nicht geoeffnet hat.
     *     Auf dem Alben-Tab stand deshalb GAR KEINE Zahl. Rueckmeldung: „bei
     *     Durchlauf steht jetzt gar keine Zahl mehr."
     *
     * Beide Male dieselbe Ursache: eine Zahl im Kopf haengt an nichts,
     * was der Kopf selbst kennt. Jetzt wird sie einmal beim Anmelden
     * geholt und nach jedem Einsortieren aufgefrischt.
     */
    var durchlaufCount by mutableStateOf(0)
        private set

    /** Einmal je Prozess fragen, nicht bei jedem Wechsel auf den Tab. */
    var localMediaAsked = false

    /** Geraeteordner, die im Zeitstrahl NICHT erscheinen (leer = alle). */
    var timelineHiddenBuckets by mutableStateOf(settings.timelineHiddenBuckets)
        private set
    var timelineFoldersOpen by mutableStateOf(false)
    var deviceFolderList by mutableStateOf<List<de.bgghome.mpd.data.LocalFolder>?>(null)

    fun loadDeviceFolderList() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val found = plattform.localFolders()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                deviceFolderList = found.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun toggleTimelineBucket(bucket: String) {
        val next = if (bucket in timelineHiddenBuckets) timelineHiddenBuckets - bucket
        else timelineHiddenBuckets + bucket
        timelineHiddenBuckets = next
        settings.timelineHiddenBuckets = next
        loadLocalMedia()
    }

    /**
     * content://-Adressen, die in den Android-Papierkorb sollen. Die UI
     * baut daraus den System-Dialog (MediaStore.createTrashRequest) — nur
     * die Activity darf ihn starten, das ViewModel kennt keine.
     */
    var pendingTrash by mutableStateOf<List<String>>(emptyList())

    /**
     * Lokale Dateien wegwerfen. Android: ueber den System-Dialog, den die
     * Oberflaeche aus [pendingTrash] baut. Desktop: direkt in den
     * Papierkorb des Systems — die Rueckfrage hat die App vorher gestellt
     * (Plattform.papierkorbFragtSelbst).
     */
    private fun wegwerfenLokal(uris: List<String>) {
        if (uris.isEmpty()) return
        if (plattform.papierkorbFragtSelbst) {
            pendingTrash = uris
            return
        }
        viewModelScope.launch {
            val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                plattform.inDenPapierkorb(uris)
            } ?: 0
            clearSelection()
            curateStatus = if (n == uris.size) plural(Res.plurals.vm_trashed_pc_n, n)
            else t(Res.string.vm_trash_pc_failed, uris.size - n)
            loadLocalMedia()
        }
    }

    // -- Desktop: Ordner auf diesem PC (04.10.2026) --------------------------

    /** Die gewaehlten PC-Ordner, sortiert — Compose-Zustand fuer den Dialog. */
    var lokaleOrdner by mutableStateOf(settings.lokaleOrdner.sorted())
        private set

    fun lokalenOrdnerHinzufuegen(pfad: String) {
        val neu = (settings.lokaleOrdner + pfad).toSet()
        settings.lokaleOrdner = neu
        lokaleOrdner = neu.sorted()
        loadDeviceFolderList()
        loadLocalMedia()
    }

    fun lokalenOrdnerEntfernen(pfad: String) {
        val neu = settings.lokaleOrdner - pfad
        settings.lokaleOrdner = neu
        lokaleOrdner = neu.sorted()
        loadDeviceFolderList()
        loadLocalMedia()
    }

    /** „3 / 10 hochgeladen …" waehrend „Sichern" im Vordergrund laeuft
     *  (Desktop); die Auswahl-Leiste zeigt es statt der Auswahlzahl. */
    var uploadFortschritt by mutableStateOf<String?>(null)
        private set

    /** Adresse des unveraenderten Originals einer Server-Datei (auch Video). */
    fun originalUrl(item: de.bgghome.mpd.api.TimelineItem): String =
        baseUrl + (item.original ?: MpdApi.originalPath(item.space, item.album, item.file))

    /**
     * Markierte Server-Fotos herunterladen (Desktop, Thomas 04.10.2026:
     * „nach dem Markieren will man diese auf dem Rechner runterladen").
     * Nacheinander, Fortschritt in der Leiste wie beim Sichern. Dateien
     * vom PC selbst werden uebersprungen — sie liegen ja schon hier.
     */
    fun herunterladen(laden: suspend (url: String, name: String) -> String?) {
        if (curateBusy) return
        val items = timelineSelection.filter { it.localUri == null }
        if (items.isEmpty()) {
            curateStatus = t(Res.string.vm_download_only_pc)
            return
        }
        curateBusy = true
        clearSelection()
        viewModelScope.launch {
            var ok = 0
            var fehler: String? = null
            var ordner: String? = null
            try {
                for ((i, item) in items.withIndex()) {
                    uploadFortschritt = t(Res.string.vm_download_progress, i, items.size)
                    try {
                        laden(originalUrl(item), item.file)?.let {
                            ok++
                            ordner = java.io.File(it).parent
                        }
                    } catch (e: MpdHttpException) {
                        if (fehler == null) fehler = "${item.file}: ${explain(e)}"
                    } catch (e: IOException) {
                        if (fehler == null) fehler = serverUnreachable()
                        break
                    }
                }
            } finally {
                curateBusy = false
                uploadFortschritt = null
            }
            curateStatus = when {
                ok == items.size -> plural(Res.plurals.vm_downloaded_n, ok, ok, ordner ?: "")
                else -> t(Res.string.vm_downloaded_partly, ok, items.size, fehler ?: "")
            }
        }
    }

    fun trashFinished(ok: Boolean, count: Int) {
        pendingTrash = emptyList()
        clearSelection()
        if (ok) {
            curateStatus = plural(Res.plurals.vm_trashed_n, count)
            loadLocalMedia()
        }
    }

    // -- Papierkorb des Geraets: ansehen, zurueckholen, endgueltig weg ------

    /**
     * Lokal bearbeitetes Bild, das auf die Zustimmung zum Ueberschreiben
     * wartet. Fremde Mediendateien darf eine App nur mit
     * MediaStore.createWriteRequest aendern — den Dialog stellt Android,
     * die UI holt das hier ab.
     */
    var pendingLocalEdit by mutableStateOf<Pair<String, ByteArray>?>(null)

    /** true, solange lokal gerechnet wird (grosse Bilder dauern). */
    var localEditBusy by mutableStateOf(false)
        internal set

    fun localEditFinished(ok: Boolean) {
        pendingLocalEdit = null
        if (ok) {
            curateStatus = t(Res.string.vm_saved)
            loadLocalMedia()
        }
    }

    /** Obergrenze fuers Mehrfach-Weitergeben — Share-Sheet und Cache
     *  sind fuer den Urlaubsabend gedacht, nicht fuer das ganze Jahr. */
    val shareMax = 20

    /**
     * „Jetzt sichern" — ausdruecklich angeforderte Geraete-Aufnahmen.
     *
     * Der Grund (07.09.2026: „kann man per Tap auf nicht
     * upgeloadete Bilder diese uploaden?"): Das Kamera-Backup holt nur,
     * was in einem ueberwachten Ordner liegt UND nach dem Einschalten
     * dazukam. Alles andere — ein Bild aus WhatsApp, ein Screenshot, eine
     * Aufnahme von vorher — wird nie gesichert. Der Zeitstrahl zeigte es
     * trotzdem dauerhaft mit der durchgestrichenen Wolke, ohne dass es
     * einen Weg gab. Jetzt ist die Wolke der Weg.
     */
    fun sichereJetzt(keys: List<String>) {
        val zu = keys.distinct().filter { it.isNotBlank() }
        if (zu.isEmpty()) return
        if (!plattform.hatLokaleOrdner) {
            plattform.sichereJetzt(zu)
            curateStatus = plural(Res.plurals.vm_backing_up_n, zu.size)
            clearSelection()
            return
        }
        // Desktop (04.10.2026): kein WorkManager, also im Vordergrund und
        // nacheinander. Die Leiste zeigt den Fortschritt; danach wird neu
        // geladen, damit die durchgestrichene Wolke verschwindet.
        if (curateBusy) return
        curateBusy = true
        clearSelection()
        uploadFortschritt = t(Res.string.vm_upload_progress, 0, zu.size)
        viewModelScope.launch {
            val r = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    plattform.sichereUndWarte(zu) { fertig, gesamt ->
                        viewModelScope.launch {
                            uploadFortschritt = t(Res.string.vm_upload_progress, fertig, gesamt)
                        }
                    }
                }
            } finally {
                curateBusy = false
                uploadFortschritt = null
            }
            if (r == null) return@launch
            curateStatus = when {
                r.ok == r.gesamt -> plural(Res.plurals.vm_backed_up_n, r.ok)
                else -> t(Res.string.vm_backed_up_partly, r.ok, r.gesamt, r.fehler ?: "")
            }
            settings.backupLastRun = System.currentTimeMillis()
            loadTimeline(refresh = true)
            loadLocalMedia()
        }
    }

    /**
     * Was der Zaehler-Chip meldet und auf einen Tipp hin sichert: nur
     * Aufnahmen aus UEBERWACHTEN Ordnern, die noch nicht oben sind.
     *
     * Nicht alles Ungesicherte im Zeitstrahl — der zeigt auch WhatsApp,
     * Screenshots und Download, wenn man sie nicht ausblendet. Diese
     * Ordner hat man BEWUSST nicht ins Backup genommen; ein Chip, der
     * sie zaehlt und auf einen Tipp hin geschlossen auf die NAS schiebt,
     * traefe eine Entscheidung, die der Nutzer schon anders getroffen
     * hat (07.09.2026: „was soll eigentlich jetzt sichern?").
     *
     * Die Regel: massenhaft nur, was das Backup ohnehin haette holen
     * sollen (aber nicht konnte, weil aelter als der Einschaltzeitpunkt);
     * einzeln alles, wenn man es ausdruecklich sagt — dafuer sind die
     * Wolke auf der Kachel und „Sichern" in der Auswahl da, die beide
     * KEINEN Ordnerfilter haben.
     *
     * localKey ist nicht nullbar, localUri ist das Kennzeichen; `album`
     * traegt bei Geraete-Aufnahmen den Ordnernamen (LocalMedia.scan).
     */
    /** Spiegel der ueberwachten Ordner als Compose-Zustand, damit
     *  [ungesichert] nach einer Aenderung im Backup-Dialog neu rechnet.
     *  Schreibt die Android-Seite (BackupState.toggleBucket). */
    var backupBuckets by mutableStateOf(settings.backupBuckets)
        internal set

    /** Backup-Dialog offen? Den Dialog selbst zeichnet Android (MainActivity),
     *  der Schalter liegt hier, weil Kopfzeile und Einstellungen ihn setzen. */
    var backupDialogOpen by mutableStateOf(false)

    /** Papierkorb des Geraets zeigen — nur Android hat einen (Unterklasse). */
    open fun zeigePapierkorb() {}

    val ungesichert: List<String>
        get() {
            val ordner = backupBuckets
            return timelineFeed
                .filter {
                    it.localUri != null && it.localKey.isNotBlank() &&
                        it.album in ordner
                }
                .map { it.localKey }
        }

    /** Zeitpunkt des letzten Timeline-Ladens (ms) — Vergleichsmarke fuer
     *  backupLastRun. */
    private var timelineLoadedAt = 0L

    /**
     * Ist seit dem letzten Laden ein Backup-Lauf durchgelaufen, dann still
     * nachladen. Die App erfaehrt vom Worker sonst nur Fehler, keine
     * Erfolge — wer sie offen liess, sah die durchgestrichene Wolke an
     * einem Foto weiter, das laengst auf der NAS lag (06.09.2026).
     */
    fun refreshAfterBackup() {
        if (timelineItems.isEmpty()) return
        if (settings.backupLastRun <= timelineLoadedAt) return
        loadTimeline(refresh = true)
        loadLocalMedia()
    }

    /** Der laufende Geraete-Scan — der letzte Aufruf gewinnt. Ohne das
     *  konnten zwei Scans mit verschiedenem Ordnerfilter ueberlappen und
     *  der aeltere zuletzt schreiben. */
    private var localMediaJob: Job? = null

    fun loadLocalMedia() {
        localMediaJob?.cancel()
        localMediaJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val hidden = timelineHiddenBuckets
            val found = plattform.scanLocalMedia(hidden)
            val ledger = plattform.ledgerKeys()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                localItems.clear()
                localItems.addAll(found)
                backupLedger = ledger
                rebuildTimelineFeed()
            }
        }
    }

    private var backupLedger: Set<String> = emptySet()

    /**
     * Server-Bestand und Geraet zusammenfuehren. Doppelte zu vermeiden ist
     * die eigentliche Arbeit — eine Aufnahme liegt nach dem Upload zweimal
     * vor. Drei Wege, absteigend nach Verlaesslichkeit:
     *
     *  1. Das Backup-Ledger (MediaStore-id). Sicher, aber nur fuer das, was
     *     diese App selbst hochgeladen hat, und weg nach Neuinstallation.
     *  2. Der Sekundenstempel: UploadWorker legt `2026-06-21_11-24-30-x.jpg`
     *     an, der Aufnahmezeitpunkt steht also im Server-Dateinamen.
     *  3. Originalname + Aufnahmetag — faengt den Altbestand, den frueher
     *     Synology Photos hochgeladen hat (dort blieben die Namen erhalten).
     *
     * Bleibt eine Aufnahme uebrig, liegt sie nur auf dem Geraet und kommt
     * mit `localUri` in den Strom.
     *
     * Die Entscheidung selbst trifft `Regeln.nurAufDemGeraet` — die Fassung
     * mit Tests. Bis 08.09.2026 stand hier eine zweite, handgeschriebene
     * Kopie derselben Logik, und nur die lief in der App; die getestete
     * wurde nie aufgerufen. Hier werden nur noch die Vergleichsmengen
     * gebaut und hineingereicht.
     */
    fun rebuildTimelineFeed() {
        val serverNameDay = HashSet<String>(timelineItems.size * 2)
        val serverStamps = HashSet<String>(timelineItems.size * 2)
        for (item in timelineItems) {
            serverNameDay.add(de.bgghome.mpd.data.Regeln.nameTagSchluessel(item.file, item.date))
            if (item.file.length >= 19) serverStamps.add(item.file.take(19))
        }
        val onlyOnDevice = de.bgghome.mpd.data.Regeln.nurAufDemGeraet(
            lokal = localItems.toList(),
            ledger = backupLedger,
            serverStempel = serverStamps,
            serverNameTag = serverNameDay,
            ledgerSchluessel = { it.localKey },
            stempel = { de.bgghome.mpd.data.Stempel.vonIso(it.date) },
            nameTag = { de.bgghome.mpd.data.Regeln.nameTagSchluessel(it.file, it.date) },
        )
        // Space-Filter erst HIER, nicht in der Abfrage: der Abgleich oben
        // (nur auf dem Geraet? schon gesichert?) braucht den ganzen
        // Serverbestand — mit gefilterter Abfrage galten Fotos im anderen
        // Space als ungesichert. Was nur auf dem Geraet liegt, gehoert
        // niemandem sonst: es zaehlt zu „Persoenlich", nicht zu „Geteilt".
        val filter = _spaceFilter
        val server = if (filter == null) timelineItems else timelineItems.filter { it.space == filter }
        val geraet = if (filter == "shared") emptyList() else onlyOnDevice
        timelineFeed.clear()
        if (geraet.isEmpty()) {
            timelineFeed.addAll(server)
        } else {
            timelineFeed.addAll(
                (server + geraet).sortedByDescending { it.date },
            )
        }
        // Das Gegenstueck: was auf dem Geraet liegt UND nachweislich oben
        // ist — die Menge fuer „Speicherplatz freigeben". Bewusst aus
        // derselben Rechnung wie der Strom, nie eine zweite Regel.
        val nurGeraet = onlyOnDevice.mapTo(HashSet()) { it.localKey }
        gesichertAufGeraet = localItems.filter { it.localKey !in nurGeraet }
        timelineFeedVersion++
    }

    /**
     * Geraete-Aufnahmen, die schon auf der NAS liegen — Google Fotos'
     * „Speicherplatz freigeben" (08.09.2026, Galerie-Prueffrage:
     * koennte jemand danach Google Fotos deinstallieren?). Nur was das
     * Ledger, der Sekundenstempel oder Name+Tag als gesichert erkennt;
     * nichts wird geraten.
     */
    var gesichertAufGeraet by mutableStateOf<List<de.bgghome.mpd.api.TimelineItem>>(emptyList())
        private set

    /** Bytes der gesicherten Aufnahmen auf dem Geraet (MediaStore.SIZE). */
    val gesichertBytes: Long
        get() = gesichertAufGeraet.sumOf { it.size }

    /**
     * Alle gesicherten Aufnahmen in den Papierkorb des Geraets — ueber
     * denselben Systemdialog wie das Wegwerfen im Zeitstrahl. Endgueltig
     * frei wird der Platz im TrashScreen; die Kopie auf der NAS bleibt.
     */
    fun speicherFreigeben() {
        val uris = gesichertAufGeraet.mapNotNull { it.localUri }
        if (uris.isEmpty()) return
        pendingTrash = uris
    }

    /** Video-Stream direkt per URL abspielen (Timeline, ohne Albumkontext). */
    fun openVideoUrl(url: String) {
        videoUrl = url
    }

    /** Zeitstrahl-Lightbox: chronologischer Pager (Fotos) + Startindex. */
    var timelineLightbox by mutableStateOf<Pair<List<Photo>, Int>?>(null)
        internal set

    fun closeTimelineLightbox() {
        timelineLightbox = null
    }

    private fun de.bgghome.mpd.api.TimelineItem.toPhoto() = if (localUri != null) {
        // Geraete-Aufnahme: eine einzige Quelle in allen Groessen. EXIF- und
        // Edit-Endpunkte greifen hier nicht — die Datei kennt der Server ja
        // noch gar nicht.
        Photo(
            file = file, space = "", album = album,
            mUrl = localUri, xlUrl = localUri, fullUrl = localUri,
            originalUrl = localUri, resolution = resolution,
            referenced = false, isLocal = true,
        )
    } else Photo(
        file = file,
        space = space,
        album = album,
        mUrl = baseUrl + (thumbnails?.m ?: MpdApi.thumbnailPath(space, album, file, "m")),
        xlUrl = baseUrl + (thumbnails?.xl ?: MpdApi.thumbnailPath(space, album, file, "xl")),
        fullUrl = thumbnails?.full?.takeIf { it.isNotBlank() }?.let { baseUrl + it }
            ?: (baseUrl + MpdApi.thumbnailPath(space, album, file, "full")),
        originalUrl = baseUrl + (original ?: MpdApi.originalPath(space, album, file)),
        resolution = resolution,
        referenced = referenced,
        editableFormat = editableFormat,
    )

    /**
     * Tipp auf eine Timeline-Kachel. Fotos (referenziert oder nicht)
     * oeffnen die ECHTE Lightbox mit chronologischem Wischen durch den
     * Zeitstrahl — die Lightbox haengt seit 02.09.2026 nicht mehr am
     * Albumkontext (Photo traegt space+album selbst). Videos: referenziert
     * → Album; unkuratiert → Player direkt.
     */
    fun openFromTimeline(item: de.bgghome.mpd.api.TimelineItem) {
        if (item.type == "video") {
            // Tippen spielt ab — auch wenn das Video in einem Album steckt.
            // Bis zum 06.09.2026 sprang ein referenziertes Video stattdessen
            // ins Album; dasselbe Tippen tat also je nach Medientyp etwas
            // voellig anderes. Rueckmeldung: „die sollen auch nicht da gleich
            // hinspringen, man will es nur wissen" — wissen kann man es im
            // Info-Blatt, wechseln soll niemand ungefragt.
            item.localUri?.let { openVideoUrl(it); return }
            val base = baseUrl.toHttpUrlOrNull() ?: return
            openVideoUrl(
                base.newBuilder()
                    .addPathSegment("api").addPathSegment("video")
                    .addPathSegment(item.space).addPathSegment(item.album)
                    .addPathSegment(item.file).build().toString(),
            )
            return
        }
        val photoItems = timelineFeed.filter { it.type == "photo" }
        val index = photoItems.indexOfFirst {
            it.space == item.space && it.album == item.album && it.file == item.file
        }
        if (index >= 0) {
            timelineLightbox = photoItems.map { it.toPhoto() } to index
        }
    }

    // -- Foto-Editor / EXIF (Lightbox) --------------------------------------

    /**
     * Datei → Zeitstempel des letzten Edits dieser Sitzung. Seit dem
     * Server-ETag (Cache-Control: no-cache, 01.09.2026) ist KEIN
     * ?t=-Cache-Bust mehr noetig — Coil revalidiert selbst, der
     * Original-Cache prueft per If-None-Match. Die Map dient nur noch als
     * Recompose-Ausloeser, damit die offene Lightbox-Seite nach dem
     * Speichern sofort neu laedt statt erst beim naechsten Oeffnen.
     */
    val editVersions = androidx.compose.runtime.mutableStateMapOf<String, Long>()

    fun bumpEditVersion(file: String) {
        editVersions[file] = System.currentTimeMillis()
    }

    /**
     * Element [id] vor/hinter [targetId] einsortieren — Drop des Silent
     * Sorting (Web: handleDragEnd). Id-basiert, damit typisierte (sichtbare)
     * und rohe (ungefilterte) Liste konsistent bleiben; die Lightbox-
     * Reihenfolge wird wie im Web aus der neuen Elementfolge neu aufgebaut.
     */
    fun moveElement(id: String, targetId: String, before: Boolean) {
        if (id == targetId) return
        val album = openAlbum ?: return

        fun <T> List<T>.moved(idOf: (T) -> String?): List<T>? {
            val item = firstOrNull { idOf(it) == id } ?: return null
            val without = filterNot { idOf(it) == id }
            val ti = without.indexOfFirst { idOf(it) == targetId }
            if (ti < 0) return null
            return without.toMutableList().apply { add(if (before) ti else ti + 1, item) }
        }

        val newTyped = album.elements.moved { it.id } ?: return
        val newRaw = album.rawElements
            .moved { it["id"]?.jsonPrimitive?.contentOrNull } ?: return
        if (newTyped == album.elements) return

        val (photos, photoIndexById) = buildPhotos(newTyped)
        openAlbum = album.copy(
            elements = newTyped,
            rawElements = newRaw,
            photos = photos,
            photoIndexById = photoIndexById,
        )
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }

    /** Lightbox-Index eines Foto-Elements der Albumansicht, oder null. */
    fun photoIndexOf(elem: de.bgghome.mpd.api.PdxElement): Int? =
        openAlbum?.photoIndexById?.get(elem.id)

    /** Index in OpenAlbum.photos, wenn die Lightbox offen ist; sonst null. */
    var lightboxIndex by mutableStateOf<Int?>(null)
        private set

    fun openLightbox(photoIndex: Int) {
        lightboxIndex = photoIndex
    }

    /**
     * Geladene Tour-Daten des offenen Albums, je Element (27.09.2026).
     *
     * Das Album ist eine LazyColumn: Scrollt eine Tour aus dem Bild, wirft
     * Compose ihren `remember`-Zustand weg. Beim Zurueckscrollen lief deshalb
     * alles von vorn — GPX holen und parsen, fuer jedes Foto auf der Spur ein
     * Vorschaubild laden, Karte bauen. Das war das Ruckeln („wenn man durch
     * ein Album am Tablet/Handy scrollt und da Touren eingebaut sind hakelt
     * es"). Der Wert ist plattformeigen (Android: Bitmaps, Desktop: URLs),
     * daher `Any`. Geleert mit dem Album.
     */
    val tourDaten = HashMap<String, Any>()
    private val tourLaeufe = HashMap<String, kotlinx.coroutines.Deferred<Any>>()

    /**
     * Ortsangaben der Fotos je Bereich und Zeitraum (Schluessel
     * [de.bgghome.mpd.util.fotoOrtSchluessel]), fuer „Fotos auf der Tour"
     * (05.11.2026, Koordinaten zuerst). Geleert mit dem Album wie [tourDaten].
     */
    private val fotoOrteCache = HashMap<String, Map<String, de.bgghome.mpd.util.FotoOrt>>()

    /**
     * Ortsangaben aller Fotos des Bereichs zwischen zwei Aufnahmetagen.
     * Ein aelterer Server ohne die Route (404) und ein Netzfehler ergeben
     * eine leere Liste: die Tour-Karte faellt dann fuer jedes Foto auf die
     * Uhrzeit zurueck, wie vor dem 05.11.2026 — die Spur selbst ist zu dem
     * Zeitpunkt schon geladen, ein zweiter Fehlertext brächte nichts.
     */
    suspend fun fotoOrte(space: String, von: String, bis: String): Map<String, de.bgghome.mpd.util.FotoOrt> {
        val key = "$space|$von|$bis"
        fotoOrteCache[key]?.let { return it }
        val orte = try {
            api.photosGeo(baseUrl, space, von, bis).photos
                .filter { it.lat.isFinite() && it.lon.isFinite() }
                .associate {
                    de.bgghome.mpd.util.fotoOrtSchluessel(it.space, it.album, it.file) to
                        de.bgghome.mpd.util.FotoOrt(it.lat, it.lon)
                }
        } catch (e: MpdHttpException) {
            emptyMap()
        } catch (e: IOException) {
            emptyMap()
        }
        fotoOrteCache[key] = orte
        return orte
    }

    /**
     * Tour-Daten holen — aus [tourDaten] oder per [laden]. Laufen Vorwaermer
     * und Element gleichzeitig an dieselbe Tour, wird nur einmal geladen:
     * der Zweite wartet auf den Lauf des Ersten.
     *
     * [laden] laeuft auf Default (29.09.2026, ANR beim Scrollen): der
     * viewModelScope ist Main, und was der Lader nicht selbst auslagerte,
     * rechnete auf dem UI-Thread — der Vorwaermer gleich fuer alle Touren
     * des Albums hintereinander. Nur der Eintrag in [tourDaten] bleibt auf
     * Main (die Map wird von Composables gelesen).
     */
    suspend fun tourHolen(key: String, laden: suspend () -> Any): Any {
        tourDaten[key]?.let { return it }
        val lauf = tourLaeufe.getOrPut(key) {
            viewModelScope.async {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { laden() }
                    .also { tourDaten[key] = it }
            }
        }
        try {
            return lauf.await()
        } finally {
            if (lauf.isCompleted) tourLaeufe.remove(key)
        }
    }

    fun closeLightbox() {
        lightboxIndex = null
        // Polaroid auf der Pinnwand: das Album war nur der Traeger des Fotos.
        if (lightboxZurPinnwand) closeAlbum()
    }

    /** Absolute Stream-URL eines video-Elements, wenn der Player offen ist. */
    var videoUrl by mutableStateOf<String?>(null)
        private set

    data class OpenPdf(val title: String, val url: String)

    /** Offene PDF-Ansicht (Titel + absolute URL), sonst null. */
    var openPdf by mutableStateOf<OpenPdf?>(null)
        private set

    /** PDF von einer Adresse zeigen (Hilfe: das Benutzerhandbuch vom Server). */
    fun openPdfUrl(title: String, url: String) { openPdf = OpenPdf(title, url) }

    fun openPdf(elem: de.bgghome.mpd.api.PdxElement) {
        val url = elem.documentUrl?.let { baseUrl + it }
            ?: mediaUrl("document", elem)
            ?: return
        openPdf = OpenPdf(elem.title ?: elem.file.orEmpty(), url)
    }

    fun closePdf() {
        openPdf = null
    }

    fun openVideo(elem: de.bgghome.mpd.api.PdxElement) {
        videoUrl = mediaUrl("video", elem)
    }

    fun closeVideo() {
        videoUrl = null
    }

    /** /api/{kind}/{space}/{source_album|album}/{datei} — wie im Web. */
    fun mediaUrl(kind: String, elem: de.bgghome.mpd.api.PdxElement): String? {
        val album = openAlbum ?: return null
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegment("api").addPathSegment(kind)
            .addPathSegment(album.summary.space)
            .addPathSegment(elem.sourceAlbum ?: album.summary.name)
            .addPathSegment(elem.file.orEmpty())
            .build().toString()
    }

    fun closeAlbum() {
        verlaufLeeren()
        // Ungespeichertes noch rausschreiben — saveNow() haelt das Album fest,
        // bevor openAlbum genullt wird.
        if (saveState == SaveState.Unsaved) saveNow()
        autoSaveJob?.cancel()
        isEditMode = false
        editingElementId = null
        saveState = SaveState.Idle
        saveError = null
        videoUrl = null
        openPdf = null
        lightboxIndex = null
        tourDaten.clear()
        tourLaeufe.values.forEach { it.cancel() }
        tourLaeufe.clear()
        openAlbum = null
        albumAusErinnerung = false
        lightboxZurPinnwand = false
    }

    fun logout() {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            busy = true
            try {
                api.logout(base)
            } catch (_: IOException) {
                // Cookie ist lokal geloescht, das reicht
            } finally {
                busy = false
                vergissKontodaten()
                state = AppState.Setup
            }
        }
    }

    /**
     * Alles vergessen, was zum angemeldeten Konto gehoert: Albenliste,
     * Zeitstrahl, Erinnerungen, Suche, Statistik, Konten, Favoriten,
     * Einstellungen. Bis 03.11.2026 blieb das alles nach dem Abmelden
     * stehen — wer sich danach mit einem anderen Konto anmeldete (etwa dem
     * Demo-Konto), sah noch die Alben des vorherigen, bis etwas neu lud.
     * Der Server gab dem neuen Konto nichts davon heraus; die Anzeige war
     * trotzdem falsch. Geraetefotos (localItems) bleiben: Die gehoeren zum
     * Geraet, nicht zum Konto.
     */
    private fun vergissKontodaten() {
        albums = null
        openAlbum = null
        pendingUndoDelete = null
        folderCandidates = null
        appSettings = null
        durchlaufCount = 0
        timelineItems.clear()
        timelineFeed.clear()
        timelineSelection.clear()
        gesichertAufGeraet = emptyList()
        timelineLightbox = null
        timelineFeedVersion++
        recycleBySpace.clear()
        editVersions.clear()
        missingModules.clear()
        memories.vergiss()
        search.vergiss()
        stats.vergiss()
        users.vergiss()
        favorites.vergiss()
    }

    // -- Gedaechtnis fuer eigene Teilen-/Beisteuern-Links -------------------
    //
    // Warum ueberhaupt: der Server liefert den Klartext-Token nur einmal, in
    // der Antwort auf `create`, und speichert selbst bloss dessen Hash. Ohne
    // dieses Gedaechtnis kann man an einem bestehenden Link genau eines tun —
    // ihn widerrufen. Den QR-Code oder den Link selbst saehe man nie wieder.
    // Begruendung und Grenzen stehen bei SettingsStore.LinkArt.

    /**
     * Adresse fuer Links, die BEI ANDEREN aufgehen muessen — QR-Codes,
     * Weitergeben, Kopieren.
     *
     * Bevorzugt die oeffentliche Adresse, die der Server kennt
     * (`whoami.base_url`, seit 05.09.2026). Sonst die eingetragene: die ist
     * im LAN richtig und ausserhalb eben nicht — darauf weist der
     * QR-Bildschirm dann hin.
     *
     * Fuer API-Aufrufe gilt weiterhin [baseUrl]: dort ist die lokale Adresse
     * die schnellere und oft die einzige, die von hier aus funktioniert.
     */
    val publicBaseUrl: String
        get() = (state as? AppState.LoggedIn)?.who?.baseUrl
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: baseUrl.trimEnd('/')

    /** Das Gedaechtnis selbst liegt in state/LinkTokens.kt; hier bleibt nur
     *  publicBaseUrl, weil es am App-Zustand (whoami) haengt. */
    val linkTokens = de.bgghome.mpd.state.LinkTokens(settings)

    // -- Passwort aendern ---------------------------------------------------

    /** Zustand und Aufruf liegen in state/PasswordChangeState.kt; hier
     *  bleibt nur, was den App-Zustand anfasst (refreshAfterPasswordChange). */
    val passwordChange = de.bgghome.mpd.state.PasswordChangeState(api, settings, viewModelScope)

    /**
     * Nach erzwungenem Wechsel: Flag ist serverseitig weg, also frisch
     * nachfragen statt es lokal anzunehmen.
     */
    fun refreshAfterPasswordChange() {
        val base = settings.baseUrl ?: return
        viewModelScope.launch {
            state = try {
                stateFor(api.whoami(base))
            } catch (_: IOException) {
                AppState.Setup
            }
        }
    }

    // -- Rueckmeldungen: eine Snackbar fuer die ganze App (08.09.2026) ------
    //
    // CI v1.2, Android: „Rueckmeldung: Snackbar im Fenster, nicht Toast —
    // sichtbar zuzuordnen und mit Rueckgaengig." Bis dahin 46 Toasts in 15
    // Dateien. Der Zustand liegt hier und nicht in einem Composable, weil
    // die Meldung den Dialog ueberleben muss, aus dem sie kommt: ein
    // rememberCoroutineScope() im Dialog stirbt mit dem Dialog (das
    // LeftCompositionCancellation-Muster vom 07.09.), viewModelScope nicht.
    // Den Host zeichnet MainActivity als oberstes Kind des Stapels, damit
    // die Meldung auch ueber Lightbox, Trails und Tours erscheint.

    val snackbar = androidx.compose.material3.SnackbarHostState()

    /**
     * Zeigt [text] als Snackbar. [kurz] entspricht Toast.LENGTH_SHORT,
     * sonst LENGTH_LONG. Der Wortlaut kommt unveraendert vom Aufrufer.
     */
    fun meldung(
        text: String,
        kurz: Boolean = false,
        /** Optionaler Knopf in der Snackbar („Öffnen") — [onAktion] laeuft beim Tipp. */
        aktion: String? = null,
        onAktion: (() -> Unit)? = null,
    ) {
        viewModelScope.launch {
            val result = snackbar.showSnackbar(
                text,
                actionLabel = aktion,
                duration = if (kurz) androidx.compose.material3.SnackbarDuration.Short
                else androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onAktion?.invoke()
        }
    }

    /** "192.168.1.2:5001" → "https://192.168.1.2:5001", ohne Schraegstrich am Ende. */
    private fun normalizeUrl(input: String): String? {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        return url
    }
}

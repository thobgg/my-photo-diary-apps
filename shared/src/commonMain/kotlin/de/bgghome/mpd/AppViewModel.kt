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
import de.bgghome.mpd.api.WhoAmI
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import de.bgghome.mpd.api.appSettings
import de.bgghome.mpd.api.durchlaufTotal
import de.bgghome.mpd.api.timeline
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

    // -- Neues Album (Web: Create-Dialog der Startseite) --------------------

    var createAlbumOpen by mutableStateOf(false)
    var folderCandidates by mutableStateOf<List<de.bgghome.mpd.api.FolderCandidate>?>(null)
    var folderCandidatesError by mutableStateOf<String?>(null)

    // -- Persoenliche Einstellungen (GET/PUT /api/settings/me) --------------

    var userSettings by mutableStateOf<de.bgghome.mpd.api.UserSettingsData?>(null)

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

    // -- Kamera-Backup-Einstellungen (Dialog im Hamburger) ------------------

    /** Basis-URL fuer alle Bild-Requests; erst nach der Anmeldung gesetzt. */
    val baseUrl: String get() = settings.baseUrl.orEmpty()

    // -- Theme (persistiert wie localStorage 'mpd-theme' im Web) ------------

    /** OwnTracks-Aufzeichnungsstufe, siehe TrailsDevices (08.09.2026). */
    var trailsRecordingLevel by mutableStateOf(settings.trailsRecordingLevel)
        internal set

    var themeId by mutableStateOf(
        de.bgghome.mpd.ui.theme.MpdThemeId.fromName(settings.themeName)
    )
        internal set
    internal var _spaceFilter by mutableStateOf<String?>(null)
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
    val search = de.bgghome.mpd.state.SearchState(api, { baseUrl }, viewModelScope)

    /** Treffer antippen: Album oeffnen und zum Element springen. */
    var pendingScrollElementId by mutableStateOf<String?>(null)

    // ================= Upload/Backup (Modell 02.09.2026, abends) =========
    // Eigene MPD-Kamera gibt es nicht mehr (Samsung-UI unschlagbar) —
    // fotografiert wird normal, das Geraeteordner-Backup (UploadWorker)
    // holt neue Aufnahmen der gewaehlten Ordner in den Durchlauf.

    /** Letzter Upload-Fehler (Worker) — Zeitstrahl zeigt ihn als Snackbar. */
    var uploadError by mutableStateOf<String?>(null)

    /** Die Fehler-Pollschleife nach einem Anstoss — immer nur eine. Vorher
     *  startete jeder kickUpload() eine neue, und drei Anstoesse hintereinander
     *  liessen drei Schleifen zwei Minuten lang nebeneinander laufen. */
    internal var uploadPollJob: Job? = null

    // ================= Kuratieren im Zeitstrahl (Modell 02.09.2026) ======
    // Filter „Unkuratiert" (referenced=false) → Mehrfachauswahl → genau
    // zwei Tasten: Einsortieren nach… (import-file, chronologisch) oder
    // Loeschen (auch Videos). 403 = C+-Rechte („nur der Kurator kann…").

    var timelineUncuratedOnly by mutableStateOf(false)
    val timelineSelection =
        androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()
    var curateBusy by mutableStateOf(false)
    var curateStatus by mutableStateOf<String?>(null)
    val recycleBySpace = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

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

    internal val role: String get() = (state as? AppState.LoggedIn)?.who?.role ?: "viewer"
    internal val isDemo: Boolean get() = (state as? AppState.LoggedIn)?.who?.isDemo ?: false

    // Die Regeln selbst stehen in data/Regeln.kt — dort sind sie ohne
    // Geraet pruefbar. Hier liegt nur der Zustand, den sie brauchen.

    /**
     * Rechte-Karte des Servers (`rights` in whoami, seit 3.0.272). Leer bei
     * aelteren Servern — dann greift der Rueckfall auf die Rollenregeln.
     */
    internal val rights: Map<String, List<String>>
        get() = (state as? AppState.LoggedIn)?.who?.rights.orEmpty()

    /**
     * Hat das Konto dieses Recht in diesem Bestand?
     *
     * Ein Recht, das nicht in der Liste steht, ist nicht erteilt — nie auf
     * unbekannte Namen pruefen. Kennt der Server das Feld nicht, faellt es
     * auf den Nachbau zurueck; der ist schlechter, aber besser als nichts.
     */

    /** Ordner MIT album.json — /api/albums listet genau diese. */
    internal val albumOrdner: Set<Pair<String, String>>?
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
        internal set
    var albumAusErinnerung by mutableStateOf(false)
        internal set
    internal var lightboxZurPinnwand = false

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
        internal set

    // Formularfelder der Einrichtung
    var serverUrl by mutableStateOf(settings.baseUrl ?: "")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    var busy by mutableStateOf(false)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    /** Gesetzt, wenn der Server ein unbekanntes Zertifikat vorzeigt. */
    var pendingCert by mutableStateOf<CertInfo?>(null)
        internal set

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

    // -- Alben + Lightbox ---------------------------------------------------

    var albums by mutableStateOf<List<AlbumSummary>?>(null)
        internal set

    // SCROLLSTELLE DER ALBENUEBERSICHT (12.09.2026: „wenn aus Album
    // zurueck in Albenuebersicht, gleiche Stelle springen"). Sie muss im
    // ViewModel liegen, nicht im Bildschirm: die Uebersicht verlaesst beim
    // Oeffnen eines Albums die Komposition (MainActivity schaltet mit einem
    // `when` ueber openAlbum), damit stirbt jedes remember darin — auch ein
    // rememberSaveable, denn es haengt an keinem SaveableStateHolder.
    var albumsScrollIndex by mutableStateOf(0)
    var albumsScrollOffset by mutableStateOf(0)
    var albumsError by mutableStateOf<String?>(null)
        internal set

    var openAlbum by mutableStateOf<OpenAlbum?>(null)
        internal set
    var albumLoading by mutableStateOf(false)
        internal set

    // -- Albumgestaltung (Schritt 4) ----------------------------------------
    // Verhalten wie album-edit.js/album-save.js: Aenderungen landen sofort im
    // Modell, gespeichert wird per Autosave 2 s nach der letzten Aenderung
    // (scheduleAutoSave) und spaetestens beim Verlassen des Edit-Modus.

    var isEditMode by mutableStateOf(false)
        internal set

    /** id des Textelements, dessen Editor gerade offen ist, sonst null. */
    var editingElementId by mutableStateOf<String?>(null)
        internal set

    var saveState by mutableStateOf(SaveState.Idle)
        internal set
    var saveError by mutableStateOf<String?>(null)
        internal set

    internal var autoSaveJob: Job? = null
    internal var saveIdleJob: Job? = null

    // -- Rueckgaengig / Wiederherstellen (Web: undoStack, MAX_UNDO) --------
    //
    // Jede Aenderung im Bearbeiten-Modus legt den Album-Stand DAVOR ab
    // (OpenAlbum ist unveraenderlich, das Ablegen kostet nichts); Rueckgaengig
    // tauscht ihn zurueck und speichert wie jede andere Aenderung. Beim
    // Verlassen des Bearbeitens und beim Albumwechsel ist der Verlauf weg.
    internal val undoStapel = ArrayDeque<OpenAlbum>()
    internal val redoStapel = ArrayDeque<OpenAlbum>()
    var canUndo by mutableStateOf(false)
        internal set
    var canRedo by mutableStateOf(false)
        internal set

    /** Fuer „Rueckgaengig" nach dem Entfernen (Web: Undo-Toast). */
    internal data class RemovedElement(
        val typed: de.bgghome.mpd.api.PdxElement,
        val raw: JsonObject,
        val typedIndex: Int,
        val rawIndex: Int,
    )

    internal var lastRemoved: RemovedElement? = null

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

    /** Link dessen Dialog gerade zum Bearbeiten offen ist (Web: link-edit-btn). */
    var editingLinkId by mutableStateOf<String?>(null)

    // -- Datei physisch loeschen (Web: deletePhotoFile, nur type=photo) -----

    /** Foto, dessen Loesch-Rueckfrage gerade offen ist. */
    var pendingDelete by mutableStateOf<de.bgghome.mpd.api.PdxElement?>(null)
        internal set

    /** Ergebnismeldung des Loeschens (war-Cover-Hinweis oder Fehler). */
    var deleteNotice by mutableStateOf<String?>(null)
        internal set

    /** Geloeschtes Album fuer die Rueckgaengig-Snackbar der Uebersicht. */
    data class DeletedAlbum(
        val space: String, val name: String, val title: String, val backup: String,
    )

    var pendingUndoDelete by mutableStateOf<DeletedAlbum?>(null)

    /** Web _confirmDeleteAlbum: loescht NUR album.json, merkt das Backup
     *  fuer den Rueckgaengig-Toast und kehrt zur Uebersicht zurueck. */

    /** true = Konfliktdialog „Album wurde woanders geaendert" ist offen. */
    var conflictOpen by mutableStateOf(false)
        internal set

    /** Antwort des Konfliktdialogs: neu laden (Aenderungen verwerfen) oder
     *  bewusst ueberschreiben (Basis leeren → Save ohne Konflikt-Header). */

    /** true = Rueckfrage „Album wirklich leer speichern?" ist offen. */
    var emptySaveConfirmOpen by mutableStateOf(false)
        internal set
    internal var emptySaveApproved = false

    /** Space des gerade ladenden Albums — buildPhotos laeuft VOR openAlbum=. */
    internal var pendingSpace: String? = null

    // -- Timeline (GET /api/timeline, Modul „timeline") ---------------------

    /** 0 = Alben, 1 = Zeitstrahl — Tab der Startansicht. */
    var homeTab by mutableStateOf(settings.homeTab)
        internal set

    // -- Modul-Entitlement (Schaufenster statt 403) -------------------------

    /** Modul-Key, dessen Schaufenster-Dialog offen ist (ui/ModuleShowcase). */
    var lockedModuleInfo by mutableStateOf<String?>(null)

    /**
     * Module, deren Endpunkte dieser Server gar nicht hat (FastAPIs nackte
     * 404, gemeldet ueber api.onModuleMissing). Rueckfall, falls
     * whoami.modules einmal nicht stimmt; gilt bis zum Abmelden.
     */
    internal val missingModules = androidx.compose.runtime.mutableStateListOf<String>()

    /**
     * Freigeschaltet laut /api/whoami — siehe [moduleFree]. Die Wahrheit
     * erzwingt ohnehin der Server (require_module).
     */

    val timelineItems = androidx.compose.runtime.mutableStateListOf<de.bgghome.mpd.api.TimelineItem>()
    var timelineLoading by mutableStateOf(false)
        internal set
    var timelineError by mutableStateOf<String?>(null)
        internal set
    internal var timelineCursor: String? = null
    internal var timelineEnd = false
    internal var timelineJob: Job? = null

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
        internal set

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
        internal set

    /** Einmal je Prozess fragen, nicht bei jedem Wechsel auf den Tab. */
    var localMediaAsked = false

    /** Geraeteordner, die im Zeitstrahl NICHT erscheinen (leer = alle). */
    var timelineHiddenBuckets by mutableStateOf(settings.timelineHiddenBuckets)
        internal set
    var timelineFoldersOpen by mutableStateOf(false)
    var deviceFolderList by mutableStateOf<List<de.bgghome.mpd.data.LocalFolder>?>(null)
    var pendingTrash by mutableStateOf<List<String>>(emptyList())

    /**
     * Lokale Dateien wegwerfen. Android: ueber den System-Dialog, den die
     * Oberflaeche aus [pendingTrash] baut. Desktop: direkt in den
     * Papierkorb des Systems — die Rueckfrage hat die App vorher gestellt
     * (Plattform.papierkorbFragtSelbst).
     */

    // -- Desktop: Ordner auf diesem PC (04.10.2026) --------------------------

    /** Die gewaehlten PC-Ordner, sortiert — Compose-Zustand fuer den Dialog. */
    var lokaleOrdner by mutableStateOf(settings.lokaleOrdner.sorted())
        internal set
    var uploadFortschritt by mutableStateOf<String?>(null)
        internal set
    var pendingLocalEdit by mutableStateOf<Pair<String, ByteArray>?>(null)

    /** true, solange lokal gerechnet wird (grosse Bilder dauern). */
    var localEditBusy by mutableStateOf(false)
        internal set
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
    internal var timelineLoadedAt = 0L

    /**
     * Ist seit dem letzten Laden ein Backup-Lauf durchgelaufen, dann still
     * nachladen. Die App erfaehrt vom Worker sonst nur Fehler, keine
     * Erfolge — wer sie offen liess, sah die durchgestrichene Wolke an
     * einem Foto weiter, das laengst auf der NAS lag (06.09.2026).
     */
    internal var localMediaJob: Job? = null

    internal var backupLedger: Set<String> = emptySet()

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
    var gesichertAufGeraet by mutableStateOf<List<de.bgghome.mpd.api.TimelineItem>>(emptyList())
        internal set

    /** Bytes der gesicherten Aufnahmen auf dem Geraet (MediaStore.SIZE). */
    val gesichertBytes: Long
        get() = gesichertAufGeraet.sumOf { it.size }

    /**
     * Alle gesicherten Aufnahmen in den Papierkorb des Geraets — ueber
     * denselben Systemdialog wie das Wegwerfen im Zeitstrahl. Endgueltig
     * frei wird der Platz im TrashScreen; die Kopie auf der NAS bleibt.
     */

    /** Zeitstrahl-Lightbox: chronologischer Pager (Fotos) + Startindex. */
    var timelineLightbox by mutableStateOf<Pair<List<Photo>, Int>?>(null)
        internal set

    internal fun de.bgghome.mpd.api.TimelineItem.toPhoto() = if (localUri != null) {
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
    val editVersions = androidx.compose.runtime.mutableStateMapOf<String, Long>()

    /** Index in OpenAlbum.photos, wenn die Lightbox offen ist; sonst null. */
    var lightboxIndex by mutableStateOf<Int?>(null)
        internal set
    val tourDaten = HashMap<String, Any>()
    internal val tourLaeufe = HashMap<String, kotlinx.coroutines.Deferred<Any>>()

    /**
     * Ortsangaben der Fotos je Bereich und Zeitraum (Schluessel
     * [de.bgghome.mpd.util.fotoOrtSchluessel]), fuer „Fotos auf der Tour"
     * (05.11.2026, Koordinaten zuerst). Geleert mit dem Album wie [tourDaten].
     */
    internal val fotoOrteCache = HashMap<String, Map<String, de.bgghome.mpd.util.FotoOrt>>()

    /**
     * Ortsangaben aller Fotos des Bereichs zwischen zwei Aufnahmetagen.
     * Ein aelterer Server ohne die Route (404) und ein Netzfehler ergeben
     * eine leere Liste: die Tour-Karte faellt dann fuer jedes Foto auf die
     * Uhrzeit zurueck, wie vor dem 05.11.2026 — die Spur selbst ist zu dem
     * Zeitpunkt schon geladen, ein zweiter Fehlertext brächte nichts.
     */

    /** Absolute Stream-URL eines video-Elements, wenn der Player offen ist. */
    var videoUrl by mutableStateOf<String?>(null)
        internal set

    data class OpenPdf(val title: String, val url: String)

    /** Offene PDF-Ansicht (Titel + absolute URL), sonst null. */
    var openPdf by mutableStateOf<OpenPdf?>(null)
        internal set
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
}

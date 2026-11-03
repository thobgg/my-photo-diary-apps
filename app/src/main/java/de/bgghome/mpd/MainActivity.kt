package de.bgghome.mpd

import coil3.request.crossfade

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.ui.AlbumViewScreen
import de.bgghome.mpd.ui.AlbumsScreen
import de.bgghome.mpd.ui.LightboxScreen
import de.bgghome.mpd.ui.MemoriesScreen
import de.bgghome.mpd.ui.MpdIcons
import de.bgghome.mpd.ui.PasswordChangeScreen
import de.bgghome.mpd.ui.SearchScreen
import de.bgghome.mpd.ui.SetupScreen
import de.bgghome.mpd.ui.StatsScreen
import de.bgghome.mpd.ui.TimelineScreen
import de.bgghome.mpd.ui.ToursScreen
import de.bgghome.mpd.ui.PdfViewerOverlay
import de.bgghome.mpd.ui.VideoPlayerOverlay
import de.bgghome.mpd.ui.theme.MpdTheme
import okio.Path.Companion.toOkioPath
import org.jetbrains.compose.resources.stringResource

/**
 * My Photo Diary — nativer Client fuer die MPD-REST-API.
 *
 * Die App spricht ausschliesslich die bestehende REST-API an; am Server wird
 * nichts geaendert, der frontend/-Ordner im MPD-Repo dient nur als
 * Nachschlagewerk.
 *
 * Reihenfolge (siehe STAND.md):
 *   0. Einrichtung     — Serveradresse, Anmeldung, Sitzung behalten  ✓
 *   1. Lightbox nativ  — Gesten, Zoom, Original nachladen            ← hier
 *   2. Albumansicht    — Raster, Coil-Cache, Offline-Vorrat
 *   3. Kamera/Upload, Push, Offline
 */
/** Deep-Link aus einer Benachrichtigung (NotifyWorker) an die Composition. */
private object PendingDeepLink {
    var value = androidx.compose.runtime.mutableStateOf<String?>(null)
}


/** Was ueber „Teilen mit"/„Öffnen mit" hereinkam, bis Compose es abholt. */
internal object PendingIncoming {
    var value = androidx.compose.runtime.mutableStateOf<
        de.bgghome.mpd.data.IncomingMedia.Incoming?
        >(null)
}

class MainActivity : ComponentActivity() {

    private fun captureDeepLink(intent: android.content.Intent?) {
        intent?.getStringExtra(de.bgghome.mpd.data.NotifyWorker.EXTRA_DEEP_LINK)
            ?.let { PendingDeepLink.value.value = it }
        // „Teilen mit MPD" und „Öffnen mit MPD" kommen als gewoehnlicher
        // Intent herein; die Compose-Seite holt es sich aus PendingIncoming.
        de.bgghome.mpd.data.IncomingMedia.from(intent)?.let {
            PendingIncoming.value.value = it
        }
        // Kopplung per QR: mpd://setup?url=https%3A%2F%2Fmpd.example.de
        intent?.data?.takeIf { it.scheme == "mpd" && it.host == "setup" }
            ?.getQueryParameter("url")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { PendingSetupUrl.value.value = it }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        captureDeepLink(intent)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(de.bgghome.mpd.data.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Texte fuer ViewModel, state/ und API-Fehler (i18n 08.09.2026)
        enableEdgeToEdge()
        captureDeepLink(intent)
        setContent {
            val vm: AndroidAppViewModel = viewModel { AndroidAppViewModel(application) }

            // Android 13+: Benachrichtigungs-Berechtigung einmal erfragen,
            // sobald der Nutzer angemeldet ist (der Memories-Morgengruss).
            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            androidx.compose.runtime.LaunchedEffect(vm.state) {
                if (vm.state is AppState.LoggedIn &&
                    android.os.Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Vordergrund-Long-Poll: sofortige Benachrichtigungs-Zustellung,
            // solange die App sichtbar ist (STARTED); im Hintergrund
            // uebernimmt der 15-min-NotifyWorker.
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.LaunchedEffect(vm.state is AppState.LoggedIn) {
                if (vm.state is AppState.LoggedIn) {
                    lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                        vm.foregroundNotifyLoop()
                    }
                }
            }

            // Deep-Link ausfuehren, sobald angemeldet
            val pendingLink = PendingDeepLink.value.value
            androidx.compose.runtime.LaunchedEffect(pendingLink, vm.state) {
                if (pendingLink != null && vm.state is AppState.LoggedIn) {
                    PendingDeepLink.value.value = null
                    if (pendingLink.startsWith("mpd://memories")) {
                        // Ein Aufruf, kein Tab-Index — warum, steht bei
                        // AppViewModel.zeigeErinnerungen (24.09.2026).
                        vm.zeigeErinnerungen()
                    } else if (pendingLink.startsWith("mpd://album")) {
                        // Upload-Digest: direkt in das frisch befuellte Album
                        val uri = android.net.Uri.parse(pendingLink)
                        val space = uri.getQueryParameter("space")
                        val album = uri.getQueryParameter("album")
                        if (space != null && album != null) {
                            vm.closeAlbum()
                            vm.openAlbum(
                                de.bgghome.mpd.api.AlbumSummary(name = album, space = space),
                            )
                        }
                    }
                }
            }

            MpdTheme(vm.themeId) {
                val context = LocalContext.current
                val dienste = remember { de.bgghome.mpd.ui.AndroidSystemDienste(context) }
                androidx.compose.runtime.CompositionLocalProvider(de.bgghome.mpd.ui.LocalSystemDienste provides dienste) {
                if (vm.backupDialogOpen) {
                    de.bgghome.mpd.ui.BackupDialog(vm) { vm.backupDialogOpen = false }
                }
                // Ein ImageLoader fuer die ganze App: nutzt den API-Client,
                // damit Cookie und Zertifikatslogik auch fuer Bilder gelten.
                val imageLoader = remember {
                    ImageLoader.Builder(context)
                        .components {
                            add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { vm.api.httpClient() }))
                        }
                        .crossfade(true)
                        // Statt Coils 250-MB-Deckel: grosszuegiger Vorrat,
                        // damit die Bibliothek warm bleibt (Android raeumt
                        // App-Caches bei Platznot selbst).
                        .diskCache {
                            coil3.disk.DiskCache.Builder()
                                .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                                .maxSizeBytes(4L * 1024 * 1024 * 1024)
                                .build()
                        }
                        .build()
                }

                when (val state = vm.state) {
                    AppState.Checking -> Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                        Box(
                            modifier = Modifier.fillMaxSize().padding(inner),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }

                    AppState.Setup -> Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                        SetupScreen(vm, Modifier.padding(inner))
                    }

                    // Server verlangt einen Passwortwechsel: alles andere
                    // wuerde ohnehin nur 403 liefern.
                    is AppState.MustChangePassword -> Scaffold(
                        modifier = Modifier.fillMaxSize(),
                    ) { inner ->
                        Box(Modifier.padding(inner)) {
                            PasswordChangeScreen(
                                vm = vm,
                                who = state.who,
                                forced = true,
                                onClose = {},
                            )
                        }
                    }

                    is AppState.LoggedIn -> {
                        val album = vm.openAlbum
                        val lightboxIndex = vm.lightboxIndex
                        Box(Modifier.fillMaxSize()) {
                            when {
                                // Startansicht: Alben ↔ Zeitstrahl per Bottom-Navigation
                                album == null -> Column(Modifier.fillMaxSize()) {
                                    // Android-Muster: Zurueck aus einem
                                    // Neben-Tab wechselt erst zum Alben-Tab,
                                    // erst von dort beendet es die App.
                                    androidx.activity.compose.BackHandler(
                                        enabled = vm.homeTab != 0,
                                    ) { vm.switchHomeTab(0) }
                                    // Ein Kopf fuer alle Tabs (03.09.);
                                    // die Alben bringen ihren eigenen mit
                                    // (mit den Zaehl-Pills).
                                    if (vm.homeTab != 0) {
                                        de.bgghome.mpd.ui.AppHeader(vm, state.who)
                                    }
                                    Box(Modifier.weight(1f)) {
                                        when (vm.homeTab) {
                                            0 -> AlbumsScreen(vm, state.who, imageLoader)
                                            1 -> TimelineScreen(vm, imageLoader)
                                            else -> de.bgghome.mpd.ui.SearchScreen(
                                                vm, imageLoader, asTab = true,
                                            )
                                        }
                                    }
                                    NavigationBar {
                                        NavigationBarItem(
                                            selected = vm.homeTab == 0,
                                            onClick = { vm.switchHomeTab(0) },
                                            icon = {
                                                Icon(MpdIcons.AlbumsGrid, null, Modifier.size(22.dp))
                                            },
                                            label = { Text(stringResource(Res.string.nav_albums)) },
                                        )
                                        NavigationBarItem(
                                            selected = vm.homeTab == 1,
                                            onClick = {
                                                vm.tryOpenModule("timeline") { vm.switchHomeTab(1) }
                                            },
                                            icon = {
                                                Icon(MpdIcons.Clock, null, Modifier.size(22.dp))
                                            },
                                            label = {
                                                Text(stringResource(Res.string.nav_timeline))
                                            },
                                        )
                                        // Unten stehen die drei Grundtaetigkeiten,
                                        // keine Module (05.09.: „eigentlich
                                        // ist alles Module — warum dann Memories?").
                                        // Sonst haetten Trails, Tours und Stats
                                        // denselben Anspruch. Memories kommt jetzt
                                        // ueber das Karussell der Timeline zum
                                        // Nutzer, statt dass er hingehen muss.
                                        NavigationBarItem(
                                            selected = vm.homeTab == 2,
                                            onClick = {
                                                vm.tryOpenModule("search") {
                                                    vm.switchHomeTab(2)
                                                }
                                            },
                                            icon = {
                                                Icon(MpdIcons.Search, null, Modifier.size(22.dp))
                                            },
                                            label = { Text(stringResource(Res.string.nav_search)) },
                                        )
                                    }
                                }

                                // Lightbox als OVERLAY ueber der Albumansicht —
                                // ersetzt sie nicht mehr, sonst stirbt die
                                // Scrollposition und Zurueck landet am Albumanfang
                                // (Tour-Marker-Befund, 03.09.).
                                else -> {
                                    AlbumViewScreen(vm, album, imageLoader)
                                    if (lightboxIndex != null) {
                                        LightboxScreen(
                                            vm,
                                            title = album.title,
                                            photos = album.photos,
                                            readOnly = album.readOnly,
                                            imageLoader = imageLoader,
                                            startIndex = lightboxIndex,
                                            onClose = { vm.closeLightbox() },
                                            fromAlbum = true,
                                        )
                                    }
                                }
                            }
                            // Zeitstrahl-Lightbox: chronologischer Pager ohne Albumkontext
                            vm.timelineLightbox?.let { (photos, index) ->
                                LightboxScreen(
                                    vm,
                                    title = "Timeline",
                                    photos = photos,
                                    readOnly = false, // Schreibrechte prueft der Server je Space
                                    imageLoader = imageLoader,
                                    startIndex = index,
                                    onClose = { vm.closeTimelineLightbox() },
                                )
                            }
                            // Aus der Pinnwand geoeffnetes Foto liegt UEBER ihr
                            // (wird aber vorher gezeichnet) — dann ausblenden.
                            if (vm.memories.open &&
                                !(vm.albumAusErinnerung && vm.openAlbum != null)
                            ) {
                                de.bgghome.mpd.ui.MemoriesScreen(vm, imageLoader)
                            }
                            // Hereingereichte Aufnahmen: sichern oder ansehen.
                            // Der Dialog fragt NICHT nach dem Ort — der
                            // Durchlauf ist gesetzt, wie beim Kamera-Backup.
                            androidx.compose.runtime.LaunchedEffect(
                                PendingIncoming.value.value,
                            ) {
                                PendingIncoming.value.value?.let {
                                    vm.incoming = it
                                    PendingIncoming.value.value = null
                                }
                            }
                            vm.incoming?.let { inc ->
                                if (inc.viewOnly) {
                                    // Ansehen ohne Umweg: wer MPD als Galerie
                                    // waehlt, will das Bild sehen, keinen Dialog.
                                    val viewCtx = androidx.compose.ui.platform
                                        .LocalContext.current
                                    androidx.compose.runtime.LaunchedEffect(inc) {
                                        vm.viewIncoming(viewCtx, inc.uris.first())
                                    }
                                } else {
                                    de.bgghome.mpd.ui.IncomingDialog(vm, inc, imageLoader)
                                }
                            }
                            // Geraete-Aufnahmen wegwerfen laeuft ueber den
                            // System-Dialog (MediaStore.createTrashRequest):
                            // Papierkorb des Geraets, 30 Tage, und die
                            // Nachfrage stellt Android selbst — anders darf
                            // eine App fremde Mediendateien gar nicht
                            // anfassen.
                            //
                            // Das haengt hier und nicht im TimelineScreen:
                            // dort wurde es nur abgeholt, solange der
                            // Zeitstrahl komponiert war. Aus einem Album
                            // heraus passierte deshalb gar nichts — kein
                            // Dialog, kein Serveraufruf, nichts (Vorgabe,
                            // 06.09.: „in der Lightbox loescht es scheinbar
                            // gar nicht"). Eine Liste von URIs ergibt EINE
                            // Rueckfrage fuer die ganze Auswahl.
                            val trashCtx = androidx.compose.ui.platform
                                .LocalContext.current
                            val trashCount = vm.pendingTrash.size
                            val trashLauncher = androidx.activity.compose
                                .rememberLauncherForActivityResult(
                                    androidx.activity.result.contract
                                        .ActivityResultContracts
                                        .StartIntentSenderForResult(),
                                ) { result ->
                                    vm.trashFinished(
                                        result.resultCode ==
                                            android.app.Activity.RESULT_OK,
                                        trashCount,
                                    )
                                }
                            androidx.compose.runtime.LaunchedEffect(
                                vm.pendingTrash,
                            ) {
                                val uris = vm.pendingTrash
                                    .map { android.net.Uri.parse(it) }
                                if (uris.isNotEmpty()) {
                                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                                        runCatching {
                                            val req = android.provider.MediaStore
                                                .createTrashRequest(
                                                    trashCtx.contentResolver,
                                                    uris, true,
                                                )
                                            trashLauncher.launch(
                                                androidx.activity.result
                                                    .IntentSenderRequest
                                                    .Builder(req.intentSender)
                                                    .build(),
                                            )
                                        }.onFailure { vm.trashFinished(false, 0) }
                                    } else {
                                        vm.curateStatus = vm.t(Res.string.trash_needs_android11)
                                        vm.trashFinished(false, 0)
                                    }
                                }
                            }

                            // Aus dem Papierkorb zurueckholen und
                            // endgueltig loeschen — dieselbe Mechanik wie
                            // oben beim Wegwerfen: es sind Schreibzugriffe
                            // auf fremde Mediendateien, den Dialog stellt
                            // Android. Eine Auswahl ergibt EINE Rueckfrage.
                            val untrashCount = vm.trash.pendingUntrash.size
                            val untrashLauncher = androidx.activity.compose
                                .rememberLauncherForActivityResult(
                                    androidx.activity.result.contract
                                        .ActivityResultContracts
                                        .StartIntentSenderForResult(),
                                ) { result ->
                                    vm.trash.untrashFinished(
                                        result.resultCode ==
                                            android.app.Activity.RESULT_OK,
                                        untrashCount,
                                    )
                                }
                            androidx.compose.runtime.LaunchedEffect(
                                vm.trash.pendingUntrash,
                            ) {
                                val uris = vm.trash.pendingUntrash
                                    .map { android.net.Uri.parse(it) }
                                if (uris.isNotEmpty()) {
                                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                                        runCatching {
                                            val req = android.provider.MediaStore
                                                .createTrashRequest(
                                                    trashCtx.contentResolver,
                                                    uris, false,
                                                )
                                            untrashLauncher.launch(
                                                androidx.activity.result
                                                    .IntentSenderRequest
                                                    .Builder(req.intentSender)
                                                    .build(),
                                            )
                                        }.onFailure { vm.trash.untrashFinished(false, 0) }
                                    } else {
                                        vm.trash.untrashFinished(false, 0)
                                    }
                                }
                            }

                            val eraseCount = vm.trash.pendingErase.size
                            val eraseLauncher = androidx.activity.compose
                                .rememberLauncherForActivityResult(
                                    androidx.activity.result.contract
                                        .ActivityResultContracts
                                        .StartIntentSenderForResult(),
                                ) { result ->
                                    vm.trash.eraseFinished(
                                        result.resultCode ==
                                            android.app.Activity.RESULT_OK,
                                        eraseCount,
                                    )
                                }
                            androidx.compose.runtime.LaunchedEffect(
                                vm.trash.pendingErase,
                            ) {
                                val uris = vm.trash.pendingErase
                                    .map { android.net.Uri.parse(it) }
                                if (uris.isNotEmpty()) {
                                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                                        runCatching {
                                            val req = android.provider.MediaStore
                                                .createDeleteRequest(
                                                    trashCtx.contentResolver, uris,
                                                )
                                            eraseLauncher.launch(
                                                androidx.activity.result
                                                    .IntentSenderRequest
                                                    .Builder(req.intentSender)
                                                    .build(),
                                            )
                                        }.onFailure { vm.trash.eraseFinished(false, 0) }
                                    } else {
                                        vm.trash.eraseFinished(false, 0)
                                    }
                                }
                            }

                            if (vm.timelineFoldersOpen) {
                                de.bgghome.mpd.ui.TimelineFoldersDialog(
                                    vm, imageLoader,
                                ) { vm.timelineFoldersOpen = false }
                            }
                            if (vm.toursOpen) ToursScreen(vm)
                            if (vm.trailsOpen) de.bgghome.mpd.ui.TrailsScreen(vm)
                            if (vm.contribOpen) de.bgghome.mpd.ui.ContribScreen(vm)
                            vm.photoShare?.let { (space, album, file) ->
                                de.bgghome.mpd.ui.ShareDialog(
                                    vm,
                                    space = space,
                                    album = album,
                                    // Beim Einzelfoto traegt der Dateiname
                                    // die Ueberschrift der E-Mail.
                                    albumTitle = file,
                                    file = file,
                                ) { vm.photoShare = null }
                            }
                            if (vm.stats.open) StatsScreen(vm)
                            if (vm.search.open) SearchScreen(vm, imageLoader)
                            if (vm.settingsOpen) de.bgghome.mpd.ui.SettingsScreen(vm, state.who)
                            if (vm.trash.open) de.bgghome.mpd.ui.TrashScreen(vm, imageLoader)
                            // Rueckmeldung des Kuratierens — „Gelöscht",
                            // „Zurückgeholt", „Konnte das Original nicht
                            // laden". Sie hing bis 07.09.2026 im
                            // TimelineScreen und war damit nur sichtbar,
                            // solange der Zeitstrahl komponiert war: aus
                            // einem Album heraus blieb jede Meldung stumm,
                            // auch jeder Fehler. Hier ist sie immer da.
                            //
                            // Derselbe Fehler wie beim Loeschen am 06.09.
                            // („in der Lightbox loescht es scheinbar gar
                            // nicht") — nicht die Aktion fehlte, sondern
                            // der Ort, an dem sie sich zeigt.
                            androidx.compose.runtime.LaunchedEffect(
                                vm.curateStatus,
                            ) {
                                vm.curateStatus?.let {
                                    vm.meldung(it)
                                    vm.curateStatus = null
                                }
                            }
                            if (vm.users.open) de.bgghome.mpd.ui.UsersScreen(vm)
                            VideoPlayerOverlay(vm)
                            de.bgghome.mpd.ui.ModuleLockedDialog(vm)
                            PdfViewerOverlay(vm)
                            de.bgghome.mpd.ui.AlertBannerOverlay(vm)

                            // Die eine Snackbar der App (vm.meldung), als
                            // LETZTES Kind: ueber Lightbox, Trails, Tours
                            // und den uebrigen Vollbild-Ansichten, unter
                            // den Dialogen (die sind eigene Fenster).
                            //
                            // Der Abstand nach unten haengt davon ab, was
                            // gerade unter ihr liegt — ein Scaffold wuerde
                            // das selbst wissen, hier gibt es keins:
                            //  - Startansicht: die NavigationBar (80 dp),
                            //    auf dem Alben-Tab dazu der ＋-FAB
                            //  - offenes Album: der Bearbeiten-FAB unten
                            //  - Lightbox und Vollbild-Ansichten: nichts
                            val ueberlagert = lightboxIndex != null ||
                                vm.timelineLightbox != null || vm.memories.open ||
                                vm.toursOpen || vm.trailsOpen || vm.contribOpen ||
                                vm.stats.open || vm.search.open || vm.settingsOpen ||
                                vm.trash.open || vm.users.open
                            val darfAnlegen = state.who.role != "viewer" && !state.who.isDemo
                            val abstandUnten = when {
                                ueberlagert -> 0.dp
                                album != null -> if (album.readOnly) 0.dp else 84.dp
                                vm.homeTab == 0 && darfAnlegen -> 80.dp + 76.dp
                                else -> 80.dp
                            }
                            androidx.compose.material3.SnackbarHost(
                                vm.snackbar,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .imePadding()
                                    .padding(bottom = abstandUnten),
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

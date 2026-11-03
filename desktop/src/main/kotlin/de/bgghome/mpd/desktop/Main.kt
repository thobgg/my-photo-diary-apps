package de.bgghome.mpd.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import de.bgghome.mpd.ui.ListenTaste
import de.bgghome.mpd.ui.ListenTasten
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import java.io.File
import de.bgghome.mpd.AppState
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.DesktopPlattform
import de.bgghome.mpd.ui.DesktopSystemDienste
import de.bgghome.mpd.ui.LocalSystemDienste
import de.bgghome.mpd.ui.AlbumViewScreen
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.ui.AlbumsScreen
import de.bgghome.mpd.ui.AppHeader
import de.bgghome.mpd.ui.ContribScreen
import de.bgghome.mpd.ui.DesktopMedien
import de.bgghome.mpd.ui.LightboxScreen
import de.bgghome.mpd.ui.LokaleOrdnerDialog
import de.bgghome.mpd.ui.LokaleVorschau
import de.bgghome.mpd.ui.MemoriesScreen
import de.bgghome.mpd.ui.PdfViewerOverlay
import de.bgghome.mpd.ui.StatsScreen
import de.bgghome.mpd.ui.ToursScreen
import de.bgghome.mpd.ui.TrailsScreen
import de.bgghome.mpd.ui.UsersScreen
import de.bgghome.mpd.ui.MpdIcons
import de.bgghome.mpd.ui.SearchScreen
import de.bgghome.mpd.ui.SettingsScreen
import de.bgghome.mpd.ui.TimelineScreen
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.ui.SetupScreen
import de.bgghome.mpd.ui.theme.MpdTheme

/**
 * Erster Desktop-Stand (22.09.2026): Fenster, Theme, Anmeldung. Danach
 * kommen Zeitstrahl und Lightbox, sobald deren Bildschirme ohne Coil,
 * osmdroid und Media3 auskommen (siehe STAND.md, Etappe 1).
 */
fun main() = application {
    val vm = remember { AppViewModel(DesktopPlattform()) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "My Photo Diary",
        // Fenster-Icon (Titelleiste, Taskleiste, Alt-Tab): dieselbe Squircle-
        // Fassung wie das Paket-Icon; ohne Angabe zeigt Java seine Kaffeetasse.
        icon = painterResource("mpd-icon.png"),
        // Maximiert statt 1100x760 (22.09.2026: "gerne Fullscreen");
        // die Titelleiste bleibt, damit sich das Fenster schliessen laesst.
        state = rememberWindowState(placement = WindowPlacement.Maximized, width = 1100.dp, height = 760.dp),
        // Listentasten (02.11.2026): Strg+Pos1/Strg+Ende ganz nach oben/unten,
        // Bild auf/ab seitenweise — in Albenuebersicht, Album und Zeitstrahl.
        // onKeyEvent, nicht onPreviewKeyEvent: hier kommt nur an, was kein
        // Eingabefeld schon verbraucht hat.
        onKeyEvent = { ev ->
            if (ev.type != KeyEventType.KeyDown) false
            else when (ev.key) {
                Key.MoveHome -> ListenTasten.verarbeite(ListenTaste.ANFANG)
                Key.MoveEnd -> ListenTasten.verarbeite(ListenTaste.ENDE)
                Key.PageUp -> ListenTasten.verarbeite(ListenTaste.SEITE_HOCH)
                Key.PageDown -> ListenTasten.verarbeite(ListenTaste.SEITE_RUNTER)
                else -> false
            }
        },
    ) {
        val dienste = remember { DesktopSystemDienste(vm.plattform.settings) }
        // Ein ImageLoader fuer das ganze Fenster: nutzt den API-Client, damit
        // Cookie und Zertifikatslogik auch fuer Bilder gelten (wie MainActivity).
        val imageLoader = remember {
            ImageLoader.Builder(PlatformContext.INSTANCE)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { vm.api.httpClient() }))
                    // Fotos aus den PC-Ordnern: Kacheln aus dem Vorschau-Cache.
                    add(LokaleVorschau(cacheOrdner().resolve("pc_vorschau")))
                }
                .crossfade(true)
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheOrdner().resolve("image_cache").toOkioPath())
                        .maxSizeBytes(4L * 1024 * 1024 * 1024)
                        .build()
                }
                .build()
        }
        MpdTheme(vm.themeId) {
            CompositionLocalProvider(LocalSystemDienste provides dienste) {
            when (val state = vm.state) {
                AppState.Checking -> Scaffold(Modifier.fillMaxSize()) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                AppState.Setup -> Scaffold(Modifier.fillMaxSize()) { inner ->
                    SetupScreen(vm, Modifier.padding(inner))
                }
                is AppState.LoggedIn -> Scaffold(Modifier.fillMaxSize()) { inner ->
                    // Alben-Uebersicht und Album-Ansicht wie MainActivity; Zeitstrahl
                    // und Lightbox folgen, sobald sie ohne Android auskommen.
                    val album = vm.openAlbum
                    Box(Modifier.fillMaxSize().padding(inner)) {
                        if (album == null) {
                            // Startansicht wie am Handy: Alben ↔ Zeitstrahl ↔ Suche
                            // ueber die Leiste unten (Bedien-Idiom kommt vom Handy).
                            Column(Modifier.fillMaxSize()) {
                                if (vm.homeTab != 0) AppHeader(vm, state.who)
                                Box(Modifier.weight(1f)) {
                                    when (vm.homeTab) {
                                        0 -> AlbumsScreen(vm, state.who, imageLoader)
                                        1 -> TimelineScreen(vm, imageLoader)
                                        else -> SearchScreen(vm, imageLoader, asTab = true)
                                    }
                                }
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = vm.homeTab == 0, onClick = { vm.switchHomeTab(0) },
                                        icon = { Icon(MpdIcons.AlbumsGrid, null, Modifier.size(22.dp)) },
                                        label = { Text(stringResource(Res.string.nav_albums)) },
                                    )
                                    NavigationBarItem(
                                        selected = vm.homeTab == 1,
                                        onClick = { vm.tryOpenModule("timeline") { vm.switchHomeTab(1) } },
                                        icon = { Icon(MpdIcons.Clock, null, Modifier.size(22.dp)) },
                                        label = { Text(stringResource(Res.string.nav_timeline)) },
                                    )
                                    NavigationBarItem(
                                        selected = vm.homeTab == 2,
                                        onClick = { vm.tryOpenModule("search") { vm.switchHomeTab(2) } },
                                        icon = { Icon(MpdIcons.Search, null, Modifier.size(22.dp)) },
                                        label = { Text(stringResource(Res.string.nav_search)) },
                                    )
                                }
                            }
                        } else {
                            AlbumViewScreen(vm, album, imageLoader)
                        }
                        val lightboxIndex = vm.lightboxIndex
                        if (album != null && lightboxIndex != null) {
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
                        // Aus der Pinnwand geoeffnetes Foto liegt UEBER ihr — ausblenden.
                        if (vm.memories.open && !(vm.albumAusErinnerung && vm.openAlbum != null)) {
                            MemoriesScreen(vm, imageLoader)
                        }
                        if (vm.toursOpen) ToursScreen(vm)
                        if (vm.trailsOpen) TrailsScreen(vm)
                        if (vm.contribOpen) ContribScreen(vm)
                        if (vm.stats.open) StatsScreen(vm)
                        if (vm.search.open) SearchScreen(vm, imageLoader)
                        if (vm.users.open) UsersScreen(vm)
                        if (vm.settingsOpen) SettingsScreen(vm, state.who)
                        PdfViewerOverlay(vm)
                        if (vm.timelineFoldersOpen) {
                            LokaleOrdnerDialog(vm, imageLoader) { vm.timelineFoldersOpen = false }
                        }
                        vm.videoUrl?.let { url ->
                            // Video: Datei in den Cache, Systemplayer (DesktopMedien).
                            LaunchedEffect(url) { DesktopMedien.abspielen(vm, url); vm.closeVideo() }
                        }
                        // Zeitstrahl-Lightbox: chronologischer Pager ohne Albumkontext
                        vm.timelineLightbox?.let { (photos, index) ->
                            LightboxScreen(
                                vm,
                                title = "Timeline",
                                photos = photos,
                                readOnly = false,
                                imageLoader = imageLoader,
                                startIndex = index,
                                onClose = { vm.closeTimelineLightbox() },
                            )
                        }
                    }
                }
                else -> Scaffold(Modifier.fillMaxSize()) { inner ->
                    Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                        Text("$state")
                    }
                }
            }
            }
        }
    }
}

/** Cache-Ordner nach XDG (Linux: ~/.cache/mpd, Windows: %LOCALAPPDATA%\\mpd). */
private fun cacheOrdner(): File {
    val basis = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache")
    return File(basis, "mpd").apply { mkdirs() }
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.ui.theme.Mpd
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/*
 * Albenuebersicht — Nachbau des Web-Rasters (index.html). Farben und
 * Karten-„Personality" (Cover-Verhaeltnis, Radius, Abstaende, Schrift)
 * kommen komplett aus dem Theme (ui/theme/MpdTheme.kt) — Gegenstueck zu
 * den CSS-Variablen des Webs. Kopfzeile: ui/AppHeader.kt.
 *
 * Cover ohne Blur-Unterlage — bewusste Abweichung vom Web (Performance,
 * Entscheidung 01.09.2026): 20+ RenderEffect-Blurs machten das Scrollen
 * hakelig (12 % Janky Frames). Falls der Web-Look zurueck soll: BlurHash
 * der Cover vom Server mitliefern lassen.
 */

@Composable
fun AlbumsScreen(
    vm: AppViewModel,
    who: WhoAmI,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { vm.loadAlbums() }

    val albums = vm.albums
    // Web-Verhalten (getFilteredAlbums in index.js): Titel aufsteigend,
    // deutsche Sortierung — bei "JJJJ - Titel"-Namen also aelteste zuerst.
    val collator = remember { java.text.Collator.getInstance(java.util.Locale.getDefault()) }
    val filtered = albums?.let { list ->
        val bySpace = vm.spaceFilter?.let { f -> list.filter { it.space == f } } ?: list
        bySpace.sortedWith(compareBy(collator) { it.title.ifBlank { it.name } })
    }

    // Rueckgaengig-Snackbar nach Album-Loeschung (Web: Undo-Toast, 10 s)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm.pendingUndoDelete) {
        val snap = vm.pendingUndoDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = Texte.t(Res.string.overview_album_deleted, snap.title),
            actionLabel = Texte.t(Res.string.common_undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            vm.undoDeleteAlbum { err ->
                vm.meldung(err)
            }
        } else {
            vm.pendingUndoDelete = null
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    // Am HANDY ist zwischen Marke und Avatar kein Platz — dort wandern die
    // Zaehl-Pills in eine eigene Zeile unter den Header. Am Tablet gehoeren
    // sie in den Kopf, in beiden Ausrichtungen: die zweite Zeile war
    // „viel zu breiter Balken" im Hochformat (12.09.2026). Die
    // Regel steht in istTablet() und misst die kuerzere Bildschirmkante,
    // nicht die gerade sichtbare Breite.
    val pillsImHeader = istTablet()
    Column(Modifier.fillMaxSize().background(Mpd.colors.bg)) {
        AppHeader(
            vm, who,
            centerContent = if (pillsImHeader) filtered?.let { { StatsPills(it) } } else null,
        )
        if (!pillsImHeader) filtered?.let {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Mpd.colors.surface)
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) { StatsPills(it) }
        }
        MemoriesTeaser(vm)

        when {
            albums == null && vm.albumsError == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> Column(Modifier.fillMaxSize()) {
                vm.albumsError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                filtered?.let {
                    AlbumGrid(vm, it, imageLoader, canEdit = who.role != "viewer" && !who.isDemo)
                }
            }
        }
    }
    SnackbarHost(
        snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
    // Neues Album (Web: Create-Dialog der Startseite) — nur fuer Rollen
    // mit Schreibrecht; der Server prueft ohnehin nochmal.
    if (who.role != "viewer" && !who.isDemo) {
        androidx.compose.material3.FloatingActionButton(
            onClick = { vm.createAlbumOpen = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Text("＋", fontSize = 26.sp) }
    }
    if (vm.createAlbumOpen) {
        CreateAlbumDialog(vm, who, imageLoader)
    }
    vm.tileCoverAlbum?.let { TileCoverDialog(vm, it, imageLoader) }
    vm.tileRenameAlbum?.let { TileRenameDialog(vm, it) }
    vm.tileCropAlbum?.let { TileCropDialog(vm, it, imageLoader) }
    vm.tileDeleteAlbum?.let { TileDeleteDialog(vm, it) }
    vm.tileShareAlbum?.let { album ->
        ShareDialog(
            vm, album.space, album.name, album.title.ifBlank { album.name },
        ) { vm.tileShareAlbum = null }
    }
    }
}

/** 📁/📷/🎬-Pills wie renderStats() im Web — „Photos" markentypisch mit Ph. */
@Composable
private fun StatsPills(albums: List<AlbumSummary>) {
    val photos = albums.sumOf { it.photoCount }
    val videos = albums.sumOf { it.videoCount }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatPill(MpdIcons.Folder, pluralStringResource(Res.plurals.overview_albums, albums.size, albums.size))
        StatPill(MpdIcons.Camera, stringResource(Res.string.overview_photos, String.format(java.util.Locale.getDefault(), "%,d", photos)))
        if (videos > 0) StatPill(MpdIcons.Video, stringResource(Res.string.overview_videos, videos))
    }
}

@Composable
private fun StatPill(icon: ImageVector, text: String) {
    val colors = Mpd.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.padding(end = 5.dp).size(13.dp), tint = colors.text)
        Text(text, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AlbumGrid(
    vm: AppViewModel,
    albums: List<AlbumSummary>,
    imageLoader: ImageLoader,
    canEdit: Boolean,
) {
    val gap = Mpd.style.gridGap
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Spalten wie die Web-Media-Queries (css/index.css).
        //
        // Bewusste Abweichung im Kachel-Theme (08.09.2026, Tab S8
        // Ultra hochkant: „Kacheln sehr gross und nur 3 in Zeile — da
        // koennten vier reinpassen"): Das Web rechnet dort ebenfalls auf
        // drei Spalten (924 px Fenster, minmax(220px), 16 px Rand, 10 px
        // Luecke → 4×220+30 = 910 > 892). Die Kachel ist aber die
        // schlichteste Karte — kein Text unter dem Bild, kein Badge —,
        // und traegt 200 dp ohne Verlust. Mit 200 dp werden es vier.
        // Die uebrigen Themes bleiben beim Web-Mass.
        val w = maxWidth.value
        val kachel = Mpd.themeId == de.bgghome.mpd.ui.theme.MpdThemeId.KACHEL
        val cells = when {
            w <= 480f -> GridCells.Fixed(2)
            w <= 768f -> GridCells.Adaptive(160.dp)
            w < 1600f -> GridCells.Adaptive(if (kachel) 200.dp else 220.dp)
            else -> GridCells.Adaptive(240.dp)
        }
        // GLEICHE STELLE BEIM ZURUECKKOMMEN (12.09.2026). Der
        // Startwert kommt aus dem ViewModel, weil dieser Bildschirm beim
        // Oeffnen eines Albums aus der Komposition faellt; beim Verlassen
        // wird die Stelle dorthin zurueckgeschrieben.
        val gridState = rememberLazyGridState(
            initialFirstVisibleItemIndex = vm.albumsScrollIndex.coerceAtMost(
                (albums.size - 1).coerceAtLeast(0),
            ),
            initialFirstVisibleItemScrollOffset = vm.albumsScrollOffset,
        )
        DisposableEffect(gridState) {
            onDispose {
                vm.albumsScrollIndex = gridState.firstVisibleItemIndex
                vm.albumsScrollOffset = gridState.firstVisibleItemScrollOffset
            }
        }
        NoSystemOverscroll {
        LazyVerticalGrid(
            state = gridState,
            columns = cells,
            modifier = Modifier
                .widthIn(max = 1600.dp)
                .fillMaxSize()
                .align(Alignment.TopCenter)
                .rubberBandOverscroll(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(albums, key = { "${it.space}/${it.name}" }) { album ->
                AlbumCard(vm, album, imageLoader, canEdit)
            }
        }
        }
        // Schnell-Scroller rechts, dieselbe Mechanik wie im Zeitstrahl.
        // Beschriftung: das Jahr aus „JJJJ - Titel", sonst der erste
        // Buchstabe — die Uebersicht ist alphabetisch sortiert, bei
        // Jahresalben heisst das chronologisch.
        val labels = remember(albums) {
            albums.map { a ->
                val titel = a.title.ifBlank { a.name }
                val jahr = titel.take(4)
                if (jahr.length == 4 && jahr.all { c -> c.isDigit() }) jahr
                else titel.take(1).uppercase()
            }
        }
        val scope = rememberCoroutineScope()
        Scrubber(gridState, labels, Mpd.colors.muted, minItems = 20) { idx ->
            scope.launch { gridState.scrollToItem(idx) }
        }
    }
}

@Composable
private fun AlbumCard(
    vm: AppViewModel,
    album: AlbumSummary,
    imageLoader: ImageLoader,
    canEdit: Boolean = false,
) {
    val colors = Mpd.colors
    val style = Mpd.style
    val thumbUrl = albumThumbUrl(vm.baseUrl, album)
    val cardShape = RoundedCornerShape(style.cardRadius)

    // Kachel-Look (minimalCard): transparente Karte ohne Rahmen/Badge,
    // Cover fuellt das Quadrat — wie html[data-theme="kachel"] im Web.
    val cardModifier = if (style.minimalCard) {
        Modifier.clickable(enabled = !vm.albumLoading) { vm.openAlbum(album) }
    } else {
        Modifier
            .clip(cardShape)
            .background(colors.surface)
            .border(1.dp, colors.border, cardShape)
            .clickable(enabled = !vm.albumLoading) { vm.openAlbum(album) }
    }

    Column(cardModifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / style.coverRatio)
                .clip(if (style.minimalCard) cardShape else RoundedCornerShape(0.dp))
                .background(colors.surfaceAlt),
        ) {
            AlbumCoverImage(
                url = thumbUrl,
                crop = album.thumbnailCrop,
                imageLoader = imageLoader,
                contentDescription = album.title,
                fallbackScale = if (style.coverFill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // 🔍 + ⋮ wie im Web: Ausschnitt und Album-Aktionen am Objekt
            if (canEdit) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AlbumTileKebab(vm, album)
                }
            }
        }
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (!style.minimalCard && style.showSpaceBadge) SpaceBadge(album.space == "personal")
            Text(
                album.title.ifBlank { album.name },
                color = colors.text,
                fontSize = style.titleSize,
                fontWeight = style.titleWeight,
                fontFamily = style.fontFamily,
                letterSpacing = style.letterSpacing,
                lineHeight = style.titleSize * 1.25f,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val total = album.photoCount + album.videoCount
            if (total > 0) {
                Text(
                    pluralStringResource(Res.plurals.album_elements, total, total),
                    color = colors.muted,
                    fontSize = style.metaSize,
                    fontFamily = style.fontFamily,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SpaceBadge(personal: Boolean) {
    val colors = Mpd.colors
    val fg = if (personal) colors.gold else colors.blueMid
    Text(
        // Web zeigt das rohe "personal"/"shared" — hier bewusst deutsch;
        // NICHT „Privat", das meint in MPD die gesperrten Elemente (🔒).
        stringResource(if (personal) Res.string.space_personal else Res.string.space_shared),
        color = fg,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(fg.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun albumThumbUrl(baseUrl: String, album: AlbumSummary): String? {
    if (album.thumbnail.isBlank()) return null
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    return base.newBuilder()
        .addPathSegment("api").addPathSegment("thumbnail")
        .addPathSegment(album.space).addPathSegment(album.name)
        .addPathSegment(album.thumbnail)
        .addQueryParameter("size", "sm")
        .build()
        .toString()
}

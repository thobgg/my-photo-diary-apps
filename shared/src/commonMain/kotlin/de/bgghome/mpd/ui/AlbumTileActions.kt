package de.bgghome.mpd.ui


import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AlbumFileEntry
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.ThumbCrop
import de.bgghome.mpd.ui.theme.Mpd
import kotlin.math.min
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.albumFiles
import de.bgghome.mpd.deleteAlbumFromTile
import de.bgghome.mpd.meldung
import de.bgghome.mpd.renameAlbumFromTile
import de.bgghome.mpd.setCoverFromTile

/*
 * Kachel-Aktionen wie im Web (index.html): auf dem Cover sitzen 🔍-Lupe
 * (Ausschnitt) und ⋮-Kebab (Umbenennen, Deckblatt, Teilen, Loeschen).
 * 04.09.: „Kein Deckblattaenderung auffindbar" — im Album-Edit-Modus
 * lag sie zwei Ebenen tief; am Objekt selbst findet man sie sofort.
 */

// ── Cover mit Ausschnitt ───────────────────────────────────────────────

/**
 * Cover-Bild der Kachel. Mit thumbnail_crop wird gerechnet wie im Web-CSS
 * (.album-cover.has-crop): Bild auf 1/w bzw. 1/h aufziehen und um
 * -x/w bzw. -y/h verschieben, der Container schneidet ab.
 */
@Composable
fun AlbumCoverImage(
    url: String?,
    crop: ThumbCrop?,
    imageLoader: ImageLoader,
    contentDescription: String?,
    fallbackScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    if (crop == null || crop.w <= 0f || crop.h <= 0f) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = fallbackScale,
            modifier = modifier,
        )
        return
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val cw = maxWidth
        val ch = maxHeight
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .size(width = cw / crop.w, height = ch / crop.h)
                .offset(x = -(cw * crop.x / crop.w), y = -(ch * crop.y / crop.h)),
        )
    }
}

// ── Lupe + Kebab ───────────────────────────────────────────────────────

/*
 * Die Lupe auf dem Deckblatt ist am 06.09.2026 entfallen.
 *
 * Sie war die Portierung von `.alb-crop-lupe` aus dem Web — dort
 * erscheint sie beim Darueberfahren mit der Maus und kostet nichts,
 * solange man sie nicht will. Am Handy gibt es kein Darueberfahren, also
 * klebte sie dauerhaft auf jedem Deckblatt. Ein Muster, das seine
 * Unsichtbarkeit voraussetzt, funktioniert nicht dort, wo es nicht
 * unsichtbar sein kann.
 *
 * Sie tat ohnehin genau dasselbe wie „🔍 Ausschnitt wählen…" im
 * ⋮-Menue — dort steht sie direkt unter „Deckblatt ändern", also in der
 * Reihenfolge, in der man beides braucht.
 */

/** ⋮ auf dem Cover — Album-Aktionen (Web: .alb-kebab). */
@Composable
fun AlbumTileKebab(vm: AppViewModel, album: AlbumSummary, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val shareEnabled = vm.appSettings?.shareEnabled != false

    Box(modifier) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MpdIcons.MoreVert, stringResource(Res.string.common_more), Modifier.size(18.dp), tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                leadingIcon = { Icon(MpdIcons.Pencil, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(Res.string.tile_rename)) },
                onClick = { open = false; vm.tileRenameAlbum = album },
            )
            DropdownMenuItem(
                leadingIcon = { Icon(MpdIcons.Image, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(Res.string.tile_cover)) },
                onClick = { open = false; vm.tileCoverAlbum = album },
            )
            // Die Lupe BLEIBT eine Lupe (Symbol fuer „Ausschnitt
            // waehlen"; am 06.09. wurde daraus ungefragt ein Zuschnitt-Icon).
            DropdownMenuItem(
                leadingIcon = { Icon(MpdIcons.Search, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(Res.string.tile_crop)) },
                onClick = {
                    open = false
                    if (album.thumbnail.isBlank()) {
                        vm.meldung(Texte.t(Res.string.tile_crop_needs_cover))
                    } else {
                        vm.tileCropAlbum = album
                    }
                },
            )
            DropdownMenuItem(
                // Teilen kann der Server global abschalten (App-Einstellungen)
                // — dann bleibt der Eintrag sichtbar, aber stumm.
                enabled = shareEnabled,
                leadingIcon = { Icon(MpdIcons.Link, null, Modifier.size(18.dp)) },
                text = { Text(stringResource(if (shareEnabled) Res.string.tile_share else Res.string.tile_share_off)) },
                onClick = { open = false; vm.tileShareAlbum = album },
            )
            HorizontalDivider()
            DropdownMenuItem(
                leadingIcon = { Icon(MpdIcons.Trash, null, Modifier.size(18.dp), tint = MpdRed) },
                text = { Text(stringResource(Res.string.tile_delete), color = MpdRed) },
                onClick = { open = false; vm.tileDeleteAlbum = album },
            )
        }
    }
}

// ── Dialoge ────────────────────────────────────────────────────────────

/** Umbenennen — wie im Web nur meta.title, der Ordner bleibt. */
@Composable
fun TileRenameDialog(vm: AppViewModel, album: AlbumSummary) {
    var title by remember(album.name) {
        mutableStateOf(album.title.ifBlank { album.name })
    }
    var saving by remember(album.name) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { vm.tileRenameAlbum = null },
        title = { Text(stringResource(Res.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.rename_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(Res.string.rename_hint, album.name),
                    color = Mpd.colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    vm.renameAlbumFromTile(album, title) { err ->
                        saving = false
                        if (err == null) {
                            vm.tileRenameAlbum = null
                        } else {
                            vm.meldung(err)
                        }
                    }
                },
            ) { Text(stringResource(if (saving) Res.string.common_saving else Res.string.rename_btn)) }
        },
        dismissButton = {
            TextButton(onClick = { vm.tileRenameAlbum = null }) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Loesch-Rueckfrage; das Rueckgaengig kommt danach als Snackbar. */
@Composable
fun TileDeleteDialog(vm: AppViewModel, album: AlbumSummary) {
    AlertDialog(
        onDismissRequest = { vm.tileDeleteAlbum = null },
        title = { Text(stringResource(Res.string.tile_delete_title, album.title.ifBlank { album.name })) },
        text = {
            Text(
                stringResource(Res.string.tile_delete_body),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.tileDeleteAlbum = null
                vm.deleteAlbumFromTile(album) { err ->
                    vm.meldung(err)
                }
            }) { Text(stringResource(Res.string.common_delete), color = MpdRed) }
        },
        dismissButton = {
            TextButton(onClick = { vm.tileDeleteAlbum = null }) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Fotoraster des Albums; Tipp setzt meta.thumbnail (Web: Deckblatt-Modal). */
@Composable
fun TileCoverDialog(vm: AppViewModel, album: AlbumSummary, imageLoader: ImageLoader) {
    val colors = Mpd.colors
    var files by remember(album.name) { mutableStateOf<List<AlbumFileEntry>?>(null) }
    var error by remember(album.name) { mutableStateOf<String?>(null) }
    var saving by remember(album.name) { mutableStateOf(false) }

    LaunchedEffect(album.space, album.name) {
        runCatching { vm.api.albumFiles(vm.baseUrl, album.space, album.name) }
            .onSuccess { files = it.files.filter { f -> f.thumbnail.isNotBlank() } }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = { vm.tileCoverAlbum = null },
        title = { Text(stringResource(Res.string.cover_title, album.title.ifBlank { album.name })) },
        text = {
            when {
                error != null -> Text(error!!, color = MpdRed)
                saving -> LoadingRow(stringResource(Res.string.common_saving_spaced))
                files == null -> LoadingRow(stringResource(Res.string.common_loading_photos))
                files!!.isEmpty() -> Text(stringResource(Res.string.cover_none), color = colors.muted)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                ) {
                    items(files!!, key = { it.filename }) { f ->
                        val isCurrent = f.filename == album.thumbnail
                        Box(
                            Modifier
                                .padding(3.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22000000))
                                .clickable(enabled = !saving) {
                                    saving = true
                                    vm.setCoverFromTile(album, f.filename) { err ->
                                        saving = false
                                        if (err == null) {
                                            vm.tileCoverAlbum = null
                                        } else {
                                            vm.meldung(err)
                                        }
                                    }
                                },
                        ) {
                            AsyncImage(
                                model = vm.baseUrl + f.thumbnail,
                                imageLoader = imageLoader,
                                contentDescription = f.filename,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (isCurrent) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(Color(0xAA2E75B6)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("aktuell", color = Color.White, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.tileCoverAlbum = null }) { Text(stringResource(Res.string.common_close)) }
        },
    )
}


@Composable
internal fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = Mpd.colors.muted)
    }
}

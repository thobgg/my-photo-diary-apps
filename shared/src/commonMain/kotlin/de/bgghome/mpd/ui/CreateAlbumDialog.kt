package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AlbumFileEntry
import de.bgghome.mpd.api.FolderCandidate
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.albumFiles

/*
 * „Neues Album" — Port des Create-Dialogs der Web-Startseite (index.js):
 * 1. Ordner-Scan (GET /api/albums/without-json): Ordner mit Medien, aber
 *    ohne album.json.
 * 2. Ordner antippen → Deckblatt aus den Fotos waehlen
 *    (GET /api/album/files).
 * 3. POST /api/album/create → Album oeffnet sich direkt.
 * Fussweg „Leeres Album": neuer Ordnername → POST /api/album/create-empty.
 */

@Composable
fun CreateAlbumDialog(vm: AppViewModel, who: WhoAmI, imageLoader: ImageLoader) {
    var chosenFolder by remember { mutableStateOf<FolderCandidate?>(null) }
    var emptyMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadFolderCandidates() }

    val onDismiss = { vm.createAlbumOpen = false }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    emptyMode -> stringResource(Res.string.create_empty_title)
                    chosenFolder != null -> stringResource(Res.string.cover_title, chosenFolder!!.folderName)
                    else -> stringResource(Res.string.create_title)
                },
            )
        },
        text = {
            when {
                emptyMode -> EmptyAlbumForm(vm, who)

                // Fehler landen in der Snackbar der Albenuebersicht, denn
                // der Dialog ist da schon zu (12.09.2026).
                chosenFolder != null -> CoverStep(vm, imageLoader, chosenFolder!!) { err ->
                    if (err != null) vm.meldung(err)
                }

                else -> FolderStep(vm, imageLoader) { chosenFolder = it }
            }
        },
        confirmButton = {
            if (!emptyMode && chosenFolder == null) {
                TextButton(onClick = { emptyMode = true }) { Text(stringResource(Res.string.create_empty_btn)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    when {
                        chosenFolder != null -> chosenFolder = null
                        emptyMode -> emptyMode = false
                        else -> onDismiss()
                    }
                },
            ) { Text(stringResource(if (chosenFolder != null || emptyMode) Res.string.common_back else Res.string.common_cancel)) }
        },
    )
}

/** Schritt 1: Ordner ohne Album — wie der Scan-Schritt des Web-Dialogs. */
@Composable
private fun FolderStep(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onPick: (FolderCandidate) -> Unit,
) {
    val colors = Mpd.colors
    val folders = vm.folderCandidates
    when {
        vm.folderCandidatesError != null -> Text(
            "${vm.folderCandidatesError}",
            color = MpdRed,
        )

        folders == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.create_scanning), color = colors.muted)
        }

        folders.isEmpty() -> Text(
            stringResource(Res.string.create_all_have_album),
            color = colors.muted,
        )

        else -> LazyColumn(Modifier.height(400.dp)) {
            items(folders, key = { "${it.space}/${it.folderName}" }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPick(folder) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (folder.firstPhoto != null) {
                        AsyncImage(
                            model = vm.baseUrl +
                                de.bgghome.mpd.api.MpdApi.thumbnailPath(
                                    folder.space, folder.folderName, folder.firstPhoto, "sm",
                                ),
                            imageLoader = imageLoader,
                            contentDescription = null,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22000000)),
                        )
                    } else {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22000000)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(MpdIcons.Folder, null, Modifier.size(22.dp), tint = colors.muted) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            folder.folderName,
                            color = colors.text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(
                                Res.string.create_media_count, folder.mediaCount,
                                stringResource(if (folder.space == "shared") Res.string.space_shared else Res.string.space_personal),
                            ),
                            color = colors.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Schritt 2: Deckblatt waehlen → Album anlegen. */
@Composable
private fun CoverStep(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    folder: FolderCandidate,
    onError: (String?) -> Unit,
) {
    val colors = Mpd.colors
    var files by remember { mutableStateOf<List<AlbumFileEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(folder) {
        runCatching { vm.api.albumFiles(vm.baseUrl, folder.space, folder.folderName) }
            .onSuccess { files = it.files.filter { f -> f.thumbnail.isNotBlank() } }
            .onFailure { error = it.message }
    }

    when {
        error != null -> Text(error!!, color = MpdRed)
        creating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.create_creating), color = colors.muted)
        }
        files == null -> CircularProgressIndicator(Modifier.size(24.dp))
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(88.dp),
            modifier = Modifier.fillMaxWidth().height(400.dp),
        ) {
            items(files!!, key = { it.filename }) { f ->
                AsyncImage(
                    model = vm.baseUrl + f.thumbnail,
                    imageLoader = imageLoader,
                    contentDescription = f.filename,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .padding(3.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22000000))
                        .clickable(enabled = !creating) {
                            creating = true
                            // Deckblatt gewaehlt = Vorgang beendet. Der
                            // Dialog geht SOFORT zu, man ist in der
                            // Albenuebersicht; das Anlegen laeuft dahinter
                            // weiter und meldet sich dort (Vorgabe,
                            // 12.09.2026: „man wird ja eher selten gleich
                            // mehrere anlegen wollen").
                            vm.createAlbumOpen = false
                            vm.createAlbumFromFolder(
                                folder.space, folder.folderName, f.filename,
                            ) { err ->
                                creating = false
                                onError(err)
                            }
                        },
                )
            }
        }
    }
}

/** Fussweg: neuer leerer Ordner (Web: create-empty fuer gemischte Alben). */
@Composable
private fun EmptyAlbumForm(vm: AppViewModel, who: WhoAmI) {
    val colors = Mpd.colors
    var name by remember { mutableStateOf("") }
    var space by remember { mutableStateOf(if (who.role == "admin") "shared" else "personal") }
    var creating by remember { mutableStateOf(false) }

    Column {
        Text(
            stringResource(Res.string.create_empty_hint),
            color = colors.muted,
            fontSize = 13.sp,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(Res.string.create_name_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        // Space-Wahl: Admin darf beides, Editoren nur Persoenlich (das
        // Schreibrecht prueft der Server ohnehin nochmal).
        if (who.role == "admin") {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.create_target), color = colors.text, modifier = Modifier.padding(end = 10.dp))
                listOf(
                    Triple("shared", MpdIcons.Users, stringResource(Res.string.space_shared)),
                    Triple("personal", MpdIcons.LockClosed, stringResource(Res.string.space_personal)),
                ).forEach { (key, icon, label) ->
                    TextButton(onClick = { space = key }) {
                        IconLabel(
                            icon, label, color = LocalContentColor.current,
                            fontWeight = if (space == key) FontWeight.Bold else FontWeight.Normal,
                            iconSize = 16.dp, gap = 6.dp,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = name.isNotBlank() && !creating,
                onClick = {
                    creating = true
                    vm.createEmptyAlbumUi(space, name.trim()) { err ->
                        creating = false
                        if (err != null) vm.meldung(err)
                    }
                },
            ) { Text(stringResource(if (creating) Res.string.create_creating_short else Res.string.create_btn)) }
        }
    }
}

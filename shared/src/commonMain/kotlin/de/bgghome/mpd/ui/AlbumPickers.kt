package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.UnassignedFile
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import de.bgghome.mpd.api.unassignedMedia

/*
 * Auswahl-Dialoge fuer die Albumgestaltung. Web-Referenz: openAddPhotoModal
 * in album-ui.js — Raster der noch nicht referenzierten Ordnerdateien
 * (/api/album/…/unassigned-photos), Tipp fuegt ein. Abweichung vom Web: der
 * Dialog bleibt fuer weitere Dateien offen (das Web schliesst nach jedem
 * Einfuegen — am Geraet nervt das bei Serien), Fertig schliesst.
 */

@Composable
internal fun AddMediaDialog(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onPick: (UnassignedFile) -> Unit,
) {
    val album = vm.openAlbum ?: return
    var files by remember { mutableStateOf<List<UnassignedFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(album.summary.name) {
        runCatching {
            vm.api.unassignedMedia(vm.baseUrl, album.summary.space, album.summary.name)
        }.onSuccess { files = it }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.media_title)) },
        text = {
            when {
                error != null -> Text(stringResource(Res.string.common_load_failed, error.orEmpty()), color = MpdRed)
                files == null -> Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                files!!.isEmpty() -> Text(
                    stringResource(Res.string.media_all_in_album),
                    color = MpdMuted,
                )

                else -> Column {
                    Text(
                        pluralStringResource(Res.plurals.media_pending, files!!.size, files!!.size),
                        color = MpdMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(88.dp),
                        modifier = Modifier.fillMaxWidth().height(380.dp),
                    ) {
                        items(files!!, key = { it.file }) { f ->
                            Box(
                                Modifier
                                    .padding(3.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        files = files!!.filterNot { it.file == f.file }
                                        onPick(f)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = vm.baseUrl + MpdApi.thumbnailPath(
                                        album.summary.space, album.summary.name, f.file, "sm",
                                    ),
                                    imageLoader = imageLoader,
                                    contentDescription = f.file,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (f.type == "video") {
                                    Box(
                                        Modifier
                                            .size(30.dp)
                                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(MpdIcons.Play, stringResource(Res.string.video), Modifier.size(16.dp), tint = Color.White) }
                                }
                                Text(
                                    f.file,
                                    color = Color.White, fontSize = 9.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) }
        },
    )
}

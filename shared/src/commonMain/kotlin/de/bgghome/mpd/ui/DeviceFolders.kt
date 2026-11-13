package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import de.bgghome.mpd.loadDeviceFolderList
import de.bgghome.mpd.toggleTimelineBucket

/**
 * Welche Geraeteordner in der Timeline erscheinen (06.09.2026:
 * „ich will am Geraet entscheiden koennen, welche Ordner des Geraetes").
 *
 * Bewusst getrennt vom Kamera-Backup, obwohl die Ordnerliste dieselbe ist:
 * dort steht, was *gesichert* wird, hier, was man *sieht*. Screenshots
 * will man vielleicht sehen, aber nicht auf der NAS haben — und umgekehrt.
 *
 * Abgehakt heisst „wird angezeigt"; gespeichert wird der Ausschluss, damit
 * „alles sichtbar" der Standard bleibt und ein neu angelegter Ordner nicht
 * stillschweigend fehlt.
 *
 * Jede Zeile traegt die neueste Aufnahme des Ordners als Vorschau — einen
 * Ordner erkennt man am Bild schneller als an „DCIM_2" (Thomas zur ersten
 * Fassung mit ☑-Zeichen: „das Menu nicht so schoen").
 */
@Composable
fun TimelineFoldersDialog(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    LaunchedEffect(Unit) { vm.loadDeviceFolderList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.folders_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.folders_intro),
                    color = colors.muted,
                    fontSize = 12.sp,
                )
                when (val folders = vm.deviceFolderList) {
                    null -> Text(
                        stringResource(Res.string.folders_need_permission),
                        color = colors.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 10.dp)) {
                        items(folders.size) { i ->
                            val folder = folders[i]
                            val shown = folder.name !in vm.timelineHiddenBuckets
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.toggleTimelineBucket(folder.name) }
                                    .padding(vertical = 5.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceAlt),
                                ) {
                                    folder.preview?.let {
                                        AsyncImage(
                                            model = it,
                                            imageLoader = imageLoader,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(42.dp),
                                        )
                                    }
                                }
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp, end = 8.dp),
                                ) {
                                    Text(
                                        folder.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        pluralStringResource(Res.plurals.folders_items_n, folder.count, folder.count),
                                        color = colors.muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                Checkbox(
                                    checked = shown,
                                    onCheckedChange = { vm.toggleTimelineBucket(folder.name) },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = colors.gold,
                                        uncheckedColor = colors.muted,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) } },
    )
}

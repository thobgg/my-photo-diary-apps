package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.loadDeviceFolderList
import de.bgghome.mpd.lokalenOrdnerEntfernen
import de.bgghome.mpd.lokalenOrdnerHinzufuegen

/**
 * Desktop: welche Ordner auf diesem PC im Zeitstrahl erscheinen
 * (Vorgabe, 04.10.2026: im Zeitstrahl markieren, um zu loeschen oder die
 * markierten hochzuladen — „es soll für die deb auf Linux und die exe in
 * Windows gehen").
 *
 * Anders als am Handy (Android-Ordnerliste mit Haken, alles sichtbar als
 * Standard) beginnt die Liste leer: einen Kameraordner gibt es am PC nicht,
 * und der Bilderordner ist dort oft das ganze Archiv oder ein Spiegel der
 * NAS. Hochgeladen wird nie von selbst, nur was man markiert und sichert.
 */
@Composable
fun LokaleOrdnerDialog(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    LaunchedEffect(vm.lokaleOrdner) { vm.loadDeviceFolderList() }
    var privat by remember { mutableStateOf(vm.settings.backupPrivat) }
    val chooseTitle = stringResource(Res.string.pc_folders_choose)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp),
        title = { Text(stringResource(Res.string.pc_folders_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.pc_folders_intro), color = colors.muted, fontSize = 12.sp)
                val folders = vm.deviceFolderList.orEmpty()
                if (vm.lokaleOrdner.isEmpty()) {
                    Text(
                        stringResource(Res.string.pc_folders_empty),
                        color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 10.dp)) {
                        items(vm.lokaleOrdner.size) { i ->
                            val pfad = vm.lokaleOrdner[i]
                            val info = folders.firstOrNull { it.name == pfad }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceAlt),
                                ) {
                                    info?.preview?.let {
                                        AsyncImage(
                                            model = it, imageLoader = imageLoader,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(42.dp),
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                    Text(
                                        pfad.substringAfterLast('/').substringAfterLast('\\').ifEmpty { pfad },
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                    )
                                    Text(
                                        pfad, color = colors.muted, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                                    )
                                    info?.let {
                                        Text(
                                            pluralStringResource(Res.plurals.folders_items_n, it.count, it.count),
                                            color = colors.muted, fontSize = 11.sp,
                                        )
                                    }
                                }
                                Icon(
                                    MpdIcons.Cross, stringResource(Res.string.pc_folders_remove),
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .clickable { vm.lokalenOrdnerEntfernen(pfad) }
                                        .padding(7.dp),
                                    tint = colors.muted,
                                )
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { dienste.waehleOrdner(chooseTitle)?.let { vm.lokalenOrdnerHinzufuegen(it) } },
                    modifier = Modifier.padding(top = 6.dp),
                ) { IconLabel(MpdIcons.Folder, stringResource(Res.string.pc_folders_add), color = colors.blue, iconSize = 16.dp, gap = 6.dp) }

                // Ziel der Uploads — dieselbe Einstellung wie das Kamera-Backup
                // am Handy (backupPrivat), damit es nur EINE Antwort gibt.
                Text(
                    stringResource(Res.string.pc_folders_target),
                    color = colors.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to Res.string.pc_folders_target_shared, true to Res.string.pc_folders_target_personal)
                        .forEach { (wert, label) ->
                            val aktiv = privat == wert
                            Text(
                                stringResource(label),
                                color = if (aktiv) colors.gold else colors.muted,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, if (aktiv) colors.gold else colors.border, RoundedCornerShape(14.dp))
                                    .clickable { privat = wert; vm.settings.backupPrivat = wert }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) } },
    )
}

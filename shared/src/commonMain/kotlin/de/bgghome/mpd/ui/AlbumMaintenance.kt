package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.BackupEntry
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.jetbrains.compose.resources.stringResource

/*
 * Album-Wartung: Sicherungen ansehen/wiederherstellen und Album loeschen.
 *
 * Die Sicherungs-Oberflaeche ist APP-EXKLUSIV — die Server-Endpunkte
 * (…/backups, …/restore) existieren laengst, aber das Web hat keine UI
 * dafuer. Sie ist unser beschlossener Undo-Ersatz (Entscheidung 02.09.2026:
 * Undo/Redo vertagt, Backups-Restore stattdessen).
 * Das Loeschen folgt dem Web (index.html _confirmDeleteAlbum): Namens-
 * Bestaetigung, geloescht wird NUR album.json (Overlay-Modell), Rueckgaengig
 * per undo-delete + Backup.
 */

@Composable
internal fun BackupsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val album = vm.openAlbum ?: return
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<BackupEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<BackupEntry?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(album.summary.name) {
        runCatching {
            vm.api.listBackups(vm.baseUrl, album.summary.space, album.summary.name)
        }.onSuccess { backups = it }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.backups_title)) },
        text = {
            LoadState(
                error = error, data = backups,
                isEmpty = { it.isEmpty() },
                errorContent = { Text(stringResource(Res.string.common_load_failed, it), color = MpdRed) },
                loading = { LoadingCenter(Modifier.fillMaxWidth().height(100.dp)) },
                empty = {
                    Text(
                        stringResource(Res.string.backups_none),
                        color = MpdMuted,
                    )
                },
            ) { list ->
                Column {
                    Text(
                        stringResource(Res.string.backups_hint),
                        color = MpdMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                        items(list, key = { it.filename }) { b ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !busy) { confirmRestore = b }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(formatBackupDate(b.created), fontSize = 14.sp)
                                    Text(
                                        stringResource(Res.string.backups_size, b.size / 1024, b.filename),
                                        color = MpdMuted, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) } },
    )

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text(stringResource(Res.string.restore_title)) },
            text = {
                Text(
                    stringResource(Res.string.restore_body, formatBackupDate(backup.created)),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    busy = true
                    scope.launch {
                        try {
                            vm.api.restoreBackup(
                                vm.baseUrl, album.summary.space,
                                album.summary.name, backup.filename,
                            )
                            vm.reloadAlbum()
                            vm.meldung(Texte.t(Res.string.restore_done), kurz = true)
                            onDismiss()
                        } catch (e: Exception) {
                            vm.meldung(Texte.t(Res.string.restore_failed, e.message))
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.restore_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}

/** Web _openDeleteConfirmModal: Albumtitel abtippen schaltet Loeschen frei. */
@Composable
internal fun DeleteAlbumDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val album = vm.openAlbum ?: return
    val expected = album.title.ifBlank { album.summary.name }
    var typed by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.delete_album_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.delete_album_body),
                )
                Text(
                    stringResource(Res.string.delete_album_confirm),
                    color = MpdMuted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Text("„$expected“", fontSize = 13.sp, color = MpdGoldLt)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.trim() == expected,
                onClick = {
                    onDismiss()
                    vm.deleteCurrentAlbum { err ->
                        vm.meldung(err)
                    }
                },
            ) { Text(stringResource(Res.string.common_delete), color = if (typed.trim() == expected) MpdRed else MpdMuted) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

private fun formatBackupDate(iso: String): String = runCatching {
    LocalDateTime.parse(iso.substringBefore("+").substringBefore("Z"))
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
}.getOrDefault(iso)

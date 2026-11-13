package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.ui.theme.MpdThemeId
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.AndroidAppViewModel
import androidx.compose.ui.platform.LocalContext
import de.bgghome.mpd.loadDeviceFolderList

/**
 * Geraeteordner-Backup (ersetzt Synology Photos, 02.09.2026): sichert
 * NEUE Aufnahmen aus DCIM/Camera in den Durchlauf-Ordner; laeuft als
 * WorkManager-Job auch ohne App. Braucht Lese-Berechtigung fuer die
 * Mediathek (Anfrage beim Einschalten).
 */
@Composable
fun BackupDialog(vm: AndroidAppViewModel, onDismiss: () -> Unit) {
    val colors = Mpd.colors
    val context = LocalContext.current
    val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) vm.backup.enable(true)
    }

    androidx.compose.runtime.LaunchedEffect(vm.backup.enabled) {
        val ok = permissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, it,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (ok) vm.loadDeviceFolderList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.backup_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.backup_intro),
                    fontSize = 13.sp,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.backup_active), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    androidx.compose.material3.Switch(
                        checked = vm.backup.enabled,
                        onCheckedChange = { on ->
                            if (on) launcher.launch(permissions)
                            else vm.backup.enable(false)
                        },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = colors.gold,
                            uncheckedTrackColor = colors.surfaceAlt,
                            uncheckedBorderColor = colors.muted,
                            uncheckedThumbColor = colors.muted,
                        ),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.backup_target), Modifier.weight(1f))
                    IconLabel(
                        if (vm.backup.privat) MpdIcons.LockClosed else MpdIcons.Users,
                        if (vm.backup.privat) stringResource(Res.string.backup_target_private) else stringResource(Res.string.backup_target_family),
                        color = colors.gold,
                        fontWeight = FontWeight.SemiBold,
                        iconSize = 16.dp, gap = 6.dp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { vm.backup.toggleTarget() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.backup_network), Modifier.weight(1f))
                    Text(
                        if (vm.backup.wifiOnly) stringResource(Res.string.backup_wifi_only) else stringResource(Res.string.backup_wifi_mobile),
                        color = colors.blue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { vm.backup.toggleNetwork() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                // Geraeteordner (wie Googles „Geraeteordner sichern")
                Text(
                    stringResource(Res.string.backup_folders),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                // Dieselbe Ordnerliste wie der Timeline-Dialog (LocalMedia.
                // folders, seit 08.09.2026 nur einmal abgefragt); hier nur
                // die Namen, alphabetisch wie bisher.
                val folders = vm.deviceFolderList?.map { it.name }?.sorted()
                if (folders == null) {
                    Text(
                        stringResource(Res.string.backup_folders_need_permission),
                        color = colors.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        Modifier.heightIn(max = 190.dp).padding(top = 2.dp),
                    ) {
                        items(folders.size) { i ->
                            val name = folders[i]
                            val active = name in vm.backup.buckets
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.backup.toggleBucket(name) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (active) MpdIcons.CheckBoxOn else MpdIcons.CheckBoxOff,
                                    if (active) stringResource(Res.string.backup_folder_on) else stringResource(Res.string.backup_folder_off),
                                    Modifier.padding(end = 10.dp).size(18.dp),
                                    tint = if (active) colors.gold else colors.muted,
                                )
                                Text(name, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
                val (since, lastRun, uploaded) = vm.backup.info()
                if (since > 0) {
                    val fmt = java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                    )
                    Text(
                        stringResource(Res.string.backup_since, fmt.format(java.util.Date(since))) +
                            (if (lastRun > 0) stringResource(Res.string.backup_last_run, fmt.format(java.util.Date(lastRun))) else "") +
                            stringResource(Res.string.backup_uploaded_n, uploaded),
                        color = colors.muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) } },
    )
}

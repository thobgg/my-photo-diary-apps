package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AndroidAppViewModel
import de.bgghome.mpd.data.IncomingMedia
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/**
 * Was mit hereingereichten Aufnahmen geschehen soll (06.09.2026).
 *
 * Zwei Wege kommen herein: „Teilen mit MPD" aus einer beliebigen App und
 * „Öffnen mit MPD" für eine einzelne Datei. Beide sind neu — das Manifest
 * kannte bis heute nur MAIN/LAUNCHER.
 *
 * Der Dialog fragt bewusst NICHT nach dem Zielort. Der Durchlauf ist
 * gesetzt, wie beim Kamera-Backup: nackte Jahreszahl, Bestand nach
 * derselben Einstellung. „Reinkommen muss ohne jede Entscheidung
 * funktionieren" (CLAUDE.md) — einsortiert wird später, im Zeitstrahl,
 * wo man ohnehin hinschaut.
 */
@Composable
fun IncomingDialog(
    vm: AndroidAppViewModel,
    incoming: IncomingMedia.Incoming,
    imageLoader: ImageLoader,
) {
    val colors = Mpd.colors
    val context = LocalContext.current
    val count = incoming.uris.size

    AlertDialog(
        onDismissRequest = { if (!vm.incomingBusy) vm.incoming = null },
        title = {
            Text(
                if (incoming.viewOnly) stringResource(Res.string.incoming_view)
                else pluralStringResource(Res.plurals.incoming_save_q, count, count),
            )
        },
        text = {
            Column {
                LazyRow(Modifier.padding(bottom = 10.dp)) {
                    items(incoming.uris.size.coerceAtMost(8)) { i ->
                        val uri = incoming.uris[i]
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceAlt),
                        ) {
                            AsyncImage(
                                model = uri,
                                imageLoader = imageLoader,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp),
                            )
                        }
                    }
                }
                if (count > 8) {
                    Text(
                        stringResource(Res.string.incoming_more, count - 8),
                        color = colors.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    if (incoming.viewOnly) {
                        IncomingMedia.displayName(context, incoming.uris.first())
                    } else {
                        stringResource(Res.string.incoming_hint)
                    },
                    color = colors.muted,
                    fontSize = 13.sp,
                )
                if (vm.incomingBusy) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            stringResource(Res.string.incoming_transferring),
                            color = colors.muted, fontSize = 13.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!incoming.viewOnly) {
                TextButton(
                    enabled = !vm.incomingBusy,
                    onClick = { vm.saveIncoming(context) },
                ) { Text(stringResource(Res.string.incoming_save)) }
            } else {
                TextButton(onClick = { vm.incoming = null }) { Text(stringResource(Res.string.common_close)) }
            }
        },
        dismissButton = {
            if (!incoming.viewOnly) {
                TextButton(
                    enabled = !vm.incomingBusy,
                    onClick = { vm.incoming = null },
                ) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

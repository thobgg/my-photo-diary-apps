package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AndroidAppViewModel
import de.bgghome.mpd.data.LocalMedia
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/*
 * Papierkorb des Geraets (07.09.2026).
 *
 * Bis hierher konnte MPD etwas wegwerfen, aber nicht zeigen, wohin. Wer
 * es zurueckwollte, musste die App verlassen und in Files oder der
 * Samsung-Galerie suchen — nach der Messlatte aus CLAUDE.md („koennte
 * jemand danach Google Fotos deinstallieren?") genau einer der Gruende,
 * die andere App liegenzulassen.
 *
 * ES GEHT NUR UM DAS GERAET. Der Papierkorb der NAS steht hier nicht: der
 * Server kann seine eigenen Loeschungen im DSM-Korb nicht von fremden
 * unterscheiden (kein Loesch-Journal), und ein Verzeichnis-Scan wuerde
 * Dateien anbieten, die andere Wege geloescht haben. Das sagt die
 * Ueberschrift deshalb ausdruecklich, statt einen halben Papierkorb als
 * ganzen auszugeben.
 *
 * Zurueckholen und endgueltiges Loeschen laufen ueber die System-Dialoge
 * (MainActivity) — anders darf eine App fremde Mediendateien nicht
 * anfassen. Eine Auswahl ergibt EINE Rueckfrage.
 */
@Composable
fun TrashScreen(vm: AndroidAppViewModel, imageLoader: ImageLoader) {
    val colors = Mpd.colors
    LaunchedEffect(Unit) { vm.trash.load() }

    val items = vm.trash.items
    val auswahl = vm.trash.selection
    var eraseConfirm by remember { mutableStateOf(false) }

    MpdScreen(
        backDescription = stringResource(Res.string.trash_close),
        onClose = { vm.trash.hide() },
        header = {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.trash_title),
                    color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(Res.string.trash_subtitle),
                    color = colors.muted, fontSize = 12.sp,
                )
            }
            if (!items.isNullOrEmpty()) {
                TextButton(
                    onClick = {
                        if (auswahl.size == items.size) vm.trash.clearSelection()
                        else vm.trash.selectAll()
                    },
                ) {
                    Text(
                        if (auswahl.size == items.size) stringResource(Res.string.common_none) else stringResource(Res.string.common_all),
                        color = colors.blue, fontSize = 13.sp,
                    )
                }
            }
        },
    ) {
        when {
            items == null -> LoadingCenter(size = 28.dp)

            // Vor Android 11 gibt es den Papierkorb gar nicht. Das ist etwas
            // anderes als „leer" und muss auch anders dastehen — sonst
            // wartet jemand auf etwas, das nie kommt.
            android.os.Build.VERSION.SDK_INT < 30 -> LeerHinweis(
                stringResource(Res.string.trash_none_title),
                stringResource(Res.string.trash_none_body),
            )

            items.isEmpty() -> LeerHinweis(
                stringResource(Res.string.trash_empty_title),
                stringResource(Res.string.trash_empty_body),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.uri }) { eintrag ->
                    TrashTile(
                        eintrag = eintrag,
                        gewaehlt = eintrag.uri in auswahl,
                        imageLoader = imageLoader,
                    ) { vm.trash.toggleSelection(eintrag.uri) }
                }
            }
        }

        if (auswahl.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.trash_selected_n, auswahl.size),
                    color = colors.muted, fontSize = 13.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { eraseConfirm = true }) {
                    Text(stringResource(Res.string.trash_erase), color = MpdRed, fontSize = 13.sp)
                }
                TextButton(onClick = { vm.trash.requestUntrash() }) {
                    Text(stringResource(Res.string.trash_restore), color = colors.blue, fontSize = 13.sp)
                }
            }
        }
    }

    if (eraseConfirm) {
        AlertDialog(
            onDismissRequest = { eraseConfirm = false },
            icon = { Icon(MpdIcons.Trash, null, Modifier.size(28.dp), tint = MpdRed) },
            // Hier darf „endgueltig" stehen — anders als beim Wegwerfen, wo
            // der Papierkorb noch dazwischen liegt. Dahinter kommt nichts
            // mehr.
            title = { Text(stringResource(Res.string.trash_erase_q)) },
            text = {
                Text(pluralStringResource(Res.plurals.trash_erase_body_n, auswahl.size, auswahl.size))
            },
            confirmButton = {
                TextButton(onClick = { eraseConfirm = false; vm.trash.requestErase() }) {
                    Text(stringResource(Res.string.common_delete), color = MpdRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { eraseConfirm = false }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun LeerHinweis(titel: String, hinweis: String) {
    val colors = Mpd.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MpdIcons.Trash, null, Modifier.size(38.dp), tint = colors.muted)
        Spacer(Modifier.height(12.dp))
        Text(titel, color = colors.text, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            hinweis,
            color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Eine Kachel. Die Restfrist steht drauf, nicht in einem Untermenue: sie
 * ist der einzige Grund, warum diese Ansicht dringlich ist. Die letzten
 * drei Tage stehen in Gold — ab da entscheidet man wirklich.
 */
@Composable
private fun TrashTile(
    eintrag: LocalMedia.Trashed,
    gewaehlt: Boolean,
    imageLoader: ImageLoader,
    onToggle: () -> Unit,
) {
    val colors = Mpd.colors
    val tage = LocalMedia.restTage(eintrag.ablauf)

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.Black)
            .clickable(onClick = onToggle),
    ) {
        AsyncImage(
            model = eintrag.uri,
            imageLoader = imageLoader,
            contentDescription = eintrag.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (gewaehlt) {
            Box(Modifier.fillMaxSize().background(colors.blue.copy(alpha = 0.35f)))
        }
        // Auswahlkreis oben links — dasselbe Muster wie im Zeitstrahl.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (gewaehlt) colors.blue else Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (gewaehlt) {
                Icon(MpdIcons.Check, stringResource(Res.string.common_selected), Modifier.size(12.dp), tint = colors.textOnAccent)
            }
        }
        if (eintrag.isVideo) {
            Icon(
                MpdIcons.Play, stringResource(Res.string.common_video), tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(22.dp),
            )
        }
        Text(
            when {
                tage == null -> stringResource(Res.string.trash_no_deadline)
                tage <= 0 -> stringResource(Res.string.trash_expiring)
                else -> pluralStringResource(Res.plurals.trash_days_left_n, tage.toInt(), tage.toInt())
            },
            color = if (tage != null && tage <= 3) colors.goldLt else Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

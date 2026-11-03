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
import androidx.compose.ui.graphics.ImageBitmap
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

/**
 * Ausschnitt-Editor (Web: Lupe → _openAlbumCropEditor). Der Rahmen bleibt
 * quadratisch — die Kachel ist es auch, ein freies Rechteck wuerde beim
 * Anzeigen verzerrt. Gespeichert wird normalisiert (0..1).
 */
@Composable
fun TileCropDialog(vm: AppViewModel, album: AlbumSummary, imageLoader: ImageLoader) {
    val context = coil3.compose.LocalPlatformContext.current
    var bitmap by remember(album.name) { mutableStateOf<ImageBitmap?>(null) }
    var crop by remember(album.name) {
        mutableStateOf(
            album.thumbnailCrop?.let { c -> Rect(c.x, c.y, c.x + c.w, c.y + c.h) }
                ?: Rect(0f, 0f, 1f, 1f),
        )
    }
    var saving by remember(album.name) { mutableStateOf(false) }

    LaunchedEffect(album.space, album.name, album.thumbnail) {
        val url = vm.baseUrl + de.bgghome.mpd.api.MpdApi.thumbnailPath(
            album.space, album.name, album.thumbnail, "xl",
        )
        val request = ImageRequest.Builder(context)
            .data(url).softwareBitmap().build()
        val bild = imageLoader.execute(request).image
        bitmap = bild?.zuImageBitmap()
    }

    /** Kantenlaengen so angleichen, dass der Ausschnitt in Pixeln quadratisch ist. */
    fun squared(r: Rect, bmp: ImageBitmap): Rect {
        val side = min(r.width * bmp.width, r.height * bmp.height)
        val w = (side / bmp.width).coerceIn(0.05f, 1f)
        val h = (side / bmp.height).coerceIn(0.05f, 1f)
        val left = r.left.coerceIn(0f, 1f - w)
        val top = r.top.coerceIn(0f, 1f - h)
        return Rect(left, top, left + w, top + h)
    }

    AlertDialog(
        onDismissRequest = { vm.tileCropAlbum = null },
        title = { Text(stringResource(Res.string.crop_title, album.title.ifBlank { album.name })) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.crop_hint),
                    color = Mpd.colors.muted,
                    fontSize = 12.sp,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .padding(top = 10.dp),
                ) {
                    CropEditor(
                        bitmap = bitmap,
                        crop = crop,
                        onCropChange = { r ->
                            crop = bitmap?.let { squared(r, it) } ?: r
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (saving) {
                    Spacer(Modifier.height(8.dp))
                    LoadingRow(stringResource(Res.string.common_saving_spaced))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null && !saving,
                onClick = {
                    saving = true
                    vm.setCoverCrop(
                        album,
                        ThumbCrop(crop.left, crop.top, crop.width, crop.height),
                    ) { err ->
                        saving = false
                        if (err == null) {
                            vm.tileCropAlbum = null
                        } else {
                            vm.meldung(err)
                        }
                    }
                },
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (album.thumbnailCrop != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            vm.setCoverCrop(album, null) { err ->
                                saving = false
                                if (err == null) {
                                    vm.tileCropAlbum = null
                                } else {
                                    vm.meldung(err)
                                }
                            }
                        },
                    ) { Text(stringResource(Res.string.crop_reset)) }
                }
                TextButton(onClick = { vm.tileCropAlbum = null }) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

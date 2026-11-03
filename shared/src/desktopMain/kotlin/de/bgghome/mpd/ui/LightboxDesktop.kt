package de.bgghome.mpd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.ui.karte.KachelEbene
import de.bgghome.mpd.ui.karte.KachelKarte
import de.bgghome.mpd.ui.karte.KartenPin
import de.bgghome.mpd.ui.karte.KartenZustand
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Desktop-Fassung der Lightbox-Teile (22.09.2026). Zoom mit Compose-
 * Transformationen statt SubsamplingScaleImageView; Datum und Zeit ueber
 * die Material-3-Picker; Karten und Foto-Editor sagen, dass sie noch
 * fehlen.
 */

/** Ab diesem Faktor kommt das Original statt der xl-Stufe. */
private const val ORIGINAL_AB = 1.6f

@Composable
internal actual fun PhotoPage(
    vm: AppViewModel,
    photo: Photo,
    imageLoader: ImageLoader,
    pagerState: PagerState,
    onSwipeUp: () -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissDragEnd: (Float, Float) -> Unit,
) {
    var scale by remember(photo.file) { mutableStateOf(1f) }
    var offset by remember(photo.file) { mutableStateOf(Offset.Zero) }
    fun setzeScale(neu: Float) {
        scale = neu.coerceIn(1f, 8f)
        if (scale == 1f) offset = Offset.Zero
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.file) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { setzeScale(if (scale > 1f) 1f else 2.5f) },
                )
            }
            // Erst ab Zoom > 1 faengt das Bild das Ziehen; sonst blaettert der Pager.
            .pointerInput(photo.file, scale > 1f) {
                if (scale > 1f) detectDragGestures { _, drag -> offset += drag }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (scale >= ORIGINAL_AB) photo.originalUrl else photo.xlUrl,
            imageLoader = imageLoader,
            contentDescription = photo.file,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
internal actual fun SystemleistenAusblenden() {}

internal actual fun kartenLink(lat: Double, lon: Double): String =
    "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=14/$lat/$lon"

@Composable
internal actual fun ExifMiniMap(lat: Double, lon: Double, modifier: Modifier) {
    val zustand = remember(lat, lon) { KartenZustand(lat, lon, 13) }
    KachelKarte(
        zustand, KachelEbene.STANDARD, modifier.clip(RoundedCornerShape(8.dp)),
        interaktiv = false, pins = listOf(KartenPin(lat, lon, null, radiusDp = 8f)),
    )
}

@Composable
internal actual fun ExifPickMap(
    lat: Double?,
    lon: Double?,
    center: Pair<Double, Double>?,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier,
) {
    val zustand = remember { KartenZustand(lat ?: 51.0, lon ?: 10.0, if (lat != null) 13 else 5) }
    var letztesZentrum by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    if (center != null && center != letztesZentrum) {
        letztesZentrum = center
        zustand.setze(center.first, center.second, 10)
    }
    KachelKarte(
        zustand, KachelEbene.STANDARD, modifier.clip(RoundedCornerShape(8.dp)),
        interaktiv = true,
        pins = if (lat != null && lon != null) listOf(KartenPin(lat, lon, null, radiusDp = 8f)) else emptyList(),
        onTap = { ort -> onPick(ort.lat, ort.lon) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun DatumWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val start = runCatching { LocalDate.parse(initial) }.getOrNull()
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(initialSelectedDateMillis = start)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = state.selectedDateMillis ?: return@TextButton onDismiss()
                onPicked(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun ZeitWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val parts = initial.split(":").mapNotNull { it.toIntOrNull() }
    val state = rememberTimePickerState(
        initialHour = parts.getOrNull(0) ?: 12, initialMinute = parts.getOrNull(1) ?: 0, is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPicked("%02d:%02d".format(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
internal actual fun LokalesSchreiben(vm: AppViewModel, onClose: () -> Unit) {}

package de.bgghome.mpd.ui

import coil3.request.allowHardware
import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.ExifData
import de.bgghome.mpd.api.PhotoEditParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/*
 * Android-Teile der Lightbox-Seitenleisten (Aufteilung 22.09.2026):
 * Standortkarten (osmdroid), System-Picker fuer Datum und Zeit, und der
 * Foto-Editor mit lokalem Bearbeiten (MediaStore.createWriteRequest).
 */

internal actual fun kartenLink(lat: Double, lon: Double): String = "geo:$lat,$lon?z=14"

@Composable
internal actual fun DatumWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val parts = initial.split("-").mapNotNull { it.toIntOrNull() }
        val cal = java.util.Calendar.getInstance()
        val dlg = android.app.DatePickerDialog(
            context,
            { _, y, m, d -> onPicked("%04d-%02d-%02d".format(y, m + 1, d)) },
            parts.getOrNull(0) ?: cal.get(java.util.Calendar.YEAR),
            (parts.getOrNull(1) ?: (cal.get(java.util.Calendar.MONTH) + 1)) - 1,
            parts.getOrNull(2) ?: cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        dlg.setOnDismissListener { onDismiss() }
        dlg.show()
        onDispose { dlg.setOnDismissListener(null); dlg.dismiss() }
    }
}

@Composable
internal actual fun ZeitWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val parts = initial.split(":").mapNotNull { it.toIntOrNull() }
        val dlg = android.app.TimePickerDialog(
            context,
            { _, h, min -> onPicked("%02d:%02d".format(h, min)) },
            parts.getOrNull(0) ?: 12, parts.getOrNull(1) ?: 0, true,
        )
        dlg.setOnDismissListener { onDismiss() }
        dlg.show()
        onDispose { dlg.setOnDismissListener(null); dlg.dismiss() }
    }
}

/** Kleine, nicht bedienbare Standortkarte (osmdroid, ArmableMapView unarmed). */
@Composable
internal actual fun ExifMiniMap(lat: Double, lon: Double, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        factory = { ctx ->
            ensureOsmdroid(ctx)
            ArmableMapView(ctx).apply {
                setTileSource(OSM_STANDARD)
                armed = false // reine Anzeige — das Panel scrollt drueber
                zoomController.setVisibility(
                    org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER,
                )
            }
        },
        update = { map ->
            val point = org.osmdroid.util.GeoPoint(lat, lon)
            map.controller.setZoom(13.0)
            map.controller.setCenter(point)
            map.overlays.removeAll { it is org.osmdroid.views.overlay.Marker }
            map.overlays.add(
                org.osmdroid.views.overlay.Marker(map).apply {
                    position = point
                    setAnchor(
                        org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                        org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM,
                    )
                },
            )
            map.invalidate()
        },
    )
}

/** Interaktive Pin-Karte: Tipp setzt/verschiebt die Nadel (Web: lb-exif-map).
 *  [center] schwenkt die Karte (Suchtreffer). */
@Composable
internal actual fun ExifPickMap(
    lat: Double?,
    lon: Double?,
    center: Pair<Double, Double>?,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        factory = { ctx ->
            ensureOsmdroid(ctx)
            ArmableMapView(ctx).apply {
                setTileSource(OSM_STANDARD)
                armed = true
                setMultiTouchControls(true)
                controller.setZoom(if (lat != null) 13.0 else 5.0)
                controller.setCenter(
                    org.osmdroid.util.GeoPoint(lat ?: 51.0, lon ?: 10.0),
                )
                overlays.add(
                    org.osmdroid.views.overlay.MapEventsOverlay(
                        object : org.osmdroid.events.MapEventsReceiver {
                            override fun singleTapConfirmedHelper(p: org.osmdroid.util.GeoPoint): Boolean {
                                onPick(p.latitude, p.longitude)
                                return true
                            }

                            override fun longPressHelper(p: org.osmdroid.util.GeoPoint) = false
                        },
                    ),
                )
            }
        },
        update = { map ->
            if (center != null && map.tag != center) {
                map.tag = center
                map.controller.setZoom(10.0)
                map.controller.animateTo(org.osmdroid.util.GeoPoint(center.first, center.second))
            }
            map.overlays.removeAll { it is org.osmdroid.views.overlay.Marker }
            if (lat != null && lon != null) {
                map.overlays.add(
                    org.osmdroid.views.overlay.Marker(map).apply {
                        position = org.osmdroid.util.GeoPoint(lat, lon)
                        setAnchor(
                            org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                            org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM,
                        )
                    },
                )
            }
            map.invalidate()
        },
    )
}

/** Android: Zustimmung zum Ueberschreiben (MediaStore.createWriteRequest), dann schreiben. */
@Composable
internal actual fun LokalesSchreiben(vm: AppViewModel, onClose: () -> Unit) {
    val editContext = LocalContext.current

    // Schreiben in eine fremde Mediendatei braucht Androids Zustimmung.
    // Der Dialog kommt genau einmal, direkt vor dem Ueberschreiben.
    val writeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts
            .StartIntentSenderForResult(),
    ) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        val pending = vm.pendingLocalEdit
        if (granted && pending != null) {
            val ok = runCatching {
                val uri = android.net.Uri.parse(pending.first)
                // Die Bytes tragen das EXIF des Originals schon in sich —
                // LocalPhotoEdit.withExif hat es beim Rechnen uebertragen.
                // Hier stand bis 08.09.2026 ein zweiter „EXIF wieder
                // anbringen"-Block, der eine ExifInterface oeffnete und
                // nichts damit tat: ein Versprechen im Kommentar, ein
                // Leerlauf im Code.
                editContext.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(pending.second)
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
            vm.localEditFinished(ok)
            if (ok) onClose()
        } else {
            vm.localEditFinished(false)
        }
    }
    androidx.compose.runtime.LaunchedEffect(vm.pendingLocalEdit) {
        val pending = vm.pendingLocalEdit ?: return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val req = android.provider.MediaStore.createWriteRequest(
                    editContext.contentResolver,
                    listOf(android.net.Uri.parse(pending.first)),
                )
                writeLauncher.launch(
                    androidx.activity.result.IntentSenderRequest
                        .Builder(req.intentSender).build(),
                )
            }.onFailure { vm.localEditFinished(false) }
        } else {
            vm.localEditFinished(false)
        }
    }
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.MapPin
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.api.TourListEntry
import de.bgghome.mpd.api.UnassignedFile
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun AddMapDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onInsert: (title: String, pins: List<MapPin>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins = remember { mutableListOf<MapPin>().toMutableStateList() }
    var title by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var mapRef by remember { mutableStateOf<ArmableMapView?>(null) }

    fun redrawOverlays() {
        val map = mapRef ?: return
        map.overlays.removeAll { it is Marker || it is Polyline }
        if (pins.size >= 2) {
            map.overlays.add(Polyline(map).apply {
                setPoints(arcPoints(pins))
                outlinePaint.color = PinBlueArgb
                outlinePaint.alpha = (0.85f * 255).toInt()
                outlinePaint.strokeWidth = 3f * context.resources.displayMetrics.density
            })
        }
        pins.forEachIndexed { i, p ->
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                icon = circleMarker(context, "${i + 1}", PinBlueArgb, 26f)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = p.label.orEmpty()
            })
        }
        map.invalidate()
    }

    fun addPin(lat: Double, lon: Double, label: String) {
        pins += MapPin(
            lat = Math.round(lat * 1e6) / 1e6.toDouble(),
            lon = Math.round(lon * 1e6) / 1e6.toDouble(),
            label = label.ifBlank { null },
        )
        redrawOverlays()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(14.dp))
                .background(MpdSurface)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.map_insert_title), fontSize = 17.sp, color = MpdText, modifier = Modifier.weight(1f))
                Icon(
                    MpdIcons.Cross, stringResource(Res.string.common_close),
                    modifier = Modifier.size(20.dp).clickable(onClick = onDismiss),
                    tint = MpdMuted,
                )
            }

            // Ortssuche (Nominatim, eigener Client — kein Bearer an Fremddienst)
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.map_search_placeholder), fontSize = 14.sp) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = search.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        scope.launch {
                            val hit = de.bgghome.mpd.util.Nominatim.suche(search)
                            searching = false
                            if (hit == null) {
                                vm.meldung(Texte.t(Res.string.map_not_found), kurz = true)
                            } else {
                                addPin(hit.lat, hit.lon, hit.label)
                                mapRef?.controller?.setZoom(10.0)
                                mapRef?.controller?.setCenter(GeoPoint(hit.lat, hit.lon))
                                search = ""
                            }
                        }
                    },
                ) { Text(if (searching) "…" else "→", fontSize = 18.sp) }
            }

            // Karte: Tipp setzt einen Pin (wie map.on('click') im Web)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        newMapView(ctx, topo = false).apply {
                            armed = true // im Dialog sofort aktiv
                            controller.setZoom(5.0)
                            controller.setCenter(GeoPoint(48.0, 10.0))
                            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                    p?.let { addPin(it.latitude, it.longitude, "") }
                                    return true
                                }
                                override fun longPressHelper(p: GeoPoint?) = false
                            }))
                            mapRef = this
                        }
                    },
                    onRelease = { it.onDetach(); mapRef = null },
                )
            }

            // Pin-Liste mit Label-Feld + Entfernen (mapRenderPinList)
            LazyColumn(Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp)) {
                items(pins.size, key = { it }) { i ->
                    val pin = pins[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(Color(PinBlueArgb)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Color.White, fontSize = 11.sp) }
                        OutlinedTextField(
                            value = pin.label.orEmpty(),
                            onValueChange = { newLabel ->
                                pins[i] = pin.copy(label = newLabel.ifBlank { null })
                                redrawOverlays()
                            },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.map_pin_label_placeholder), fontSize = 12.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(
                            "%.4f, %.4f".format(pin.lat, pin.lon),
                            color = MpdMuted, fontSize = 10.sp,
                        )
                        Icon(
                            MpdIcons.Cross, stringResource(Res.string.map_remove_pin),
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(18.dp)
                                .clickable { pins.removeAt(i); redrawOverlays() },
                            tint = MpdMuted,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 80) title = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.map_title_placeholder), fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = pins.isNotEmpty(),
                    onClick = { onInsert(title, pins.toList()) },
                ) { Text(stringResource(Res.string.common_insert)) }
            }
        }
    }
}

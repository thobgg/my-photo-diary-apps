package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MapPin
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.state.Texte
import de.bgghome.mpd.ui.karte.GeoPunkt
import de.bgghome.mpd.ui.karte.KachelEbene
import de.bgghome.mpd.ui.karte.KachelKarte
import de.bgghome.mpd.ui.karte.KartenLinie
import de.bgghome.mpd.ui.karte.KartenPin
import de.bgghome.mpd.ui.karte.KartenZustand
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.util.GpxTrack
import de.bgghome.mpd.util.parseGpx
import de.bgghome.mpd.util.tourStats
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.gpxUrl
import de.bgghome.mpd.fotoOrte
import de.bgghome.mpd.mediaUrl
import de.bgghome.mpd.meldung
import de.bgghome.mpd.openLightbox
import de.bgghome.mpd.tourHolen

/*
 * Desktop-Fassung der Album-Elemente mit Karte: die Compose-Kachelkarte
 * (ui/karte) statt osmdroid, sonst derselbe Aufbau wie MapElements.kt auf
 * Android (Titel, Ebenen-Umschalter, Scharfschalten, Bogenlinie zwischen
 * Pins, Spur mit Start/Ziel und Fotomarkern, Tour-Chips, Hoehenprofil).
 * Ton bleibt vorerst ein Hinweis (Media3 hat auf dem Desktop keinen Ersatz).
 */

@Composable
actual fun MapElementBlock(elem: PdxElement) {
    if (elem.pins.isEmpty()) return
    var topo by remember(elem.id) { mutableStateOf(elem.layer == "topo") }
    var armed by remember(elem.id) { mutableStateOf(false) }
    val zustand = remember(elem.id) { KartenZustand() }
    var eingepasst by remember(elem.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        elem.title?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(kartenHoehe()).clip(RoundedCornerShape(8.dp))) {
            if (!eingepasst) {
                zustand.passeEin(elem.pins.map { GeoPunkt(it.lat, it.lon) }, constraints.maxWidth, constraints.maxHeight, 48)
                eingepasst = true
            }
            KachelKarte(
                zustand, if (topo) KachelEbene.TOPO else KachelEbene.STANDARD,
                Modifier.fillMaxSize(), interaktiv = armed,
                pins = elem.pins.mapIndexed { i, p -> KartenPin(p.lat, p.lon, "${i + 1}") },
                linien = if (elem.pins.size >= 2) listOf(KartenLinie(bogenPunkte(elem.pins), PinBlue.copy(alpha = 0.85f), 3f)) else emptyList(),
            )
            ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
            LayerToggle(topo, { topo = !topo }, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Was eine Tour einmal geladen hat — liegt in [AppViewModel.tourDaten]. */
private class TourGeladenDesktop(val track: GpxTrack, val pins: List<KartenPin>)

internal actual suspend fun ladeTour(
    vm: AppViewModel,
    album: de.bgghome.mpd.OpenAlbum,
    elem: PdxElement,
    imageLoader: ImageLoader,
    context: coil3.PlatformContext,
): Any {
    val url = vm.baseUrl.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegment("api").addPathSegment("gpx")
        .addPathSegment(album.summary.space)
        .addPathSegment(elem.sourceAlbum ?: album.summary.name)
        .addPathSegment(elem.file.orEmpty())
        .build().toString()
    val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        parseGpx(vm.api.fetchText(url))
    }
    if (parsed.points.isEmpty()) throw TourOhnePunkte()
    // Fotomarker: Koordinaten aus dem EXIF zuerst, sonst die Aufnahmezeit
    // aus dem Dateinamen auf die Spur gelegt (util/TourFotos.kt, 05.11.2026),
    // als Mini-Foto wie auf Android (22.09.: „die Karte zeigt
    // nicht die kleinen MiniFotos an").
    val orte = de.bgghome.mpd.util.spurTage(parsed)
        ?.let { (von, bis) -> vm.fotoOrte(album.summary.space, von, bis) }
        .orEmpty()
    // Wie Android: nicht im Aufrufer-Kontext (Main) rechnen (29.09.2026).
    val pins = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val liste = mutableListOf<KartenPin>()
        val positionen = de.bgghome.mpd.util.tourFotoPositionen(
            parsed,
            album.photos.map { photo ->
                de.bgghome.mpd.util.TourFoto(
                    photo.file,
                    orte[de.bgghome.mpd.util.fotoOrtSchluessel(photo.space, photo.album, photo.file)],
                )
            },
        )
        album.photos.forEachIndexed { photoIndex, photo ->
            val pos = positionen[photoIndex] ?: return@forEachIndexed
            liste += KartenPin(pos.lat, pos.lon, photo.file, tag = photoIndex, bildUrl = photo.mUrl)
        }
        parsed.anzeigePunkte
        liste
    }
    return TourGeladenDesktop(parsed, pins)
}

@Composable
actual fun TourElementBlock(vm: AppViewModel, elem: PdxElement, imageLoader: ImageLoader) {
    val album = vm.openAlbum ?: return
    // Wie Android: geladene Tour im ViewModel, sonst laedt jedes
    // Zurueckscrollen in der LazyColumn alles neu (27.09.2026).
    val cacheKey = tourSchluessel(album, elem)
    val geladen = vm.tourDaten[cacheKey] as? TourGeladenDesktop
    var track by remember(elem.id) { mutableStateOf(geladen?.track) }
    var fotoPins by remember(elem.id) { mutableStateOf(geladen?.pins ?: emptyList()) }
    var loadError by remember(elem.id) { mutableStateOf<String?>(null) }
    var topo by remember(elem.id) { mutableStateOf(elem.mapLayer == "topo") }
    var armed by remember(elem.id) { mutableStateOf(false) }
    var kartenVollbild by remember(elem.id) { mutableStateOf(false) }
    // Nach dem Foto zurueck ins Vollbild — wie Android (27.09.2026).
    var vollbildNachFoto by remember(elem.id) { mutableStateOf(false) }
    LaunchedEffect(vm.lightboxIndex) {
        if (vm.lightboxIndex == null && vollbildNachFoto) {
            vollbildNachFoto = false
            kartenVollbild = true
        }
    }
    val zustand = remember(elem.id) { KartenZustand() }
    var eingepasst by remember(elem.id) { mutableStateOf(false) }

    val context = coil3.compose.LocalPlatformContext.current
    LaunchedEffect(cacheKey) {
        if (track != null) return@LaunchedEffect
        try {
            val t = vm.tourHolen(cacheKey) {
                ladeTour(vm, album, elem, imageLoader, context)
            } as TourGeladenDesktop
            fotoPins = t.pins
            track = t.track
        } catch (e: TourOhnePunkte) {
            loadError = Texte.t(Res.string.gpx_no_points)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = Texte.t(Res.string.gpx_load_failed, e.message)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Mpd.colors.surface)
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(10.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tourSymbol(elem.typeHint), fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                elem.title ?: elem.file ?: stringResource(Res.string.tour_default_title),
                color = Color(0xFFE0E0E0), fontSize = 16.sp, modifier = Modifier.weight(1f),
            )
            // „In Karten-App oeffnen" (13.11.2026) — GPX an OsmAnd und Co.
            val dienste = LocalSystemDienste.current
            val oeffnenScope = rememberCoroutineScope()
            val gpxDatei = elem.file
            if (dienste.kannGpxOeffnen && gpxDatei != null) {
                GpxOeffnenKnopf({
                    oeffnenScope.launch {
                        dienste.oeffneGpx(
                            vm,
                            vm.api.gpxUrl(vm.baseUrl, album.summary.space, elem.sourceAlbum ?: album.summary.name, gpxDatei),
                            gpxDatei,
                        )
                    }
                })
            }
            LayerToggle(topo, { topo = !topo })
        }

        val karte: @Composable (Modifier, Boolean) -> Unit = { mod, vollbild ->
            BoxWithConstraints(mod.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                val t = track
                when {
                    loadError != null -> Text(loadError!!, color = MpdRed, fontSize = 14.sp)
                    t == null -> CircularProgressIndicator()
                    else -> {
                        // Ausgeduennt fuers Zeichnen, einmal je Track.
                        val spur = remember(t) { t.anzeigePunkte.map { GeoPunkt(it.lat, it.lon) } }
                        if (!eingepasst) {
                            zustand.passeEin(spur, constraints.maxWidth, constraints.maxHeight, 24)
                            eingepasst = true
                        }
                        KachelKarte(
                            zustand, if (topo) KachelEbene.TOPO else KachelEbene.STANDARD,
                            Modifier.fillMaxSize(), interaktiv = vollbild || armed,
                            linien = listOf(
                                KartenLinie(spur, Color.White.copy(alpha = 0.9f), 6f),
                                KartenLinie(spur, TrackMagenta, 3f),
                            ),
                            pins = listOf(
                                KartenPin(spur.first().lat, spur.first().lon, "S", StartGruen, 11f),
                                KartenPin(spur.last().lat, spur.last().lon, "Z", ZielRot, 11f),
                            ) + fotoPins,
                            onPinTap = { pin ->
                                (pin.tag as? Int)?.let { idx ->
                                    if (vollbild) {
                                        kartenVollbild = false
                                        vollbildNachFoto = true
                                    }
                                    vm.openLightbox(idx)
                                }
                            },
                            imageLoader = imageLoader,
                        )
                        if (!vollbild) {
                            ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
                            VollbildKnopf { kartenVollbild = true }
                        }
                    }
                }
            }
        }
        karte(Modifier.fillMaxWidth().height(kartenHoehe()), false)
        if (kartenVollbild) {
            KarteVollbild(onClose = { kartenVollbild = false }) { mod -> karte(mod, true) }
        }

        track?.let { t ->
            val stats = remember(t) { tourStats(t) }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                stats.forEach { stat ->
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF0A0A0A))
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(20.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stat.icon, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                        Text(stat.value, color = Color(0xFFE0E0E0), fontSize = 13.sp)
                    }
                }
            }
            if (t.hasEle && elem.showElevation) ElevationProfile(t)
        }
    }
}


@Composable
actual fun AudioElementBlock(vm: AppViewModel, elem: PdxElement) {
    val url = vm.mediaUrl("audio", elem) ?: return
    val colors = Mpd.colors
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp)).background(colors.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { scope.launch { DesktopMedien.abspielen(vm, url) } }) { Text("▶") }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(elem.title ?: elem.file ?: "Tonaufnahme", color = colors.text, fontSize = 15.sp)
            Text("Öffnet im Systemplayer", color = colors.muted, fontSize = 12.sp)
        }
    }
}

/** Karte einfuegen — wie die Android-Fassung, mit der Compose-Kachelkarte. */
@Composable
actual fun AddMapDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onInsert: (title: String, pins: List<MapPin>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pins = remember { mutableListOf<MapPin>().toMutableStateList() }
    var title by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val zustand = remember { KartenZustand(48.0, 10.0, 5) }

    fun addPin(lat: Double, lon: Double, label: String) {
        pins += MapPin(
            lat = Math.round(lat * 1e6) / 1e6.toDouble(),
            lon = Math.round(lon * 1e6) / 1e6.toDouble(),
            label = label.ifBlank { null },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(14.dp))
                .background(MpdSurface)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.map_insert_title), fontSize = 17.sp, color = MpdText, modifier = Modifier.weight(1f))
                Icon(
                    MpdIcons.Cross, stringResource(Res.string.common_close),
                    modifier = Modifier.size(20.dp).clickable(onClick = onDismiss), tint = MpdMuted,
                )
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, singleLine = true,
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
                                zustand.setze(hit.lat, hit.lon, 10)
                                search = ""
                            }
                        }
                    },
                ) { Text(if (searching) "…" else "→", fontSize = 18.sp) }
            }
            Box(Modifier.fillMaxWidth().height(340.dp).padding(top = 8.dp).clip(RoundedCornerShape(8.dp))) {
                KachelKarte(
                    zustand, KachelEbene.STANDARD, Modifier.fillMaxSize(), interaktiv = true,
                    pins = pins.mapIndexed { i, p -> KartenPin(p.lat, p.lon, "${i + 1}") },
                    linien = if (pins.size >= 2) listOf(KartenLinie(bogenPunkte(pins), PinBlue.copy(alpha = 0.85f), 3f)) else emptyList(),
                    onTap = { ort -> addPin(ort.lat, ort.lon, "") },
                )
            }
            LazyColumn(Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp)) {
                items(pins.size, key = { it }) { i ->
                    val pin = pins[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(PinBlue),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Color.White, fontSize = 11.sp) }
                        OutlinedTextField(
                            value = pin.label.orEmpty(),
                            onValueChange = { neu -> pins[i] = pin.copy(label = neu.ifBlank { null }) },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.map_pin_label_placeholder), fontSize = 12.sp) },
                            textStyle = TextStyle(fontSize = 13.sp),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text("%.4f, %.4f".format(pin.lat, pin.lon), color = MpdMuted, fontSize = 10.sp)
                        Icon(
                            MpdIcons.Cross, stringResource(Res.string.map_remove_pin),
                            modifier = Modifier.padding(start = 6.dp).size(18.dp).clickable { pins.removeAt(i) },
                            tint = MpdMuted,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = title, onValueChange = { if (it.length <= 80) title = it }, singleLine = true,
                placeholder = { Text(stringResource(Res.string.map_title_placeholder), fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
                Spacer(Modifier.width(8.dp))
                TextButton(enabled = pins.isNotEmpty(), onClick = { onInsert(title, pins.toList()) }) {
                    Text(stringResource(Res.string.common_insert))
                }
            }
        }
    }
}

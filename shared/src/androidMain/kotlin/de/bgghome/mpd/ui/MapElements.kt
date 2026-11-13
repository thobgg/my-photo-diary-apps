package de.bgghome.mpd.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import coil3.request.allowHardware

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MapPin
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.util.GpxTrack
import de.bgghome.mpd.util.parseGpx
import de.bgghome.mpd.util.tourStats
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import org.jetbrains.compose.resources.stringResource

/*
 * map- und tour-Elemente — Nachbau der Leaflet-Teile aus
 * frontend/js/album-render.js mit osmdroid (Rasterkacheln wie Leaflet).
 * Kachelquellen, Farben, Masse und Statistik entsprechen dem Web.
 */

// Kachelquellen — Abweichung vom Web: dort laeuft CARTO Voyager keyfrei,
// weil Browser-Requests (Referer) erlaubt sind. Aus einer App liefert CARTO
// nur „API KEY REQUIRED"-Kacheln. Darum Standard-OSM als Grundkarte
// (fuer leichte private Nutzung mit korrektem User-Agent zulaessig).
internal val OSM_STANDARD = XYTileSource(
    "OsmStandard", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap",
)

internal val OPEN_TOPO = XYTileSource(
    "OpenTopoMap", 0, 17, 256, ".png",
    arrayOf(
        "https://a.tile.opentopomap.org/",
        "https://b.tile.opentopomap.org/",
        "https://c.tile.opentopomap.org/",
    ),
    "© OpenTopoMap",
)

private val TrackMagenta = android.graphics.Color.parseColor("#E6007E")
private val PhotoGold = android.graphics.Color.parseColor("#C8922A")
internal val PinBlueArgb = android.graphics.Color.parseColor("#2E75B6")
private val StartGreen = android.graphics.Color.parseColor("#27AE60")
private val EndRed = android.graphics.Color.parseColor("#E74C3C")

private var osmConfigured = false

internal fun ensureOsmdroid(context: Context) {
    if (osmConfigured) return
    val cfg = Configuration.getInstance()
    cfg.userAgentValue = context.packageName
    cfg.osmdroidBasePath = File(context.cacheDir, "osmdroid")
    cfg.osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    osmConfigured = true
}

/** Foto auf der Tour: Position, Lightbox-Index, fertig gebackener Pin. */
internal data class TourPhotoMarker(
    val lat: Double,
    val lon: Double,
    val photoIndex: Int,
    val pin: BitmapDrawable,
)

/** Mini-Foto-Pin (Google-Maps-Stil): quadratisch beschnitten, runde Ecken,
 *  weisser Rand mit Schattenkante — das Foto selbst steht auf der Karte. */
internal fun photoPin(context: Context, thumb: Bitmap): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (52f * density).toInt()
    val border = 2.5f * density
    val corner = 6f * density
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    // quadratischer Mittelausschnitt des Thumbs
    val side = minOf(thumb.width, thumb.height)
    val src = android.graphics.Rect(
        (thumb.width - side) / 2, (thumb.height - side) / 2,
        (thumb.width + side) / 2, (thumb.height + side) / 2,
    )
    val inner = android.graphics.RectF(border, border, size - border, size - border)
    // weisser Rahmen (leicht groesser, als Kontur)
    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        setShadowLayer(3f * density, 0f, 1.5f * density, 0x66000000)
    }
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()),
        corner + border, corner + border, framePaint,
    )
    // Foto mit runden Ecken einpassen
    val path = android.graphics.Path().apply { addRoundRect(inner, corner, corner, android.graphics.Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(thumb, src, inner, Paint(Paint.FILTER_BITMAP_FLAG))
    canvas.restore()
    return BitmapDrawable(context.resources, out)
}

/** Kreis-Marker (Pins, Start/Ziel) — Gegenstueck zu den divIcons des Webs. */
internal fun circleMarker(
    context: Context,
    text: String,
    fillColor: Int,
    sizeDp: Float,
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (sizeDp * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val border = 2.5f * density
    val r = size / 2f
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = border
    }
    canvas.drawCircle(r, r, r - border / 2, fill)
    canvas.drawCircle(r, r, r - border / 2, stroke)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = sizeDp * density * 0.46f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val ty = r - (label.descent() + label.ascent()) / 2
    canvas.drawText(text, r, ty, label)
    return BitmapDrawable(context.resources, bmp)
}

/**
 * Karte mit Scharfschaltung: unbewaffnet ignoriert sie jede Beruehrung
 * (Album scrollt normal drueber), erst ein Tipp aktiviert sie. Bewusste
 * Verbesserung gegenueber Leaflet im Web, wo die Karte jede Wischgeste
 * schluckt — am Handy kam man kaum am Album vorbei (beim Testen selbst
 * mehrfach hineingefallen).
 */
class ArmableMapView(context: Context) : MapView(context) {
    var armed = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!armed) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Alle Punkte einpassen — ohne die Endlosschleife von osmdroid.
 *
 * ANR 05.11.2026 (Vorgabe: „app hat jetzt hänger da wo karte kommt"), Trace
 * aus dem Bugreport: Hauptthread 20 s in Projection.getCloserPixel, gerufen
 * aus MapView.zoomToBoundingBox im onFirstLayout der Tour-Karte. Der Weg:
 * zoomToBoundingBox rechnet mit Breite minus 2 x Rand; ist die Karte beim
 * ersten Layout kleiner als der Rand (oder noch 0 x 0), wird der Logarithmus
 * einer negativen Zahl zur Zoomstufe NaN, die Projektion bekommt eine
 * Kartengroesse NaN, und getCloserPixel addiert sie in einer while-Schleife,
 * die so nie endet. Dasselbe passiert mit einer nicht endlichen Koordinate.
 *
 * Deshalb hier: nur endliche Punkte, und eingepasst wird erst, wenn die
 * Karte groesser als der doppelte Rand ist — sonst beim naechsten Layout
 * noch einmal. Bis dahin Mitte und Zoom 12, damit nie Leere steht.
 */
internal fun MapView.passeSicherEin(punkte: List<GeoPoint>, randPx: Int) {
    val endlich = punkte.filter { it.latitude.isFinite() && it.longitude.isFinite() }
    if (endlich.isEmpty()) return
    val box = BoundingBox.fromGeoPoints(endlich)
    if (endlich.size == 1 || box.latitudeSpan == 0.0 && box.longitudeSpanWithDateLine == 0.0) {
        controller.setZoom(12.0)
        controller.setCenter(endlich[0])
        return
    }
    fun gross() = width > 2 * randPx && height > 2 * randPx
    if (gross()) {
        zoomToBoundingBox(box, false, randPx)
        return
    }
    controller.setZoom(12.0)
    controller.setCenter(box.centerWithDateLine)
    addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: android.view.View, l: Int, t: Int, r: Int, b: Int,
            ol: Int, ot: Int, or: Int, ob: Int,
        ) {
            if (!gross()) return
            removeOnLayoutChangeListener(this)
            zoomToBoundingBox(box, false, randPx)
        }
    })
}

internal fun newMapView(context: Context, topo: Boolean): ArmableMapView {
    ensureOsmdroid(context)
    return ArmableMapView(context).apply {
        setTileSource(if (topo) OPEN_TOPO else OSM_STANDARD)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
    }
}


/** Web: 320px Hoehe, unter 768px Breite 240px. */
@Composable
private fun mapHeight(): androidx.compose.ui.unit.Dp =
    if (LocalConfiguration.current.screenWidthDp <= 768) 240.dp else 320.dp


// -- map-Element ------------------------------------------------------------

@Composable
actual fun MapElementBlock(elem: PdxElement) {
    if (elem.pins.isEmpty()) return
    var topo by remember(elem.id) { mutableStateOf(elem.layer == "topo") }
    var armed by remember(elem.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        elem.title?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(mapHeight())
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    newMapView(ctx, topo).apply {
                        val pins = elem.pins
                        pins.forEachIndexed { i, pin ->
                            overlays += Marker(this).apply {
                                position = GeoPoint(pin.lat, pin.lon)
                                icon = circleMarker(ctx, "${i + 1}", PinBlueArgb, 26f)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                title = pin.label
                            }
                        }
                        if (pins.size >= 2) {
                            overlays.add(0, Polyline(this).apply {
                                setPoints(arcPoints(pins))
                                outlinePaint.color = PinBlueArgb
                                outlinePaint.alpha = (0.85f * 255).toInt()
                                outlinePaint.strokeWidth = 3f * ctx.resources.displayMetrics.density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            })
                        }
                        addOnFirstLayoutListener { _, _, _, _, _ ->
                            // 1 Pin → Zoom 12 wie im Web, sonst alle einpassen
                            passeSicherEin(
                                pins.map { GeoPoint(it.lat, it.lon) },
                                (48 * resources.displayMetrics.density).toInt(),
                            )
                        }
                    }
                },
                update = { view ->
                    view.setTileSource(if (topo) OPEN_TOPO else OSM_STANDARD)
                    view.armed = armed
                },
                onRelease = { it.onDetach() },
            )
            ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
            LayerToggle(topo, { topo = !topo }, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * Geschwungene Verbindungslinie (Flugkarten-Bogen) — Gegenstueck zu _buildArc
 * im Web. Dort wird pro Zoom im Pixelraum gerechnet; hier einmalig im
 * projizierten Gradraum (Laengengrade mit cos(Breite) gestaucht), was
 * optisch dieselben Boegen ergibt, ohne bei jedem Zoom neu zu bauen.
 */
internal fun arcPoints(pins: List<MapPin>): List<GeoPoint> {
    val bow = 0.18
    val samples = 24
    val kx = cos(Math.toRadians(pins.first().lat))
    val pts = mutableListOf<GeoPoint>()
    for (s in 0 until pins.size - 1) {
        val x0 = pins[s].lon * kx
        val y0 = pins[s].lat
        val x1 = pins[s + 1].lon * kx
        val y1 = pins[s + 1].lat
        val dx = x1 - x0
        val dy = y1 - y0
        val len = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
        val nx = dy / len
        val ny = -dx / len
        val cx = (x0 + x1) / 2 + nx * len * bow
        val cy = (y0 + y1) / 2 + ny * len * bow
        for (i in (if (s == 0) 0 else 1)..samples) {
            val t = i.toDouble() / samples
            val u = 1 - t
            val x = u * u * x0 + 2 * u * t * cx + t * t * x1
            val y = u * u * y0 + 2 * u * t * cy + t * t * y1
            pts += GeoPoint(y, x / kx)
        }
    }
    return pts
}

// -- tour-Element -----------------------------------------------------------


/** Was eine Tour einmal geladen hat — liegt in [AppViewModel.tourDaten]. */
private class TourGeladen(val track: GpxTrack, val markers: List<TourPhotoMarker>)

internal actual suspend fun ladeTour(
    vm: AppViewModel,
    album: de.bgghome.mpd.OpenAlbum,
    elem: PdxElement,
    imageLoader: coil3.ImageLoader,
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
    // „Fotos auf der Tour": Koordinaten aus dem EXIF zuerst, sonst die
    // Dateinamens-Zeit auf den Track interpoliert (util/TourFotos.kt,
    // 05.11.2026) — und als ECHTE Mini-Foto-Pins backen (03.09.: goldene
    // Punkte reichten nicht — die Fotos selbst sollen auf der Karte stehen).
    val orte = de.bgghome.mpd.util.spurTage(parsed)
        ?.let { (von, bis) -> vm.fotoOrte(album.summary.space, von, bis) }
        .orEmpty()
    //
    // NICHT AUF MAIN (29.09.2026, ANR beim Scrollen): Die Schleife lief im
    // Aufrufer-Kontext, also auf dem Main-Thread — je Foto positionAt ueber
    // den ganzen Track, dazu das Backen der Pins. Alles nach Default; die
    // Pins werden gleich hier gebacken, nicht erst in der AndroidView-Factory.
    val markers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val liste = mutableListOf<TourPhotoMarker>()
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
            val request = coil3.request.ImageRequest.Builder(context)
                .data(photo.mUrl).size(128).allowHardware(false).build()
            val bmp = (imageLoader.execute(request).image as? coil3.BitmapImage)?.bitmap
                ?: return@forEachIndexed
            liste += TourPhotoMarker(pos.lat, pos.lon, photoIndex, photoPin(context, bmp))
        }
        // Anzeigepunkte ebenfalls hier vorrechnen (lazy), nicht in der Factory.
        parsed.anzeigePunkte
        liste
    }
    return TourGeladen(parsed, markers)
}

@Composable
actual fun TourElementBlock(vm: AppViewModel, elem: PdxElement, imageLoader: coil3.ImageLoader) {
    val context = LocalContext.current
    val album = vm.openAlbum ?: return
    val cacheKey = tourSchluessel(album, elem)
    val geladen = vm.tourDaten[cacheKey] as? TourGeladen
    var track by remember(elem.id) { mutableStateOf(geladen?.track) }
    var photoMarkers by remember(elem.id) {
        mutableStateOf(geladen?.markers ?: emptyList())
    }
    var loadError by remember(elem.id) { mutableStateOf<String?>(null) }
    var topo by remember(elem.id) { mutableStateOf(elem.mapLayer == "topo") }
    var armed by remember(elem.id) { mutableStateOf(false) }
    // VOLLBILD (16.09.2026). Das Web hat dem Tour-Element drei Knoepfe
    // gegeben: Kartenart, Fotos der Strecke, Vollbild. Die ersten beiden hatte
    // die App schon — Kartenart als LayerToggle im Kopf, die Fotos seit dem
    // 03.09. als Pins auf der Spur. Es fehlte nur das Vollbild, und das gab
    // es bisher allein in Tours und Trails, nicht im Album.
    var kartenVollbild by remember(elem.id) { mutableStateOf(false) }
    // Foto aus dem Vollbild geoeffnet (27.09.2026, Vorgabe: „wenn man bei
    // MPD Tour in Vollbild ein Foto anklickt und dann wieder zurueck geht,
    // soll man wieder in Vollbild rauskommen"). Das Vollbild muss fuer die
    // Lightbox zu — sie laege sonst darunter —, also merken und nach dem
    // Schliessen wieder oeffnen. Die Lightbox liegt als Overlay ueber dem
    // Album, dieses Element bleibt solange bestehen.
    var vollbildNachFoto by remember(elem.id) { mutableStateOf(false) }
    LaunchedEffect(vm.lightboxIndex) {
        if (vm.lightboxIndex == null && vollbildNachFoto) {
            vollbildNachFoto = false
            kartenVollbild = true
        }
    }

    LaunchedEffect(cacheKey) {
        if (track != null) return@LaunchedEffect
        try {
            val t = vm.tourHolen(cacheKey) {
                ladeTour(vm, album, elem, imageLoader, context)
            } as TourGeladen
            photoMarkers = t.markers
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
            // surface wie im Standard-Theme (kachel) — border weicht dort ab
            // (transparent) und bleibt fest (Audit 08.09.2026).
            .background(Mpd.colors.surface)
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(10.dp)),
    ) {
        // Header wie .tour-header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tourSymbol(elem.typeHint), fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                elem.title ?: elem.file ?: stringResource(Res.string.tour_default_title),
                color = Color(0xFFE0E0E0),
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
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

        // clipToBounds ist Pflicht: Compose clippt Kind-Views nicht, und
        // osmdroid zeichnet sonst ueber Kopfzeile, Chips und Profil.
        //
        // Die Karte ist ein Lambda, damit Album- und Vollbildfassung
        // DIESELBE sind und nicht auseinanderlaufen. [vollbild] macht zwei
        // Dinge anders: Im Album muss man die Karte erst antippen, damit
        // sie sich verschieben laesst (sonst scrollt das Album nicht mehr
        // weiter) — im Vollbild gibt es nichts zu scrollen, dort ist sie
        // sofort bedienbar. Und ein Tipp auf ein Foto schliesst erst das
        // Vollbild, sonst oeffnete sich die Lightbox UNTER dem Dialog.
        val karte: @Composable (Modifier, Boolean) -> Unit = { mod, vollbild ->
        Box(
            mod.clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val t = track
            when {
                loadError != null -> Text(loadError!!, color = MpdRed, fontSize = 14.sp)
                t == null -> CircularProgressIndicator()
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        newMapView(ctx, topo).apply {
                            // Ausgeduennt fuers Zeichnen; Statistik rechnet mit allen Punkten.
                            val geo = t.anzeigePunkte.map { GeoPoint(it.lat, it.lon) }
                            val density = ctx.resources.displayMetrics.density
                            // Track: weisse Unterlage + Magenta — lesbar auf jeder Karte
                            overlays += Polyline(this).apply {
                                setPoints(geo)
                                outlinePaint.color = android.graphics.Color.WHITE
                                outlinePaint.alpha = (0.9f * 255).toInt()
                                outlinePaint.strokeWidth = 6f * density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            overlays += Polyline(this).apply {
                                setPoints(geo)
                                outlinePaint.color = TrackMagenta
                                outlinePaint.strokeWidth = 3f * density
                                outlinePaint.strokeCap = Paint.Cap.ROUND
                            }
                            overlays += Marker(this).apply {
                                position = geo.first()
                                icon = circleMarker(ctx, "S", StartGreen, 22f)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            }
                            overlays += Marker(this).apply {
                                position = geo.last()
                                icon = circleMarker(ctx, "Z", EndRed, 22f)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            }
                            // Foto-Pins: Mini-Thumbnails direkt auf dem Track;
                            // Tipp oeffnet die Lightbox an genau diesem Foto
                            // (Karte muss scharf geschaltet sein).
                            photoMarkers.forEach { pm ->
                                overlays += Marker(this).apply {
                                    position = GeoPoint(pm.lat, pm.lon)
                                    icon = pm.pin
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                    setOnMarkerClickListener { _, _ ->
                                        if (vollbild) {
                                            kartenVollbild = false
                                            vollbildNachFoto = true
                                        }
                                        vm.openLightbox(pm.photoIndex)
                                        true
                                    }
                                }
                            }
                            addOnFirstLayoutListener { _, _, _, _, _ ->
                                passeSicherEin(geo, (24 * density).toInt())
                            }
                        }
                    },
                    update = { view ->
                        view.setTileSource(if (topo) OPEN_TOPO else OSM_STANDARD)
                        view.armed = vollbild || armed
                    },
                    onRelease = { it.onDetach() },
                )
            }
            if (track != null && !vollbild) {
                ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
                VollbildKnopf { kartenVollbild = true }
            }
        }
        }
        karte(Modifier.fillMaxWidth().height(mapHeight()), false)
        if (kartenVollbild) {
            KarteVollbild(onClose = { kartenVollbild = false }) { mod -> karte(mod, true) }
        }

        track?.let { t ->
            // Statistik-Chips wie .tour-stats — einmal je Track, nicht je Recomposition.
            val stats = remember(t) { tourStats(t) }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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


package de.bgghome.mpd.ui.karte

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/*
 * Eigene Kachelkarte in Compose (22.09.2026: „ich will alles
 * moeglichst nativ"). Ersetzt am Desktop osmdroid: dieselben Kachelquellen
 * wie Web und Android (OpenStreetMap, OpenTopoMap), Web-Mercator, ganze
 * Zoomstufen, Pins und Linien auf einem Canvas darueber. Kacheln laedt
 * Coil ueber einen eigenen OkHttp-Client mit User-Agent (OSM-Regel) und
 * eigenem Plattencache — nie ueber den API-Client, der traegt den
 * Bearer-Token. Wenn sie taugt, kann sie spaeter auch auf Android
 * osmdroid abloesen; dann ist auch die Karte gemeinsamer Code.
 */

data class GeoPunkt(val lat: Double, val lon: Double)

/** Pin auf der Karte; [tag] reicht der Aufrufer durch (z.B. Foto-Index). */
data class KartenPin(
    val lat: Double,
    val lon: Double,
    val text: String? = null,
    val farbe: Color = Color(0xFF2E75B6),
    val radiusDp: Float = 13f,
    val tag: Any? = null,
    /** Mini-Foto statt Kreis (Google-Maps-Stil, wie photoPin auf Android):
     *  quadratisch, runde Ecken, weisser Rand. Braucht den ImageLoader der Karte. */
    val bildUrl: String? = null,
)

data class KartenLinie(val punkte: List<GeoPunkt>, val farbe: Color, val breiteDp: Float)

enum class KachelEbene(val maxZoom: Int, val quelle: String) {
    STANDARD(19, "© OpenStreetMap") {
        override fun url(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"
    },
    TOPO(17, "© OpenTopoMap") {
        override fun url(z: Int, x: Int, y: Int): String {
            val s = "abc"[(x + y) % 3]
            return "https://$s.tile.opentopomap.org/$z/$x/$y.png"
        }
    };

    abstract fun url(z: Int, x: Int, y: Int): String
}

object WebMercator {
    private const val MAX_LAT = 85.0511
    fun xTile(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)
    fun yTile(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)
    }
    fun lon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0
    fun lat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * y / (1 shl z))))
}

/** Mittelpunkt und Zoomstufe; Compose-Zustand, damit die Karte mitzieht. */
class KartenZustand(lat: Double = 48.0, lon: Double = 10.0, zoom: Int = 5) {
    var lat by mutableStateOf(lat)
    var lon by mutableStateOf(lon)
    var zoom by mutableStateOf(zoom)

    fun setze(lat: Double, lon: Double, zoom: Int? = null) {
        this.lat = lat; this.lon = lon
        zoom?.let { this.zoom = it }
    }

    /** Alle Punkte ins Bild, mit Rand; ein einzelner Punkt bekommt [einzelZoom]. */
    fun passeEin(punkte: List<GeoPunkt>, breitePx: Int, hoehePx: Int, randPx: Int = 32, einzelZoom: Int = 12, maxZoom: Int = 17) {
        if (punkte.isEmpty() || breitePx <= 0 || hoehePx <= 0) return
        val minLat = punkte.minOf { it.lat }; val maxLat = punkte.maxOf { it.lat }
        val minLon = punkte.minOf { it.lon }; val maxLon = punkte.maxOf { it.lon }
        val mitteLat = (minLat + maxLat) / 2; val mitteLon = (minLon + maxLon) / 2
        if (punkte.size == 1 || (maxLat - minLat < 1e-6 && maxLon - minLon < 1e-6)) {
            setze(mitteLat, mitteLon, einzelZoom); return
        }
        for (z in maxZoom downTo 1) {
            val w = (WebMercator.xTile(maxLon, z) - WebMercator.xTile(minLon, z)) * 256
            val h = (WebMercator.yTile(minLat, z) - WebMercator.yTile(maxLat, z)) * 256
            if (w <= breitePx - 2 * randPx && h <= hoehePx - 2 * randPx) { setze(mitteLat, mitteLon, z); return }
        }
        setze(mitteLat, mitteLon, 1)
    }
}

/** Kachel-Cache je Plattform (Android: cacheDir/tiles, Desktop: ~/.cache/mpd/tiles). */
expect fun kachelCacheOrdner(context: PlatformContext): File

private val kachelClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "MyPhotoDiary (https://bgg-home.de)")
                    .build(),
            )
        }
        .build()
}

@Composable
fun rememberKachelLoader(): ImageLoader {
    val ctx = LocalPlatformContext.current
    return remember {
        ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { kachelClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(kachelCacheOrdner(ctx).apply { mkdirs() }.toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}

/**
 * Die Karte. [interaktiv] = Ziehen, Mausrad/Doppelklick zoomen; sonst reine
 * Anzeige (Panel scrollt drueber). [onTap] bekommt den Ort unter dem
 * Zeiger, [onPinTap] den getroffenen Pin (hat Vorrang).
 */
@Composable
fun KachelKarte(
    zustand: KartenZustand,
    ebene: KachelEbene,
    modifier: Modifier = Modifier,
    interaktiv: Boolean = true,
    pins: List<KartenPin> = emptyList(),
    linien: List<KartenLinie> = emptyList(),
    onTap: ((GeoPunkt) -> Unit)? = null,
    onPinTap: ((KartenPin) -> Unit)? = null,
    /** Fuer Pins mit [KartenPin.bildUrl]; der ImageLoader der App (Sitzung). */
    imageLoader: ImageLoader? = null,
    /** Eigene Zeichnung ueber Linien und Pins (z.B. Heatmap); bekommt die Projektion. */
    zeichne: (DrawScope.(zumSchirm: (GeoPunkt) -> Offset) -> Unit)? = null,
) {
    val loader = rememberKachelLoader()
    val density = LocalDensity.current
    val tileDp = with(density) { 256.toDp() }
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.clipToBounds().background(Color(0xFF2A2A2A))) {
        val wPx = constraints.maxWidth
        val hPx = constraints.maxHeight
        val z = zustand.zoom.coerceIn(1, ebene.maxZoom)
        val n = 1 shl z
        val cxPx = WebMercator.xTile(zustand.lon, z) * 256
        val cyPx = WebMercator.yTile(zustand.lat, z) * 256
        val left = cxPx - wPx / 2.0
        val top = cyPx - hPx / 2.0

        fun zumSchirm(p: GeoPunkt) = Offset(
            (WebMercator.xTile(p.lon, z) * 256 - left).toFloat(),
            (WebMercator.yTile(p.lat, z) * 256 - top).toFloat(),
        )
        fun zumOrt(o: Offset) = GeoPunkt(
            WebMercator.lat((top + o.y) / 256, z),
            WebMercator.lon((left + o.x) / 256, z),
        )
        // verschiebe/zoomBei lesen den Zustand bei JEDEM Aufruf frisch: sie
        // laufen aus pointerInput-Bloecken, die nur bei neuer Groesse neu
        // starten — mit den Werten von oben rechnete eine laufende Geste
        // sonst gegen den Mittelpunkt vom Gestenbeginn.
        fun verschiebe(dx: Float, dy: Float) {
            val zz = zustand.zoom.coerceIn(1, ebene.maxZoom)
            val cx = WebMercator.xTile(zustand.lon, zz) * 256
            val cy = WebMercator.yTile(zustand.lat, zz) * 256
            zustand.setze(
                WebMercator.lat((cy - dy) / 256, zz),
                WebMercator.lon((cx - dx) / 256, zz),
            )
        }
        fun zoomBei(o: Offset, delta: Int) {
            val zz = zustand.zoom.coerceIn(1, ebene.maxZoom)
            val neu = (zz + delta).coerceIn(1, ebene.maxZoom)
            if (neu == zz) return
            val l = WebMercator.xTile(zustand.lon, zz) * 256 - wPx / 2.0
            val t = WebMercator.yTile(zustand.lat, zz) * 256 - hPx / 2.0
            val ortLat = WebMercator.lat((t + o.y) / 256, zz)
            val ortLon = WebMercator.lon((l + o.x) / 256, zz)
            // Der Ort unter dem Zeiger bleibt unter dem Zeiger.
            val ox = WebMercator.xTile(ortLon, neu) * 256 - (o.x - wPx / 2.0)
            val oy = WebMercator.yTile(ortLat, neu) * 256 - (o.y - hPx / 2.0)
            zustand.setze(WebMercator.lat(oy / 256, neu), WebMercator.lon(ox / 256, neu), neu)
        }
        fun pinBei(o: Offset): KartenPin? = pins.lastOrNull { p ->
            val s = zumSchirm(GeoPunkt(p.lat, p.lon))
            val r = with(density) { (p.radiusDp + 4).dp.toPx() }
            (s - o).getDistance() <= r
        }

        val tippeAktuell by rememberUpdatedState { o: Offset, doppelt: Boolean ->
            if (doppelt) zoomBei(o, 1)
            else pinBei(o)?.let { onPinTap?.invoke(it) } ?: onTap?.invoke(zumOrt(o))
        }

        val gesten = if (!interaktiv) Modifier else Modifier
            // Mausrad: als Ereignisschleife, weil Modifier.onPointerEvent nur
            // auf dem Desktop existiert und diese Karte auch auf Android laeuft.
            .pointerInput(wPx, hPx) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type != PointerEventType.Scroll) continue
                        val change = ev.changes.firstOrNull() ?: continue
                        val dy = change.scrollDelta.y
                        if (dy != 0f) { zoomBei(change.position, if (dy < 0) 1 else -1); change.consume() }
                    }
                }
            }
            // Ein Finger (oder Maus) zieht, zwei Finger zoomen. Bis
            // 29.10.2026 gab es nur Ziehen — am Touchscreen liess sich die
            // Karte weder auf- noch zuziehen („In OSM-Fenster von Trail kann
            // man nicht zoomen"). Die Karte kennt nur ganze Zoomstufen:
            // der Faktor wird gesammelt und schaltet ab x1,5 bzw. /1,5 eine
            // Stufe, um den Mittelpunkt zwischen den Fingern.
            .pointerInput(wPx, hPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var faktor = 1f
                    var unterwegs = false
                    var gesamtPan = Offset.Zero
                    var gesamtZoom = 1f
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.any { it.isConsumed }) break
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        if (!unterwegs) {
                            gesamtPan += pan
                            gesamtZoom *= zoom
                            val zoomWeg = abs(1 - gesamtZoom) * ev.calculateCentroidSize(useCurrent = false)
                            if (zoomWeg > viewConfiguration.touchSlop ||
                                gesamtPan.getDistance() > viewConfiguration.touchSlop
                            ) unterwegs = true
                        }
                        if (unterwegs) {
                            if (pan != Offset.Zero) verschiebe(pan.x, pan.y)
                            if (ev.changes.count { it.pressed } >= 2) {
                                faktor *= zoom
                                val mitte = ev.calculateCentroid(useCurrent = true)
                                if (faktor >= 1.5f) { zoomBei(mitte, 1); faktor = 1f }
                                else if (faktor <= 1f / 1.5f) { zoomBei(mitte, -1); faktor = 1f }
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            // Nur bei neuer Groesse neu starten (02.11.2026): bis dahin hing
            // der Block an Mittelpunkt und Zoom und startete beim Ziehen mit
            // jedem Mauspixel neu. Die aktuellen Werte holt tippeAktuell.
            .pointerInput(wPx, hPx) {
                detectTapGestures(
                    onTap = { o -> tippeAktuell(o, false) },
                    onDoubleTap = { o -> tippeAktuell(o, true) },
                )
            }

        Box(Modifier.fillMaxSize().then(gesten)) {
            val tx0 = floor(left / 256).toInt()
            val tx1 = floor((left + wPx) / 256).toInt()
            val ty0 = max(0, floor(top / 256).toInt())
            val ty1 = min(n - 1, floor((top + hPx) / 256).toInt())
            // key je Kachel (02.11.2026: „In OSM-Karte kann nicht fluessig
            // ... mit Maus gezogen werden"): ohne key rueckte beim Ziehen
            // ueber eine Kachelgrenze jede Bildstelle eine Kachel weiter, alle
            // Kacheln luden neu und das Bild flackerte. Mit key bleibt jede
            // Kachel ihr Bild und wird nur verschoben.
            for (ty in ty0..ty1) for (tx in tx0..tx1) key(z, tx, ty) {
                val wx = ((tx % n) + n) % n
                AsyncImage(
                    model = ebene.url(z, wx, ty),
                    imageLoader = loader,
                    contentDescription = null,
                    modifier = Modifier
                        .offset { IntOffset((tx * 256 - left).roundToInt(), (ty * 256 - top).roundToInt()) }
                        .size(tileDp),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                for (l in linien) {
                    if (l.punkte.size < 2) continue
                    val path = Path()
                    l.punkte.forEachIndexed { i, p ->
                        val s = zumSchirm(p)
                        if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y)
                    }
                    drawPath(path, l.farbe, style = Stroke(width = l.breiteDp.dp.toPx(), cap = StrokeCap.Round))
                }
                for (p in pins) {
                    if (p.bildUrl != null) continue // als Composable darueber
                    val s = zumSchirm(GeoPunkt(p.lat, p.lon))
                    val r = p.radiusDp.dp.toPx()
                    drawCircle(Color.White, r + 2.dp.toPx(), s)
                    drawCircle(p.farbe, r, s)
                    p.text?.let { t ->
                        val layout = textMeasurer.measure(
                            t, TextStyle(color = Color.White, fontSize = (p.radiusDp * 0.85f).sp, fontWeight = FontWeight.Bold),
                        )
                        drawText(layout, topLeft = Offset(s.x - layout.size.width / 2f, s.y - layout.size.height / 2f))
                    }
                }
                zeichne?.invoke(this) { p -> zumSchirm(p) }
            }
            // Foto-Marker: 52 dp, runde Ecken, weisser Rand mit Schatten -
            // dieselbe Optik wie photoPin auf Android; Klick wie ein Pin.
            if (imageLoader != null) for (p in pins) {
                val url = p.bildUrl ?: continue
                val s = zumSchirm(GeoPunkt(p.lat, p.lon))
                val halb = with(density) { 26.dp.roundToPx() }
                AsyncImage(
                    model = url, imageLoader = imageLoader, contentDescription = p.text,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset { IntOffset(s.x.roundToInt() - halb, s.y.roundToInt() - halb) }
                        .size(52.dp)
                        .shadow(3.dp, RoundedCornerShape(8.dp))
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(2.5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (onPinTap != null) Modifier.clickable { onPinTap(p) } else Modifier),
                )
            }
            Text(
                ebene.quelle, color = Color(0xCC000000), fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

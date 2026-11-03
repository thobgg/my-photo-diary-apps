package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.api.MapPin
import de.bgghome.mpd.ui.karte.GeoPunkt
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.util.GpxTrack
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.hypot

/*
 * Bausteine um die Karten, plattformneutral (22.09.2026): vorher in
 * MapElements.kt (Android), jetzt fuer die Compose-Kachelkarte des
 * Desktops mitbenutzt.
 */

/** Web: 320px Hoehe, unter 768px Breite 240px. */
@Composable
internal fun kartenHoehe(): Dp = if (fensterBreiteDp() <= 768) 240.dp else 320.dp

internal val TrackMagenta = Color(0xFFE6007E)
internal val StartGruen = Color(0xFF27AE60)
internal val ZielRot = Color(0xFFE74C3C)

internal fun tourSymbol(typeHint: String?): String = when (typeHint) {
    "hiking" -> "🥾"; "cycling" -> "🚴"; "running" -> "🏃"
    "swimming" -> "🏊"; "walking" -> "🚶"; else -> "📍"
}

/**
 * Geschwungene Verbindungslinie (Flugkarten-Bogen) — Gegenstueck zu _buildArc
 * im Web; einmalig im projizierten Gradraum gerechnet (Laengengrade mit
 * cos(Breite) gestaucht).
 */
internal fun bogenPunkte(pins: List<MapPin>): List<GeoPunkt> {
    val bow = 0.18
    val samples = 24
    val kx = cos(Math.toRadians(pins.first().lat))
    val pts = mutableListOf<GeoPunkt>()
    for (s in 0 until pins.size - 1) {
        val x0 = pins[s].lon * kx; val y0 = pins[s].lat
        val x1 = pins[s + 1].lon * kx; val y1 = pins[s + 1].lat
        val dx = x1 - x0; val dy = y1 - y0
        val len = hypot(dx, dy).takeIf { it > 0 } ?: 1.0
        val nx = dy / len; val ny = -dx / len
        val cx = (x0 + x1) / 2 + nx * len * bow
        val cy = (y0 + y1) / 2 + ny * len * bow
        for (i in (if (s == 0) 0 else 1)..samples) {
            val t = i.toDouble() / samples; val u = 1 - t
            val x = u * u * x0 + 2 * u * t * cx + t * t * x1
            val y = u * u * y0 + 2 * u * t * cy + t * t * y1
            pts += GeoPunkt(y, x / kx)
        }
    }
    return pts
}

/** Overlay, das die Karte scharf schaltet und das dezent ansagt. */
@Composable
internal fun ArmOverlay(armed: Boolean, onArm: () -> Unit, modifier: Modifier = Modifier) {
    if (armed) return
    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onArm,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            stringResource(Res.string.map_tap_to_move),
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Layer-Umschalter Karte/Gelaende als Overlay, wie der tour-layer-btn des Webs
 *  (dort Emoji 🗺️/🏔️ — hier Symbole, Regel 08.09.2026). */
@Composable
internal fun LayerToggle(topo: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onToggle)
            .padding(8.dp),
    ) {
        Icon(
            if (topo) MpdIcons.Mountain else MpdIcons.Map,
            stringResource(if (topo) Res.string.map_layer_topo else Res.string.map_layer_map),
            Modifier.size(20.dp), tint = Color.White,
        )
    }
}

/** Hoehenprofil — Gegenstueck zu _renderElevationProfile (SVG im Web). */
@Composable
internal fun ElevationProfile(track: GpxTrack) {
    // Einmal je Track (29.09.2026): min/max und die Kurve wurden bei jeder
    // Recomposition ueber alle Punkte neu gerechnet, und der Canvas zeichnete
    // bei jedem Bild einen Pfad mit zehntausenden Ecken. Bei 80 dp Hoehe ist
    // mehr als ein Punkt je Pixel unsichtbar — also auf hoechstens
    // PROFIL_SPALTEN Spalten eindampfen (Mittelwert je Spalte); min und max
    // stammen weiter aus allen Punkten.
    val profil = remember(track) { hoehenProfil(track) } ?: return
    val eles = profil.spalten
    val minE = profil.min
    val maxE = profil.max
    val range = (maxE - minE).takeIf { it > 0 } ?: 1.0

    // Vor dem Canvas geholt: drawBehind/Canvas sind keine Composables.
    val blue = Mpd.colors.blue
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val w = size.width
            val h = size.height
            val step = w / (eles.size - 1)
            val line = Path()
            val area = Path()
            eles.forEachIndexed { i, e ->
                val x = i * step
                val y = (h - ((e - minE) / range) * (h - 10.dp.toPx()) - 2.dp.toPx()).toFloat()
                if (i == 0) {
                    line.moveTo(x, y)
                    area.moveTo(x, h)
                    area.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    area.lineTo(x, y)
                }
            }
            area.lineTo(w, h)
            area.close()
            drawPath(
                area,
                brush = Brush.verticalGradient(
                    listOf(blue.copy(alpha = 0x80 / 255f), blue.copy(alpha = 0x0D / 255f)),
                ),
                alpha = 0.7f,
            )
            drawPath(line, color = blue, style = Stroke(width = 1.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${minE.toInt()} m", color = Color(0x59FFFFFF), fontSize = 11.sp)
            Text("${maxE.toInt()} m", color = Color(0x59FFFFFF), fontSize = 11.sp)
        }
    }
}

private const val PROFIL_SPALTEN = 400

internal class HoehenProfil(val spalten: List<Double>, val min: Double, val max: Double)

/** Hoehenkurve fuers Zeichnen, null wenn zu wenige Hoehen. Siehe [ElevationProfile]. */
internal fun hoehenProfil(track: GpxTrack): HoehenProfil? {
    val eles = track.points.mapNotNull { it.ele }
    if (eles.size < 2) return null
    val minE = eles.min()
    val maxE = eles.max()
    if (eles.size <= PROFIL_SPALTEN) return HoehenProfil(eles, minE, maxE)
    val spalten = ArrayList<Double>(PROFIL_SPALTEN)
    for (k in 0 until PROFIL_SPALTEN) {
        val von = k * eles.size / PROFIL_SPALTEN
        val bis = ((k + 1) * eles.size / PROFIL_SPALTEN).coerceAtLeast(von + 1)
        var summe = 0.0
        for (i in von until bis) summe += eles[i]
        spalten += summe / (bis - von)
    }
    return HoehenProfil(spalten, minE, maxE)
}

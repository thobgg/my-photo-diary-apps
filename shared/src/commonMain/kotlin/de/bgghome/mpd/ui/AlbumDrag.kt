package de.bgghome.mpd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.api.PdxElement
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Verschieben per Long-Press — Port des „Silent Sorting" aus album-drag.js:
 * Waehrend des Ziehens bewegt sich nichts (kein Reflow, kein Flackern), nur
 * ein Indikator zeigt die Einfuegestelle; erst beim Loslassen wird das
 * Modell umsortiert und neu gerendert. Zielsuche wie updateIndicator im Web:
 * Fotokacheln werden links/rechts der naechsten Kachelmitte eingefuegt
 * (vertikale Naehe ±10 px), volle-Breite-Elemente darueber/darunter (±30 px).
 */

/** Eine registrierte Kachel: Fenster-Koordinaten + Einfuegerichtung. */
class DragTile(val rect: Rect, val horizontal: Boolean)

class AlbumDragState {
    /** Element-id → sichtbare Kachel (LazyColumn: nur komponierte Items). */
    val tiles = mutableStateMapOf<String, DragTile>()

    var draggedElem by mutableStateOf<PdxElement?>(null)
        private set
    var fingerPos by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<DropTarget?>(null)

    data class DropTarget(val id: String, val before: Boolean, val rect: Rect, val horizontal: Boolean)

    fun start(elem: PdxElement, finger: Offset) {
        draggedElem = elem
        fingerPos = finger
        target = null
    }

    fun reset() {
        draggedElem = null
        target = null
    }

    /** Zielsuche — Schwellen in px (density × Web-px-Werte). */
    fun computeTarget(density: Float): DropTarget? {
        val draggedId = draggedElem?.id ?: return null
        val f = fingerPos
        var best: DropTarget? = null
        var bestDist = Float.MAX_VALUE
        for ((id, tile) in tiles) {
            if (id == draggedId) continue
            val r = tile.rect
            if (tile.horizontal) {
                val pad = 10f * density
                if (f.y >= r.top - pad && f.y <= r.bottom + pad) {
                    val midX = r.left + r.width / 2
                    val dist = abs(f.x - midX)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = DropTarget(id, f.x < midX, r, true)
                    }
                }
            } else {
                val pad = 30f * density
                if (f.y >= r.top - pad && f.y <= r.bottom + pad) {
                    val midY = r.top + r.height / 2
                    val dist = abs(f.y - midY)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = DropTarget(id, f.y < midY, r, false)
                    }
                }
            }
        }
        return best
    }
}

/**
 * Huelle um jede verschiebbare Kachel: registriert die Fensterposition,
 * startet den Drag per Long-Press (Haptik wie navigator.vibrate im Web)
 * und blendet das Original waehrend des Ziehens ab (sortable-chosen-Optik).
 */
@Composable
fun DragTileBox(
    drag: AlbumDragState,
    elem: PdxElement,
    enabled: Boolean,
    horizontal: Boolean,
    onDrop: (id: String, targetId: String, before: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    val haptics = LocalHapticFeedback.current
    val webIdiom = LocalSystemDienste.current.webIdiom
    DisposableEffect(elem.id) {
        onDispose { drag.tiles.remove(elem.id) }
    }
    val dimmed = drag.draggedElem?.id == elem.id
    // Die gezogene Kachel anheften, solange sie unterwegs ist. Ohne das
    // verwirft die LazyColumn das Item, sobald der Auto-Scroll es aus dem
    // Bild schiebt — mit ihm endet der pointerInput, und die Geste bricht
    // ab (29.10.2026: „verschieben von Bildern oder Texten geht nicht über
    // den Ausschnitt"). Angeheftete Items bleiben komponiert, auch
    // ausserhalb des sichtbaren Bereichs.
    val pinnable = LocalPinnableContainer.current
    DisposableEffect(dimmed, pinnable) {
        val handle = if (dimmed) pinnable?.pin() else null
        onDispose { handle?.release() }
    }
    // Eigene Lage im Fenster: der Finger wird darueber in Fensterkoordinaten
    // umgerechnet. Aufaddierte Bewegungsschritte liefen beim Auto-Scroll
    // davon — scrollt die Kachel unter dem ruhenden Finger weg, meldet die
    // Geste eine Bewegung, die der Finger nie gemacht hat.
    val lage = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(
        modifier
            .onGloballyPositioned {
                lage[0] = it
                if (enabled) drag.tiles[elem.id] = DragTile(it.boundsInWindow(), horizontal)
            }
            .graphicsLayer { alpha = if (dimmed) 0.35f else 1f }
            .then(
                if (!enabled) Modifier
                else Modifier.pointerInput(elem.id, webIdiom) {
                    val onStart: (Offset) -> Unit = { offset ->
                        drag.tiles[elem.id]?.rect?.let { rect ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            drag.start(elem, rect.topLeft + offset)
                        }
                    }
                    val onMove: (PointerInputChange, Offset) -> Unit = { change, amount ->
                        if (drag.draggedElem != null) {
                            change.consume()
                            val c = lage[0]
                            drag.fingerPos =
                                if (c != null && c.isAttached) c.localToWindow(change.position)
                                else drag.fingerPos + amount
                            drag.target = drag.computeTarget(density)
                        }
                    }
                    val onEnd: () -> Unit = {
                        val t = drag.target
                        val d = drag.draggedElem
                        if (t != null && d != null && t.id != d.id) onDrop(d.id, t.id, t.before)
                        drag.reset()
                    }
                    // Maus (Web-Idiom): Ziehen beginnt sofort, wie sortable.js im
                    // Web - am Desktop scrollt das Rad, nicht der Finger, also
                    // kollidiert das nicht (22.09.2026: „das Umordnen der
                    // pdx-Elemente in Album Edit geht leider nicht"). Finger:
                    // Long-Press wie bisher, sonst kaempft das Ziehen mit dem Scrollen.
                    if (webIdiom) {
                        detectDragGestures(
                            onDragStart = onStart, onDrag = onMove,
                            onDragEnd = onEnd, onDragCancel = { drag.reset() },
                        )
                    } else {
                        detectDragGesturesAfterLongPress(
                            onDragStart = onStart, onDrag = onMove,
                            onDragEnd = onEnd, onDragCancel = { drag.reset() },
                        )
                    }
                }
            ),
        content = content,
    )
}

/**
 * Ueberlagerung waehrend des Ziehens: Einfuege-Indikator (blaue Linie wie
 * #drop-indicator im Web) + Mini-Vorschau des gezogenen Elements am Finger.
 * Muss als letztes Kind in der Box ueber der LazyColumn liegen.
 */
@Composable
fun DragOverlay(drag: AlbumDragState, baseUrl: String, imageLoader: ImageLoader) {
    val elem = drag.draggedElem ?: return
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val indicatorColor = Color(0xFF2E75B6)

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        drag.target?.let { t ->
            val r = t.rect.translate(-origin)
            with(density) {
                if (t.horizontal) {
                    // Senkrechte Linie links/rechts der Zielkachel
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    ((if (t.before) r.left else r.right) - 1.5f.dp.toPx()).roundToInt(),
                                    r.top.roundToInt(),
                                )
                            }
                            .size(3.dp, r.height.toDp())
                            .background(indicatorColor, RoundedCornerShape(1.5.dp)),
                    )
                } else {
                    // Waagerechte Linie ueber/unter dem Zielelement
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    r.left.roundToInt(),
                                    ((if (t.before) r.top else r.bottom) - 1.5f.dp.toPx()).roundToInt(),
                                )
                            }
                            .size(r.width.toDp(), 3.dp)
                            .background(indicatorColor, RoundedCornerShape(1.5.dp)),
                    )
                }
            }
        }

        // Ghost: knapp ueber dem Finger, damit er nicht verdeckt wird
        val pos = drag.fingerPos - origin
        val ghostW = with(density) { 96.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset((pos.x - ghostW / 2).roundToInt(), (pos.y - ghostW - with(density) { 24.dp.toPx() }).roundToInt()) }
                .graphicsLayer { alpha = 0.9f },
        ) {
            val thumb = elem.thumbnails?.sm
            if ((elem.type == "photo" || elem.type == "video") && thumb != null) {
                AsyncImage(
                    model = baseUrl + thumb,
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                val (icon, label) = when (elem.type) {
                    "text" -> MpdIcons.TextT to "Text"
                    "separator" -> MpdIcons.SeparatorLine to "Trenner"
                    "link" -> MpdIcons.Link to "Link"
                    "audio" -> MpdIcons.Audio to "Audio"
                    "document" -> MpdIcons.Document to "Dokument"
                    "map" -> MpdIcons.Map to "Karte"
                    "tour" -> MpdIcons.Tour to "Tour"
                    else -> null to elem.type
                }
                val chip = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC1A1A1A))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                if (icon != null) {
                    IconLabel(icon, label, color = Color.White, fontSize = 14.sp, iconSize = 16.dp, modifier = chip)
                } else {
                    Text(label, color = Color.White, fontSize = 14.sp, modifier = chip)
                }
            }
        }
    }
}

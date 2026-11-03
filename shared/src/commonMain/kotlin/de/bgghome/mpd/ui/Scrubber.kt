package de.bgghome.mpd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Schnell-Scroller wie in Google Photos: Griff am rechten Rand, beim Ziehen
 * eine Blase mit der Beschriftung der Stelle, darunter Jahresmarken. Sonst
 * spiegelt der Griff nur den Scroll-Fortschritt und blendet sich weg.
 *
 * Stand bis zum 12.09.2026 als `private` in TimelineScreen.kt und war damit
 * an den Zeitstrahl gefesselt. Rueckmeldung: „Tablet Modus soll bei beiden
 * Formatausrichtungen rechts die Googletypischen Slider sein" — die
 * Albenuebersicht hatte gar keinen. Statt einer Kopie hier die gemeinsame
 * Fassung: sie kennt nur noch eine Liste von Beschriftungen, eine je
 * Rasterzelle, und weiss nichts von Fotos oder Alben.
 */

/**
 * @param labels Eine Beschriftung je Rasterzelle, in Rasterreihenfolge.
 *   Endet eine auf vier Ziffern, wird daraus eine Jahresmarke.
 * @param minItems Ab wie vielen Zellen sich der Griff ueberhaupt lohnt.
 * @param onJump Springt zu diesem Rasterindex.
 */
@Composable
internal fun Scrubber(
    gridState: LazyGridState,
    labels: List<String>,
    markColor: Color,
    minItems: Int = 40,
    onJump: (Int) -> Unit,
) = ScrubberKern(
    labels, markColor, minItems,
    ersterSichtbarer = { gridState.firstVisibleItemIndex },
    scrollt = { gridState.isScrollInProgress },
    kannScrollen = { gridState.canScrollForward || gridState.canScrollBackward },
    taste = { t ->
        when (t) {
            ListenTaste.ANFANG -> gridState.scrollToItem(0)
            ListenTaste.ENDE -> gridState.layoutInfo.totalItemsCount.let { if (it > 0) gridState.scrollToItem(it - 1) }
            ListenTaste.SEITE_HOCH -> gridState.animateScrollBy(-gridState.layoutInfo.viewportSize.height * 0.9f)
            ListenTaste.SEITE_RUNTER -> gridState.animateScrollBy(gridState.layoutInfo.viewportSize.height * 0.9f)
        }
    },
    onJump = onJump,
)

/** Dieselbe Leiste fuer eine LazyColumn — die Albumansicht (29.10.2026:
 *  „die Slider sind aber nicht da, das ist bei langen Alben besonders
 *  ungeschickt"). */
@Composable
internal fun Scrubber(
    listState: LazyListState,
    labels: List<String>,
    markColor: Color,
    minItems: Int = 40,
    onJump: (Int) -> Unit,
) = ScrubberKern(
    labels, markColor, minItems,
    ersterSichtbarer = { listState.firstVisibleItemIndex },
    scrollt = { listState.isScrollInProgress },
    kannScrollen = { listState.canScrollForward || listState.canScrollBackward },
    taste = { t ->
        when (t) {
            ListenTaste.ANFANG -> listState.scrollToItem(0)
            ListenTaste.ENDE -> listState.layoutInfo.totalItemsCount.let { if (it > 0) listState.scrollToItem(it - 1) }
            ListenTaste.SEITE_HOCH -> listState.animateScrollBy(-listState.layoutInfo.viewportSize.height * 0.9f)
            ListenTaste.SEITE_RUNTER -> listState.animateScrollBy(listState.layoutInfo.viewportSize.height * 0.9f)
        }
    },
    onJump = onJump,
)

/** Listentasten am Desktop (02.11.2026: „sinnvolle Shortcuts, Strg+Pos1
 *  geht ganz hoch im Album bzw. Strg+Ende"). */
enum class ListenTaste { ANFANG, ENDE, SEITE_HOCH, SEITE_RUNTER }

/**
 * Wohin die Listentasten gehen: an die zuletzt eingeblendete Liste mit
 * Scroller (Albenuebersicht, Album, Zeitstrahl). Das Fenster (desktop/
 * Main.kt) reicht nur Tasten weiter, die kein Eingabefeld verbraucht hat —
 * Strg+Pos1 in einem Textfeld bleibt also „an den Textanfang".
 */
object ListenTasten {
    private val ziele = mutableListOf<(ListenTaste) -> Unit>()
    internal fun anmelden(ziel: (ListenTaste) -> Unit) { ziele += ziel }
    internal fun abmelden(ziel: (ListenTaste) -> Unit) { ziele -= ziel }
    fun verarbeite(t: ListenTaste): Boolean {
        val ziel = ziele.lastOrNull() ?: return false
        ziel(t); return true
    }
}

@Composable
private fun ScrubberKern(
    labels: List<String>,
    markColor: Color,
    minItems: Int,
    ersterSichtbarer: () -> Int,
    scrollt: () -> Boolean,
    kannScrollen: () -> Boolean,
    taste: suspend (ListenTaste) -> Unit,
    onJump: (Int) -> Unit,
) {
    // Desktop (02.11.2026: „erscheint nur nach Mausrad kurz, kann aber
    // nicht angefasst werden", „in Alben nichts"): dort steht die Leiste
    // immer da, sobald die Liste laenger als das Fenster ist — unabhaengig
    // von der Zahl der Eintraege; ein Album mit breiten Fotozeilen hatte
    // unter minItems Bloecke und bekam gar keine. Android bleibt beim
    // Google-Fotos-Verhalten (nur beim Scrollen).
    val desktop = LocalSystemDienste.current.webIdiom
    val tastenScope = rememberCoroutineScope()
    val aktuelleTaste by rememberUpdatedState(taste)
    if (desktop) DisposableEffect(Unit) {
        val ziel: (ListenTaste) -> Unit = { t -> tastenScope.launch { aktuelleTaste(t) } }
        ListenTasten.anmelden(ziel)
        onDispose { ListenTasten.abmelden(ziel) }
    }

    val total = labels.size
    if (total < 2 || (!desktop && total < minItems)) return
    val immerDa by remember { derivedStateOf { desktop && kannScrollen() } }

    var scrubbing by remember { mutableStateOf(false) }
    // Griff sichtbar UND greifbar nur waehrend des Scrollens und 1,5 s
    // danach, wie bei Google Fotos. Bis 29.10.2026 lag der Greifstreifen
    // immer am rechten Rand, auch unsichtbar: ein Wisch dort sprang durchs
    // Raster statt zu scrollen, und Knoepfe darunter (das Entfernen-Kreuz
    // im Album-Bearbeiten am Handy) waren nicht zu treffen.
    var griffDa by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollt() }.collectLatest { laeuft ->
            if (laeuft) griffDa = true
            else { delay(1500); griffDa = false }
        }
    }
    var scrubFrac by remember { mutableFloatStateOf(0f) }
    // Google-Fotos-Haptik: Tick bei jedem Gruppenwechsel unterm Griff
    val haptics = LocalHapticFeedback.current
    var lastHapticLabel by remember { mutableStateOf("") }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val handleH = if (scrubbing) 56.dp else 46.dp
        val handleHPx = with(LocalDensity.current) { handleH.toPx() }
        val track = (heightPx - handleHPx).coerceAtLeast(1f)

        // Griffposition NICHT in der Komposition lesen: der graphicsLayer-
        // Block unten liest den Scrollzustand selbst — der Griff bewegt sich
        // dann ohne eine einzige Recomposition pro Zeile.
        val followFrac = remember(total) {
            derivedStateOf {
                (ersterSichtbarer() / (total - 1).toFloat()).coerceIn(0f, 1f)
            }
        }
        val handleY = with(LocalDensity.current) { (scrubFrac * track).toDp() }

        AnimatedVisibility(
            visible = scrubbing || griffDa || immerDa,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Desktop: duenne Schiene unter dem Griff, damit man sieht,
                // dass hier eine Leiste ist und wie lang die Liste ist.
                if (desktop) Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-7).dp)
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(markColor.copy(alpha = 0.25f), RoundedCornerShape(2.dp)),
                )
                // Jahresmarken entlang der Leiste: erste Position jedes
                // Jahres, ausgeduennt auf Mindestabstand.
                if (scrubbing) {
                    val marks = remember(labels) {
                        val result = mutableListOf<Pair<String, Float>>()
                        var lastYear = ""
                        labels.forEachIndexed { i, l ->
                            val y = l.takeLast(4)
                            if (y != lastYear && y.length == 4 && y.all { c -> c.isDigit() }) {
                                lastYear = y
                                result += y to i / (total - 1).toFloat()
                            }
                        }
                        // Mindestabstand 4 % der Hoehe, sonst ueberlappen
                        val thinned = mutableListOf<Pair<String, Float>>()
                        for (m in result) {
                            if (thinned.isEmpty() || m.second - thinned.last().second >= 0.04f) {
                                thinned += m
                            }
                        }
                        thinned
                    }
                    marks.forEach { (year, f) ->
                        Text(
                            year,
                            color = markColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(
                                    x = (-14).dp,
                                    y = with(LocalDensity.current) { (f * track).toDp() },
                                ),
                        )
                    }
                }
                // Blase mit der Beschriftung waehrend des Ziehens
                val blasenText = labels
                    .getOrNull((scrubFrac * (total - 1)).toInt())
                    .orEmpty()
                if (scrubbing && blasenText.isNotBlank()) {
                    val label = blasenText
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-24).dp, y = handleY + 8.dp)
                            .background(MpdBlue, RoundedCornerShape(15.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-5).dp)
                        .graphicsLayer {
                            translationY =
                                (if (scrubbing) scrubFrac else followFrac.value) * track
                        }
                        .size(width = if (scrubbing) 10.dp else 7.dp, height = handleH)
                        .background(MpdBlue, RoundedCornerShape(5.dp)),
                )
            }
        }

        // Greifflaeche breiter als der sichtbare Griff — nur, solange er da ist
        // Am Desktop schmaler (20 dp), weil er dort immer liegt und sonst
        // den rechten Rand der Kacheln fuer Klicks abdeckt.
        if (griffDa || scrubbing || immerDa) Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(if (desktop) 20.dp else 48.dp)
                .then(
                    // Klick auf die Schiene springt dorthin (Desktop-Idiom)
                    if (desktop) Modifier.pointerInput(total, heightPx) {
                        detectTapGestures { offset ->
                            val f = (offset.y / heightPx).coerceIn(0f, 1f)
                            onJump((f * (total - 1)).toInt())
                        }
                    } else Modifier,
                )
                .pointerInput(total, heightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            scrubbing = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scrubFrac = (offset.y / heightPx).coerceIn(0f, 1f)
                            onJump((scrubFrac * (total - 1)).toInt())
                        },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        change.consume()
                        scrubFrac = (change.position.y / heightPx).coerceIn(0f, 1f)
                        onJump((scrubFrac * (total - 1)).toInt())
                        // Tick, sobald der Griff eine neue Gruppe erreicht
                        val label = labels
                            .getOrNull((scrubFrac * (total - 1)).toInt()).orEmpty()
                        if (label != lastHapticLabel) {
                            lastHapticLabel = label
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
        )
    }
}

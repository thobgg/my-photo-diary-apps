package de.bgghome.mpd.ui

import coil3.request.crossfade

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.TimelineItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/*
 * Zeitstrahl (GET /api/timeline) — Google-Photos-artiges Raster ueber alle
 * Medien, absteigend nach Aufnahmedatum, mit Cursor-Paging. Tipp auf eine
 * referenzierte Datei springt ins Album direkt zur Lightbox; unkuratierte
 * Dateien (referenced=false) oeffnen einen einfachen Viewer.
 *
 * Das Schloss gesperrter Dateien stand hier bis zum 06.09.2026 oben rechts
 * auf der Kachel — Rueckmeldung: „Schloss an der Stelle unwichtig". Es wirkt
 * beim Mitschauen in der Lightbox, als Dauerkennzeichen im Raster ist es
 * nur Rauschen.
 *
 * Google-Photos-Zutaten (02.09.2026):
 *  - Pinch-Zoom-Dichte: Tag (grosse Kacheln, Wochentags-Ueberschriften)
 *    / Monat / Jahr (Winzraster). Zwei-Finger-Geste wird im Initial-Pass
 *    abgefangen, damit das Grid nicht gleichzeitig scrollt; beim Umschalten
 *    bleibt das oberste sichtbare Photo der Anker.
 *  - Schnell-Scroller am rechten Rand mit Datums-Blase.
 *  - Schwebende Datums-Kopfzeile oben links, sobald gescrollt ist.
 */

/** "2019-07-03T…" → "Juli 2019" in der Systemsprache; unbekanntes Format
 *  unveraendert. Monatsnamen kommen seit 08.09.2026 aus dem Locale statt
 *  aus einer festen deutschen Liste. */
private fun monthLabel(date: String): String {
    if (date.length < 7) return date
    val month = date.substring(5, 7).toIntOrNull()
    val year = date.substring(0, 4)
    if (month == null || month !in 1..12) return date
    val name = java.time.Month.of(month)
        .getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.getDefault())
    return "$name $year"
}

/** "2019-07-03T…" → "Mi., 3. Juli 2019" (Tag-Dichte); englisch
 *  "Wed, 3 July 2019". Das Muster haengt an der Sprache, nicht am Land. */
private fun dayLabel(date: String): String {
    if (date.length < 10) return date
    val y = date.substring(0, 4).toIntOrNull() ?: return date
    val m = date.substring(5, 7).toIntOrNull() ?: return date
    val d = date.substring(8, 10).toIntOrNull() ?: return date
    if (m !in 1..12) return date
    val locale = java.util.Locale.getDefault()
    val pattern = if (locale.language == "de") "EEE, d. MMMM yyyy" else "EEE, d MMMM yyyy"
    return runCatching {
        java.time.LocalDate.of(y, m, d)
            .format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
    }.getOrDefault(date)
}

private fun yearLabel(date: String): String = date.take(4)

/** Dichtestufen wie die Zoom-Ebenen von Google Photos. */
private const val DENSITY_DAY = 0
private const val DENSITY_MONTH = 1
private const val DENSITY_YEAR = 2
// Apple-Stil-Uebersichten: weiter rauszoomen zeigt Karten statt Kacheln
private const val DENSITY_MONTH_CARDS = 3
private const val DENSITY_YEAR_CARDS = 4

private class TlLayout(
    val groups: List<Pair<String, List<TimelineItem>>>,
    /** Gruppen-Label je Grid-Index (Header wie Zellen) — fuer Chip + Blase. */
    val indexLabels: List<String>,
    /** Item je Grid-Index (null bei Headern) — Anker beim Dichtewechsel. */
    val indexItems: List<TimelineItem?>,
)

private fun buildLayout(items: List<TimelineItem>, density: Int): TlLayout {
    val label: (String) -> String = when (density) {
        DENSITY_DAY -> ::dayLabel
        DENSITY_YEAR -> ::yearLabel
        else -> ::monthLabel
    }
    val groups = mutableListOf<Pair<String, MutableList<TimelineItem>>>()
    val indexLabels = mutableListOf<String>()
    val indexItems = mutableListOf<TimelineItem?>()
    for (item in items) {
        val l = label(item.date)
        if (groups.lastOrNull()?.first != l) groups += l to mutableListOf()
        groups.last().second += item
    }
    for ((l, members) in groups) {
        indexLabels += l; indexItems += null // Header
        for (m in members) { indexLabels += l; indexItems += m }
    }
    return TlLayout(groups, indexLabels, indexItems)
}

/** Farb-Look der Timeline — „hell" ist neu, „auto" folgt dem App-Theme. */
internal data class TlColors(
    val bg: Color,
    val cell: Color,
    val header: Color,
    val chipBg: Color,
    val chipBorder: Color,
    val marks: Color,
)

private val TL_DARK = TlColors(
    bg = Color(0xFF111111), cell = Color(0xFF1E1E1E), header = Color.White,
    chipBg = Color(0xCC1E1E1E), chipBorder = Color(0x33FFFFFF), marks = Color(0x99FFFFFF),
)
private val TL_LIGHT = TlColors(
    bg = Color(0xFFF6F5F2), cell = Color(0xFFE6E4DF), header = Color(0xFF1C1C1C),
    chipBg = Color(0xF2FFFFFF), chipBorder = Color(0x22000000), marks = Color(0x99000000),
)

@Composable
internal fun tlColors(vm: AppViewModel): TlColors = when (vm.timelineTheme) {
    "hell" -> TL_LIGHT
    "auto" -> if (de.bgghome.mpd.ui.theme.Mpd.colors.bg.luminance() < 0.5f) TL_DARK else TL_LIGHT
    else -> TL_DARK
}

@Composable
fun TimelineScreen(vm: AppViewModel, imageLoader: ImageLoader) {
    val tl = tlColors(vm)
    // Erinnerungen kommen zum Nutzer, statt dass er hingeht: das
    // Karussell oben braucht die Tagesdaten, ohne dass jemand die
    // Memories-Ansicht geoeffnet haette.
    LaunchedEffect(Unit) {
        if (vm.moduleAllowed("memories")) vm.memories.ladeWennVeraltet()
    }
    LaunchedEffect(Unit) {
        // Beim Betreten erneut versuchen, AUCH wenn beim letzten Mal ein
        // Fehler stand. Bis 11.09.2026 stand hier `&& timelineError == null`
        // — ein einziger Netzaussetzer unterwegs legte den Zeitstrahl bis
        // zum App-Neustart still („nach App-Neustart dann auch
        // wieder die Timeline"), waehrend die Alben weiterliefen.
        if (vm.timelineItems.isEmpty()) vm.loadTimeline()
        // Die Albenliste beantwortet „liegt in einem Albumordner?" — ohne
        // sie gilt jede Server-Datei als geschuetzt, dann waere Loeschen
        // grundlos gesperrt.
        vm.loadAlbums()
    }

    // Zurueck aus dem Hintergrund: hat das Kamera-Backup zwischendurch
    // etwas hochgeladen, ist die Wolke an diesen Fotos falsch geworden.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshAfterBackup()
                // Ueber Nacht im Hintergrund: das Karussell zeigt sonst
                // weiter die Erinnerungen von gestern.
                if (vm.moduleAllowed("memories")) vm.memories.ladeWennVeraltet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    GeraetefotosAnfordern(vm)

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // rememberSaveable: die gewaehlte Dichte ueberlebt den Prozess-Tod.
    var densityLevel by rememberSaveable { mutableIntStateOf(DENSITY_MONTH) }
    var anchor by remember { mutableStateOf<TimelineItem?>(null) }

    // Nachladen, sobald das Ende in Sicht kommt (Cursor-Paging)
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (lastVisible >= vm.timelineItems.size - 30) vm.loadTimeline()
            }
    }

    Box(Modifier.fillMaxSize().background(tl.bg)) {
        when {
            // Fehler verdraengt den Inhalt nur, wenn es keinen gibt. Ist
            // schon ein Teil geladen, bleibt er stehen und der Fehler wird
            // zur Zeile darunter — mit „Erneut versuchen", das genau dort
            // weitermacht, wo der Lauf abbrach.
            vm.timelineError != null && vm.timelineFeed.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(vm.timelineError!!, color = MpdRed, fontSize = 14.sp)
                TextButton(onClick = { vm.loadTimeline(refresh = true) }) {
                    Text(stringResource(Res.string.timeline_retry), color = MpdBlue)
                }
            }

            vm.timelineFeed.isEmpty() && vm.timelineLoading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center),
            )

            else -> {
                val layout = remember(
                    vm.timelineFeedVersion, densityLevel, vm.timelineUncuratedOnly,
                ) {
                    val items = if (vm.timelineUncuratedOnly) {
                        vm.timelineFeed.filter { !it.referenced }
                    } else vm.timelineFeed
                    buildLayout(items, densityLevel)
                }

                // Anker halten: nach dem Dichtewechsel zum selben Photo scrollen
                LaunchedEffect(densityLevel) {
                    if (densityLevel > DENSITY_YEAR) return@LaunchedEffect
                    val a = anchor ?: return@LaunchedEffect
                    anchor = null
                    val idx = layout.indexItems.indexOfFirst {
                        it != null && it.space == a.space && it.album == a.album && it.file == a.file
                    }
                    if (idx >= 0) gridState.scrollToItem(idx)
                }

                if (densityLevel >= DENSITY_MONTH_CARDS) {
                    TimelineCards(
                        vm = vm,
                        imageLoader = imageLoader,
                        level = densityLevel,
                        anchor = anchor,
                        onOpenGroup = { item, targetLevel ->
                            anchor = item
                            densityLevel = targetLevel
                        },
                        onPinch = { newLevel, item ->
                            anchor = item
                            densityLevel = newLevel
                        },
                    )
                    return@Box
                }

                val cellMin = when (densityLevel) {
                    DENSITY_DAY -> 150.dp
                    DENSITY_YEAR -> 58.dp
                    else -> 110.dp
                }

                // Wisch-Markieren wie bei Google Fotos (06.09.:
                // „wischen über mehrere Kacheln wie Google — aber
                // zusätzlich?"): der Langdruck markiert wie bisher, und wer
                // den Finger liegen laesst und weiterzieht, nimmt die
                // ueberstrichenen Kacheln mit. Antippen bleibt unveraendert.
                //
                // Die erste Kachel hat schon der Langdruck der Kachel selbst
                // erledigt; ihr neuer Zustand gibt die Richtung vor — von
                // einer markierten aus wischt man ab, sonst an. Jede Kachel
                // wird waehrend eines Zuges nur einmal angefasst, sonst
                // flackerte sie beim Vor- und Zurueckwischen.
                val byKey = remember(layout) {
                    layout.groups.asSequence().flatMap { it.second.asSequence() }
                        .associateBy { "${it.space}/${it.album}/${it.file}" }
                }
                var dragAdds by remember { mutableStateOf(true) }
                val dragTouched = remember { mutableSetOf<String>() }
                fun keyAt(pos: androidx.compose.ui.geometry.Offset): String? =
                    gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        pos.x >= info.offset.x && pos.x <= info.offset.x + info.size.width &&
                            pos.y >= info.offset.y && pos.y <= info.offset.y + info.size.height
                    }?.key as? String

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(cellMin),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(layout, densityLevel) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { pos ->
                                    dragTouched.clear()
                                    keyAt(pos)?.let { key ->
                                        dragTouched.add(key)
                                        byKey[key]?.let { dragAdds = vm.isSelected(it) }
                                    }
                                },
                                onDrag = { change, _ ->
                                    val key = keyAt(change.position)
                                    if (key != null && dragTouched.add(key)) {
                                        byKey[key]?.let { item ->
                                            if (vm.isSelected(item) != dragAdds) {
                                                vm.toggleSelect(item)
                                            }
                                        }
                                    }
                                },
                                onDragEnd = { dragTouched.clear() },
                                onDragCancel = { dragTouched.clear() },
                            )
                        }
                        // Pinch im Initial-Pass abfangen, sonst scrollt das
                        // Grid waehrend der Zwei-Finger-Geste dazwischen.
                        .pointerInput(densityLevel) {
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                var zoom = 1f
                                var fired = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.changes.none { it.pressed }) break
                                    if (event.changes.size > 1) {
                                        zoom *= event.calculateZoom()
                                        event.changes.forEach { it.consume() }
                                        if (!fired) {
                                            val next = when {
                                                zoom > 1.35f -> densityLevel - 1 // groeber → feiner
                                                zoom < 0.72f -> densityLevel + 1
                                                else -> densityLevel
                                            }.coerceIn(DENSITY_DAY, DENSITY_YEAR_CARDS)
                                            if (next != densityLevel) {
                                                fired = true
                                                anchor = layout.indexItems
                                                    .drop(gridState.firstVisibleItemIndex)
                                                    .firstOrNull { it != null }
                                                densityLevel = next
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
                ) {
                    val memoryYears = vm.memories.data?.years.orEmpty().filter {
                        it.photos.isNotEmpty()
                    }
                    if (memoryYears.isNotEmpty()) {
                        item(key = "memories", span = { GridItemSpan(maxLineSpan) }) {
                            MemoriesCarousel(vm, imageLoader, memoryYears, tl.header)
                        }
                    }
                    layout.groups.forEach { (label, items) ->
                        item(key = "header-$label", span = { GridItemSpan(maxLineSpan) }) {
                            // Prominent wie bei Google Fotos — die Zeitangabe
                            // ist die Orientierung, nicht Beiwerk (Thomas
                            // 03.09.: „gar keine Zeitangaben").
                            Text(
                                label,
                                color = tl.header,
                                fontSize = when (densityLevel) {
                                    DENSITY_YEAR -> 22.sp
                                    DENSITY_DAY -> 17.sp
                                    else -> 19.sp
                                },
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(
                                    start = 12.dp, top = 24.dp, bottom = 8.dp,
                                ),
                            )
                        }
                        items(
                            items,
                            key = { "${it.space}/${it.album}/${it.file}" },
                            // Tages-Stufe: Tage mit wenigen Fotos zeigen sie
                            // GROSS (Google-Muster) — sonst besteht die Zeile
                            // zu 75 % aus schwarzer Flaeche (03.09.).
                            // 1-3 Fotos → 3 Zellen breit, 4 → 2, ab 5 → 1.
                            span = if (densityLevel == DENSITY_DAY) {
                                {
                                    GridItemSpan(
                                        (maxLineSpan / items.size.coerceAtLeast(1))
                                            .coerceIn(1, minOf(3, maxLineSpan)),
                                    )
                                }
                            } else null,
                        ) { item ->
                            TimelineCell(
                                vm, item, imageLoader,
                                compact = densityLevel == DENSITY_YEAR,
                                // Grosse Kacheln (Spann ≥ 2) brauchen das
                                // 900er-Thumb, sonst wirken sie weich.
                                large = densityLevel == DENSITY_DAY && items.size <= 4,
                            )
                        }
                    }
                    if (vm.timelineLoading && vm.timelineItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                    }
                }

                // Schwebende Datums-Kopfzeile (klebt oben, solange gescrollt
                // ist). derivedStateOf: der rohe firstVisibleItemIndex aendert
                // sich bei JEDER Zeile und wuerde den ganzen Screen
                // recomponieren — das Label wechselt nur an Gruppengrenzen.
                val currentLabel by remember(layout) {
                    derivedStateOf { layout.indexLabels.getOrNull(gridState.firstVisibleItemIndex) }
                }
                val scrolled by remember { derivedStateOf { gridState.canScrollBackward } }
                // Kapsel nicht sofort ausblenden: waehrend des Scrollens an,
                // danach noch 1,5 s stehen lassen (Google-Verhalten).
                var chipVisible by remember { mutableStateOf(false) }
                LaunchedEffect(gridState) {
                    snapshotFlow { gridState.isScrollInProgress }
                        .collectLatest { scrolling ->
                            if (scrolling) {
                                chipVisible = true
                            } else {
                                kotlinx.coroutines.delay(1500)
                                chipVisible = false
                            }
                        }
                }
                AnimatedVisibility(
                    visible = currentLabel != null && scrolled && chipVisible,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                ) {
                    Text(
                        currentLabel.orEmpty(),
                        color = tl.header,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(tl.chipBg, RoundedCornerShape(14.dp))
                            .border(1.dp, tl.chipBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }

                Scrubber(gridState, layout.indexLabels, tl.marks) { idx ->
                    scope.launch { gridState.scrollToItem(idx) }
                }

                // Filter „Unkuratiert" — das „was liegt im Eingang"-Fenster
                if (vm.timelineSelection.isEmpty()) {
                    val chipColor = if (vm.timelineUncuratedOnly) Color.Black else tl.header
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (vm.timelineUncuratedOnly) MpdGoldLt
                                else tl.chipBg,
                            )
                            .border(1.dp, tl.chipBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                vm.timelineUncuratedOnly = !vm.timelineUncuratedOnly
                                vm.clearSelection()
                            }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (vm.timelineUncuratedOnly) {
                            Icon(MpdIcons.Inbox, null, Modifier.size(14.dp), tint = chipColor)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            if (vm.timelineUncuratedOnly) stringResource(Res.string.timeline_filter_uncurated)
                            else stringResource(Res.string.timeline_filter_all),
                            color = chipColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Stufe 3: was noch nicht auf der NAS liegt, als Zahl —
                // Googles „Sicherung abschliessen". Ohne sie bleibt
                // liegen, was in keinem ueberwachten Ordner liegt, und
                // niemand erfaehrt davon. Tippen sichert alles.
                //
                // Nur wenn es etwas zu melden gibt und keine Auswahl
                // laeuft: ein dauerhaftes „0 nicht gesichert" waere
                // Rauschen, und waehrend des Markierens gehoert die
                // Kopfzeile der Auswahl.
                val offen = if (vm.timelineSelection.isEmpty()) vm.ungesichert
                else emptyList()
                if (offen.isNotEmpty()) {
                    IconLabel(
                        MpdIcons.CloudOff, stringResource(Res.string.timeline_not_backed_up, offen.size),
                        color = tl.header, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        iconSize = 14.dp, gap = 5.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 10.dp, start = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(tl.chipBg)
                            .border(1.dp, tl.chipBorder, RoundedCornerShape(14.dp))
                            .clickable { vm.sichereJetzt(offen) }
                            .padding(horizontal = 11.dp, vertical = 5.dp),
                    )
                }

                // Teilstand geladen, aber der Rest fehlt: Hinweis statt Stille.
                if (vm.timelineError != null && vm.timelineFeed.isNotEmpty()) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xEE3A1F1F))
                            .clickable { vm.loadTimeline() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.timeline_partial),
                            color = Color.White, fontSize = 13.sp,
                        )
                        Text(
                            stringResource(Res.string.timeline_retry),
                            color = MpdGoldLt, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }

                CurationBar(vm)
                // Die Rueckmeldung des Kuratierens stand bis 07.09.2026 hier —
                // und wurde damit NUR gezeigt, solange der Zeitstrahl
                // komponiert war. Aus einem Album heraus blieb jede Meldung
                // stumm, auch Fehler. Sie haengt jetzt in MainActivity, wo
                // sie immer erreichbar ist.
                // Upload-/Backup-Fehler des Workers als Snackbar
                LaunchedEffect(vm.uploadError) {
                    vm.uploadError?.let {
                        vm.meldung(it)
                        vm.uploadError = null
                    }
                }
            }
        }
    }
}

/** Auswahl-Leiste: genau zwei Tasten (Modell 02.09.2026). */
/** Ein Symbolknopf der Auswahl-Leiste; ausgegraut statt versteckt, wenn er
 *  gerade nichts tun kann. */
@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Icon(
        icon, label,
        Modifier
            .padding(start = 10.dp)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
        tint = if (enabled) tint else tint.copy(alpha = 0.35f),
    )
}

@Composable
private fun CurationBar(vm: AppViewModel) {
    if (vm.timelineSelection.isEmpty() && !vm.curateBusy) return
    var pickerOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xEE1E1E1E))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MpdIcons.Cross, stringResource(Res.string.timeline_clear_selection), Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { vm.clearSelection() }
                .padding(6.dp),
            tint = Color.White,
        )
        Text(
            vm.uploadFortschritt ?: stringResource(Res.string.timeline_selected, vm.timelineSelection.size),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (vm.curateBusy) {
            CircularProgressIndicator(Modifier.size(20.dp))
        } else {
            // Vier gleiche Symbolknoepfe in fester Reihenfolge, wie Google
            // Fotos in der Auswahl: Weitergeben, Einsortieren, Sichern,
            // Loeschen — der Papierkorb zuletzt und rot. Nichts davon
            // verschwindet je nach Auswahl; was gerade nicht geht, ist
            // ausgegraut (08.09.2026: „da gibt es kein Sichern auf
            // Tablet in Timeline nach Fotoauswahl" — vorher erschien der
            // Knopf nur, wenn Ungesichertes markiert war).
            //
            // Gold, die eine Hauptaktion je Ansicht, gibt es hier nur noch
            // im Filter „Durchlauf": dort ist die ganze Ansicht zum
            // Einsortieren da. Im vollen Zeitstrahl enthaelt eine Auswahl
            // meist laengst kuratierte Fotos, und die haeufigste Handlung
            // ist Weitergeben — da waere ein goldenes Einsortieren
            // anmassend („Einsortieren zu praesent"). Bis zum
            // 06.09. waren es zwei Emoji auf farbigen Rechtecken.
            val dienste = LocalSystemDienste.current
            val lokale = vm.timelineSelection.filter {
                it.localUri != null && it.localKey.isNotBlank()
            }
            if (dienste.kannFotosTeilen) BarAction(
                MpdIcons.Share, stringResource(Res.string.timeline_share_selection),
            ) { dienste.teileAuswahl(vm) }
            // Desktop: an der Stelle des Weitergebens — die Originale der
            // markierten Server-Fotos in den Downloads-Ordner (04.10.2026).
            if (dienste.kannHerunterladen) BarAction(
                MpdIcons.Download, stringResource(Res.string.timeline_download_selection),
                enabled = vm.timelineSelection.any { it.localUri == null },
            ) { vm.herunterladen { url, name -> dienste.ladeInDownloads(vm, url, name) } }
            if (vm.timelineUncuratedOnly) {
                Row(
                    Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MpdGoldLt)
                        .clickable { pickerOpen = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        MpdIcons.AlbumsGrid, null,
                        Modifier.size(16.dp), tint = Color.Black,
                    )
                    Text(
                        stringResource(Res.string.timeline_sort_into),
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            } else {
                BarAction(
                    MpdIcons.AlbumsGrid, stringResource(Res.string.timeline_sort_into),
                ) { pickerOpen = true }
            }
            BarAction(
                MpdIcons.CloudOff,
                pluralStringResource(Res.plurals.timeline_backup_now_n, lokale.size, lokale.size),
                enabled = lokale.isNotEmpty(),
            ) { vm.sichereJetzt(lokale.map { it.localKey }) }
            BarAction(
                MpdIcons.Trash, stringResource(Res.string.timeline_delete), tint = MpdRed,
            ) {
                val serverFiles = vm.timelineSelection
                    .filter { it.localUri == null }
                if (serverFiles.isEmpty() && vm.plattform.papierkorbFragtSelbst) {
                    // Nur Geraete-Aufnahmen markiert: Android fragt
                    // gleich selbst nach, eine eigene Rueckfrage
                    // davor waere dieselbe Entscheidung zweimal.
                    // Am Desktop fragt niemand — dort fragt die App.
                    vm.deleteSelection()
                } else if (serverFiles.isEmpty()) {
                    deleteConfirm = true
                } else {
                    // Vor der Rueckfrage wissen, ob die NAS einen
                    // Papierkorb hat — sonst steht dort eine vage
                    // Auskunft, wo eine klare moeglich ist.
                    vm.loadRecycleStatusForSpaces(
                        serverFiles.map { it.space }.toSet(),
                    )
                    deleteConfirm = true
                }
            }
        }
    }

    if (pickerOpen) {
        CurateAlbumPicker(vm) { pickerOpen = false }
    }
    if (deleteConfirm) {
        // Vorher sagen, was geschieht: einsortierte Dateien bleiben, sonst
        // klaffte im Album eine Luecke („nur löschbar wenn nicht in
        // Album"). Der Server erlaubt es einem Kurator sehr wohl.
        val onDevice = vm.timelineSelection.count { it.localUri != null }
        val deletable = vm.timelineSelection.count { vm.isDeletable(it) }
        val kept = vm.timelineSelection.count { it.localUri == null && !vm.isDeletable(it) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = {
                Text(
                    when {
                        deletable == 0 && onDevice > 0 -> stringResource(Res.string.timeline_delete_title_trash)
                        deletable == 0 -> stringResource(Res.string.timeline_delete_title_nothing)
                        else -> pluralStringResource(Res.plurals.timeline_delete_title_n, deletable, deletable)
                    },
                )
            },
            text = {
                // Zwei Orte, zwei Schicksale: was auf der NAS liegt, ist weg;
                // was nur auf dem Geraet liegt, geht in dessen Papierkorb und
                // ist 30 Tage lang zurueckholbar.
                // Dieselbe Wahrheit wie im Album-Dialog: ob der Papierkorb
                // der NAS aktiv ist, entscheidet, ob „geloescht" endgueltig
                // heisst. Das darf nicht davon abhaengen, wo man loescht.
                // Der Papierkorb haengt an der DSM-Freigabe, nicht an der
                // App: betrifft die Auswahl mehrere Bereiche, koennen die
                // Antworten auseinandergehen.
                val spaces = vm.timelineSelection
                    .filter { it.localUri == null }.map { it.space }.toSet()
                val states = spaces.map { vm.recycleBySpace[it] }
                val serverFate = when {
                    states.isEmpty() || states.any { it == null } ->
                        stringResource(Res.string.timeline_fate_server)
                    // Kein „unsortiert im Album" wie in Lightbox und
                    // Album-Dialog: hier wird nur geloescht, was zu KEINEM
                    // Album gehoert (isDeletable) — es gibt also keinen
                    // Platz, den die Datei beim Zurueckholen verlieren
                    // koennte.
                    states.all { it == true } -> stringResource(Res.string.timeline_fate_recycle)
                    states.all { it == false } -> stringResource(Res.string.timeline_fate_final)
                    else -> stringResource(Res.string.timeline_fate_mixed)
                }
                val deviceNote = if (onDevice == 0) "" else " " +
                    pluralStringResource(
                        if (vm.plattform.papierkorbFragtSelbst) Res.plurals.timeline_device_note
                        else Res.plurals.timeline_pc_note,
                        onDevice, onDevice,
                    )
                Text(
                    when {
                        deletable == 0 && kept == 0 && onDevice > 0 ->
                            deviceNote.trim()
                        deletable == 0 && kept == 1 ->
                            stringResource(Res.string.timeline_kept_one) + deviceNote
                        deletable == 0 ->
                            stringResource(Res.string.timeline_kept_all, kept) + deviceNote
                        kept > 0 ->
                            pluralStringResource(Res.plurals.timeline_kept_some, kept, kept) +
                                " " + serverFate + deviceNote
                        else ->
                            stringResource(Res.string.timeline_curate_hint) + " " + serverFate + deviceNote
                    },
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleteConfirm = false
                    vm.deleteSelection()
                }) { Text(stringResource(Res.string.timeline_delete), color = MpdRed) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}

/** Zielalbum waehlen — Datums-Vorschlag: Alben passend zum Aufnahmejahr
 *  der Auswahl zuerst. Nur echte Alben (der Server lehnt Durchlauf ab). */
@Composable
private fun CurateAlbumPicker(vm: AppViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val selYear = vm.timelineSelection.minOfOrNull { it.date.take(4) } ?: ""
    val albums = (vm.albums ?: emptyList())
        .filter { query.isBlank() || it.title.contains(query, true) || it.name.contains(query, true) }
        .sortedWith(
            compareByDescending<de.bgghome.mpd.api.AlbumSummary> {
                it.year.toString() == selYear
            }.thenByDescending { it.year },
        )

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.timeline_sort_into_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.timeline_album_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.padding(top = 8.dp).heightIn(max = 340.dp),
                ) {
                    items(albums, key = { "${it.space}/${it.name}" }) { album ->
                        val yearMatch = album.year.toString() == selYear
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onDismiss()
                                    vm.importSelectionTo(album.space, album.name)
                                }
                                .padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                album.title.ifEmpty { album.name },
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            if (yearMatch) {
                                IconLabel(
                                    MpdIcons.Star, stringResource(Res.string.timeline_year_match), color = MpdGoldLt, fontSize = 11.sp,
                                    iconSize = 12.dp, gap = 3.dp,
                                )
                            } else if (album.year > 0) {
                                Text("${album.year}", color = MpdMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}



@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineCell(
    vm: AppViewModel,
    item: TimelineItem,
    imageLoader: ImageLoader,
    compact: Boolean = false,
    large: Boolean = false,
) {
    val selected = vm.isSelected(item)
    val selectionActive = vm.timelineSelection.isNotEmpty()
    // Desktop (04.10.2026, Vorgabe: „mit Maus???"): Gedrueckthalten ist das
    // Handy-Idiom. Mit der Maus markiert man wie in Google Fotos im Browser
    // ueber den Haken-Kreis, der beim Ueberfahren erscheint, oder per
    // Strg-Klick. Gedrueckthalten bleibt zusaetzlich.
    val webIdiom = LocalSystemDienste.current.webIdiom
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var strg by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .background(tlColors(vm).cell)
            .hoverable(hover, enabled = webIdiom)
            .pointerInput(webIdiom) {
                if (!webIdiom) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            strg = e.keyboardModifiers.isCtrlPressed || e.keyboardModifiers.isMetaPressed
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = {
                    if (selectionActive || strg) vm.toggleSelect(item)
                    else vm.openFromTimeline(item)
                },
                onLongClick = { vm.toggleSelect(item) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // BlurHash als Sofort-Platzhalter: winzig dekodiert (24×24) und vom
        // Crop hochskaliert — die Kachel hat ab dem ersten Frame Farbe statt
        // Grau, waehrend Coil laedt.
        val placeholder = item.blurhash?.let { bh ->
            remember(bh) {
                de.bgghome.mpd.util.BlurHash.painter(bh, 24, 24)
            }
        }
        AsyncImage(
            // Geraete-Aufnahmen kommen direkt aus dem MediaStore; Coil
            // erzeugt den Thumb selbst.
            model = item.localUri ?: vm.baseUrl + if (item.type == "video") {
                MpdApi.videoThumbPath(item.space, item.album, item.file)
            } else {
                val size = if (large) "m" else "sm"
                (if (large) item.thumbnails?.m else item.thumbnails?.sm)
                    ?.takeIf { it.isNotBlank() }
                    ?: MpdApi.thumbnailPath(item.space, item.album, item.file, size)
            },
            imageLoader = imageLoader,
            contentDescription = item.file,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.type == "video" && !compact) {
            Box(
                Modifier.size(34.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(MpdIcons.Play, stringResource(Res.string.timeline_video), Modifier.size(16.dp), tint = Color.White) }
        }
        // Steckt das Foto in einem Album (album.json)? Vorgabe, 06.09.:
        // „kann man an Fotos in der Timeline erkennen, ob es in einem
        // Album-Ordner/album.json steckt?" — bis dahin verriet das nur der
        // Umschalter „Alle / Unkuratiert" und das Verhalten beim Tippen.
        //
        // Markiert wird hier ausnahmsweise die Regel und nicht die Ausnahme:
        // das vertraute Alben-Zeichen sagt unmittelbar „ist einsortiert",
        // waehrend ein Zeichen fuer „unkuratiert" erst erklaert werden
        // muesste. Traegt am Ende fast jede Kachel das Symbol, ist es
        // Rauschen — dann invertieren (!item.referenced).
        //
        // Geraete-Aufnahmen bekommen es nie: sie sind zwangsläufig
        // unkuratiert, und ihre Wolke sagt das Wesentliche schon.
        if (item.referenced && item.localUri == null && !compact) {
            Icon(
                MpdIcons.AlbumsGrid, stringResource(Res.string.timeline_in_album),
                Modifier.align(Alignment.BottomStart).padding(4.dp).size(13.dp),
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
        // Der Sicherungsstatus, wie bei Google Fotos: markiert wird nur,
        // was NICHT gesichert ist. Auf Winzkacheln waere es Brei.
        if (item.localUri != null && !compact) {
            // Die Wolke IST der Knopf — wie bei Google Fotos, wo das
            // durchgestrichene Symbol in der Detailansicht das Sichern
            // ausloest. Vorher war sie reine Auskunft, und fuer Aufnahmen
            // ausserhalb der ueberwachten Ordner gab es ueberhaupt keinen
            // Weg auf die NAS (07.09.2026).
            //
            // Die Tippflaeche ist groesser als das Symbol: 14 dp trifft
            // niemand, 32 dp schon. Sie liegt in der Ecke und damit nicht
            // im Weg des Tipps auf die Kachel.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { vm.sichereJetzt(listOf(item.localKey)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MpdIcons.CloudOff, stringResource(Res.string.timeline_backup_now),
                    Modifier.size(14.dp),
                    tint = Color.White,
                )
            }
        }
        if (webIdiom && !selected && !compact && (hovered || selectionActive)) {
            // Leerer Haken-Kreis: ein Klick darauf markiert, ohne die
            // Lightbox zu oeffnen. Bei laufender Auswahl auf allen Kacheln.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (hovered) 0.45f else 0.25f))
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                    .clickable { vm.toggleSelect(item) },
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x662E75B6))
                    .border(2.dp, MpdGoldLt),
            )
            Icon(
                MpdIcons.Check, stringResource(Res.string.timeline_selected_mark),
                tint = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(MpdGoldLt, CircleShape)
                    .padding(3.dp)
                    .size(12.dp),
            )
        }
    }
}


/* ── Karten-Uebersichten (Apple-Stil: Jahre → Monate → Raster) ──────────── */

/** Titelbild einer Gruppe: Foto aus der Mitte — der erste Eintrag eines
 *  Monats ist ueberdurchschnittlich oft ein Beleg/Screenshot. */
private fun coverOf(members: List<TimelineItem>): TimelineItem {
    val photos = members.filter { it.type == "photo" }
    // Bevorzugt ein Foto MIT BlurHash (kuratierte haben einen) — sonst ist
    // die Karte bis zum Laden des Titelbilds nur schwarze Flaeche.
    val withHash = photos.filter { it.blurhash != null }
    return withHash.getOrNull(withHash.size / 2)
        ?: photos.getOrNull(photos.size / 2)
        ?: members.first()
}

@Composable
private fun TimelineCards(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    level: Int,
    anchor: TimelineItem?,
    onOpenGroup: (TimelineItem, Int) -> Unit,
    onPinch: (Int, TimelineItem?) -> Unit,
) {
    val labelFn: (String) -> String =
        if (level == DENSITY_YEAR_CARDS) ::yearLabel else ::monthLabel
    val groups = remember(vm.timelineFeedVersion, level, vm.timelineUncuratedOnly) {
        val items = if (vm.timelineUncuratedOnly) {
            vm.timelineFeed.filter { !it.referenced }
        } else vm.timelineFeed
        val result = mutableListOf<Pair<String, MutableList<TimelineItem>>>()
        for (item in items) {
            val l = labelFn(item.date)
            if (result.lastOrNull()?.first != l) result += l to mutableListOf()
            result.last().second += item
        }
        result
    }
    val state = rememberLazyGridState()

    // Anker: die Karte der Gruppe anfahren, aus der gezoomt wurde
    LaunchedEffect(level) {
        val a = anchor ?: return@LaunchedEffect
        val idx = groups.indexOfFirst { it.first == labelFn(a.date) }
        if (idx >= 0) state.scrollToItem(idx)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (level == DENSITY_YEAR_CARDS) 330.dp else 230.dp),
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(level) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoom = 1f
                    var fired = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                        if (event.changes.size > 1) {
                            zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            if (!fired) {
                                val next = when {
                                    zoom > 1.35f -> level - 1
                                    zoom < 0.72f -> level + 1
                                    else -> level
                                }.coerceIn(DENSITY_DAY, DENSITY_YEAR_CARDS)
                                if (next != level) {
                                    fired = true
                                    val item = groups
                                        .getOrNull(state.firstVisibleItemIndex)
                                        ?.second?.firstOrNull()
                                    onPinch(next, item)
                                }
                            }
                        }
                    }
                }
            },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
    ) {
        items(groups, key = { it.first }) { (label, members) ->
            TimelineCard(
                vm = vm,
                imageLoader = imageLoader,
                label = label,
                members = members,
                big = level == DENSITY_YEAR_CARDS,
            ) {
                onOpenGroup(
                    members.first(),
                    if (level == DENSITY_YEAR_CARDS) DENSITY_MONTH_CARDS else DENSITY_MONTH,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TimelineCard(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    label: String,
    members: List<TimelineItem>,
    big: Boolean,
    onClick: () -> Unit,
) {
    val cover = remember(label, members.size) { coverOf(members) }
    val placeholder = cover.blurhash?.let { bh ->
        remember(bh) {
            de.bgghome.mpd.util.BlurHash.painter(bh, 24, 24)
        }
    }
    Box(
        Modifier
            .padding(6.dp)
            .aspectRatio(if (big) 1.45f else 1.2f)
            .clip(RoundedCornerShape(16.dp))
            .background(tlColors(vm).cell)
            .combinedClickable(onClick = onClick, onLongClick = {}),
    ) {
        val coverUrl = vm.baseUrl + if (cover.type == "video") {
            MpdApi.videoThumbPath(cover.space, cover.album, cover.file)
        } else {
            cover.thumbnails?.m?.takeIf { it.isNotBlank() }
                ?: MpdApi.thumbnailPath(cover.space, cover.album, cover.file, "m")
        }
        val smUrl = cover.thumbnails?.sm?.takeIf { it.isNotBlank() }?.let { vm.baseUrl + it }
        AsyncImage(
            // Falls das kleine Thumb schon im Speicher liegt (Raster-Stufe),
            // dient es als scharfer Sofort-Platzhalter unter dem m-Load.
            model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(coverUrl)
                .apply { smUrl?.let { placeholderMemoryCacheKey(it) } }
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            modifier = Modifier.fillMaxSize(),
        )
        // Lesbarkeits-Verlauf unten + Beschriftung wie bei den Grossen
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color(0xB3000000),
                    ),
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(
                label,
                color = Color.White,
                fontSize = if (big) 22.sp else 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                pluralStringResource(Res.plurals.timeline_photo_count, members.size, members.size),
                color = Color(0xCCFFFFFF),
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Erinnerungs-Karussell ueber dem Zeitstrahl — ein Anriss, kein zweiter
 * Screen: je Jahrgang eine Kachel, Tippen oeffnet die volle Ansicht.
 *
 * Memories war bis zum 05.09.2026 ein Tab unten. Rueckmeldung: „eigentlich ist
 * alles Module — warum dann Memories?" Genau deshalb ist es keiner mehr:
 * unten stehen nur die drei Grundtaetigkeiten. Der emotionale Grund fuer
 * Memories bleibt trotzdem richtig — er traegt nur in die andere Richtung.
 * Erinnerungen sind nichts, wohin man geht; ein Tab verlangt die
 * Entscheidung „ich schaue jetzt mal nach Erinnerungen", und die trifft
 * niemand. Hier liegen sie auf dem Weg.
 */
@Composable
private fun MemoriesCarousel(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    years: List<de.bgghome.mpd.api.MemoryYear>,
    headerColor: androidx.compose.ui.graphics.Color,
) {
    Column(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
        Text(
            stringResource(Res.string.timeline_memories),
            color = headerColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout
                .PaddingValues(horizontal = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            items(years, key = { it.year }) { block ->
                val photo = block.photos.first()
                Column(
                    Modifier
                        .width(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            vm.tryOpenModule("memories") { vm.memories.open = true }
                        },
                ) {
                    AsyncImage(
                        model = vm.baseUrl + photo.thumbnailUrl,
                        imageLoader = imageLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Text(
                        // „vor 3 Jahren" — das Jahr allein sagt nichts.
                        pluralStringResource(Res.plurals.timeline_years_ago, block.yearsAgo, block.yearsAgo),
                        color = headerColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

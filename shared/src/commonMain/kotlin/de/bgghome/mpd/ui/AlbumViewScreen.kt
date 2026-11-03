package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import de.bgghome.mpd.ui.theme.Mpd
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.OpenAlbum
import de.bgghome.mpd.SaveState
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource

/*
 * Lesende Albumansicht — 1:1-Nachbau des Web-Renderings
 * (frontend/js/album-render.js + style.css, View-Modus).
 *
 * Layout-Regeln des Webs:
 *  - Inhaltsspalte zentriert, max. 1600, Texte max. 1200 breit.
 *  - Fotos sammeln sich in Reihen: Zielhoehe 400 (schmal: 200, 2 Spalten),
 *    Breite je Foto = Zielhoehe x Seitenverhaeltnis; Reihe ist voll, wenn
 *    die Containerbreite (minus 100) erreicht ist. Beim Rendern wird die
 *    Reihe exakt buendig skaliert: h = (Breite - Luecken) / Summe(AR),
 *    gedeckelt bei 1,5-facher Zielhoehe.
 *  - Ein einzelnes Foto direkt vor einem Text (oder am Albumende) wird
 *    gross gerendert: volle Breite, Hoehe 500 (schmal: Breite/AR), xl.
 *  - Andere Elementtypen schliessen die laufende Fotoreihe ab.
 */

// Farbwerte: MpdTheme.kt (eine Quelle fuer alle Screens)

private const val GAP = 4f
private const val MOBILE_BREAKPOINT = 768f
private const val ROW_FULL_SLACK = 100f

// -- Layoutbloecke ----------------------------------------------------------

private data class RowItem(val elem: PdxElement, val widthDp: Float)

private sealed interface Block {
    val key: String

    data class PhotoRow(val items: List<RowItem>, val heightDp: Float) : Block {
        override val key = "row-" + items.joinToString("+") { it.elem.id }
    }

    /** heightDp null = schmale Ansicht: Hoehe folgt aus Breite/AR. */
    data class SinglePhoto(val elem: PdxElement, val heightDp: Float?) : Block {
        override val key = "single-" + elem.id
    }

    data class Generic(val elem: PdxElement) : Block {
        override val key = elem.type + "-" + elem.id
    }
}

/** Port der Reihen-Logik aus renderAlbum() — masse in dp statt px. */
private fun buildBlocks(elements: List<PdxElement>, containerWidth: Float): List<Block> {
    val isMobile = containerWidth <= MOBILE_BREAKPOINT
    val targetRowHeight = if (isMobile) 200f else 400f
    val columnsMobile = 2

    val blocks = mutableListOf<Block>()
    val currentRow = mutableListOf<RowItem>()
    var currentRowWidth = 0f

    fun rowHeight(row: List<RowItem>): Float {
        val sumAr = row.sumOf { it.elem.aspectRatio.toDouble() }.toFloat()
        val totalGap = (row.size - 1) * GAP
        val h = (containerWidth - totalGap) / sumAr
        return if (h > targetRowHeight * 1.5f) targetRowHeight else h
    }

    fun flushRow() {
        if (currentRow.isEmpty()) return
        if (currentRow.size == 1) {
            val elem = currentRow[0].elem
            blocks += Block.SinglePhoto(
                elem,
                heightDp = if (isMobile) containerWidth / elem.aspectRatio else 500f,
            )
        } else {
            val h = rowHeight(currentRow)
            blocks += Block.PhotoRow(
                currentRow.map { RowItem(it.elem, h * it.elem.aspectRatio) },
                heightDp = h,
            )
        }
        currentRow.clear()
        currentRowWidth = 0f
    }

    elements.forEachIndexed { idx, elem ->
        when (elem.type) {
            "photo", "video" -> {
                if (elem.error != null) {
                    blocks += Block.Generic(elem)
                    return@forEachIndexed
                }
                val imageWidth = targetRowHeight * elem.aspectRatio
                currentRow += RowItem(elem, imageWidth)
                currentRowWidth += imageWidth + GAP

                val next = elements.getOrNull(idx + 1)
                val isLastBeforeText = next == null || next.type == "text"
                val rowFull = if (isMobile) currentRow.size >= columnsMobile
                else currentRowWidth >= containerWidth - ROW_FULL_SLACK

                if (isLastBeforeText && currentRow.size == 1) flushRow()
                else if (rowFull) flushRow()
            }

            "text", "separator", "link", "audio", "document", "map", "tour" -> {
                flushRow()
                blocks += Block.Generic(elem)
            }

            // Unbekannte Typen: wie im Web still ueberspringen
            else -> Unit
        }
    }
    flushRow()
    return blocks
}

// -- Screen -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumViewScreen(vm: AppViewModel, album: OpenAlbum, imageLoader: ImageLoader) {
    // Zurueck beendet erst den Edit-Modus (und speichert dabei), dann das Album.
    BackHandler {
        if (vm.isEditMode) vm.toggleEditMode() else vm.closeAlbum()
    }

    // Zurueck aus dem Hintergrund: in der Zwischenzeit kann am Browser oder
    // am anderen Geraet gespeichert worden sein. Solange hier nichts
    // Ungespeichertes wartet, holt die App den frischen Stand — sonst zeigt
    // sie einen veralteten Inhalt und laeuft bei der naechsten Aenderung in
    // den Konfliktschutz (siehe frischenStandHolen, 12.09.2026).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.frischenStandHolen()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val mediaCount = album.mediaCount
    val textCount = album.textCount

    val drag = remember { AlbumDragState() }
    val listState = rememberLazyListState()
    val density = LocalDensity.current.density
    var listBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Letzte Element-id je LazyColumn-Item — fuer die Einfuegeposition des
    // FAB-Menues (Web: getLastArrayIndexForDomElem + Viewport-Mitte).
    var blockLastIds by remember { mutableStateOf<List<String>>(emptyList()) }

    fun insertAfterIdAtViewportMiddle(): String? {
        val info = listState.layoutInfo
        val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
        val first = info.visibleItemsInfo.firstOrNull() ?: return null
        // Im Edit-Modus ist Item 0 die Meta-Zeile — Blockindex = Itemindex − 1.
        val offset = if (vm.isEditMode) 1 else 0
        var after: String? =
            if (first.index - offset > 0) blockLastIds.getOrNull(first.index - offset - 1) else null
        for (item in info.visibleItemsInfo) {
            val blockIndex = item.index - offset
            if (blockIndex >= blockLastIds.size) break
            if (blockIndex >= 0 && item.offset + item.size / 2 <= mid) {
                blockLastIds.getOrNull(blockIndex)?.let { after = it }
            }
        }
        return after
    }

    var fabMenuOpen by remember { mutableStateOf(false) }
    var maintenanceMenuOpen by remember { mutableStateOf(false) }
    /** "backups" | "delete" */
    var maintenanceDialog by remember { mutableStateOf<String?>(null) }
    /** "separator" | "link" — welcher Einfuege-Dialog offen ist. */
    var insertDialog by remember { mutableStateOf<String?>(null) }
    var insertAfterId by remember { mutableStateOf<String?>(null) }

    // Entfernen ohne Rueckfrage, abgesichert per „Rueckgaengig" — wie der
    // Undo-Toast von removePhotoFromAlbum im Web.
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val onRemove: (PdxElement) -> Unit = { elem ->
        vm.removeElement(elem.id)?.let { name ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = Texte.t(Res.string.view_removed_from_album, name),
                    actionLabel = Texte.t(Res.string.common_undo),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.undoRemove()
            }
        }
    }

    // Auto-Scroll am Rand waehrend des Ziehens (SortableJS scroll:true,
    // sensitivity 80, speed 12 — hier px/Frame in density umgerechnet).
    LaunchedEffect(drag.draggedElem) {
        if (drag.draggedElem == null) return@LaunchedEffect
        while (drag.draggedElem != null) {
            val bounds = listBounds
            if (bounds != null) {
                val zone = 80f * density
                val speed = 12f * density
                val dy = when {
                    drag.fingerPos.y < bounds.top + zone -> -speed
                    drag.fingerPos.y > bounds.bottom - zone -> speed
                    else -> 0f
                }
                if (dy != 0f) {
                    listState.scrollBy(dy)
                    drag.target = drag.computeTarget(density)
                }
            }
            kotlinx.coroutines.delay(16)
        }
    }

    // Tastatur im Web-Idiom: Strg+Z / Strg+Umschalt+Z (auch Strg+Y) im
    // Bearbeiten-Modus, wie undo/redo im Web. Ein Fokus auf der Wurzel,
    // Textfelder fangen ihr eigenes Strg+Z selbst ab.
    val webIdiom = LocalSystemDienste.current.webIdiom
    val tastenFokus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (webIdiom) LaunchedEffect(vm.isEditMode) { if (vm.isEditMode) runCatching { tastenFokus.requestFocus() } }
    val tastatur = if (!webIdiom) Modifier else Modifier
        .focusRequester(tastenFokus).focusable()
        .onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyDown || !ev.isCtrlPressed || !vm.isEditMode) return@onKeyEvent false
            when {
                ev.key == Key.Z && ev.isShiftPressed -> { vm.redo(); true }
                ev.key == Key.Z -> { vm.undo(); true }
                ev.key == Key.Y -> { vm.redo(); true }
                else -> false
            }
        }
    Scaffold(
        modifier = Modifier.fillMaxSize().then(tastatur),
        topBar = {
            TopAppBar(
                title = {
                    // Handy hochkant (384 dp): Zurueck-Knopf und drei bis
                    // vier Knoepfe rechts lassen dem Titel gut 170 dp.
                    // titleLarge (22 sp) schaffte darin kaum zwoelf Zeichen
                    // und lief ueber die Leiste hinaus — 16.09.2026:
                    // „passt quasi nie auch nur annaehernd auf die Leiste".
                    // Kompakt: zwei Zeilen in 16 sp, dann Ellipse. Die
                    // Leiste ist 64 dp hoch, 2 × 20 + 16 passen hinein.
                    // Am Tablet bleibt die grosse Schrift, einzeilig.
                    val kompakt = !istTablet()
                    Column {
                        Text(
                            album.title,
                            style = if (kompakt) MaterialTheme.typography.titleMedium.copy(lineHeight = 20.sp)
                                    else MaterialTheme.typography.titleLarge,
                            maxLines = if (kompakt) 2 else 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                album.summary.year.takeIf { it > 0 }?.toString(),
                                stringResource(
                                    Res.string.view_counts,
                                    pluralStringResource(Res.plurals.view_media_count, mediaCount, mediaCount),
                                    pluralStringResource(Res.plurals.view_text_count, textCount, textCount),
                                ),
                            ).joinToString(" — "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MpdMuted,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    BackButton(
                        stringResource(if (vm.isEditMode) Res.string.view_back_end_edit else Res.string.view_back_close),
                    ) {
                        if (vm.isEditMode) vm.toggleEditMode() else vm.closeAlbum()
                    }
                },
                actions = {
                    // Kalender wie der calendar-btn in der Web-Kopfzeile:
                    // fuer alle, auch nur-lesend — Wochentage nachschauen
                    // braucht keine Schreibrechte.
                    IconButton(onClick = { maintenanceDialog = "calendar" }) {
                        Icon(
                            MpdIcons.Calendar, stringResource(Res.string.view_calendar), Modifier.size(20.dp),
                            tint = MpdMuted,
                        )
                    }
                    if (!album.readOnly) {
                        // Web locked-toggle (Schluessel im Header): zeigt/
                        // verbirgt gesperrte Elemente im View-Modus und
                        // schreibt meta.show_locked.
                        IconButton(onClick = { vm.toggleShowLocked() }) {
                            Icon(
                                if (album.showLocked) MpdIcons.LockOpen else MpdIcons.LockClosed,
                                stringResource(if (album.showLocked) Res.string.view_locked_shown else Res.string.view_locked_hidden),
                                Modifier.size(20.dp),
                                tint = if (album.showLocked) MpdMuted else MpdGoldLt,
                            )
                        }
                        SaveStatusIndicator(vm)
                        // ⋮-Wartungsmenue: Sicherungen (app-exklusiv, unser
                        // Undo-Ersatz) + Album loeschen (Web: Karten-Kebab).
                        Box {
                            IconButton(onClick = { maintenanceMenuOpen = true }) {
                                Icon(MpdIcons.MoreVert, stringResource(Res.string.common_more), Modifier.size(22.dp), tint = MpdText)
                            }
                            DropdownMenu(
                                expanded = maintenanceMenuOpen,
                                onDismissRequest = { maintenanceMenuOpen = false },
                            ) {
                                // Kalender auch hier — wie im mobilen
                                // Web-Kebab (album.html).
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.view_menu_calendar)) },
                                    onClick = {
                                        maintenanceMenuOpen = false
                                        maintenanceDialog = "calendar"
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.view_menu_backups)) },
                                    onClick = {
                                        maintenanceMenuOpen = false
                                        maintenanceDialog = "backups"
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.tile_delete), color = MpdRed) },
                                    onClick = {
                                        maintenanceMenuOpen = false
                                        maintenanceDialog = "delete"
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // FAB-Bereich wie die Handy-Web-Version (fab-container): Stift =
            // Bearbeiten an; im Edit-Modus ✓ (Fertig, speichert) + „+" mit dem
            // Einfuege-Menue. Icons sind die Web-SVGs (MpdIcons), das Menue
            // zeigt ALLE Elementtypen aus fabAction() — noch nicht gebaute
            // Eintraege sind abgeblendet und sagen das per Snackbar.
            if (!album.readOnly) {
                val notYet: () -> Unit = {
                    fabMenuOpen = false
                    vm.meldung(Texte.t(Res.string.view_not_yet), kurz = true)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (vm.isEditMode && fabMenuOpen) {
                        FabMenuItem(MpdIcons.TextT, stringResource(Res.string.fab_text)) {
                            fabMenuOpen = false
                            vm.addTextElement(insertAfterIdAtViewportMiddle())
                        }
                        FabMenuItem(MpdIcons.Camera, stringResource(Res.string.fab_photo)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "media"
                        }
                        FabMenuItem(MpdIcons.SeparatorLine, stringResource(Res.string.fab_separator)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "separator"
                        }
                        FabMenuItem(MpdIcons.Map, stringResource(Res.string.fab_map)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "map"
                        }
                        FabMenuItem(MpdIcons.Audio, stringResource(Res.string.fab_audio)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "audio"
                        }
                        FabMenuItem(MpdIcons.Document, stringResource(Res.string.fab_document)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "documents"
                        }
                        FabMenuItem(MpdIcons.Link, stringResource(Res.string.fab_link)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "link"
                        }
                        // Wie das Web (album.js loadModuleEntitlements): ohne
                        // Tours-Modul entfaellt nur das Einfuegen; eingebettete
                        // Touren rendern weiter (/api/gpx gehoert zum Kern).
                        if (vm.moduleAllowed("tours")) {
                            FabMenuItem(MpdIcons.Tour, stringResource(Res.string.fab_tour)) {
                                fabMenuOpen = false
                                insertAfterId = insertAfterIdAtViewportMiddle()
                                insertDialog = "tour"
                            }
                        }
                        FabMenuItem(MpdIcons.Link, stringResource(Res.string.fab_mixed)) {
                            fabMenuOpen = false
                            insertAfterId = insertAfterIdAtViewportMiddle()
                            insertDialog = "mixed"
                        }
                    }
                    // ＋ (Einfuegemenue) nur im Edit-Modus, unten rechts —
                    // wie der fab-container des Webs. Der Edit-Umschalter
                    // sitzt seit 02.09.2026 als album-primary-fab unten
                    // MITTIG (eigenes Overlay weiter unten).
                    if (vm.isEditMode) {
                        val plusRotation by animateFloatAsState(
                            targetValue = if (fabMenuOpen) 45f else 0f,
                            label = "fabPlusRotation",
                        )
                        FloatingActionButton(
                            onClick = { fabMenuOpen = !fabMenuOpen },
                            containerColor = MpdBlue,
                            contentColor = Color.White,
                        ) {
                            Icon(
                                MpdIcons.Plus, stringResource(Res.string.fab_add),
                                Modifier.size(24.dp).graphicsLayer { rotationZ = plusRotation },
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        when (insertDialog) {
            "media" -> AddMediaDialog(
                vm, imageLoader,
                onDismiss = { insertDialog = null },
                onPick = { f ->
                    // Anker nachziehen: mehrere Picks landen in Wahl-Reihenfolge
                    vm.addMediaElement(insertAfterId, f.file, f.type)
                        ?.let { insertAfterId = it }
                },
            )

            "separator" -> SeparatorInsertDialog(
                onDismiss = { insertDialog = null },
                onInsert = { label, style ->
                    insertDialog = null
                    vm.addSeparator(insertAfterId, label, style)
                },
            )

            "link" -> LinkDialog(
                dialogTitle = stringResource(Res.string.link_insert_title),
                onDismiss = { insertDialog = null },
                onSubmit = { url, title, note ->
                    insertDialog = null
                    vm.addLink(insertAfterId, url, title, note)
                },
            )

            "audio", "documents" -> AddFolderFileDialog(
                vm, kind = insertDialog!!,
                onDismiss = { insertDialog = null },
                onPick = { f -> vm.addFolderFile(insertAfterId, f.file, f.type) },
            )

            "tour" -> AddTourDialog(
                vm,
                onDismiss = { insertDialog = null },
                // Jede Tour landet direkt hinter dem Anker — rueckwaerts
                // eingefuegt stehen mehrere also chronologisch.
                onPicked = { picks -> picks.asReversed().forEach { (f, src) -> vm.addTour(insertAfterId, f, src) } },
            )

            "map" -> AddMapDialog(
                vm,
                onDismiss = { insertDialog = null },
                onInsert = { title, pins ->
                    insertDialog = null
                    vm.addMap(insertAfterId, title, pins)
                },
            )

            "mixed" -> MixedPickerDialog(
                vm, imageLoader,
                onDismiss = { insertDialog = null },
                onInsert = { picks ->
                    insertDialog = null
                    picks.forEach { (sourceAlbum, file) ->
                        vm.addPhotoFromOtherAlbum(insertAfterId, sourceAlbum, file)
                    }
                },
            )
        }

        // Cover-Wechsler (Web openThumbnailModal) — Einstieg in MetaEditRow
        if (vm.coverPickerOpen) {
            CoverPickerDialog(vm, imageLoader) { vm.coverPickerOpen = false }
        }

        when (maintenanceDialog) {
            "calendar" -> AlbumCalendarDialog(album.elements) { maintenanceDialog = null }
            "backups" -> BackupsDialog(vm) { maintenanceDialog = null }
            "delete" -> DeleteAlbumDialog(vm) { maintenanceDialog = null }
        }

        // Konfliktdialog (409 vom Update): ehrlich fragen statt still
        // ueberschreiben — Entscheidung vom 02.09.2026.
        if (vm.conflictOpen) {
            AlertDialog(
                onDismissRequest = { /* bewusst keine Wisch-weg-Option */ },
                title = { Text(stringResource(Res.string.conflict_title)) },
                text = {
                    Text(
                        stringResource(Res.string.conflict_body),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.resolveConflict(reload = true) }) { Text(stringResource(Res.string.conflict_reload)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.resolveConflict(reload = false) }) { Text(stringResource(Res.string.conflict_overwrite)) }
                },
            )
        }

        // Leer-Speichern-Rueckfrage (Web saveAlbumData-Guard)
        if (vm.emptySaveConfirmOpen) {
            AlertDialog(
                onDismissRequest = { vm.answerEmptySave(false) },
                title = { Text(stringResource(Res.string.empty_save_title)) },
                text = {
                    Text(
                        stringResource(Res.string.empty_save_body),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { vm.answerEmptySave(true) }) { Text(stringResource(Res.string.empty_save_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.answerEmptySave(false) }) { Text(stringResource(Res.string.common_cancel)) }
                },
            )
        }

        // Link bearbeiten (Web: link-edit-btn ✎ → openAddLinkDialog(elem))
        vm.editingLinkId?.let { linkId ->
            val linkElem = album.elements.find { it.id == linkId }
            if (linkElem == null) {
                vm.editingLinkId = null
            } else {
                LinkDialog(
                    dialogTitle = stringResource(Res.string.link_edit_title),
                    initialUrl = linkElem.url.orEmpty(),
                    initialTitle = linkElem.title.orEmpty(),
                    initialNote = linkElem.note.orEmpty(),
                    submitLabel = stringResource(Res.string.common_save),
                    onDismiss = { vm.editingLinkId = null },
                    onSubmit = { url, title, note ->
                        vm.editingLinkId = null
                        vm.updateLink(linkId, url, title, note)
                    },
                )
            }
        }

        // Rueckfrage vor dem physischen Loeschen + Ergebnismeldung
        DeleteFileDialog(vm)
        LaunchedEffect(vm.deleteNotice) {
            vm.deleteNotice?.let {
                snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
                vm.clearDeleteNotice()
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .onGloballyPositioned { listBounds = it.boundsInWindow() },
        ) {
            // Web: .container width 95 %, max-width 1600px, zentriert
            val containerWidth = minOf(maxWidth.value * 0.95f, 1600f)
            // Web-Sichtbarkeitsregel: showLocked = editMode || meta.show_locked
            // — im Edit-Modus sind Gesperrte IMMER sichtbar (sonst waeren sie
            // nach dem Sperren unerreichbar), im View-Modus entscheidet meta.
            val blocks = remember(album, containerWidth, vm.isEditMode) {
                val visible =
                    if (vm.isEditMode) album.elements
                    else album.elements.filter { album.showLocked || !it.locked }
                buildBlocks(visible, containerWidth)
            }
            blockLastIds = blocks.map { block ->
                when (block) {
                    is Block.PhotoRow -> block.items.last().elem.id
                    is Block.SinglePhoto -> block.elem.id
                    is Block.Generic -> block.elem.id
                }
            }

            // Vorwaermer fuer Touren (27.09.2026, Vorgabe: „das ruckeln ist
            // dann jetzt einmalig??? Gibt es kein Vorwaermer?"). Die
            // LazyColumn baut eine Tour erst, wenn sie ins Bild kommt — und
            // laedt dann GPX und Foto-Nadeln genau waehrend des Scrollens.
            // Deshalb beim Oeffnen alle Touren nacheinander im Hintergrund
            // holen; das Element findet sie dann fertig in vm.tourDaten.
            // Einmal je Tour, Fehler zeigt das Element selbst an. Gerechnet
            // wird auf Default (tourHolen), nicht hier auf Main — sonst ANR
            // beim Scrollen (29.09.2026).
            val tourKontext = coil3.compose.LocalPlatformContext.current
            LaunchedEffect(album.summary, album.photos.size) {
                album.elements.filter { it.type == "tour" && it.file != null }.forEach { elem ->
                    try {
                        vm.tourHolen(tourSchluessel(album, elem)) {
                            ladeTour(vm, album, elem, imageLoader, tourKontext)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Das Element versucht es selbst noch einmal und zeigt den Fehler.
                    }
                }
            }

            // Sprungmarke aus der Suche: zum Block mit dem Treffer-Element
            // scrollen (Block-Keys tragen alle Element-ids).
            LaunchedEffect(blocks, vm.pendingScrollElementId) {
                val id = vm.pendingScrollElementId ?: return@LaunchedEffect
                if (blocks.isEmpty()) return@LaunchedEffect
                val idx = blocks.indexOfFirst { b ->
                    b.key.split('-', '+').contains(id)
                }
                if (idx >= 0) listState.scrollToItem(idx)
                vm.pendingScrollElementId = null
            }

            NoSystemOverscroll {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().rubberBandOverscroll(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (vm.isEditMode) {
                    item("meta-edit") {
                        Box(Modifier.width(containerWidth.dp)) { MetaEditRow(vm) }
                    }
                }
                items(blocks, key = { it.key }) { block ->
                    Box(Modifier.width(containerWidth.dp)) {
                        when (block) {
                            is Block.PhotoRow -> PhotoRowBlock(vm, block, imageLoader, drag, onRemove)
                            is Block.SinglePhoto -> SinglePhotoBlock(vm, block, imageLoader, drag, onRemove)
                            is Block.Generic -> GenericBlock(vm, block.elem, imageLoader, drag, onRemove)
                        }
                    }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
            }

            // Schnell-Scroller rechts wie im Zeitstrahl (29.10.2026: „die
            // Slider sind aber nicht da, das ist bei langen Alben besonders
            // ungeschickt"). Eine Beschriftung je Listen-Item, in derselben
            // Reihenfolge wie die LazyColumn: die Blase zeigt das Kapitel,
            // in dem man landet — die letzte Ueberschrift oder das letzte
            // Trenner-Label davor. Ohne Kapitel bleibt die Blase weg.
            val scrubLabels = remember(blocks, vm.isEditMode) {
                var kapitel = ""
                val proBlock = blocks.map { b ->
                    val e = (b as? Block.Generic)?.elem
                    val neu = when {
                        e == null -> null
                        e.type == "text" && e.style == "heading" -> e.text
                        e.type == "separator" -> e.label
                        else -> null
                    }?.lineSequence()?.firstOrNull()?.trim()?.take(40)
                    if (!neu.isNullOrBlank()) kapitel = neu
                    kapitel
                }
                (if (vm.isEditMode) listOf("") else emptyList()) + proBlock + listOf(kapitel)
            }
            Scrubber(listState, scrubLabels, MpdMuted, minItems = 15) { idx ->
                scope.launch { listState.scrollToItem(idx) }
            }

            DragOverlay(drag, vm.baseUrl, imageLoader)

            // album-primary-fab des Webs (02.09.2026: „mittig, dann
            // gelb"): 58er-Kreis unten MITTIG, blau mit Stift; im Edit-Modus
            // gold mit Haken. Umschalten speichert wie gehabt.
            if (!album.readOnly) {
                FloatingActionButton(
                    onClick = {
                        fabMenuOpen = false
                        vm.toggleEditMode()
                    },
                    shape = CircleShape,
                    containerColor = if (vm.isEditMode) MpdGold else MpdBlue,
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .size(58.dp),
                ) {
                    Icon(
                        if (vm.isEditMode) MpdIcons.Check else MpdIcons.Pencil,
                        stringResource(if (vm.isEditMode) Res.string.common_done else Res.string.fab_edit),
                        Modifier.size(24.dp),
                    )
                }
            }

            // Rueckgaengig / Wiederherstellen links und rechts neben dem
            // Haken, im Bearbeiten-Modus immer sichtbar (29.10.2026: „dann
            // erreicht man es ja gar nicht, wenn man unten ist"). Die Pfeile
            // in der Titelzeile oben scrollen mit dem Album weg.
            if (!album.readOnly && vm.isEditMode) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 27.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VerlaufKnopf("↶", stringResource(Res.string.undo_title), vm.canUndo) { vm.undo() }
                    Spacer(Modifier.width(58.dp + 28.dp))
                    VerlaufKnopf("↷", stringResource(Res.string.redo_title), vm.canRedo) { vm.redo() }
                }
            }

            // fab-backdrop: Tipp neben das offene Menue schliesst es.
            if (fabMenuOpen) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x33000000))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { fabMenuOpen = false },
                )
            }
        }
    }
}

// -- Fotos ------------------------------------------------------------------

@Composable
private fun PhotoRowBlock(
    vm: AppViewModel,
    row: Block.PhotoRow,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = GAP.dp),
        horizontalArrangement = Arrangement.spacedBy(GAP.dp, Alignment.CenterHorizontally),
    ) {
        row.items.forEach { item ->
            DragTileBox(
                drag, item.elem,
                enabled = vm.isEditMode,
                horizontal = true,
                onDrop = vm::moveElement,
                modifier = Modifier.width(item.widthDp.dp).height(row.heightDp.dp),
            ) {
                PhotoThumb(vm, item.elem, imageLoader, Modifier.fillMaxSize(), useXl = false)
                EditElementButtons(vm, item.elem, onRemove)
            }
        }
    }
}

@Composable
private fun SinglePhotoBlock(
    vm: AppViewModel,
    block: Block.SinglePhoto,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    val heightMod = block.heightDp?.let { Modifier.height(it.dp) } ?: Modifier
    DragTileBox(
        drag, block.elem,
        enabled = vm.isEditMode,
        horizontal = true,
        onDrop = vm::moveElement,
        modifier = Modifier.fillMaxWidth().then(heightMod).padding(bottom = GAP.dp),
    ) {
        PhotoThumb(vm, block.elem, imageLoader, Modifier.fillMaxSize(), useXl = true)
        EditElementButtons(vm, block.elem, onRemove)
    }
}

@Composable
private fun PhotoThumb(
    vm: AppViewModel,
    elem: PdxElement,
    imageLoader: ImageLoader,
    modifier: Modifier,
    useXl: Boolean,
) {
    val thumbs = elem.thumbnails ?: return
    val url = vm.baseUrl + if (useXl) thumbs.xl else thumbs.m
    val isVideo = elem.type == "video"

    Box(
        modifier = modifier
            .background(Color.Black)
            // Im Edit-Modus oeffnet der Foto-Tipp nicht die Lightbox —
            // dort liegt seit 07.09.2026 das Kachelmenue darauf
            // (EditElementButtons). Im Web tut der Tipp dort gar nichts;
            // dass er hier etwas tut, ist eine Android-Zutat.
            .clickable(enabled = !vm.isEditMode) {
                if (isVideo) {
                    vm.openVideo(elem)
                } else {
                    vm.photoIndexOf(elem)?.let { vm.openLightbox(it) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            imageLoader = imageLoader,
            contentDescription = elem.file,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MpdIcons.Play, stringResource(Res.string.video), Modifier.size(26.dp), tint = Color.White)
            }
        }
        // Aufnahmedatum-Badge im Edit-Modus (Web photo-date-badge,
        // _dateFromFilename: nur ISO-Reihenfolge am Namensanfang).
        if (vm.isEditMode) {
            dateFromFilename(elem.file)?.let { capture ->
                // Unten links (seit 08.09.2026 — unten rechts sitzt wieder
                // der Papierkorb, Entscheidung; das Web hat das
                // Datum unten rechts, edit-photo.css:106). Bewusst anders
                // gesetzt als die Knoepfe: Schreibmaschinenschrift,
                // gedaempft, kein Kreis. Es ist Auskunft, kein Knopf.
                Text(
                    capture,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Port von _dateFromFilename (album-render.js): lieber kein Badge als ein
 *  falsches — nur JJJJ-MM-TT(-hh-mm) am Namensanfang, optional Kamera-Praefix. */
private val DATE_RE = Regex(
    """^(?:(?:IMG|VID|PXL|DSC|MVIMG|Screenshot|WhatsApp)[-_ ]?)?(\d{4})[-_]?(\d{2})[-_]?(\d{2})(?:[-_T ]?(\d{2})[-_:]?(\d{2}))?""",
)

private fun dateFromFilename(fname: String?): String? {
    val m = DATE_RE.find(fname ?: return null) ?: return null
    val (y, mo, d, h, mi) = m.destructured
    val yn = y.toInt()
    if (yn < 1990 || yn > 2100) return null
    if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
    val base = "$d.$mo.$y"
    return if (h.isNotEmpty() && mi.isNotEmpty()) "$base $h:$mi" else base
}

// -- Uebrige Elementtypen ---------------------------------------------------

@Composable
private fun GenericBlock(
    vm: AppViewModel,
    elem: PdxElement,
    imageLoader: ImageLoader,
    drag: AlbumDragState,
    onRemove: (PdxElement) -> Unit,
) {
    DragTileBox(
        drag, elem,
        // Offener Editor bleibt tippbar — kein Long-Press-Drag auf ihm.
        enabled = vm.isEditMode && vm.editingElementId != elem.id,
        // Bewusste Abweichung vom Web: dort bekommen auch volle-Breite-
        // Elemente (Trenner, Audio …) den Links/Rechts-Indikator der Fotos;
        // hier zeigen alle Nicht-Foto-Elemente darueber/darunter — bei
        // voller Breite ist „links von der Mitte = davor" nicht zu treffen.
        horizontal = false,
        onDrop = vm::moveElement,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            elem.error != null -> MissingBlock(elem)
            elem.type == "text" -> TextBlock(vm, elem)
            elem.type == "separator" -> SeparatorBlock(vm, elem)
            elem.type == "link" -> LinkBlock(vm, elem)
            elem.type == "document" -> DocumentBlock(vm, elem, imageLoader)
            elem.type == "audio" -> AudioElementBlock(vm, elem)
            elem.type == "map" -> MapElementBlock(elem)
            elem.type == "tour" -> TourElementBlock(vm, elem, imageLoader)
        }
        EditElementButtons(vm, elem, onRemove)
    }
}

@Composable
private fun MissingBlock(elem: PdxElement) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, MpdBorder, RoundedCornerShape(4.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("${elem.file ?: elem.type}: ${elem.error}", color = MpdRed, fontSize = 14.sp)
    }
}

@Composable
private fun TextBlock(vm: AppViewModel, elem: PdxElement) {
    // In Zeichen-Lambdas (drawBehind) sind Composable-Reads nicht erlaubt →
    // Theme-Farben hier in den Composable-Scope hochziehen.
    val accent = MpdBlue
    val borderColor = MpdBorder
    if (vm.isEditMode && vm.editingElementId == elem.id) {
        TextBlockEditor(vm, elem)
        return
    }
    if (vm.isEditMode) {
        // Editierbar markiert (gestrichelte Kontur wie .editable im Web);
        // Tipp oeffnet den Editor. Leere Texte bekommen einen Platzhalter,
        // sonst gaebe es nichts zum Antippen.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .drawBehind {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.5f),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    )
                }
                .clickable { vm.startTextEdit(elem.id) },
        ) {
            if (elem.text.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.text_placeholder), color = MpdMuted, fontSize = 16.sp, fontStyle = FontStyle.Italic)
                }
            } else {
                TextBlockContent(elem, showSource = false)
            }
            // Stilwahl (Web: style-select-btn) — unten rechts, unter dem ×
            TextStyleButton(vm, elem, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        return
    }
    TextBlockContent(elem)
}

@Composable
private fun TextBlockContent(elem: PdxElement, showSource: Boolean = true) {
    // Markdown-light (v1.5) nur in der Ansicht — der Editor bleibt Rohtext.
    val text = mdLight(elem.text.orEmpty())
    val borderColor = MpdBorder // fuer drawBehind (kein Composable-Read im Zeichen-Lambda)
    when (elem.style) {
        "info" -> StyledTextBox(
            text,
            borderStart = 3.dp to MpdBlue,
            background = MpdBluePale,
            color = MpdText,
        )

        "quote" -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .drawBehind {
                    // Web: border-top + border-bottom 1px var(--border)
                    drawLine(borderColor, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text("“", color = MpdMuted, fontSize = 44.sp)
            Text(
                text,
                color = MpdText,
                fontSize = 18.sp,
                lineHeight = 30.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // Quellenangabe (v1.5) — Web: „— Quelle" klein unterm Zitat,
            // nur in der Ansicht
            if (showSource && !elem.source.isNullOrBlank()) {
                Text(
                    stringResource(Res.string.quote_source, elem.source),
                    color = MpdMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        "note" -> StyledTextBox(
            text,
            borderStart = 4.dp to MpdGold,
            background = MpdGoldBg,
            color = MpdGoldLt,
        )

        // heading (v1.5): Kapitel-Ueberschrift in Gold, linksbuendig
        // (Web: .text-style-heading, 1.7rem/700)
        "heading" -> Text(
            text,
            color = MpdGold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 4.dp),
        )

        // default: zentriert, lesefreundlich bis 1200
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = MpdText,
                fontSize = 16.sp,
                lineHeight = 29.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StyledTextBox(
    text: androidx.compose.ui.text.AnnotatedString,
    borderStart: Pair<androidx.compose.ui.unit.Dp, Color>,
    background: Color,
    color: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .height(IntrinsicSize.Min)
            .background(background, RoundedCornerShape(2.dp)),
    ) {
        // Web: border-left in Akzentfarbe
        Box(
            Modifier
                .width(borderStart.first)
                .fillMaxHeight()
                .background(borderStart.second),
        )
        Text(
            text,
            color = color,
            fontSize = 16.sp,
            lineHeight = 29.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun SeparatorBlock(vm: AppViewModel, elem: PdxElement) {
    if (vm.isEditMode) {
        SeparatorEditRow(vm, elem)
        return
    }
    val style = elem.style ?: "line"
    if (style == "space") {
        Spacer(Modifier.height(40.dp))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeparatorLine(style, Modifier.weight(1f))
        if (style == "dots" && elem.label == null) {
            Text("· · ·", color = MpdBlue.copy(alpha = 0.55f), fontSize = 20.sp, letterSpacing = 8.sp)
        }
        elem.label?.let {
            Text(
                it.uppercase(),
                color = MpdMuted,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        SeparatorLine(style, Modifier.weight(1f))
    }
}

@Composable
private fun LinkBlock(vm: AppViewModel, elem: PdxElement) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val host = remember(elem.url) {
        runCatching { java.net.URI(elem.url).host?.removePrefix("www.") }.getOrNull() ?: elem.url.orEmpty()
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(23.dp))
                .background(MpdSurface)
                .border(1.dp, MpdBorder, RoundedCornerShape(23.dp))
                .clickable {
                    if (vm.isEditMode) {
                        // Web: link-edit-btn ✎ — hier oeffnet der Chip selbst
                        // den Bearbeiten-Dialog, im Edit-Modus browst niemand.
                        vm.editingLinkId = elem.id
                    } else {
                        elem.url?.let {
                            runCatching { uriHandler.openUri(it) }
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MpdIcons.Link, null, Modifier.padding(end = 10.dp).size(18.dp), tint = MpdText)
            Column {
                Text(elem.title ?: host, color = MpdText, fontSize = 15.sp)
                if (elem.title != null) Text(host, color = MpdMuted, fontSize = 12.sp)
            }
        }
        elem.note?.let {
            Text(it, color = MpdMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun DocumentBlock(vm: AppViewModel, elem: PdxElement, imageLoader: ImageLoader) {
    val datei = elem.file?.lowercase().orEmpty()
    val isPdf = datei.endsWith(".pdf")
    // TEXTDATEIEN SEIT PDX 1.5.1 (Server, 15.09.2026): document.file darf
    // jetzt auch .txt und .md sein, ausgeliefert als text/plain.
    //
    // Die App hat sie NICHT uebersprungen, wie die Vorwaertskompatibilitaet
    // es vorsieht — sie fielen in den Zweig „kein Vorschaubild", bekamen
    // die PDF-Karte mit „Tippen zum Oeffnen" und landeten im PDF-Betrachter,
    // der an einer Textdatei mit einer Fehlermeldung abbricht. Ein Weg, der
    // ins Leere fuehrt, ist schlechter als kein Weg.
    //
    // Bis die Klappzeile gebaut ist (Web zeigt den Text aufklappbar, aus
    // der Datei gelesen, Deckel 100 KB), steht hier eine ehrliche Karte
    // ohne Tipp-Moeglichkeit.
    val isText = datei.endsWith(".txt") || datei.endsWith(".md")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Seit PDX 1.5.1 steht der Titel IM Element: in der PDF-Karte an
        // Stelle des Dateinamens, bei einer Textdatei in der Klappzeile.
        // Nur Bild-Dokumente behalten ihn darueber.
        if (!isText && !isPdf && elem.thumbnails != null) {
            elem.title?.let {
                Text(it, color = MpdText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        if (isText) {
            TextDokument(vm, elem)
        } else if (isPdf || elem.thumbnails == null) {
            // PDF-Karte wie im Web: Icon + Name + Tipp-Hinweis, oeffnet den Reader
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MpdSurface)
                    .border(1.dp, MpdBorder, RoundedCornerShape(8.dp))
                    .clickable { vm.openPdf(elem) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MpdIcons.Document, null, Modifier.padding(end = 12.dp).size(26.dp), tint = MpdText)
                Column {
                    // PDX 1.5.1: der Titel steht IN der Karte, an Stelle des
                    // Dateinamens — nicht mehr darueber.
                    Text(
                        elem.title?.takeIf { it.isNotBlank() } ?: elem.file.orEmpty(),
                        color = MpdText, fontSize = 15.sp,
                    )
                    Text(stringResource(Res.string.document_tap_open), color = MpdMuted, fontSize = 12.sp)
                }
            }
        } else {
            // Bild-Dokumente stehen wie im Web direkt im Album
            AsyncImage(
                model = vm.baseUrl + elem.thumbnails.xl,
                imageLoader = imageLoader,
                contentDescription = elem.title ?: elem.file,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// -- FAB-Menue + Einfuege-Dialoge -------------------------------------------

@Composable
private fun PlaceholderCard(icon: String, title: String, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MpdSurface)
            .border(1.dp, MpdBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text(title, color = MpdText, fontSize = 15.sp)
            Text(hint, color = MpdMuted, fontSize = 12.sp)
        }
    }
}

/**
 * Textdatei im Album (PDX 1.5.1, Server 15.09.2026): zusammengeklappt eine
 * Zeile mit dem Titel, aufgeklappt der Text in Schreibmaschinenschrift.
 *
 * Drei Regeln von der Server-Seite, jede mit einem Grund:
 *
 *  - Der Inhalt wird AUS DER DATEI gelesen, nie in die album.json kopiert.
 *    Sonst gaebe es zwei Wahrheiten, und wer die Datei am Rechner aendert,
 *    saehe es im Album nicht.
 *  - Geholt wird ERST BEIM AUFKLAPPEN. Ein Album mit zehn Textdateien
 *    machte sonst zehn Abrufe, nur um geoeffnet zu werden.
 *  - Deckel 100 KB in der Albumansicht. Wer mehr sehen will, oeffnet die
 *    Datei im Browser; ein Album ist keine Textverarbeitung.
 *
 * Fehlt der `title`, steht hier eine ABGELEITETE Beschriftung: die erste
 * nicht-leere Zeile, sonst der Dateiname. Sie wird NICHT gespeichert —
 * genau davor warnt der Server-Eintrag. Wer sie als echten Titel
 * zurueckschriebe, gaebe jeder angefassten Datei einen Titel, den niemand
 * vergeben hat. Die App hat ohnehin keinen Weg, einem Dokument einen Titel
 * zu geben; das bleibt beim naechsten Ausbau zu beachten.
 */
@Composable
private fun TextDokument(vm: AppViewModel, elem: PdxElement) {
    var offen by remember(elem.id) { mutableStateOf(false) }
    var inhalt by remember(elem.id) { mutableStateOf<String?>(null) }
    var kopfzeile by remember(elem.id) { mutableStateOf<String?>(null) }
    var fehler by remember(elem.id) { mutableStateOf<String?>(null) }

    val adresse = elem.documentUrl?.let { vm.baseUrl + it }
        ?: vm.mediaUrl("document", elem)

    // DIE ERSTE ZEILE IST DIE BESCHRIFTUNG, der Dateiname nur der letzte
    // Ausweg (16.09.2026). Zugeklappt kannte die App den Inhalt
    // aber noch nicht — geholt wird er erst beim Aufklappen —, also stand
    // dort immer der Dateiname. Bei „tag1_1992-06-27.txt" sagt der nichts.
    //
    // Deshalb hier ein winziger Vorgriff: die ersten 512 Byte per Range.
    // Das widerspricht der Regel nicht, die den VOLLEN Text meint; ein
    // halbes Kilobyte kostet im LAN nichts. Nur wenn gar kein `title`
    // gesetzt ist, sonst ist die Zeile ohnehin unnoetig.
    LaunchedEffect(elem.id, adresse) {
        if (!elem.title.isNullOrBlank() || adresse == null) return@LaunchedEffect
        kopfzeile = runCatching { vm.api.fetchTextHead(adresse) }.getOrNull()
    }

    LaunchedEffect(offen, elem.id) {
        if (!offen || inhalt != null || fehler != null) return@LaunchedEffect
        // DEN RUECKFALL NICHT VERGESSEN (16.09.2026). Erste Fassung nahm
        // nur `document_url` — und das Feld steht NICHT in der album.json,
        // der Server setzt es beim Ausliefern. Fehlt es, stand hier sofort
        // „Der Text liess sich nicht laden", ohne dass je ein Abruf lief.
        // `openPdf` hat denselben Rueckfall seit langem: die Adresse aus
        // Bestand, Album und Dateinamen selbst bauen. Genau die Sorte
        // Fehler, die ich anderen gerade nachgewiesen habe — ein Weg, der
        // still ins Leere fuehrt.
        val url = adresse ?: run {
            fehler = Texte.t(Res.string.document_text_failed)
            return@LaunchedEffect
        }
        try {
            val roh = vm.api.fetchText(url)
            inhalt = if (roh.length > TEXT_DECKEL) {
                roh.take(TEXT_DECKEL) + "\n\n" + Texte.t(Res.string.document_text_cut)
            } else roh
        } catch (e: Exception) {
            fehler = e.message?.takeIf { it.isNotBlank() }
                ?: Texte.t(Res.string.document_text_failed_reason, e.javaClass.simpleName)
        }
    }

    // Reihenfolge: gesetzter Titel, sonst die erste nicht-leere Zeile,
    // und erst wenn beides fehlt der Dateiname.
    val beschriftung = elem.title?.takeIf { it.isNotBlank() }
        ?: (inhalt ?: kopfzeile)
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.removePrefix("#")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        ?: elem.file.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MpdSurface)
            .border(1.dp, MpdBorder, RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { offen = !offen }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MpdIcons.Document, null, Modifier.padding(end = 12.dp).size(22.dp), tint = MpdText)
            Text(
                beschriftung,
                color = MpdText, fontSize = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (offen) "▾" else "▸", color = MpdMuted, fontSize = 14.sp)
        }
        if (offen) {
            when {
                fehler != null -> Text(
                    fehler!!, color = MpdRed, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
                inhalt == null -> Box(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp)) }
                else -> Text(
                    // Markdown wird leicht ausgezeichnet — derselbe Darsteller
                    // wie bei den Tagebuchtexten (fett, kursiv, Code).
                    if (elem.file?.lowercase()?.endsWith(".md") == true) mdLight(inhalt!!)
                    else androidx.compose.ui.text.AnnotatedString(inhalt!!),
                    color = MpdText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** 100 KB wie im Web — ein Album ist keine Textverarbeitung. */
private const val TEXT_DECKEL = 100 * 1024

/** Runder Knopf fuer Rueckgaengig/Wiederherstellen neben dem Haken-FAB. */
@Composable
private fun VerlaufKnopf(zeichen: String, beschreibung: String, aktiv: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(Mpd.colors.surface)
            .clickable(enabled = aktiv, onClick = onClick)
            .semantics { contentDescription = beschreibung },
        contentAlignment = Alignment.Center,
    ) {
        Text(zeichen, fontSize = 22.sp, color = if (aktiv) Mpd.colors.text else Mpd.colors.muted)
    }
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import kotlinx.coroutines.launch
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.OpenAlbum
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.pluralStringResource
import de.bgghome.mpd.addFolderFile
import de.bgghome.mpd.addLink
import de.bgghome.mpd.addMap
import de.bgghome.mpd.addMediaElement
import de.bgghome.mpd.addPhotoFromOtherAlbum
import de.bgghome.mpd.addSeparator
import de.bgghome.mpd.addTextElement
import de.bgghome.mpd.addTour
import de.bgghome.mpd.answerEmptySave
import de.bgghome.mpd.clearDeleteNotice
import de.bgghome.mpd.closeAlbum
import de.bgghome.mpd.frischenStandHolen
import de.bgghome.mpd.meldung
import de.bgghome.mpd.moduleAllowed
import de.bgghome.mpd.redo
import de.bgghome.mpd.removeElement
import de.bgghome.mpd.resolveConflict
import de.bgghome.mpd.toggleEditMode
import de.bgghome.mpd.toggleShowLocked
import de.bgghome.mpd.tourHolen
import de.bgghome.mpd.undo
import de.bgghome.mpd.undoRemove
import de.bgghome.mpd.updateLink

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

/** Runder Knopf fuer Rueckgaengig/Wiederherstellen neben dem Haken-FAB. */
@Composable
internal fun VerlaufKnopf(zeichen: String, beschreibung: String, aktiv: Boolean, onClick: () -> Unit) {
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

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import org.jetbrains.compose.resources.StringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.bgghome.mpd.state.Texte
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.SaveState
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.canCurate
import de.bgghome.mpd.cancelDeleteFile
import de.bgghome.mpd.closeTextEdit
import de.bgghome.mpd.commitTextEdit
import de.bgghome.mpd.meldung
import de.bgghome.mpd.performDeleteFile
import de.bgghome.mpd.redo
import de.bgghome.mpd.requestDeleteFile
import de.bgghome.mpd.saveNow
import de.bgghome.mpd.setAlbumTitle
import de.bgghome.mpd.setAlbumYear
import de.bgghome.mpd.setQuoteSource
import de.bgghome.mpd.setSeparatorLabel
import de.bgghome.mpd.setSeparatorStyle
import de.bgghome.mpd.setTextStyle
import de.bgghome.mpd.toggleLocked
import de.bgghome.mpd.undo

/*
 * Edit-Bausteine der Albumgestaltung — Web-Referenzen: album-edit.js
 * (Texte, Stile, Schloss), album-ui.js (Dialoge, FAB-Menue),
 * album-insert.js (Entfernen/Loeschen), edit-photo.css (Optik).
 */

// -- Speicherstatus ----------------------------------------------------------

/** Speicherstatus in der Kopfzeile — Wortlaut wie updateSaveIndicator im Web. */
@Composable
internal fun SaveStatusIndicator(vm: AppViewModel) {
    val (label, color) = when (vm.saveState) {
        SaveState.Saving -> stringResource(Res.string.save_saving) to MpdMuted
        SaveState.Saved -> stringResource(Res.string.save_saved) to MpdGreen
        SaveState.Unsaved -> stringResource(Res.string.save_unsaved) to MpdGoldLt
        SaveState.Error -> stringResource(Res.string.save_error) to MpdRed
        SaveState.Idle -> return
    }
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable(enabled = vm.saveState == SaveState.Error) {
                // Fehlertext zeigen und erneut versuchen (Web: mpdConfirm-Dialog)
                vm.saveError?.let { vm.meldung(it) }
                vm.saveNow()
            },
    )
}

// -- Knoepfe auf den Kacheln -------------------------------------------------

@Composable
private fun TileButton(
    alignment: Alignment,
    boxScope: BoxScope,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) = with(boxScope) {
    Box(
        Modifier
            .align(alignment)
            .padding(6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(0xB3000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Drei Knoepfe auf jeder Foto-Kachel im Edit-Modus: Schloss oben links,
 * Entfernen-× oben rechts, Papierkorb unten rechts (Entscheidung,
 * 08.09.2026, siehe Kommentar unten). Dazu das Tipp-Menue (siehe
 * [TileMenu]) mit denselben Handlungen in Worten.
 *
 * Vorgeschichte: Umgestellt am 07.09.2026 auf nur zwei Knoepfe an den
 * Web-Positionen, weil Thomas fragte, ob in jeder Ecke ein Symbol sein
 * muss. Der Befund war dreiteilig:
 *
 *  - Es waren VIER Ecken belegt: Schloss, ×, Papierkorb und das
 *    Aufnahmedatum. Am Handy ist eine Kachel rund 198 × 150 dp gross
 *    (zwei Spalten, siehe buildBlocks), jede Ecke 28 dp plus Rand — gut
 *    ein Fuenftel der Flaeche lag auf Knoepfen, und zwar auf dem Rahmen
 *    des Motivs. Am Tablet sind dieselben vier Ecken unter 3 %.
 *  - Keine der vier Positionen war die des Webs (edit-core.css:173-186,
 *    edit-photo.css:106): dort liegen × und Papierkorb nebeneinander oben
 *    links, das Schloss oben rechts, das Datum unten rechts. Die
 *    Abweichung war keine Entscheidung — beide Positionen standen
 *    unveraendert seit dem Erststand eacacdb im Code.
 *  - × (aus dem Album nehmen, folgenlos) und Papierkorb (Datei wegwerfen)
 *    sahen gleich gross und gleich wichtig aus. Der Alltagshandgriff und
 *    die seltene, folgenreiche Handlung duerfen nicht gleich aussehen.
 *
 * Das Schloss bleibt sichtbar, obwohl es selten benutzt wird (4 von 44
 * Alben): es zeigt einen ZUSTAND. Ein Knopf, den man nur druecken kann,
 * darf ins Menue; eine Markierung, die man SEHEN muss, nicht.
 */
@Composable
internal fun BoxScope.EditElementButtons(
    vm: AppViewModel,
    elem: PdxElement,
    onRemove: (PdxElement) -> Unit,
) {
    if (!vm.isEditMode) return
    var menuOpen by remember(elem.id) { mutableStateOf(false) }

    // Tippflaeche fuer das Menue — nur auf Medienkacheln. Bei Text, Karte,
    // Tour und Dokument hat der Tipp im Edit-Modus schon eine Aufgabe
    // (Editor oeffnen, Karte schieben); dort waere diese Flaeche ein
    // Deckel darueber. Zuerst deklariert, damit die Knoepfe darueber
    // liegen und ihren eigenen Tipp behalten.
    //
    // Tippen und nicht langes Druecken: langes Druecken IST im Edit-Modus
    // das Verschieben (detectDragGesturesAfterLongPress, AlbumDrag.kt).
    // Der Tipp war frei — er tat hier bisher nichts, genau wie im Web.
    val hatMenue = elem.istDatei
    if (hatMenue) {
        Box(Modifier.matchParentSize().clickable { menuOpen = true })
        TileMenu(vm, elem, menuOpen) { menuOpen = false }
    }

    // Anordnung: Entscheidung vom 08.09.2026 — „links oben Schloss,
    // rechts oben × und rechts unten Muelleimer ist doch besser?" Ja.
    // Das ist die App-Anordnung von vor dem 07.09. (das Web hat × und
    // Papierkorb nebeneinander oben links); der Papierkorb kommt zurueck
    // auf die Kachel, weil er im Tipp-Menue nicht gefunden wurde. Gegen
    // ein Versehen schuetzt nicht das Verstecken, sondern die Rueckfrage
    // („Datei loeschen?", mit Papierkorb-Stand der Freigabe) und dahinter
    // der DSM-Papierkorb. Das Aufnahmedatum rueckt dafuer nach unten links.
    TileButton(Alignment.TopStart, this, onClick = { vm.toggleLocked(elem.id) }) {
        Icon(
            if (elem.locked) MpdIcons.LockClosed else MpdIcons.LockOpen,
            stringResource(if (elem.locked) Res.string.lock_private else Res.string.lock_public),
            Modifier.size(15.dp),
            tint = if (elem.locked) MpdGoldLt else Color.White,
        )
    }
    TileButton(Alignment.TopEnd, this, onClick = { onRemove(elem) }) {
        Icon(MpdIcons.Cross, stringResource(Res.string.edit_remove), Modifier.size(14.dp), tint = Color.White)
    }
    // Physisch loeschen: dieselbe Bedingung wie der Menuepunkt — Foto
    // und Video, nur Dateien des eigenen Albumordners.
    // Tour-Kachel (13.11.2026): das × nimmt nur das Element heraus (ohne
    // Rueckfrage, die GPX bleibt), der Papierkorb legt Element UND Datei
    // weg — mit Rueckfrage. Nur im Familienbestand (dort liegen Touren) und
    // nur fuer Dateien dieses Albumordners; der Server sperrt sonst.
    val tourLoeschbar = elem.type == "tour" && elem.file != null && elem.sourceAlbum == null &&
        vm.openAlbum?.summary?.space == "shared" && vm.canCurate("shared")
    if (tourLoeschbar) {
        TileButton(Alignment.BottomEnd, this, onClick = { vm.requestDeleteFile(elem) }) {
            Icon(MpdIcons.Trash, stringResource(Res.string.edit_delete_tour), Modifier.size(15.dp), tint = MpdRed)
        }
    }
    if (elem.istDatei && elem.error == null && elem.sourceAlbum == null) {
        TileButton(Alignment.BottomEnd, this, onClick = { vm.requestDeleteFile(elem) }) {
            Icon(MpdIcons.Trash, stringResource(Res.string.edit_delete_file), Modifier.size(15.dp), tint = MpdRed)
        }
    }
}

/**
 * Das Menue hinter dem Tipp auf eine Medienkachel. Hier steht, was selten
 * gebraucht wird und einen Namen verdient statt eines Symbols.
 *
 * Das Schloss steht trotz seines Knopfes oben rechts hier nochmal drin:
 * das Symbol zeigt den Zustand, der Menuepunkt sagt, was passiert.
 */
@Composable
private fun TileMenu(
    vm: AppViewModel,
    elem: PdxElement,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (elem.locked) Res.string.menu_unlock else Res.string.menu_lock)) },
            leadingIcon = {
                Icon(
                    if (elem.locked) MpdIcons.LockClosed else MpdIcons.LockOpen,
                    null,
                    Modifier.size(18.dp),
                    tint = if (elem.locked) MpdGoldLt else MpdMuted,
                )
            },
            onClick = { vm.toggleLocked(elem.id); onDismiss() },
        )
        // Physisch loeschen: Foto UND Video (05.11.2026: „Videos haben
        // keine Loeschfunktion"). Das Web bietet den Papierkorb nur bei
        // type=photo; der Server nimmt seit 09/2026 beide Endungen, der
        // Zeitstrahl loescht Videos laengst. Bewusste Abweichung vom Web.
        // Weiterhin nur Dateien des eigenen Albumordners — der Endpunkt
        // loest den Namen im aktuellen Album auf, source_album-Referenzen
        // wuerden ins Leere gehen.
        when {
            elem.istDatei && elem.error == null && elem.sourceAlbum == null ->
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.menu_delete_file), color = MpdRed) },
                    leadingIcon = {
                        Icon(MpdIcons.Trash, null, Modifier.size(18.dp), tint = MpdRed)
                    },
                    onClick = { vm.requestDeleteFile(elem); onDismiss() },
                )
            // Warum der Punkt fehlt, statt ihn wortlos wegzulassen: ein
            // fehlender Menuepunkt sieht aus wie ein Fehler. Ein Satz, der
            // den Grund nennt, ist billiger als die Frage danach.
            elem.istDatei && elem.sourceAlbum != null ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(Res.string.menu_lives_in_folder, elem.sourceAlbum),
                            color = MpdMuted, fontSize = 13.sp,
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
        }
    }
}

// -- Textelement -------------------------------------------------------------

/**
 * Inline-Editor eines Textelements. Aenderungen werden beim Schliessen (OK,
 * Tipp auf "Fertig", Zurueck) ins Modell uebernommen — commitTextEdit trimmt
 * und stoesst den Autosave an, wie blur→handleTextChange im Web.
 */
@Composable
internal fun TextBlockEditor(vm: AppViewModel, elem: PdxElement) {
    var value by remember(elem.id) { mutableStateOf(TextFieldValue(elem.text.orEmpty())) }
    var emojiOpen by remember(elem.id) { mutableStateOf(false) }
    var source by remember(elem.id) { mutableStateOf(elem.source.orEmpty()) }
    val focusRequester = remember { FocusRequester() }
    // Der Editor traegt den gewaehlten PDX-Stil (05.09.: „im
    // Bearbeiten-Modus fehlen die PDX-Schriftstile"). Vorher sah jeder
    // Block gleich aus — man schrieb eine Ueberschrift und sah Fliesstext,
    // der Unterschied zeigte sich erst nach dem Schliessen. Das Web
    // behaelt die text-style-Klasse am editierbaren Feld, wir machen es
    // jetzt genauso.
    val style = elem.style ?: "default"
    val accent = when (style) {
        "info" -> MpdBlue
        "note", "heading" -> MpdGold
        else -> MpdBorder
    }
    val container = when (style) {
        "info" -> MpdBluePale
        "note" -> MpdGoldBg
        else -> Color.Transparent
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            textStyle = editorTextStyle(style),
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.6f),
                focusedContainerColor = container,
                unfocusedContainerColor = container,
                cursorColor = MpdBlue,
            ),
        )
        // Quellenangabe nur am Zitat (PDX v1.5)
        if (style == "quote") {
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.text_source_label)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                textStyle = TextStyle(color = MpdText, fontSize = 14.sp),
            )
        }
        // Stilwahl, Hinweis und OK in EINER Zeile.
        //
        // Der Stil-Knopf war hier bisher GAR NICHT — TextBlock kehrt beim
        // Oeffnen des Editors vorher zurueck, damit verschwand die
        // Stilwahl genau dann, wenn man sie braucht (05.09.: „im
        // Bearbeiten-Modus fehlen die PDX-Schriftstile"). Im Web bleibt
        // der Knopf am Block, auch waehrend getippt wird.
        //
        // Der Hinweis lag vorher als supportingText am Textfeld und schob
        // OK eine Zeile tiefer — daher „das OK haengt etwas tief".
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextStyleButton(vm, elem)
            Spacer(Modifier.width(8.dp))
            // Emoji-Picker wie im Web (toggleEmojiPicker): Xfce hat keinen im
            // System (22.09.2026: „glaube ich nicht!"). Einfuegen an
            // der Cursorposition.
            Box {
                TextButton(onClick = { emojiOpen = true }) { Text("😀", fontSize = 16.sp) }
                androidx.compose.material3.DropdownMenu(expanded = emojiOpen, onDismissRequest = { emojiOpen = false }) {
                    androidx.compose.foundation.layout.FlowRow(Modifier.width(320.dp).padding(6.dp)) {
                        EMOJI_SET.forEach { e ->
                            Text(
                                e, fontSize = 22.sp,
                                modifier = Modifier.padding(2.dp).clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val sel = value.selection
                                        val neu = value.text.replaceRange(sel.start, sel.end, e)
                                        value = TextFieldValue(neu, TextRange(sel.start + e.length))
                                        emojiOpen = false
                                    }
                                    .padding(3.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // Markdown-light (v1.5): hier steht bewusst der Rohtext mit
            // Sternchen — gerendert wird nur in der Ansicht.
            Text(
                stringResource(Res.string.text_md_hint),
                color = MpdMuted, fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.closeTextEdit() }) {
                Text(stringResource(Res.string.common_ok), color = MpdBlue, fontSize = 15.sp)
            }
        }
    }
    LaunchedEffect(elem.id) { focusRequester.requestFocus() }
    // Uebernahme beim Verlassen der Composition — deckt OK, "Fertig",
    // Zurueck-Taste und Wechsel zu einem anderen Textblock gleichermassen ab.
    DisposableEffect(elem.id) {
        onDispose {
            vm.commitTextEdit(elem.id, value.text)
            vm.setQuoteSource(elem.id, source)
        }
    }
}

/**
 * Schrift des Editors je PDX-Stil — Spiegel von `TextBlockContent` in der
 * Albumansicht. Nicht das ganze Aussehen (Rahmenlinien des Zitats, die
 * Anfuehrungszeichen, die Einrueckung) — nur das, was beim Schreiben
 * zaehlt: Groesse, Gewicht, Farbe, Kursive.
 */
@Composable
private fun editorTextStyle(style: String): TextStyle = when (style) {
    "heading" -> TextStyle(
        color = MpdGold, fontSize = 24.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
    )
    "quote" -> TextStyle(
        color = MpdText, fontSize = 18.sp, lineHeight = 30.sp,
        fontStyle = FontStyle.Italic,
    )
    "note" -> TextStyle(color = MpdGoldLt, fontSize = 16.sp, lineHeight = 29.sp)
    "info" -> TextStyle(color = MpdText, fontSize = 16.sp, lineHeight = 29.sp)
    // Standard steht in der Ansicht zentriert (TextBlockContent), also auch
    // beim Schreiben — sonst sprang er beim Antippen nach links und beim
    // Schliessen zurueck („beim Bearbeiten von Text springt der zentrierte
    // Text immer nach linksbuendig").
    else -> TextStyle(color = MpdText, fontSize = 16.sp, lineHeight = 29.sp, textAlign = TextAlign.Center)
}

// Beschriftung wie im Web (styleEmoji + styleLabel in js/album-edit.js) —
// das Sinnbild macht den Knopf auf einen Blick lesbar.
private val TEXT_STYLES = listOf(
    Triple("default", "📝", Res.string.style_default), Triple("heading", "🔠", Res.string.style_heading),
    Triple("info", "ℹ️", Res.string.style_info), Triple("quote", "💬", Res.string.style_quote),
    Triple("note", "📌", Res.string.style_note),
)

/** „📝 Standard" — Sinnbild plus lokalisierter Name. */
@Composable
private fun styleLabel(t: Triple<String, String, StringResource>): String = t.second + " " + stringResource(t.third)

/** Stil-Knopf des Textblocks (Web: style-select-btn + openStyleDropdown). */
@Composable
internal fun TextStyleButton(vm: AppViewModel, elem: PdxElement, modifier: Modifier = Modifier) {
    var menuOpen by remember { mutableStateOf(false) }
    val current = elem.style ?: "default"
    Box(modifier) {
        Text(
            TEXT_STYLES.firstOrNull { it.first == current }?.let { styleLabel(it) } ?: stringResource(Res.string.style_default),
            color = MpdMuted,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MpdSurface)
                .border(1.dp, MpdBorder, RoundedCornerShape(12.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            TEXT_STYLES.forEach { t ->
                val key = t.first
                DropdownMenuItem(
                    text = { Text(styleLabel(t), color = if (key == current) MpdBlue else MpdText) },
                    onClick = {
                        menuOpen = false
                        vm.setTextStyle(elem.id, key)
                    },
                )
            }
        }
    }
}

// -- Album-Meta (Titel + Jahr) -----------------------------------------------

/**
 * Titel + Jahr im Edit-Modus, erste Zeile ueber den Elementen (Web:
 * enableMetaEdit ersetzt h1 + Jahres-Span durch Inputs). Uebernahme beim
 * Verlassen des Felds; leerer Titel faellt auf den alten zurueck, Jahr
 * nur 1800–2099.
 */
@Composable
internal fun MetaEditRow(vm: AppViewModel) {
    val album = vm.openAlbum ?: return
    var title by remember(album.summary.name) { mutableStateOf(album.title) }
    var year by remember(album.summary.name) {
        mutableStateOf(album.summary.year.takeIf { it > 0 }?.toString().orEmpty())
    }

    fun commit() {
        vm.setAlbumTitle(title)
        year.toIntOrNull()?.let { vm.setAlbumYear(it) }
    }

    DisposableEffect(album.summary.name) {
        onDispose { commit() }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            singleLine = true,
            label = { Text(stringResource(Res.string.meta_title)) },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = year,
            onValueChange = { v ->
                if (v.length <= 4 && v.all { it.isDigit() }) year = v
            },
            singleLine = true,
            label = { Text(stringResource(Res.string.meta_year)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(110.dp)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
        Spacer(Modifier.width(10.dp))
        // Cover-Wechsler wie im Web (thumbnail-change-btn): Bild-SYMBOL in
        // Knopfgroesse — der alte „Cover…"-Textknopf war nicht auffindbar
        // (04.09.).
        // Rueckgaengig / Wiederherstellen wie undo-btn/redo-btn im Web.
        UndoRedoButton("↶", stringResource(Res.string.undo_title), vm.canUndo) { vm.undo() }
        Spacer(Modifier.width(6.dp))
        UndoRedoButton("↷", stringResource(Res.string.redo_title), vm.canRedo) { vm.redo() }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .align(Alignment.CenterVertically)
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Mpd.colors.surface)
                .clickable { vm.coverPickerOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                MpdIcons.Image,
                contentDescription = stringResource(Res.string.meta_cover_change),
                modifier = Modifier.size(24.dp),
                tint = Mpd.colors.text,
            )
        }
    }
}

@Composable
private fun RowScope.UndoRedoButton(zeichen: String, beschreibung: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.CenterVertically)
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Mpd.colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = beschreibung },
        contentAlignment = Alignment.Center,
    ) {
        Text(zeichen, fontSize = 22.sp, color = if (enabled) Mpd.colors.text else Mpd.colors.muted)
    }
}

// -- Trenner -----------------------------------------------------------------

internal val SEP_STYLES = listOf(
    "line" to Res.string.sep_line, "bold" to Res.string.sep_bold, "dashed" to Res.string.sep_dashed,
    "dots" to Res.string.sep_dots, "space" to Res.string.sep_space,
)

internal fun sepStyleChar(style: String) = when (style) {
    "bold" -> "━"; "dashed" -> "╌"; "dots" -> "···"; "space" -> "↕"; else -> "—"
}

/**
 * Trenner im Edit-Modus — wie im Web: Label-Feld sitzt sichtbar zwischen den
 * Linien (separator-label-edit, max. 80 Zeichen, uebernommen beim Verlassen),
 * daneben der Stil-Knopf mit Auswahl (openSepStyleDropdown). Auch der
 * „space"-Trenner zeigt die Zeile, sonst waere er nicht anfassbar.
 */
@Composable
internal fun SeparatorEditRow(vm: AppViewModel, elem: PdxElement) {
    val style = elem.style ?: "line"
    var label by remember(elem.id) { mutableStateOf(elem.label.orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(elem.id) {
        onDispose { vm.setSeparatorLabel(elem.id, label) }
    }

    Row(
        // 40 dp Rand links/rechts: dort sitzen Schloss und ×-Knopf der Kachel.
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .padding(horizontal = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeparatorLine(style, Modifier.weight(1f))
        Box(Modifier.padding(horizontal = 10.dp)) {
            BasicTextField(
                value = label,
                onValueChange = { if (it.length <= 80) label = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MpdText,
                    fontSize = 13.sp,
                    letterSpacing = 1.5.sp,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MpdText),
                modifier = Modifier
                    .width(180.dp)
                    .background(MpdSurface, RoundedCornerShape(6.dp))
                    .border(1.dp, MpdBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .onFocusChanged { if (!it.isFocused) vm.setSeparatorLabel(elem.id, label) },
            )
            if (label.isEmpty()) {
                Text(
                    stringResource(Res.string.sep_label_placeholder),
                    color = MpdMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        SeparatorLine(style, Modifier.weight(1f))
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(sepStyleChar(style), color = MpdMuted, fontSize = 16.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SEP_STYLES.forEach { (key, nameRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${sepStyleChar(key)}  ${stringResource(nameRes)}",
                                color = if (key == style) MpdBlue else MpdText,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            vm.setSeparatorStyle(elem.id, key)
                        },
                    )
                }
            }
        }
    }
}

// -- FAB-Menue ---------------------------------------------------------------

/** Eintrag des Einfuege-Menues — Optik wie .fab-menu-item im Web
 *  (Pille 44 dp, Radius 22, header-bg #1e2d3d, Linien-Icon 20 dp). */
@Composable
internal fun FabMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(bottom = 7.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.55f }
            .shadow(6.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(MpdHeaderBg)
            .clickable(onClick = onClick)
            .height(44.dp)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MpdText)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MpdText, fontSize = 14.sp)
    }
}

// -- Einfuege- und Loesch-Dialoge --------------------------------------------

/** Web openSeparatorDialog: Label (optional, max. 80) + Stilwahl. */
@Composable
internal fun SeparatorInsertDialog(
    onDismiss: () -> Unit,
    onInsert: (label: String, style: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("line") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sep_insert_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= 80) label = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.sep_insert_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SEP_STYLES.forEach { (key, nameRes) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { style = key }
                            .background(if (key == style) MpdBluePale else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sepStyleChar(key),
                            color = if (key == style) MpdBlue else MpdMuted,
                            fontSize = 16.sp,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(stringResource(nameRes), color = if (key == style) MpdBlue else MpdText, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInsert(label, style) }) { Text(stringResource(Res.string.common_insert)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * Web openAddLinkDialog: URL (Pflicht, http/https), Titel + Notiz optional.
 * Mit Vorbelegung auch der Bearbeiten-Dialog (link-edit-btn ✎).
 */
@Composable
internal fun LinkDialog(
    dialogTitle: String,
    initialUrl: String = "",
    initialTitle: String = "",
    initialNote: String = "",
    submitLabel: String? = null,
    onDismiss: () -> Unit,
    onSubmit: (url: String, title: String, note: String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var title by remember { mutableStateOf(initialTitle) }
    var note by remember { mutableStateOf(initialNote) }
    // Serverseitig erlaubt _LINK_URL_RE nur http/https — hier dieselbe Huerde,
    // damit der Fehler nicht erst beim Autosave auffliegt.
    val urlOk = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { if (it.length <= 2048) url = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.link_url_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 120) title = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.link_title_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 200) note = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.link_note_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = urlOk, onClick = { onSubmit(url, title, note) }) {
                Text(submitLabel ?: stringResource(Res.string.common_insert))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * Rueckfrage vor dem physischen Loeschen (Web: deletePhotoFile/mpdConfirm) —
 * Hinweistext je nach #recycle-Status des Spaces.
 */
@Composable
internal fun DeleteFileDialog(vm: AppViewModel) {
    val elem = vm.pendingDelete ?: return
    // Wohin die Datei geht UND wie man sie zurueckholt. Vorgabe, 07.09.2026:
    // „Waere nicht pragmatischer Ansatz denkbar? Wiederherstellen ausserhalb
    // von MPD, am DateiBrowser/DS File" — statt eines eigenen Rueckgaengig
    // im Server (signierter Zettel, Inode-Vergleich, ein halber Arbeitstag)
    // steht das Wissen jetzt dort, wo es gebraucht wird. Der Gewinn waere
    // ohnehin klein gewesen: wer in File Station wiederherstellt, bekommt
    // dasselbe Ergebnis, das die teure Loesung im Konfliktfall geliefert
    // haette — die Datei zurueck im Albumordner, aber ohne ihren Eintrag in
    // der album.json, also unsortiert.
    // Der Papierkorb ist eine Eigenschaft der Freigabe, also je Space —
    // seit 08.09.2026 nur noch in recycleBySpace gehalten (vorher ein
    // zweites recycleActive nur fuer diesen Dialog).
    val recycleActive = vm.openAlbum?.summary?.space?.let { vm.recycleBySpace[it] }
    // Tour-Kachel: Element raus und GPX in den Papierkorb (13.11.2026).
    val istTour = elem.type == "tour"
    val hint = when (recycleActive) {
        true -> stringResource(Res.string.delete_hint_recycle)
        false -> stringResource(Res.string.delete_hint_norecycle)
        null -> stringResource(Res.string.delete_hint_default)
    }
    AlertDialog(
        onDismissRequest = { vm.cancelDeleteFile() },
        icon = { Icon(MpdIcons.Trash, null, Modifier.size(28.dp), tint = MpdRed) },
        // Nicht „endgueltig" sagen, wenn es nicht endgueltig ist: bei
        // aktivem Papierkorb der Freigabe widersprach der Titel dem
        // Hinweis direkt darunter („landet im Papierkorb").
        title = { Text(stringResource(if (istTour) Res.string.delete_tour_title else Res.string.delete_file_title)) },
        text = {
            Column {
                Text(elem.file.orEmpty(), color = MpdText, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (istTour) Res.string.delete_tour_body else Res.string.delete_file_body),
                    color = MpdMuted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    color = if (recycleActive == true) MpdMuted else MpdGoldLt,
                    fontSize = 14.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.performDeleteFile() }) {
                Text(stringResource(Res.string.common_delete), color = MpdRed)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelDeleteFile() }) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

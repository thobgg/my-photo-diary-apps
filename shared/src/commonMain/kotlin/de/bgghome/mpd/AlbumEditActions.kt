package de.bgghome.mpd

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.state.modifiedOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import de.bgghome.mpd.api.coverFromSource
import de.bgghome.mpd.api.deleteFile
import de.bgghome.mpd.api.deleteTourFile

// Album bearbeiten: Elemente aendern, einfuegen, verschieben, entfernen; Undo.
// Erweiterungen von AppViewModel, herausgeloest aus AppViewModel.kt (13.11.2026).

internal fun AppViewModel.merkeUndo(vorher: OpenAlbum) {
    if (!isEditMode) return
    undoStapel.addLast(vorher)
    while (undoStapel.size > 50) undoStapel.removeFirst()
    redoStapel.clear()
    canUndo = true; canRedo = false
}

internal fun AppViewModel.verlaufLeeren() {
    undoStapel.clear(); redoStapel.clear()
    canUndo = false; canRedo = false
}

fun AppViewModel.undo() {
    val jetzt = openAlbum ?: return
    val vorher = undoStapel.removeLastOrNull() ?: return
    redoStapel.addLast(jetzt)
    openAlbum = vorher
    canUndo = undoStapel.isNotEmpty(); canRedo = true
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

fun AppViewModel.redo() {
    val jetzt = openAlbum ?: return
    val danach = redoStapel.removeLastOrNull() ?: return
    undoStapel.addLast(jetzt)
    openAlbum = danach
    canRedo = redoStapel.isNotEmpty(); canUndo = true
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

fun AppViewModel.toggleEditMode() {
    if (isEditMode) {
        editingElementId = null
        isEditMode = false
        verlaufLeeren()
        // "Fertig" speichert — Autosave ist der Normalfall (Web ebenso).
        if (saveState == SaveState.Unsaved || saveState == SaveState.Error) saveNow()
    } else if (openAlbum?.readOnly == false) {
        isEditMode = true
        frischenStandHolen()
    }
}

/**
 * Den Serverstand holen, SOLANGE nichts zu verlieren ist.
 *
 * Warum das den Konfliktdialog fast abschafft (12.09.2026:
 * „aber Aenderungen werden doch immer sofort gespeichert"): Richtig,
 * zwei Sekunden nach der letzten Aenderung. Die Luecke ist nicht das
 * Speichern, sondern die Zeit, in der ein Album offen LIEGT. Beim
 * Oeffnen merkt sich die App den Stand von diesem Moment; aendert
 * jemand danach im Browser etwas, ist dieser Stand veraltet, und die
 * App weiss es nicht — sie zeigt sogar einen veralteten Inhalt. Die
 * naechste eigene Aenderung speichert dann gegen den alten Stand, und
 * der Server sagt zu Recht Nein. Zwischen beiden Handlungen koennen
 * Stunden liegen.
 *
 * Der richtige Zeitpunkt ist VOR der ersten Aenderung, nicht vor dem
 * Speichern. Vorher liegt nichts Eigenes vor, das Neuladen kostet also
 * nichts. Danach waere es gefaehrlich: die App wuerde mit frischem
 * Stempel ihre eigene, alte Elementliste hochschreiben und eine fremde
 * Ergaenzung stillschweigend loeschen — genau das automatische
 * Ueberschreiben, das der Schutz verhindern soll.
 *
 * Alternative — das Album sperren, solange es woanders offen
 * ist — waere teurer und haette ihn selbst am oeftesten getroffen: eine
 * Sperre muss jemand wieder wegraeumen, und ein zugeschlagener
 * Browser-Tab sagt dem Server nichts.
 */

fun AppViewModel.frischenStandHolen() {
    if (openAlbum == null) return
    if (saveState == SaveState.Unsaved || saveState == SaveState.Error) return
    if (autoSaveJob?.isActive == true) return
    reloadAlbum()
}

fun AppViewModel.startTextEdit(id: String) {
    if (isEditMode) editingElementId = id
}

fun AppViewModel.closeTextEdit() {
    editingElementId = null
}

/**
 * Ein Element in typisierter UND roher Liste aendern, dann Autosave —
 * gemeinsamer Unterbau aller Feld-Edits.
 */

internal fun AppViewModel.updateElement(
    id: String,
    typed: (de.bgghome.mpd.api.PdxElement) -> de.bgghome.mpd.api.PdxElement,
    raw: (JsonObject) -> JsonObject,
) {
    val album = openAlbum ?: return
    openAlbum = album.copy(
        elements = album.elements.map { if (it.id == id) typed(it) else it },
        rawElements = album.rawElements.map {
            if (it["id"]?.jsonPrimitive?.contentOrNull == id) raw(it) else it
        },
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

/** Wie handleTextChange im Web: trimmen, Modell aendern, Autosave planen. */
fun AppViewModel.commitTextEdit(id: String, newText: String) {
    val trimmed = newText.trim()
    val old = openAlbum?.elements?.find { it.id == id } ?: return
    if (old.text.orEmpty() == trimmed) return
    updateElement(
        id,
        typed = { it.copy(text = trimmed) },
        raw = { JsonObject(it + ("text" to JsonPrimitive(trimmed))) },
    )
}

fun AppViewModel.removeElement(id: String, autoSave: Boolean = true): String? {
    val album = openAlbum ?: return null
    val typedIndex = album.elements.indexOfFirst { it.id == id }
    val rawIndex = album.rawElements
        .indexOfFirst { it["id"]?.jsonPrimitive?.contentOrNull == id }
    if (typedIndex < 0 || rawIndex < 0) return null
    val typed = album.elements[typedIndex]
    lastRemoved = AppViewModel.RemovedElement(typed, album.rawElements[rawIndex], typedIndex, rawIndex)

    val newTyped = album.elements.filterIndexed { i, _ -> i != typedIndex }
    val (photos, photoIndexById) = buildPhotos(newTyped)
    openAlbum = album.copy(
        elements = newTyped,
        rawElements = album.rawElements.filterIndexed { i, _ -> i != rawIndex },
        photos = photos,
        photoIndexById = photoIndexById,
        textCount = album.textCount - if (typed.type == "text") 1 else 0,
        mediaCount = album.mediaCount -
            if (typed.type == "photo" || typed.type == "video") 1 else 0,
    )
    if (editingElementId == id) editingElementId = null
    if (autoSave) {
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }
    return typed.file ?: typed.label ?: typed.title
        ?: typed.text?.take(30)?.takeIf { it.isNotBlank() } ?: typed.type
}

/** „Rueckgaengig" der Snackbar: an alter Stelle wieder einsetzen. */
fun AppViewModel.undoRemove() {
    val removed = lastRemoved ?: return
    lastRemoved = null
    val album = openAlbum ?: return
    val newTyped = album.elements.toMutableList()
        .apply { add(removed.typedIndex.coerceAtMost(size), removed.typed) }
    val (photos, photoIndexById) = buildPhotos(newTyped)
    openAlbum = album.copy(
        elements = newTyped,
        rawElements = album.rawElements.toMutableList()
            .apply { add(removed.rawIndex.coerceAtMost(size), removed.raw) },
        photos = photos,
        photoIndexById = photoIndexById,
        textCount = album.textCount + if (removed.typed.type == "text") 1 else 0,
        mediaCount = album.mediaCount +
            if (removed.typed.type == "photo" || removed.typed.type == "video") 1 else 0,
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

/**
 * Web toggleShowLocked: meta.show_locked kippen und neu rendern.
 * Bewusste Abweichung: die App speichert die Wahl sofort per Autosave —
 * im Web ueberlebt sie nur, wenn zufaellig noch etwas anderes speichert.
 */

fun AppViewModel.toggleShowLocked() {
    val album = openAlbum ?: return
    val newValue = !album.showLocked
    val (photos, photoIndexById) = buildPhotos(album.elements, showLocked = newValue)
    openAlbum = album.copy(
        showLocked = newValue,
        photos = photos,
        photoIndexById = photoIndexById,
        rawMeta = JsonObject(album.rawMeta + ("show_locked" to JsonPrimitive(newValue))),
    )
    if (!album.readOnly) {
        merkeUndo(album)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
    }
}

/** Web toggleLocked: locked=true setzen, sonst Schluessel entfernen. */
fun AppViewModel.toggleLocked(id: String) {
    val old = openAlbum?.elements?.find { it.id == id } ?: return
    val nowLocked = !old.locked
    updateElement(
        id,
        typed = { it.copy(locked = nowLocked) },
        raw = {
            if (nowLocked) JsonObject(it + ("locked" to JsonPrimitive(true)))
            else JsonObject(it - "locked")
        },
    )
}

/** Trenner-Label — Web: separator-label-edit; leer entfernt den Schluessel. */
fun AppViewModel.setSeparatorLabel(id: String, label: String) {
    val trimmed = label.trim()
    val old = openAlbum?.elements?.find { it.id == id } ?: return
    if (old.label.orEmpty() == trimmed) return
    updateElement(
        id,
        typed = { it.copy(label = trimmed.ifEmpty { null }) },
        raw = {
            if (trimmed.isEmpty()) JsonObject(it - "label")
            else JsonObject(it + ("label" to JsonPrimitive(trimmed)))
        },
    )
}

/** Text-Stil — Web: handleStyleChange; "default" entfernt den Schluessel
 *  (der Server poppt style=="default" ohnehin). */

fun AppViewModel.setTextStyle(id: String, style: String) {
    updateElement(
        id,
        typed = { it.copy(style = style.takeIf { s -> s != "default" }) },
        raw = {
            if (style == "default") JsonObject(it - "style")
            else JsonObject(it + ("style" to JsonPrimitive(style)))
        },
    )
}

/** Quellenangabe am Zitat (PDX v1.5): leer entfernt den Schluessel. */
fun AppViewModel.setQuoteSource(id: String, source: String) {
    val trimmed = source.trim()
    val old = openAlbum?.elements?.find { it.id == id } ?: return
    if (old.source.orEmpty() == trimmed) return
    updateElement(
        id,
        typed = { it.copy(source = trimmed.ifEmpty { null }) },
        raw = {
            if (trimmed.isEmpty()) JsonObject(it - "source")
            else JsonObject(it + ("source" to JsonPrimitive(trimmed)))
        },
    )
}

/** Web submitLinkDialog im Bearbeiten-Zweig: url Pflicht, Rest optional. */
fun AppViewModel.updateLink(id: String, url: String, title: String, note: String) {
    val cleanUrl = url.trim()
    if (cleanUrl.isEmpty()) return
    val cleanTitle = title.trim()
    val cleanNote = note.trim()
    updateElement(
        id,
        typed = {
            it.copy(
                url = cleanUrl,
                title = cleanTitle.ifEmpty { null },
                note = cleanNote.ifEmpty { null },
            )
        },
        raw = {
            val base = it - "title" - "note"
            var out = base + ("url" to JsonPrimitive(cleanUrl))
            if (cleanTitle.isNotEmpty()) out = out + ("title" to JsonPrimitive(cleanTitle))
            if (cleanNote.isNotEmpty()) out = out + ("note" to JsonPrimitive(cleanNote))
            JsonObject(out)
        },
    )
}

fun AppViewModel.requestDeleteFile(elem: de.bgghome.mpd.api.PdxElement) {
    pendingDelete = elem
}

/**
 * Tour-Kachel in den Papierkorb (13.11.2026): erst das Element heraus
 * und das Album speichern, DANN die GPX loeschen. Die Reihenfolge ist
 * Pflicht — der Server sperrt das Loeschen mit 409, solange ein Album
 * die Datei nennt (TOURS.md §4.2). Scheitert das Speichern, kommt das
 * Element zurueck und nichts ist geloescht. Kein „Rueckgaengig": die
 * Datei liegt im Papierkorb der Freigabe, nicht in der App.
 */

internal fun AppViewModel.performDeleteTour(elem: de.bgghome.mpd.api.PdxElement) {
    val album = openAlbum ?: return
    val file = elem.file ?: return
    viewModelScope.launch {
        removeElement(elem.id, autoSave = false)
        if (!speichereSofort()) {
            undoRemove()
            meldung(t(Res.string.tour_save_failed))
            return@launch
        }
        lastRemoved = null
        try {
            val recycled = api.deleteTourFile(baseUrl, album.summary.name, file)
            meldung(
                t(if (recycled) Res.string.tours_recycled else Res.string.tours_deleted_no_bin),
                kurz = true,
            )
        } catch (e: Exception) {
            meldung(t(Res.string.tours_not_deleted, e.message ?: t(Res.string.err_generic)))
        }
    }
}

fun AppViewModel.cancelDeleteFile() {
    pendingDelete = null
}

fun AppViewModel.clearDeleteNotice() {
    deleteNotice = null
}

/** Nach der Rueckfrage: Datei loeschen, Element raus — OHNE Undo (Web:
 *  "file is physically gone, undo would be misleading"). */

fun AppViewModel.performDeleteFile() {
    val elem = pendingDelete ?: return
    pendingDelete = null
    if (elem.type == "tour") return performDeleteTour(elem)
    val album = openAlbum ?: return
    val file = elem.file ?: return
    viewModelScope.launch {
        try {
            val res =
                api.deleteFile(
                    baseUrl, album.summary.space, album.summary.name, file,
                    albumContext = true, // der Album-Editor IST die Album-Ansicht
                )
            // „ALBUM WURDE WOANDERS GEAENDERT" BEIM EIGENEN LOESCHEN
            // (12.09.2026: „immer doch diese dadellige Warnung,
            // wenn ich am Handy paar Bilder aus Album loesche").
            //
            // Woanders war niemand — es war die App selbst. Der Server
            // nimmt beim Loeschen den Eintrag SELBST aus der album.json
            // und stempelt meta.modified neu (bewusst so, seit
            // 06.09.2026, sonst holte ein spaeterer Save das geloeschte
            // Element still zurueck). Die App speicherte danach
            // trotzdem — mit dem Stempel von VOR der Loeschung. Der
            // Konfliktschutz sah zu Recht eine fremde Aenderung und
            // fragte. Jedes Mal, unvermeidlich.
            //
            // Hat der Server die Datei schon ausgetragen, ist hier nur
            // die Anzeige nachzuziehen. Der frische Stempel muss aber
            // mitkommen, sonst laeuft der NAECHSTE echte Save in
            // dasselbe Messer.
            if (res.removedFromJson) {
                removeElement(elem.id, autoSave = false)
                runCatching {
                    api.album(baseUrl, album.summary.space, album.summary.name)
                }.getOrNull()?.let { env ->
                    openAlbum = openAlbum?.copy(baseModified = modifiedOf(env.raw))
                }
            } else {
                removeElement(elem.id)
            }
            lastRemoved = null // kein „Rueckgaengig" — die Datei ist weg
            if (res.wasThumbnail) {
                deleteNotice =
                    t(Res.string.vm_cover_deleted_x, file)
            }
        } catch (e: MpdHttpException) {
            deleteNotice = t(Res.string.vm_err_delete_failed_x, e.detail)
        } catch (e: IOException) {
            deleteNotice = t(Res.string.vm_err_delete_failed_x, e.message)
        }
    }
}

/** Trenner-Stil — Web: changeSepStyle; "line" entfernt den Schluessel. */
fun AppViewModel.setSeparatorStyle(id: String, style: String) {
    updateElement(
        id,
        typed = { it.copy(style = style.takeIf { s -> s != "line" }) },
        raw = {
            if (style == "line") JsonObject(it - "style")
            else JsonObject(it + ("style" to JsonPrimitive(style)))
        },
    )
}

/** Web generateId(): max numerischer id + 1, vierstellig. */
internal fun AppViewModel.generateId(album: OpenAlbum): String =
    (album.rawElements
        .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
        .maxOrNull()?.plus(1) ?: 1).toString().padStart(4, '0')

/**
 * Neues Element hinter [afterId] einfuegen (null = Albumanfang — wie
 * splice(insertAfterIndex+1) im Web bei insertAfterIndex -1), in
 * typisierter UND roher Liste, dann Autosave.
 */

internal fun AppViewModel.insertElement(
    afterId: String?,
    typedElem: de.bgghome.mpd.api.PdxElement,
    rawElem: JsonObject,
) {
    val album = openAlbum ?: return
    fun <T> List<T>.inserted(item: T, idOf: (T) -> String?): List<T> {
        val idx = if (afterId == null) -1 else indexOfFirst { idOf(it) == afterId }
        return toMutableList().apply { add(idx + 1, item) }
    }

    val newTyped = album.elements.inserted(typedElem) { it.id }
    val (photos, photoIndexById) = buildPhotos(newTyped)
    openAlbum = album.copy(
        elements = newTyped,
        rawElements = album.rawElements
            .inserted(rawElem) { it["id"]?.jsonPrimitive?.contentOrNull },
        photos = photos,
        photoIndexById = photoIndexById,
        textCount = album.textCount + if (typedElem.type == "text") 1 else 0,
        mediaCount = album.mediaCount +
            if (typedElem.type == "photo" || typedElem.type == "video") 1 else 0,
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

/**
 * Web addPhotoToAlbum: Element ist nur {id, type, file} — resolution und
 * blurhash traegt der Server beim naechsten Album-GET nach
 * (_enrich_photo_meta). Die Thumbnail-/Original-URLs sind synthetisch
 * (gleiches Muster wie routers/albums.py), damit die Kachel sofort
 * rendert; bis zum Nachladen gilt Seitenverhaeltnis 1,5 wie im Web.
 */

fun AppViewModel.addMediaElement(afterId: String?, file: String, type: String): String? {
    val album = openAlbum ?: return null
    if (!isEditMode) return null
    val newId = generateId(album)
    val space = album.summary.space
    val name = album.summary.name
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = type, file = file,
            thumbnails = de.bgghome.mpd.api.Thumbnails(
                sm = MpdApi.thumbnailPath(space, name, file, "sm"),
                m = MpdApi.thumbnailPath(space, name, file, "m"),
                xl = MpdApi.thumbnailPath(space, name, file, "xl"),
                full = MpdApi.thumbnailPath(space, name, file, "full"),
            ),
            original = MpdApi.originalPath(space, name, file),
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive(type))
            put("file", JsonPrimitive(file))
        },
    )
    return newId
}

/** Web enableMetaEdit: leerer Titel faellt auf den alten zurueck. */
fun AppViewModel.setAlbumTitle(title: String) {
    val album = openAlbum ?: return
    val trimmed = title.trim()
    if (trimmed.isEmpty() || trimmed == album.title) return
    openAlbum = album.copy(
        title = trimmed,
        rawMeta = JsonObject(album.rawMeta + ("title" to JsonPrimitive(trimmed))),
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

/** Web enableMetaEdit: Jahr nur im Bereich 1800–2099 uebernehmen. */
fun AppViewModel.setAlbumYear(year: Int) {
    val album = openAlbum ?: return
    if (year < 1800 || year > 2099 || year == album.summary.year) return
    openAlbum = album.copy(
        summary = album.summary.copy(year = year),
        rawMeta = JsonObject(album.rawMeta + ("year" to JsonPrimitive(year))),
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

/** Web insertTextAtScrollPosition: leerer Text, Editor direkt offen. */
fun AppViewModel.addTextElement(afterId: String?) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val newId = generateId(album)
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(id = newId, type = "text", text = ""),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(""))
        },
    )
    editingElementId = newId
}

/** Web insertSeparatorAtScrollPosition: label/style nur wenn gesetzt. */
fun AppViewModel.addSeparator(afterId: String?, label: String, style: String) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val newId = generateId(album)
    val trimmed = label.trim()
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = "separator",
            label = trimmed.ifEmpty { null },
            style = style.takeIf { it != "line" },
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("separator"))
            if (trimmed.isNotEmpty()) put("label", JsonPrimitive(trimmed))
            if (style != "line") put("style", JsonPrimitive(style))
        },
    )
}

/** Web insertLinkAtScrollPosition: url Pflicht, title/note optional. */
fun AppViewModel.addLink(afterId: String?, url: String, title: String, note: String) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val cleanUrl = url.trim()
    if (cleanUrl.isEmpty()) return
    val newId = generateId(album)
    val cleanTitle = title.trim()
    val cleanNote = note.trim()
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = "link", url = cleanUrl,
            title = cleanTitle.ifEmpty { null },
            note = cleanNote.ifEmpty { null },
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("link"))
            put("url", JsonPrimitive(cleanUrl))
            if (cleanTitle.isNotEmpty()) put("title", JsonPrimitive(cleanTitle))
            if (cleanNote.isNotEmpty()) put("note", JsonPrimitive(cleanNote))
        },
    )
}

/** Web openAddAudioModal/openAddDocumentModal: Element {id, type, file}.
 *  Fuer Nicht-PDF-Dokumente synthetische Thumb-URLs wie bei Fotos, damit
 *  die Kachel sofort rendert; PDFs bekommen die document_url. */

fun AppViewModel.addFolderFile(afterId: String?, file: String, type: String) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val newId = generateId(album)
    val space = album.summary.space
    val name = album.summary.name
    val isPdf = file.lowercase().endsWith(".pdf")
    // Vorschau-Adressen sofort selbst bauen — wie bei Fotos aus anderen
    // Alben (addPhotoFromOtherAlbum). Bis 08.09.2026 bekamen nur
    // Dokumente welche; ein aus dem Ordner eingefuegtes Foto hatte
    // thumbnails == null, die Kachel zeichnete NICHTS, und auch nach
    // dem Speichern holte niemand die Serverdaten nach („jetzt
    // ist kein Bild mehr angezeigt im Album, wohl kein refresh"). Die
    // Aufloesung kennt erst der Server; refreshEnrichment() holt sie
    // nach dem naechsten Speichern still nach.
    val thumbs = when (type) {
        "photo", "document" -> if (isPdf) null else de.bgghome.mpd.api.Thumbnails(
            sm = MpdApi.thumbnailPath(space, name, file, "sm"),
            m = MpdApi.thumbnailPath(space, name, file, "m"),
            xl = MpdApi.thumbnailPath(space, name, file, "xl"),
            full = MpdApi.thumbnailPath(space, name, file, "full"),
        )
        // /api/thumbnail antwortet fuer Videos 415 — der FFmpeg-Thumb
        // liegt unter /api/video-thumb (wie im Zeitstrahl).
        "video" -> MpdApi.videoThumbPath(space, name, file).let {
            de.bgghome.mpd.api.Thumbnails(sm = it, m = it, xl = it)
        }
        else -> null
    }
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = type, file = file,
            thumbnails = thumbs,
            original = if (type == "photo") MpdApi.originalPath(space, name, file) else null,
            documentUrl = if (type == "document" && isPdf)
                MpdApi.documentPath(space, name, file)
            else null,
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive(type))
            put("file", JsonPrimitive(file))
        },
    )
}

/** Web addTourToAlbum: nur {id, type, file} — Defaults gelten. */
/** [sourceAlbum]: die GPX liegt in einem anderen Album (Antwort von
 *  /api/tours/place) — das Element zeigt dorthin, wie beim Foto. */

fun AppViewModel.addTour(afterId: String?, filename: String, sourceAlbum: String? = null) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val newId = generateId(album)
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = "tour", file = filename, sourceAlbum = sourceAlbum,
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("tour"))
            put("file", JsonPrimitive(filename))
            if (sourceAlbum != null) put("source_album", JsonPrimitive(sourceAlbum))
        },
    )
}

/** Tour-Elemente des offenen Albums, die auf genau diese Datei zeigen
 *  (Datei im Albumordner oder per source_album in einem anderen). */

fun AppViewModel.tourElementeFuer(folder: String, file: String): List<de.bgghome.mpd.api.PdxElement> {
    val album = openAlbum ?: return emptyList()
    return album.elements.filter {
        it.type == "tour" && it.file == file && (it.sourceAlbum ?: album.summary.name) == folder
    }
}

/** Tour-Element auf eine andere Datei umstellen — Platz, Titel und alle
 *  anderen Angaben bleiben (Tausch Huawei ↔ Komoot, 11.10.2026). */

fun AppViewModel.ersetzeTourDatei(id: String, file: String, sourceAlbum: String?) {
    updateElement(
        id,
        typed = { it.copy(file = file, sourceAlbum = sourceAlbum) },
        raw = { roh ->
            val neu = roh.toMutableMap()
            neu["file"] = JsonPrimitive(file)
            if (sourceAlbum != null) neu["source_album"] = JsonPrimitive(sourceAlbum)
            else neu.remove("source_album")
            JsonObject(neu)
        },
    )
}

/**
 * Sofort speichern und warten, ob es geklappt hat. Noetig, bevor eine
 * Tour geloescht wird, die eben aus dem Album genommen wurde: der
 * Server prueft used_in an der GESPEICHERTEN album.json und sagt sonst
 * 409.
 */

suspend fun AppViewModel.speichereSofort(): Boolean {
    autoSaveJob?.cancel()
    while (saveState == SaveState.Saving) delay(200)
    val album = openAlbum ?: return false
    save(album)
    return saveState == SaveState.Saved
}

/** Web insertMapFromDialog: pins [{lat,lon,label?}], title optional. */
fun AppViewModel.addMap(afterId: String?, title: String, pins: List<de.bgghome.mpd.api.MapPin>) {
    val album = openAlbum ?: return
    if (!isEditMode || pins.isEmpty()) return
    val newId = generateId(album)
    val cleanTitle = title.trim()
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = "map", pins = pins,
            title = cleanTitle.ifEmpty { null },
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("map"))
            put("pins", JsonArray(pins.map { p ->
                buildJsonObject {
                    put("lat", JsonPrimitive(p.lat))
                    put("lon", JsonPrimitive(p.lon))
                    p.label?.takeIf { it.isNotBlank() }
                        ?.let { put("label", JsonPrimitive(it)) }
                }
            }))
            if (cleanTitle.isNotEmpty()) put("title", JsonPrimitive(cleanTitle))
        },
    )
}

/** Mixed-Picker (PDX v1.4): Foto aus anderem Album per source_album. */
fun AppViewModel.addPhotoFromOtherAlbum(afterId: String?, sourceAlbum: String, file: String) {
    val album = openAlbum ?: return
    if (!isEditMode) return
    val newId = generateId(album)
    val space = album.summary.space
    insertElement(
        afterId,
        de.bgghome.mpd.api.PdxElement(
            id = newId, type = "photo", file = file, sourceAlbum = sourceAlbum,
            thumbnails = de.bgghome.mpd.api.Thumbnails(
                sm = MpdApi.thumbnailPath(space, sourceAlbum, file, "sm"),
                m = MpdApi.thumbnailPath(space, sourceAlbum, file, "m"),
                xl = MpdApi.thumbnailPath(space, sourceAlbum, file, "xl"),
                full = MpdApi.thumbnailPath(space, sourceAlbum, file, "full"),
            ),
            original = MpdApi.originalPath(space, sourceAlbum, file),
        ),
        buildJsonObject {
            put("id", JsonPrimitive(newId))
            put("type", JsonPrimitive("photo"))
            put("file", JsonPrimitive(file))
            put("source_album", JsonPrimitive(sourceAlbum))
        },
    )
}

/**
 * Cover setzen (Web openThumbnailModal): lokales Foto direkt in
 * meta.thumbnail; referenziertes erst per cover-from-source kopieren.
 */

fun AppViewModel.setAlbumCover(elem: de.bgghome.mpd.api.PdxElement, onDone: (String?) -> Unit) {
    val album = openAlbum ?: return
    val file = elem.file ?: return
    viewModelScope.launch {
        val thumbName = if (elem.sourceAlbum != null) {
            try {
                api.coverFromSource(
                    baseUrl, album.summary.space, album.summary.name,
                    elem.sourceAlbum, file,
                )
            } catch (e: Exception) {
                onDone(e.message ?: t(Res.string.vm_err_copy_failed))
                return@launch
            }
        } else file
        openAlbum = (openAlbum ?: return@launch).let { current ->
            current.copy(
                summary = current.summary.copy(thumbnail = thumbName),
                rawMeta = JsonObject(current.rawMeta + ("thumbnail" to JsonPrimitive(thumbName))),
            )
        }
        albums = null // Albenliste beim naechsten Besuch frisch laden (neues Cover)
        saveState = SaveState.Unsaved
        scheduleAutoSave()
        onDone(null)
    }
}

fun AppViewModel.moveElement(id: String, targetId: String, before: Boolean) {
    if (id == targetId) return
    val album = openAlbum ?: return

    fun <T> List<T>.moved(idOf: (T) -> String?): List<T>? {
        val item = firstOrNull { idOf(it) == id } ?: return null
        val without = filterNot { idOf(it) == id }
        val ti = without.indexOfFirst { idOf(it) == targetId }
        if (ti < 0) return null
        return without.toMutableList().apply { add(if (before) ti else ti + 1, item) }
    }

    val newTyped = album.elements.moved { it.id } ?: return
    val newRaw = album.rawElements
        .moved { it["id"]?.jsonPrimitive?.contentOrNull } ?: return
    if (newTyped == album.elements) return

    val (photos, photoIndexById) = buildPhotos(newTyped)
    openAlbum = album.copy(
        elements = newTyped,
        rawElements = newRaw,
        photos = photos,
        photoIndexById = photoIndexById,
    )
    merkeUndo(album)
    saveState = SaveState.Unsaved
    scheduleAutoSave()
}

package de.bgghome.mpd

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.state.modifiedOf
import de.bgghome.mpd.state.serverUnreachable
import de.bgghome.mpd.state.updateBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import de.bgghome.mpd.api.albumsWithoutJson
import de.bgghome.mpd.api.createAlbum
import de.bgghome.mpd.api.createEmptyAlbum

// Alben laden, oeffnen, speichern, anlegen, loeschen; Kachel-Aktionen der Uebersicht.
// Erweiterungen von AppViewModel, herausgeloest aus AppViewModel.kt (13.11.2026).

internal fun AppViewModel.patchAlbumMeta(
    summary: AlbumSummary,
    patch: Map<String, kotlinx.serialization.json.JsonElement?>,
    onDone: (String?) -> Unit,
) {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        try {
            try {
                patchOnce(base, summary, patch)
            } catch (e: MpdHttpException) {
                // Jemand war schneller. Der Wunsch gilt trotzdem:
                // einmal still mit frischem Stand wiederholen.
                if (e.code == 409) patchOnce(base, summary, patch) else throw e
            }
            albums = null
            loadAlbums()
            onDone(null)
        } catch (e: MpdHttpException) {
            onDone(e.detail)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onDone(e.message ?: t(Res.string.vm_err_save_failed))
        }
    }
}

/** Ein Durchlauf: laden, Feld setzen, speichern mit Konflikt-Basis. */
internal suspend fun AppViewModel.patchOnce(
    base: String,
    summary: AlbumSummary,
    patch: Map<String, kotlinx.serialization.json.JsonElement?>,
) {
    val env = api.album(base, summary.space, summary.name)
    val meta = env.raw["meta"]?.jsonObject ?: buildJsonObject {}
    var newMeta = meta
    for ((key, value) in patch) {
        newMeta = if (value == null) {
            JsonObject(newMeta - key)
        } else {
            JsonObject(newMeta + (key to value))
        }
    }
    val elements = env.raw["elements"]?.jsonArray
        ?.map { it.jsonObject } ?: emptyList()
    // Wie save(): gefilterter Body und die geladene Fassung als
    // Konflikt-Basis. Das Fenster ist klein (Laden und Schreiben
    // liegen Millisekunden auseinander), aber ohne den Header
    // ueberschriebe der Patch still, was jemand gerade im Web
    // oder am anderen Geraet geaendert hat.
    api.updateAlbum(
        base, summary.space, summary.name,
        updateBody(newMeta, elements),
        baseModified = modifiedOf(env.raw),
    )
}

/** Deckblatt: ein alter Zuschnitt passt nicht zum neuen Bild → weg. */
fun AppViewModel.setCoverFromTile(summary: AlbumSummary, filename: String, onDone: (String?) -> Unit) =
    patchAlbumMeta(
        summary,
        mapOf("thumbnail" to JsonPrimitive(filename), "thumbnail_crop" to null),
        onDone,
    )

/** Umbenennen aendert wie im Web nur meta.title, nie den Ordner. */
fun AppViewModel.renameAlbumFromTile(summary: AlbumSummary, title: String, onDone: (String?) -> Unit) =
    patchAlbumMeta(summary, mapOf("title" to JsonPrimitive(title.trim())), onDone)

/** Cover-Ausschnitt (Lupe) — normalisiert wie im Web. */
fun AppViewModel.setCoverCrop(
    summary: AlbumSummary,
    crop: de.bgghome.mpd.api.ThumbCrop?,
    onDone: (String?) -> Unit,
) = patchAlbumMeta(
    summary,
    mapOf(
        "thumbnail_crop" to crop?.let {
            buildJsonObject {
                put("x", JsonPrimitive(it.x))
                put("y", JsonPrimitive(it.y))
                put("w", JsonPrimitive(it.w))
                put("h", JsonPrimitive(it.h))
            }
        },
    ),
    onDone,
)

/** Loeschen von der Kachel — gleiche Mechanik wie im Album (nur
 *  album.json weg, Backup fuer den Rueckgaengig-Toast). */

fun AppViewModel.deleteAlbumFromTile(summary: AlbumSummary, onError: (String) -> Unit) {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        try {
            val backup = api.deleteAlbum(base, summary.space, summary.name)
            pendingUndoDelete = backup?.let {
                AppViewModel.DeletedAlbum(
                    summary.space, summary.name,
                    summary.title.ifBlank { summary.name }, it,
                )
            }
            albums = null
            loadAlbums()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onError(e.message ?: t(Res.string.vm_err_delete_failed))
        }
    }
}

fun AppViewModel.loadFolderCandidates() {
    val base = settings.baseUrl ?: return
    folderCandidates = null
    folderCandidatesError = null
    viewModelScope.launch {
        try {
            folderCandidates = api.albumsWithoutJson(base).folders
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            folderCandidatesError = e.message ?: t(Res.string.vm_err_scan_failed)
        }
    }
}

/**
 * Album aus einem bestehenden Ordner anlegen. Danach steht man in der
 * Albenuebersicht, das neue Album ist dort.
 *
 * DIE DECKBLATT-SCHLEIFE (12.09.2026: „nach Auswahl Deckblatt,
 * Rueckkehr, fuehrt immer zur erneuten Auswahl und nicht zurueck zur
 * Albenuebersicht") entstand hier: der try-Block umfasste auch alles
 * NACH dem Anlegen. Stolperte das Nachladen oder das Oeffnen des neuen
 * Albums, meldete der catch einen Fehler — obwohl das Album laengst
 * angelegt war. Der Dialog blieb offen und zeigte wieder die
 * Deckblatt-Wahl. Der zweite Versuch traf dann auf ein bereits
 * bestehendes Album und scheiterte endgueltig: aus einem Aussetzer
 * wurde eine Sackgasse, aus der nur Abbrechen herausfuehrte.
 *
 * Jetzt steht nur der eine Aufruf im try, der schiefgehen DARF.
 *
 * DER DIALOG IST BEIM AUFRUF SCHON ZU (12.09.2026: „eigentlich
 * sollte man doch nach Deckblattauswahl in die Albenuebersicht
 * zurueckkommen, man wird ja eher selten gleich mehrere anlegen
 * wollen"). Das Anlegen kann eine Minute dauern — so lange jemanden
 * vor einem Wartekreis festzuhalten, waere Preis-Arbeit. Er landet
 * sofort in der Uebersicht, das Album erscheint, sobald es fertig ist,
 * und eine Snackbar sagt es. Nur ein echter Fehlschlag meldet sich, und
 * zwar ebenfalls dort, nicht in einem Dialog, den es nicht mehr gibt.
 */

fun AppViewModel.createAlbumFromFolder(
    space: String,
    folderName: String,
    thumbnail: String,
    onDone: (String?) -> Unit,
) {
    val base = settings.baseUrl
    if (base == null) {
        // Ohne Adresse gab es hier ein nacktes return: onDone kam nie,
        // und der Dialog blieb auf „Erstelle Album …" stehen.
        onDone(t(Res.string.vm_err_create_failed))
        return
    }
    viewModelScope.launch {
        try {
            api.createAlbum(base, space, folderName, thumbnail)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // BRICHT DIE LEITUNG AB, HEISST DAS NICHT, DASS NICHTS
            // PASSIERT IST (12.09.2026). Der Server arbeitet weiter und
            // legt fertig an — Thomas sah deshalb erst einen Fehler und
            // „irgendwann mal" das Album doch. Also nachsehen, statt zu
            // behaupten: steht es in der Liste, war es ein Erfolg.
            val entstanden = e !is MpdHttpException && runCatching {
                api.albums(base).any { it.space == space && it.name == folderName }
            }.getOrDefault(false)
            if (!entstanden) {
                onDone(e.message ?: t(Res.string.vm_err_create_failed))
                return@launch
            }
        }
        // Ab hier ist das Album da. Was jetzt noch schiefgeht, darf den
        // Anlegen-Vorgang nicht mehr als gescheitert darstellen.
        onDone(null)
        albums = null
        loadAlbums()
        meldung(t(Res.string.vm_album_created_x, folderName))
    }
}

/** Leeres Album (neuer Ordner) anlegen und oeffnen. */
fun AppViewModel.createEmptyAlbumUi(space: String, name: String, onDone: (String?) -> Unit) {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        try {
            api.createEmptyAlbum(base, space, name)
            albums = null
            loadAlbums()
            createAlbumOpen = false
            openAlbum(AlbumSummary(name = name, space = space))
            onDone(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onDone(e.message ?: t(Res.string.vm_err_create_failed))
        }
    }
}

fun AppViewModel.loadAlbums() {
    if (albums != null) return
    viewModelScope.launch {
        albumsError = null
        try {
            albums = api.albums(baseUrl)
        } catch (e: MpdHttpException) {
            // Status mit in die Meldung: der Text des Servers sagt nicht,
            // ob es ein Recht war (403), ein fehlender Bestand (404) oder
            // ein Serverfehler (500). Fruehere Diagnosezeile, jetzt
            // sichtbar statt protokolliert (16.09.2026).
            albumsError = t(Res.string.vm_err_http_x, e.code.toString(), e.detail)
        } catch (e: IOException) {
            albumsError = serverUnreachable(e)
        }
    }
}

fun AppViewModel.openAlbum(summary: AlbumSummary, showFile: String? = null) {
    if (albumLoading) return
    viewModelScope.launch {
        albumLoading = true
        albumsError = null
        pendingSpace = summary.space
        try {
            val envelope = api.album(baseUrl, summary.space, summary.name)
            val content = envelope.content
            // elements bleibt UNgefiltert — die Sichtbarkeit gesperrter
            // Elemente entscheidet die Albumansicht beim Rendern (Web:
            // showLocked = editMode || meta.show_locked; frueher filterte
            // die App hier und Gesperrte "verschwanden" im Edit-Modus).
            val (photos, photoIndexById) =
                buildPhotos(content.elements, summary.name, content.meta.showLocked)
            tourDaten.clear()
            fotoOrteCache.clear()
            openAlbum = OpenAlbum(
                summary = summary,
                title = content.meta.title.ifBlank { summary.name },
                elements = content.elements,
                showLocked = content.meta.showLocked,
                photos = photos,
                photoIndexById = photoIndexById,
                mediaCount = content.elements.count { it.type == "photo" || it.type == "video" },
                textCount = content.elements.count { it.type == "text" },
                readOnly = content.readOnly,
                baseModified = modifiedOf(envelope.raw),
                rawMeta = envelope.raw["meta"]?.jsonObject ?: JsonObject(emptyMap()),
                rawElements = envelope.raw["elements"]?.jsonArray
                    ?.map { it.jsonObject } ?: emptyList(),
            )
            // Sprung aus der Timeline: direkt zur Datei in der Lightbox
            showFile?.let { file ->
                photos.indexOfFirst { it.file == file }.takeIf { it >= 0 }
                    ?.let { lightboxIndex = it }
            }
        } catch (e: MpdHttpException) {
            albumsError = e.detail
            albumAusErinnerung = false
            lightboxZurPinnwand = false
        } catch (e: IOException) {
            albumsError = serverUnreachable(e)
            albumAusErinnerung = false
            lightboxZurPinnwand = false
        } finally {
            albumLoading = false
        }
    }
    // Papierkorb-Status nebenbei holen — bestimmt nur den Hinweistext
    // der Loesch-Rueckfrage (Web: fetchRecycleStatus). Frisch je
    // Albumoeffnung, wie bisher; gehalten wird er seit 08.09.2026 nur
    // noch in recycleBySpace (vorher daneben ein zweites recycleActive
    // fuer den Album-Editor — zwei Wahrheiten fuer dieselbe Freigabe).
    recycleBySpace.remove(summary.space)
    loadRecycleStatusForSpaces(setOf(summary.space))
}

internal fun AppViewModel.scheduleAutoSave() {
    autoSaveJob?.cancel()
    autoSaveJob = viewModelScope.launch {
        delay(2000)
        save(openAlbum ?: return@launch)
    }
}

fun AppViewModel.saveNow() {
    val album = openAlbum ?: return
    autoSaveJob?.cancel()
    viewModelScope.launch { save(album) }
}

fun AppViewModel.deleteCurrentAlbum(onError: (String) -> Unit) {
    val album = openAlbum ?: return
    viewModelScope.launch {
        try {
            val backup = api.deleteAlbum(baseUrl, album.summary.space, album.summary.name)
            pendingUndoDelete = backup?.let {
                AppViewModel.DeletedAlbum(album.summary.space, album.summary.name, album.title, it)
            }
            closeAlbum()
            albums = null // Uebersicht frisch laden
        } catch (e: Exception) {
            onError(e.message ?: t(Res.string.vm_err_delete_failed))
        }
    }
}

/** Web _undoDeleteAlbum: Backup zurueckspielen, Liste neu laden. */
fun AppViewModel.undoDeleteAlbum(onError: (String) -> Unit) {
    val snap = pendingUndoDelete ?: return
    pendingUndoDelete = null
    viewModelScope.launch {
        try {
            api.undoDeleteAlbum(baseUrl, snap.space, snap.name, snap.backup)
            albums = null
            loadAlbums()
        } catch (e: Exception) {
            onError(e.message ?: t(Res.string.vm_err_restore_failed))
        }
    }
}

fun AppViewModel.resolveConflict(reload: Boolean) {
    conflictOpen = false
    if (reload) {
        autoSaveJob?.cancel()
        saveState = SaveState.Idle
        reloadAlbum()
    } else {
        openAlbum = openAlbum?.copy(baseModified = null)
        saveNow()
    }
}

/** Antwort der Leer-Speichern-Rueckfrage (Web saveAlbumData-Guard). */
fun AppViewModel.answerEmptySave(confirmed: Boolean) {
    emptySaveConfirmOpen = false
    if (confirmed) {
        emptySaveApproved = true
        saveNow()
    } else {
        saveState = SaveState.Unsaved
    }
}

internal suspend fun AppViewModel.save(album: OpenAlbum) {
    // Laeuft schon ein Save, in 2 s erneut versuchen statt still verwerfen.
    if (saveState == SaveState.Saving) {
        scheduleAutoSave()
        return
    }
    // Web saveAlbumData-Guard: alle Elemente entfernt → einmal ehrlich
    // nachfragen, statt das Drehbuch still zu leeren.
    if (album.rawElements.isEmpty() && !emptySaveApproved) {
        emptySaveConfirmOpen = true
        return
    }
    emptySaveApproved = false
    saveIdleJob?.cancel()
    saveState = SaveState.Saving
    saveError = null
    val body = updateBody(album.rawMeta, album.rawElements)
    try {
        val responseText = api.updateAlbum(
            baseUrl, album.summary.space, album.summary.name, body,
            baseModified = album.baseModified,
        )
        // Neues meta.modified als naechste Konflikt-Basis uebernehmen
        // (Vertrag: update-Antwort traegt "modified"; alter Server ohne
        // das Feld → Basis leeren, dann geht kein Header mehr mit und
        // es bleibt beim Last-Write-Wins).
        val newModified = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(responseText)
                .jsonObject["modified"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        openAlbum = openAlbum?.let { current ->
            current.copy(
                baseModified = newModified,
                rawMeta = newModified?.let {
                    JsonObject(current.rawMeta + ("modified" to JsonPrimitive(it)))
                } ?: current.rawMeta,
            )
        }
        saveState = SaveState.Saved
        saveIdleJob = viewModelScope.launch {
            delay(2000)
            if (saveState == SaveState.Saved) saveState = SaveState.Idle
        }
        refreshEnrichment()
    } catch (e: MpdHttpException) {
        if (e.code == 409) {
            // Album wurde woanders geaendert — ehrlicher Dialog statt
            // stillem Ueberschreiben (Entscheidung vom 02.09.2026).
            saveState = SaveState.Unsaved
            conflictOpen = true
            return
        }
        saveState = SaveState.Error
        saveError = e.detail
    } catch (e: IOException) {
        saveState = SaveState.Error
        saveError = serverUnreachable(e)
    }
}

/**
 * Nach dem Speichern die berechneten Felder des Servers nachholen —
 * thumbnails, original, resolution, blurhash, error — fuer Elemente, die
 * die App selbst eingefuegt hat und die deshalb nur {id, type, file}
 * kennen (08.09.2026). Ohne das stimmt bis zum naechsten Oeffnen des
 * Albums die Reihenhoehe nicht (Seitenverhaeltnis 1.5 statt echt) und
 * ein Foto ohne Vorschau-Adresse bleibt leer.
 *
 * Still und ohne den Bearbeiten-Modus zu verlassen: es wird nur in die
 * typisierten Elemente gemischt, die rohen bleiben, wie sie sind (die
 * berechneten Felder gehen ohnehin nie zum Server zurueck).
 */

internal suspend fun AppViewModel.refreshEnrichment() {
    val album = openAlbum ?: return
    val needs = album.elements.any {
        (it.type == "photo" || it.type == "video") &&
            it.error == null && (it.thumbnails == null || it.resolution == null)
    }
    if (!needs) return
    val env = runCatching {
        api.album(baseUrl, album.summary.space, album.summary.name)
    }.getOrNull() ?: return
    val fresh = env.content.elements.associateBy { it.id }
    val current = openAlbum ?: return
    if (current.summary != album.summary) return
    val merged = current.elements.map { e ->
        val f = fresh[e.id] ?: return@map e
        e.copy(
            thumbnails = f.thumbnails ?: e.thumbnails,
            original = f.original ?: e.original,
            resolution = f.resolution ?: e.resolution,
            blurhash = f.blurhash ?: e.blurhash,
            error = f.error,
            editableFormat = f.editableFormat,
        )
    }
    if (merged == current.elements) return
    val (photos, photoIndexById) = buildPhotos(merged)
    openAlbum = current.copy(
        elements = merged, photos = photos, photoIndexById = photoIndexById,
    )
}

/** allImages des Webs: nur Fotos (ohne Videos, ohne Fehler) — Lightbox-Reihenfolge. */
internal fun AppViewModel.buildPhotos(
    elements: List<de.bgghome.mpd.api.PdxElement>,
    albumName: String? = null,
    showLocked: Boolean = openAlbum?.showLocked ?: true,
): Pair<List<Photo>, Map<String, Int>> {
    val name = albumName ?: openAlbum?.summary?.name.orEmpty()
    val space = openAlbum?.summary?.space
    // Gesperrte Fotos gehoeren nur in die Lightbox, wenn show_locked es
    // erlaubt — wie allImages im Web (View-Modus-Sicht).
    val photoElements = elements.filter {
        it.type == "photo" && it.thumbnails != null && it.error == null &&
            it.original != null && (showLocked || !it.locked)
    }
    val photos = photoElements.map { e ->
        val t = e.thumbnails!!
        Photo(
            file = e.file.orEmpty(),
            space = space ?: pendingSpace.orEmpty(),
            album = e.sourceAlbum ?: name,
            mUrl = baseUrl + t.m,
            xlUrl = baseUrl + t.xl,
            fullUrl = t.full.takeIf { it.isNotBlank() }?.let { baseUrl + it },
            originalUrl = baseUrl + e.original!!,
            resolution = e.resolution,
            editableFormat = e.editableFormat,
        )
    }
    return photos to photoElements.withIndex().associate { (i, e) -> e.id to i }
}

/** Album frisch vom Server holen (nach Umbenennen/EXIF-Rewrite). */
fun AppViewModel.reloadAlbum() {
    openAlbum?.summary?.let { openAlbum(it) }
}

fun AppViewModel.closeAlbum() {
    verlaufLeeren()
    // Ungespeichertes noch rausschreiben — saveNow() haelt das Album fest,
    // bevor openAlbum genullt wird.
    if (saveState == SaveState.Unsaved) saveNow()
    autoSaveJob?.cancel()
    isEditMode = false
    editingElementId = null
    saveState = SaveState.Idle
    saveError = null
    videoUrl = null
    openPdf = null
    lightboxIndex = null
    tourDaten.clear()
    tourLaeufe.values.forEach { it.cancel() }
    tourLaeufe.clear()
    openAlbum = null
    albumAusErinnerung = false
    lightboxZurPinnwand = false
}

package de.bgghome.mpd

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.state.moduleLockedText
import de.bgghome.mpd.state.serverUnreachable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import de.bgghome.mpd.api.deleteFile
import de.bgghome.mpd.api.durchlaufTotal
import de.bgghome.mpd.api.importFile
import de.bgghome.mpd.api.recycleStatus
import de.bgghome.mpd.api.timeline

// Zeitstrahl: Laden, Geraetefotos, Kuratieren (Auswahl, Einsortieren, Loeschen), Backup.
// Erweiterungen von AppViewModel, herausgeloest aus AppViewModel.kt (13.11.2026).

fun AppViewModel.kickUpload() {
    plattform.kickUpload()
    uploadPollJob?.cancel()
    uploadPollJob = viewModelScope.launch {
        repeat(40) {
            delay(3000)
            plattform.holeUploadFehler()?.let {
                uploadError = it
                return@launch
            }
        }
    }
}

internal fun AppViewModel.sameItem(
    a: de.bgghome.mpd.api.TimelineItem,
    b: de.bgghome.mpd.api.TimelineItem,
) = a.space == b.space && a.album == b.album && a.file == b.file

fun AppViewModel.isSelected(item: de.bgghome.mpd.api.TimelineItem) =
    timelineSelection.any { sameItem(it, item) }

/**
 * Markieren — auch Aufnahmen vom Geraet (06.09.2026: „kann man
 * nicht in der Timeline Fotos die nur auf Gerät sind löschen?").
 *
 * Was mit ihnen geht, entscheiden die Aktionen einzeln: wegwerfen ja,
 * einsortieren nein. Auf der NAS haben sie weder Space noch Album, ein
 * Server-Aufruf ginge mit leerem Space hinaus.
 */

fun AppViewModel.toggleSelect(item: de.bgghome.mpd.api.TimelineItem) {
    // Wer weder wegwerfen noch einsortieren darf, soll nicht erst
    // markieren und dann am 403 haengenbleiben.
    if (item.localUri == null && !canContribute(item.space)) {
        curateStatus = t(Res.string.vm_shared_curator_only)
        return
    }
    val existing = timelineSelection.indexOfFirst { sameItem(it, item) }
    if (existing >= 0) timelineSelection.removeAt(existing)
    else timelineSelection.add(item)
}

fun AppViewModel.clearSelection() = timelineSelection.clear()

/** Einsortieren: je Foto ein atomarer Aufruf, chronologisch aufsteigend
 *  — dann stimmt die Grundreihenfolge im Zielalbum. */

fun AppViewModel.importSelectionTo(targetSpace: String, targetAlbum: String) {
    if (curateBusy) return
    // Geraete-Aufnahmen kann der Server nicht einsortieren, er hat sie
    // nicht. Erst sichern (das Kamera-Backup erledigt es), dann kuratieren.
    val all = timelineSelection.sortedBy { it.date }
    val batch = all.filter { it.localUri == null }
    val skipped = all.size - batch.size
    if (batch.isEmpty()) {
        curateStatus = t(Res.string.vm_device_only_backup_first)
        clearSelection()
        return
    }
    if (skipped > 0) {
        curateStatus = t(Res.string.vm_skipped_unsaved_n, skipped)
    }
    curateBusy = true
    viewModelScope.launch {
        var ok = 0
        var schonDa = 0
        var umbenannt = 0
        var adoptiert = 0
        var firstError: String? = null
        for (item in batch) {
            try {
                val r = api.importFile(
                    baseUrl, targetSpace, targetAlbum,
                    item.space, item.album, item.file,
                )
                ok++
                if (r.duplicate) schonDa++
                if (r.renamedFrom != null) umbenannt++
                if (r.adopted) adoptiert++
            } catch (e: MpdHttpException) {
                if (firstError == null) firstError = e.detail
            } catch (e: IOException) {
                if (firstError == null) firstError = serverUnreachable()
                break
            }
        }
        curateBusy = false
        // Namenskollisionen sind seit dem 08.09. kein Fehler mehr
        // (Server 4ee1b4e): bytegleich → lag schon dort, Quelle im
        // Papierkorb; anderer Inhalt → unter neuem Namen. Beides
        // ehrlich dazusagen, sonst wundert man sich ueber „_1".
        val zusatz = listOfNotNull(
            schonDa.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_dup_n, it) },
            umbenannt.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_renamed_n, it) },
            adoptiert.takeIf { it > 0 }?.let { plural(Res.plurals.vm_import_adopted_n, it) },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        curateStatus = if (firstError == null) {
            t(Res.string.vm_imported_to, ok, targetAlbum, zusatz)
        } else {
            t(Res.string.vm_imported_partial, ok, batch.size, zusatz, firstError)
        }
        clearSelection()
        loadTimeline(refresh = true)
    }
}

/**
 * Papierkorb-Status je Space fuer die Loesch-Rueckfrage der Timeline.
 *
 * Der Papierkorb ist eine Eigenschaft der FREIGABE: der Server prueft
 * `<space>/#recycle` (album_files.py), also ob auf der DSM-Freigabe
 * der Papierkorb eingeschaltet ist (06.09.2026). Das kann je
 * Space verschieden sein — Shared hat ihn vielleicht, das Home nicht.
 * Eine Auswahl quer ueber beide haette also zwei Schicksale, und ein
 * einzelnes recycleActive wuerde fuer die Haelfte luegen.
 */

fun AppViewModel.loadRecycleStatusForSpaces(spaces: Set<String>) {
    for (space in spaces) {
        if (space.isBlank() || recycleBySpace.containsKey(space)) continue
        viewModelScope.launch {
            runCatching { api.recycleStatus(baseUrl, space) }
                // null = der Server sagt es nicht; dann bleibt der
                // Eintrag offen und der Dialog wird nicht konkret.
                .onSuccess { answer -> answer?.let { recycleBySpace[space] = it } }
        }
    }
}

/**
 * Darf diese Datei aus der Timeline geloescht werden?
 *
 * 06.09.2026, ausdruecklich: „im Timeline darf NIE Medien
 * geloescht werden koennen, die in einem Albumordner mit album.json
 * sind." Das ist strenger als „nicht referenziert" — in einem
 * Albumordner kann eine Datei liegen, die (noch) in keiner album.json
 * steht; die waere sonst loeschbar gewesen.
 *
 * /api/albums listet genau die Ordner mit album.json (albums.py: wer
 * keine hat, wird uebersprungen). Die Albenliste ist also die Antwort
 * auf die Frage — solange sie da ist. Ist sie es nicht, gilt die Datei
 * als geschuetzt: bei „nie" ist der sichere Standard der einzige.
 *
 * Zusaetzlich bleibt die Referenz-Pruefung: eine Datei im Durchlauf
 * kann per source_album in einem Album haengen, ohne dort zu liegen.
 * Die Regel selbst steht in Regeln.darfLoeschen; hier nur der Zustand.
 */

fun AppViewModel.isDeletable(item: de.bgghome.mpd.api.TimelineItem): Boolean {
    // Im Zeitstrahl geht die Geraete-Aufnahme einen eigenen Weg (den
    // Android-Papierkorb), deshalb hier ausdruecklich false statt der
    // Regel — die Auswahl behandelt beide Faelle getrennt.
    if (item.localUri != null) return false
    return de.bgghome.mpd.data.Regeln.darfLoeschen(
        nurAufGeraet = false,
        referenziert = item.referenced,
        ausAlbum = false,
        space = item.space,
        album = item.album,
        darfBeitragen = canContribute(item.space),
        albumOrdner = albumOrdner,
    )
}

fun AppViewModel.isDeletable(p: Photo, fromAlbum: Boolean = false): Boolean =
    de.bgghome.mpd.data.Regeln.darfLoeschen(
        nurAufGeraet = p.isLocal,
        referenziert = p.referenced,
        ausAlbum = fromAlbum,
        space = p.space,
        album = p.album,
        darfBeitragen = canContribute(p.space),
        albumOrdner = albumOrdner,
    )

/**
 * Einzelnes Foto aus der Lightbox wegwerfen. Geraete-Aufnahmen gehen
 * ueber den Android-Papierkorb (die UI holt pendingTrash ab), alles
 * andere ueber den Server.
 */

fun AppViewModel.deleteSinglePhoto(p: Photo, fromAlbum: Boolean = false, onDone: () -> Unit) {
    // Der Kontext MUSS mit: ohne ihn scheiterte das Loeschen aus dem
    // Album an der eigenen Regel („gehoert zu einem Album"), also genau
    // dort, wo es erlaubt sein soll. Am 06.09.: „in der Lightbox
    // loescht es scheinbar gar nicht."
    if (!isDeletable(p, fromAlbum)) {
        curateStatus = t(Res.string.vm_belongs_to_album_delete_there)
        return
    }
    if (p.isLocal) {
        wegwerfenLokal(listOf(p.originalUrl))
        onDone()
        return
    }
    viewModelScope.launch {
        try {
            // Aus dem Album heraus ist Loeschen der vorgesehene Weg —
            // ohne dieses Feld schuetzt der Server die Datei mit 403.
            api.deleteFile(baseUrl, p.space, p.album, p.file, albumContext = fromAlbum)
            curateStatus = t(Res.string.vm_deleted)
            onDone()
            loadTimeline(refresh = true)
            // Und das offene Album: sonst steht die Datei dort weiter,
            // obwohl sie weg ist. Bisher wurde nur der Zeitstrahl
            // aufgefrischt — deshalb „geloescht, aber immer noch zu
            // sehen" (06.09.). Der Album-Editor braucht das
            // nicht, er nimmt das Element selbst aus der Liste
            // (removeElement); aus der Lightbox heraus gibt es kein
            // Element-Handle, also wird neu geladen.
            openAlbum?.summary?.let { openAlbum(it) }
        } catch (e: MpdHttpException) {
            curateStatus = explain(e)
            rechteNachladenNach(e)
        } catch (e: IOException) {
            curateStatus = serverUnreachable()
        }
    }
}

/**
 * Nach einem 403 die Rechte neu holen: Aendert ein Verwalter eine Rolle,
 * ist die App bis zum naechsten whoami veraltet — und bietet dann
 * entweder zu viel an (Knopf laeuft in den Fehler) oder zu wenig
 * (Funktion fehlt grundlos).
 */

internal fun AppViewModel.rechteNachladenNach(e: MpdHttpException) {
    if (e.code != 403) return
    viewModelScope.launch {
        runCatching {
            val frisch = api.whoami(baseUrl)
            if (state is AppState.LoggedIn) state = AppState.LoggedIn(frisch)
        }
    }
}

/**
 * Server-Fehler in einen Satz uebersetzen, den man lesen kann. Der
 * Server liefert bei 403 zwar schon deutschen Text, aber er erklaert
 * die Rolle nicht — und ein leeres detail landete bisher als „null"
 * in der Meldung.
 *
 * Bewusst NICHT dasselbe wie `apiErrorText()` in ui/Bausteine.kt: das
 * nimmt bei 403 immer den Satz des Aufrufers (Modul-Hinweis), dieses
 * hier zuerst das `detail` des Servers und erst dann einen eigenen
 * Satz. Zwei Abbildungen, zwei Zwecke — Rechtefehler beim Kuratieren
 * gegen Modulsperren beim Oeffnen.
 */

fun AppViewModel.explain(e: MpdHttpException): String = when {
    e.detail.isNotBlank() -> e.detail
    e.code == 403 -> t(Res.string.vm_explain_403)
    e.code == 404 -> t(Res.string.vm_explain_404)
    e.code == 409 -> t(Res.string.vm_explain_409)
    else -> t(Res.string.vm_explain_other, e.code)
}

/** Grund, warum eine Datei bleibt — fuer die Meldung. */
fun AppViewModel.protectionReason(item: de.bgghome.mpd.api.TimelineItem): String? = when {
    item.localUri != null -> null
    !canContribute(item.space) -> t(Res.string.vm_reason_no_permission)
    item.referenced -> t(Res.string.vm_reason_in_album)
    albums == null -> t(Res.string.vm_reason_albums_not_loaded)
    albums.orEmpty().any { it.space == item.space && it.name == item.album } ->
        t(Res.string.vm_reason_in_album_folder)
    else -> null
}

/**
 * Loeschen (Ausschuss im Durchlauf): sequenziell, auch Videos.
 *
 * Einsortierte Dateien bleiben unangetastet — 06.09.2026: „es
 * darf nur löschbar sein wenn nicht in Album". Der Server erzwingt das
 * nur fuer Beitragende (album_files.py: contributor_only); ein Kurator
 * duerfte auch referenzierte Dateien loeschen, und dann klafft im Album
 * eine Luecke. Loeschen ist fuer den Ausschuss da, nicht zum Aufraeumen
 * fertiger Alben — also filtert die App vorher (isDeletable) und sagt,
 * was sie uebergangen hat.
 */

fun AppViewModel.deleteSelection() {
    if (curateBusy) return
    val all = timelineSelection.toList()
    // Geraete-Aufnahmen wandern nicht ueber den Server, sondern in den
    // Android-Papierkorb — 30 Tage wiederherstellbar, und das System
    // fragt selbst nach. Die UI holt sie hier ab.
    val local = all.mapNotNull { it.localUri }
    val batch = all.filter { isDeletable(it) }
    val kept = all.count { it.localUri == null && !isDeletable(it) }
    if (local.isNotEmpty()) wegwerfenLokal(local)
    if (batch.isEmpty()) {
        curateStatus = when {
            kept == 0 -> null // rein lokal: der Systemdialog uebernimmt
            kept == 1 -> t(Res.string.vm_kept_one)
            else -> t(Res.string.vm_kept_all_n, kept)
        }
        clearSelection()
        return
    }
    curateBusy = true
    viewModelScope.launch {
        var ok = 0
        var firstError: String? = null
        for (item in batch) {
            try {
                // Zeitstrahl: ausdruecklich OHNE Album-Kontext. Was zu
                // einem Album gehoert, wird dort geloescht, nicht hier.
                api.deleteFile(baseUrl, item.space, item.album, item.file)
                ok++
            } catch (e: MpdHttpException) {
                if (firstError == null) firstError = e.detail
            } catch (e: IOException) {
                if (firstError == null) firstError = serverUnreachable()
                break
            }
        }
        curateBusy = false
        val keptNote = if (kept > 0) t(Res.string.vm_kept_note_n, kept) else ""
        curateStatus = if (firstError == null) t(Res.string.vm_deleted_n, ok, keptNote)
        else t(Res.string.vm_deleted_partial, ok, batch.size, firstError, keptNote)
        clearSelection()
        loadTimeline(refresh = true)
    }
}

// -- Favoriten (App-Extra 02.09.2026) ------------------------------------

/** Stern-Zustand je Space und der Roundtrip ins favoriten-Album liegen
 *  in state/FavoritesState.kt. */

fun AppViewModel.cycleTimelineTheme() {
    val order = listOf("dunkel", "hell", "auto")
    timelineTheme = order[(order.indexOf(timelineTheme) + 1).mod(order.size)]
    settings.timelineTheme = timelineTheme
}

/**
 * Erinnerung antippen: Album oeffnen (im Bestand des Fotos), Foto in der Lightbox.
 * Die Pinnwand bleibt offen und kommt beim Zurueck wieder.
 *
 * `nurFoto`: ein Polaroid will das eine Bild gross sehen — schliesst
 * man die Lightbox, geht es direkt zurueck zur Pinnwand, nicht erst
 * ins Album. „noch X weitere" dagegen will ins Album; dort fuehrt erst
 * das Schliessen des Albums zurueck.
 *
 * BIS 26.09.2026 SAH MAN NICHTS: Album und Lightbox gingen auf, aber
 * die Pinnwand wird in beiden Hosts NACH ihnen gezeichnet und lag
 * darueber. Jetzt blendet der Host sie aus, solange
 * [albumAusErinnerung] gilt.
 */

fun AppViewModel.loadDurchlaufCount(who: WhoAmI? = (state as? AppState.LoggedIn)?.who) {
    val karte = who?.rights ?: return
    val spaces = listOf("shared", "personal")
        .filter { "contribute" in karte[it].orEmpty() }
    if (spaces.isEmpty()) {
        durchlaufCount = 0
        return
    }
    viewModelScope.launch {
        runCatching { spaces.sumOf { api.durchlaufTotal(baseUrl, it) } }
            .onSuccess { durchlaufCount = it }
    }
}

fun AppViewModel.loadTimeline(refresh: Boolean = false) {
    if (refresh) {
        // Jedes Loeschen, Einsortieren und Sichern laeuft hier durch —
        // der guenstigste Ort, die Zahl im Kopf nachzuziehen.
        loadDurchlaufCount()
        // Ein laufender Lauf wird abgebrochen und von vorn begonnen.
        // Bis 08.09.2026 wurde ein refresh bei laufendem Laden still
        // verworfen — wer waehrend des eifrigen Erstladens (bei 17.500
        // Dateien Sekunden) loeschte oder einsortierte, sah danach
        // den alten Stand.
        timelineJob?.cancel()
        timelineItems.clear()
        timelineCursor = null
        timelineEnd = false
        timelineError = null
    } else if (timelineJob?.isActive == true) {
        return
    }
    if (timelineEnd) return
    timelineJob = viewModelScope.launch {
        timelineLoading = true
        try {
            do {
                // Ein Wiederholungsversuch je Seite (11.09.2026
                // unterwegs: „die Timeline war instabil, da kam mal
                // Server nicht erreichbar"). Der Zeitstrahl holt bei
                // 17.500 Dateien rund 36 Seiten am Stueck — ueber
                // Mobilfunk genuegte bisher EIN Aussetzer, und der
                // ganze Lauf war verloren, auch die 35 gelungenen.
                val page = try {
                    api.timeline(baseUrl, timelineCursor, limit = 500)
                } catch (e: IOException) {
                    delay(1500)
                    api.timeline(baseUrl, timelineCursor, limit = 500)
                }
                timelineItems.addAll(page.items)
                timelineCursor = page.nextCursor
                timelineEnd = page.nextCursor == null
                rebuildTimelineFeed()
            } while (!timelineEnd)
            timelineError = null
        } catch (e: MpdHttpException) {
            timelineError = if (e.code == 403) {
                moduleLockedText("Timeline")
            } else e.detail
        } catch (e: IOException) {
            // Der Teilstand BLEIBT stehen: timelineCursor zeigt auf die
            // letzte gelungene Seite, timelineEnd ist false — ein
            // spaeterer Aufruf macht genau dort weiter, statt von vorn.
            timelineError = serverUnreachable(e)
        } finally {
            // Ein abgebrochener Lauf darf den Nachfolger nicht auf
            // „fertig" setzen — der hat timelineLoading gerade selbst
            // gesetzt.
            if (isActive) {
                timelineLoading = false
                timelineLoadedAt = System.currentTimeMillis()
            }
        }
    }
}

fun AppViewModel.loadDeviceFolderList() {
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val found = plattform.localFolders()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            deviceFolderList = found.takeIf { it.isNotEmpty() }
        }
    }
}

fun AppViewModel.toggleTimelineBucket(bucket: String) {
    val next = if (bucket in timelineHiddenBuckets) timelineHiddenBuckets - bucket
    else timelineHiddenBuckets + bucket
    timelineHiddenBuckets = next
    settings.timelineHiddenBuckets = next
    loadLocalMedia()
}

/**
 * content://-Adressen, die in den Android-Papierkorb sollen. Die UI
 * baut daraus den System-Dialog (MediaStore.createTrashRequest) — nur
 * die Activity darf ihn starten, das ViewModel kennt keine.
 */

internal fun AppViewModel.wegwerfenLokal(uris: List<String>) {
    if (uris.isEmpty()) return
    if (plattform.papierkorbFragtSelbst) {
        pendingTrash = uris
        return
    }
    viewModelScope.launch {
        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            plattform.inDenPapierkorb(uris)
        } ?: 0
        clearSelection()
        curateStatus = if (n == uris.size) plural(Res.plurals.vm_trashed_pc_n, n)
        else t(Res.string.vm_trash_pc_failed, uris.size - n)
        loadLocalMedia()
    }
}

fun AppViewModel.lokalenOrdnerHinzufuegen(pfad: String) {
    val neu = (settings.lokaleOrdner + pfad).toSet()
    settings.lokaleOrdner = neu
    lokaleOrdner = neu.sorted()
    loadDeviceFolderList()
    loadLocalMedia()
}

fun AppViewModel.lokalenOrdnerEntfernen(pfad: String) {
    val neu = settings.lokaleOrdner - pfad
    settings.lokaleOrdner = neu
    lokaleOrdner = neu.sorted()
    loadDeviceFolderList()
    loadLocalMedia()
}

/** „3 / 10 hochgeladen …" waehrend „Sichern" im Vordergrund laeuft
 *  (Desktop); die Auswahl-Leiste zeigt es statt der Auswahlzahl. */

/** Adresse des unveraenderten Originals einer Server-Datei (auch Video). */
fun AppViewModel.originalUrl(item: de.bgghome.mpd.api.TimelineItem): String =
    baseUrl + (item.original ?: MpdApi.originalPath(item.space, item.album, item.file))

/**
 * Markierte Server-Fotos herunterladen (Desktop, Thomas 04.10.2026:
 * „nach dem Markieren will man diese auf dem Rechner runterladen").
 * Nacheinander, Fortschritt in der Leiste wie beim Sichern. Dateien
 * vom PC selbst werden uebersprungen — sie liegen ja schon hier.
 */

fun AppViewModel.herunterladen(laden: suspend (url: String, name: String) -> String?) {
    if (curateBusy) return
    val items = timelineSelection.filter { it.localUri == null }
    if (items.isEmpty()) {
        curateStatus = t(Res.string.vm_download_only_pc)
        return
    }
    curateBusy = true
    clearSelection()
    viewModelScope.launch {
        var ok = 0
        var fehler: String? = null
        var ordner: String? = null
        try {
            for ((i, item) in items.withIndex()) {
                uploadFortschritt = t(Res.string.vm_download_progress, i, items.size)
                try {
                    laden(originalUrl(item), item.file)?.let {
                        ok++
                        ordner = java.io.File(it).parent
                    }
                } catch (e: MpdHttpException) {
                    if (fehler == null) fehler = "${item.file}: ${explain(e)}"
                } catch (e: IOException) {
                    if (fehler == null) fehler = serverUnreachable()
                    break
                }
            }
        } finally {
            curateBusy = false
            uploadFortschritt = null
        }
        curateStatus = when {
            ok == items.size -> plural(Res.plurals.vm_downloaded_n, ok, ok, ordner ?: "")
            else -> t(Res.string.vm_downloaded_partly, ok, items.size, fehler ?: "")
        }
    }
}

fun AppViewModel.trashFinished(ok: Boolean, count: Int) {
    pendingTrash = emptyList()
    clearSelection()
    if (ok) {
        curateStatus = plural(Res.plurals.vm_trashed_n, count)
        loadLocalMedia()
    }
}

// -- Papierkorb des Geraets: ansehen, zurueckholen, endgueltig weg ------

/**
 * Lokal bearbeitetes Bild, das auf die Zustimmung zum Ueberschreiben
 * wartet. Fremde Mediendateien darf eine App nur mit
 * MediaStore.createWriteRequest aendern — den Dialog stellt Android,
 * die UI holt das hier ab.
 */

fun AppViewModel.localEditFinished(ok: Boolean) {
    pendingLocalEdit = null
    if (ok) {
        curateStatus = t(Res.string.vm_saved)
        loadLocalMedia()
    }
}

/** Obergrenze fuers Mehrfach-Weitergeben — Share-Sheet und Cache
 *  sind fuer den Urlaubsabend gedacht, nicht fuer das ganze Jahr. */

fun AppViewModel.sichereJetzt(keys: List<String>) {
    val zu = keys.distinct().filter { it.isNotBlank() }
    if (zu.isEmpty()) return
    if (!plattform.hatLokaleOrdner) {
        plattform.sichereJetzt(zu)
        curateStatus = plural(Res.plurals.vm_backing_up_n, zu.size)
        clearSelection()
        return
    }
    // Desktop (04.10.2026): kein WorkManager, also im Vordergrund und
    // nacheinander. Die Leiste zeigt den Fortschritt; danach wird neu
    // geladen, damit die durchgestrichene Wolke verschwindet.
    if (curateBusy) return
    curateBusy = true
    clearSelection()
    uploadFortschritt = t(Res.string.vm_upload_progress, 0, zu.size)
    viewModelScope.launch {
        val r = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                plattform.sichereUndWarte(zu) { fertig, gesamt ->
                    viewModelScope.launch {
                        uploadFortschritt = t(Res.string.vm_upload_progress, fertig, gesamt)
                    }
                }
            }
        } finally {
            curateBusy = false
            uploadFortschritt = null
        }
        if (r == null) return@launch
        curateStatus = when {
            r.ok == r.gesamt -> plural(Res.plurals.vm_backed_up_n, r.ok)
            else -> t(Res.string.vm_backed_up_partly, r.ok, r.gesamt, r.fehler ?: "")
        }
        settings.backupLastRun = System.currentTimeMillis()
        loadTimeline(refresh = true)
        loadLocalMedia()
    }
}

/**
 * Was der Zaehler-Chip meldet und auf einen Tipp hin sichert: nur
 * Aufnahmen aus UEBERWACHTEN Ordnern, die noch nicht oben sind.
 *
 * Nicht alles Ungesicherte im Zeitstrahl — der zeigt auch WhatsApp,
 * Screenshots und Download, wenn man sie nicht ausblendet. Diese
 * Ordner hat man BEWUSST nicht ins Backup genommen; ein Chip, der
 * sie zaehlt und auf einen Tipp hin geschlossen auf die NAS schiebt,
 * traefe eine Entscheidung, die der Nutzer schon anders getroffen
 * hat (07.09.2026: „was soll eigentlich jetzt sichern?").
 *
 * Die Regel: massenhaft nur, was das Backup ohnehin haette holen
 * sollen (aber nicht konnte, weil aelter als der Einschaltzeitpunkt);
 * einzeln alles, wenn man es ausdruecklich sagt — dafuer sind die
 * Wolke auf der Kachel und „Sichern" in der Auswahl da, die beide
 * KEINEN Ordnerfilter haben.
 *
 * localKey ist nicht nullbar, localUri ist das Kennzeichen; `album`
 * traegt bei Geraete-Aufnahmen den Ordnernamen (LocalMedia.scan).
 */
/** Spiegel der ueberwachten Ordner als Compose-Zustand, damit
 *  [ungesichert] nach einer Aenderung im Backup-Dialog neu rechnet.
 *  Schreibt die Android-Seite (BackupState.toggleBucket). */

fun AppViewModel.refreshAfterBackup() {
    if (timelineItems.isEmpty()) return
    if (settings.backupLastRun <= timelineLoadedAt) return
    loadTimeline(refresh = true)
    loadLocalMedia()
}

/** Der laufende Geraete-Scan — der letzte Aufruf gewinnt. Ohne das
 *  konnten zwei Scans mit verschiedenem Ordnerfilter ueberlappen und
 *  der aeltere zuletzt schreiben. */

fun AppViewModel.loadLocalMedia() {
    localMediaJob?.cancel()
    localMediaJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val hidden = timelineHiddenBuckets
        val found = plattform.scanLocalMedia(hidden)
        val ledger = plattform.ledgerKeys()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            localItems.clear()
            localItems.addAll(found)
            backupLedger = ledger
            rebuildTimelineFeed()
        }
    }
}

fun AppViewModel.rebuildTimelineFeed() {
    val serverNameDay = HashSet<String>(timelineItems.size * 2)
    val serverStamps = HashSet<String>(timelineItems.size * 2)
    for (item in timelineItems) {
        serverNameDay.add(de.bgghome.mpd.data.Regeln.nameTagSchluessel(item.file, item.date))
        if (item.file.length >= 19) serverStamps.add(item.file.take(19))
    }
    val onlyOnDevice = de.bgghome.mpd.data.Regeln.nurAufDemGeraet(
        lokal = localItems.toList(),
        ledger = backupLedger,
        serverStempel = serverStamps,
        serverNameTag = serverNameDay,
        ledgerSchluessel = { it.localKey },
        stempel = { de.bgghome.mpd.data.Stempel.vonIso(it.date) },
        nameTag = { de.bgghome.mpd.data.Regeln.nameTagSchluessel(it.file, it.date) },
    )
    // Space-Filter erst HIER, nicht in der Abfrage: der Abgleich oben
    // (nur auf dem Geraet? schon gesichert?) braucht den ganzen
    // Serverbestand — mit gefilterter Abfrage galten Fotos im anderen
    // Space als ungesichert. Was nur auf dem Geraet liegt, gehoert
    // niemandem sonst: es zaehlt zu „Persoenlich", nicht zu „Geteilt".
    val filter = _spaceFilter
    val server = if (filter == null) timelineItems else timelineItems.filter { it.space == filter }
    val geraet = if (filter == "shared") emptyList() else onlyOnDevice
    timelineFeed.clear()
    if (geraet.isEmpty()) {
        timelineFeed.addAll(server)
    } else {
        timelineFeed.addAll(
            (server + geraet).sortedByDescending { it.date },
        )
    }
    // Das Gegenstueck: was auf dem Geraet liegt UND nachweislich oben
    // ist — die Menge fuer „Speicherplatz freigeben". Bewusst aus
    // derselben Rechnung wie der Strom, nie eine zweite Regel.
    val nurGeraet = onlyOnDevice.mapTo(HashSet()) { it.localKey }
    gesichertAufGeraet = localItems.filter { it.localKey !in nurGeraet }
    timelineFeedVersion++
}

/**
 * Geraete-Aufnahmen, die schon auf der NAS liegen — Google Fotos'
 * „Speicherplatz freigeben" (08.09.2026, Galerie-Prueffrage:
 * koennte jemand danach Google Fotos deinstallieren?). Nur was das
 * Ledger, der Sekundenstempel oder Name+Tag als gesichert erkennt;
 * nichts wird geraten.
 */

fun AppViewModel.speicherFreigeben() {
    val uris = gesichertAufGeraet.mapNotNull { it.localUri }
    if (uris.isEmpty()) return
    pendingTrash = uris
}

/** Video-Stream direkt per URL abspielen (Timeline, ohne Albumkontext). */
fun AppViewModel.openVideoUrl(url: String) {
    videoUrl = url
}

fun AppViewModel.closeTimelineLightbox() {
    timelineLightbox = null
}

fun AppViewModel.openFromTimeline(item: de.bgghome.mpd.api.TimelineItem) {
    if (item.type == "video") {
        // Tippen spielt ab — auch wenn das Video in einem Album steckt.
        // Bis zum 06.09.2026 sprang ein referenziertes Video stattdessen
        // ins Album; dasselbe Tippen tat also je nach Medientyp etwas
        // voellig anderes. Rueckmeldung: „die sollen auch nicht da gleich
        // hinspringen, man will es nur wissen" — wissen kann man es im
        // Info-Blatt, wechseln soll niemand ungefragt.
        item.localUri?.let { openVideoUrl(it); return }
        val base = baseUrl.toHttpUrlOrNull() ?: return
        openVideoUrl(
            base.newBuilder()
                .addPathSegment("api").addPathSegment("video")
                .addPathSegment(item.space).addPathSegment(item.album)
                .addPathSegment(item.file).build().toString(),
        )
        return
    }
    val photoItems = timelineFeed.filter { it.type == "photo" }
    val index = photoItems.indexOfFirst {
        it.space == item.space && it.album == item.album && it.file == item.file
    }
    if (index >= 0) {
        timelineLightbox = photoItems.map { it.toPhoto() } to index
    }
}

// -- Foto-Editor / EXIF (Lightbox) --------------------------------------

/**
 * Datei → Zeitstempel des letzten Edits dieser Sitzung. Seit dem
 * Server-ETag (Cache-Control: no-cache, 01.09.2026) ist KEIN
 * ?t=-Cache-Bust mehr noetig — Coil revalidiert selbst, der
 * Original-Cache prueft per If-None-Match. Die Map dient nur noch als
 * Recompose-Ausloeser, damit die offene Lightbox-Seite nach dem
 * Speichern sofort neu laedt statt erst beim naechsten Oeffnen.
 */

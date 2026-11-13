package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AlbumSummary
import de.bgghome.mpd.api.MapPin
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.api.TourListEntry
import de.bgghome.mpd.api.UnassignedFile
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.deleteTour
import de.bgghome.mpd.api.gpxFiles
import de.bgghome.mpd.api.placeTour
import de.bgghome.mpd.api.tours
import de.bgghome.mpd.api.unassignedFiles
import de.bgghome.mpd.canCurate
import de.bgghome.mpd.ersetzeTourDatei
import de.bgghome.mpd.meldung
import de.bgghome.mpd.removeElement
import de.bgghome.mpd.setAlbumCover
import de.bgghome.mpd.speichereSofort
import de.bgghome.mpd.tourElementeFuer

/*
 * Einfuege-Dialoge fuer die restlichen Elementtypen + Cover-Wechsler.
 * Web-Referenzen: openAddAudioModal/openAddDocumentModal (album-ui.js),
 * openAddTourModal (Tour-Browser mit Fotozeitraum-Filter), openAddMapDialog
 * (album-map.js: Ortssuche, Tipp=Pin, Pin-Liste, Bogen-Vorschau),
 * openMixedPicker (album-mixed-picker.js, source_album) und
 * openThumbnailModal (Cover).
 *
 * Android-Anpassungen (bewusst): Dialoge bleiben fuer Serien offen,
 * Mixed-Picker mit Haekchen-Mehrfachauswahl und „Einfuegen (n)".
 */

// -- Audio / Dokument (Listen-Picker) ---------------------------------------

@Composable
internal fun AddFolderFileDialog(
    vm: AppViewModel,
    kind: String, // "audio" | "documents"
    onDismiss: () -> Unit,
    onPick: (UnassignedFile) -> Unit,
) {
    val album = vm.openAlbum ?: return
    var files by remember { mutableStateOf<List<UnassignedFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val title = stringResource(if (kind == "audio") Res.string.folder_audio_title else Res.string.folder_document_title)
    val icon = if (kind == "audio") MpdIcons.Audio else MpdIcons.Document

    LaunchedEffect(album.summary.name, kind) {
        runCatching {
            vm.api.unassignedFiles(vm.baseUrl, album.summary.space, album.summary.name, kind)
        }.onSuccess { files = it }.onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LoadState(
                error = error, data = files,
                isEmpty = { it.isEmpty() },
                errorContent = { Text(stringResource(Res.string.common_load_failed, it), color = MpdRed) },
                loading = { LoadingCenter(Modifier.fillMaxWidth().height(100.dp)) },
                empty = {
                    Text(
                        stringResource(Res.string.folder_empty),
                        color = MpdMuted,
                    )
                },
            ) { list ->
                LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                    items(list, key = { it.file }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    files = files!!.filterNot { it.file == f.file }
                                    onPick(f)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, Modifier.padding(end = 10.dp).size(20.dp), tint = MpdText)
                            Text(
                                f.file, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_done)) } },
    )
}

// -- Tour-Browser ------------------------------------------------------------

/** Eine Zeile im Tour-Dialog: gelistete Tour oder GPX nur im Albumordner. */
private data class TourWahl(
    val key: String,
    val tour: TourListEntry,
    /** Liegt schon im Ordner dieses Albums — Einfuegen braucht keinen Serveraufruf. */
    val imOrdner: Boolean,
    /** Nur im Albumordner, nicht in der Tourenliste (persoenliche Alben). */
    val nurOrdner: Boolean = false,
)

/**
 * Tour einfuegen — seit 11.10.2026 mit Mehrfachauswahl (Vorgabe: „mehrere
 * Touren im Einfügen-Fenster auswählbar, einfügen, aber auch
 * Löschmöglichkeit mit Bestätigungsabsicherung").
 *
 * Antippen markiert. „Einfuegen" legt alle markierten chronologisch ins
 * Album (je eine ueber /api/tours/place, wie im Web). „Loeschen" legt die
 * markierten in den Papierkorb der NAS — nur mit Kurator-Recht am
 * Familienbestand und nur, was in keinem Album steckt (der Server sagt
 * sonst 409); vorher eine Rueckfrage.
 */
@Composable
internal fun AddTourDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onPicked: (List<Pair<String, String?>>) -> Unit,
) {
    val album = vm.openAlbum ?: return
    // used_in und folder beziehen sich auf den Familienbestand — in einem
    // persoenlichen Album bedeutet ein gleichnamiger Ordner nichts (Web
    // _tourBrowserRow).
    val shared = album.summary.space == "shared"
    val albumName = album.summary.name
    val darfLoeschen = vm.canCurate("shared")
    val scope = rememberCoroutineScope()
    var tours by remember { mutableStateOf<List<TourListEntry>?>(null) }
    var folderFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var auswahl by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var loeschFrage by remember { mutableStateOf(false) }
    var tauschFrage by remember { mutableStateOf(false) }
    var neuLaden by remember { mutableStateOf(0) }

    // Fotozeitraum aus den Dateinamen (Web _albumPhotoDateRange)
    val range = remember(album.elements) {
        val re = Regex("""^(\d{4}-\d{2}-\d{2})""")
        val dates = album.elements
            .filter { it.type == "photo" }
            .mapNotNull { it.file?.let { f -> re.find(f)?.groupValues?.get(1) } }
        if (dates.isEmpty()) null else dates.min() to dates.max()
    }
    var showAll by remember { mutableStateOf(range == null) }

    LaunchedEffect(album.summary.name, neuLaden) {
        runCatching { vm.api.tours(vm.baseUrl) }
            .onSuccess { tours = it }
            .onFailure {
                error = apiErrorText(it, de.bgghome.mpd.state.moduleLockedText(Texte.t(Res.string.module_tours_name)))
            }
        runCatching {
            vm.api.gpxFiles(vm.baseUrl, album.summary.space, album.summary.name)
        }.onSuccess { folderFiles = it.toSet() }
    }

    // Alle Zeilen, die gerade zu sehen sind — Grundlage fuer Auswahl,
    // Einfuegen und Loeschen.
    val zeilen: List<TourWahl> = remember(tours, folderFiles, showAll, range) {
        val all = tours ?: return@remember emptyList()
        val shown = if (range != null && !showAll) {
            all.filter { it.date.isNotEmpty() && it.date >= range.first && it.date <= range.second }
        } else all
        // GPX im Albumordner, die die Liste nicht kennt — persoenliche
        // Alben (die Liste deckt nur den Familienbestand ab).
        val listed = if (shared) all.filter { it.folder == albumName }.map { it.filename }.toSet()
        else emptySet()
        shown.map { TourWahl(it.key, it, imOrdner = shared && it.folder == albumName) } +
            folderFiles.filterNot { it in listed }.sortedDescending().map {
                TourWahl("folder-$it", TourListEntry(filename = it), imOrdner = true, nurOrdner = true)
            }
    }
    val gewaehlt = zeilen.filter { it.key in auswahl }
    // Doppel-Aufzeichnungen (Huawei-Uhr und Komoot zugleich, 11.10.2026):
    // Touren, deren Aufzeichnungszeit sich ueberschneidet. Nur ein Hinweis —
    // welche bleibt, entscheidet Thomas beim Gestalten des Albums.
    val doppelt = remember(tours) { doppelteTouren(tours.orEmpty()) }
    // Steckt nur in DIESEM Album? Dann darf sie weg — das Element wird
    // vorher entfernt. In einem anderen Album bleibt sie unangetastet.
    fun nurHier(t: TourListEntry) =
        shared && t.usedIn.isNotEmpty() && t.usedIn.all { it.album == albumName }
    // Loeschbar: gelistet (mit Ordner), Kurator-Recht, in keinem oder nur
    // in diesem Album.
    val loeschbar = if (darfLoeschen) gewaehlt.filter {
        !it.nurOrdner && it.tour.folder.isNotEmpty() && (it.tour.usedIn.isEmpty() || nurHier(it.tour))
    } else emptyList()
    // Tausch: genau zwei markiert, eine steckt nur hier (die geht), die
    // andere steckt nicht hier (die nimmt ihren Platz ein).
    val tausch: Pair<TourWahl, TourWahl>? = run {
        if (!darfLoeschen || gewaehlt.size != 2) return@run null
        val alt = gewaehlt.filter { !it.nurOrdner && nurHier(it.tour) }
        val neu = gewaehlt.filter { w -> w.tour.usedIn.none { it.album == albumName } || !shared }
        if (alt.size == 1 && neu.size == 1 && alt[0] != neu[0]) alt[0] to neu[0] else null
    }

    fun einfuegen() {
        busy = true
        status = Texte.t(Res.string.tour_copying)
        statusIsError = false
        scope.launch {
            val picks = mutableListOf<Pair<String, String?>>()
            try {
                for (w in gewaehlt.sortedBy { it.tour.date }) {
                    if (w.imOrdner) {
                        picks += w.tour.filename to null
                    } else {
                        // Offen → wird verschoben; liegt sie in einem anderen
                        // Album, bleibt sie dort und das Element zeigt per
                        // source_album hin.
                        val placed = vm.api.placeTour(
                            vm.baseUrl, w.tour, albumName, album.summary.space, append = false,
                        )
                        picks += placed.file to placed.sourceAlbum
                    }
                }
                onPicked(picks)
                onDismiss()
            } catch (e: Exception) {
                // Was schon verschoben ist, gehoert ins Album — sonst laege
                // es im Albumordner, ohne dass ein Element darauf zeigt.
                if (picks.isNotEmpty()) onPicked(picks)
                status = Texte.t(Res.string.tour_error, e.message)
                statusIsError = true
                auswahl = emptySet()
                neuLaden++
            } finally {
                busy = false
            }
        }
    }

    fun loeschen() {
        val liste = loeschbar
        busy = true
        statusIsError = false
        scope.launch {
            var ok = 0
            var fehler: String? = null
            // Eingebundene zuerst aus dem Album nehmen und SOFORT speichern —
            // der Server prueft used_in an der gespeicherten album.json.
            val hier = liste.filter { nurHier(it.tour) }
            if (hier.isNotEmpty()) {
                hier.forEach { w ->
                    vm.tourElementeFuer(w.tour.folder, w.tour.filename).forEach { vm.removeElement(it.id) }
                }
                if (!vm.speichereSofort()) {
                    status = Texte.t(Res.string.tour_save_failed)
                    statusIsError = true
                    busy = false
                    return@launch
                }
            }
            for ((i, w) in liste.withIndex()) {
                status = Texte.t(Res.string.tours_recycle_progress, i.toString(), liste.size.toString())
                try {
                    vm.api.deleteTour(vm.baseUrl, w.tour)
                    ok++
                } catch (e: Exception) {
                    if (fehler == null) fehler = e.message
                }
            }
            status = Texte.t(Res.string.tours_recycled_n, ok.toString(), liste.size.toString()) +
                (fehler?.let { " — $it" } ?: "")
            statusIsError = fehler != null
            auswahl = emptySet()
            busy = false
            neuLaden++
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tour_insert_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        range?.let { "${it.first} – ${it.second}" } ?: stringResource(Res.string.tour_no_range),
                        color = MpdMuted, fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(stringResource(Res.string.tour_all), color = MpdMuted, fontSize = 12.sp)
                    Switch(
                        checked = showAll,
                        onCheckedChange = { showAll = it },
                        enabled = range != null,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                when {
                    error != null -> Text(
                        stringResource(Res.string.tour_load_failed, error.orEmpty()),
                        color = MpdRed, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    tours == null -> Box(
                        Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    else -> {
                        LazyColumn(Modifier.fillMaxWidth().height(340.dp).padding(top = 6.dp)) {
                            if (zeilen.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(if (range != null && !showAll) Res.string.tour_none_in_range else Res.string.tour_none),
                                        color = MpdMuted, fontSize = 13.sp,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                            var abschnittGezeigt = false
                            zeilen.forEach { w ->
                                if (w.nurOrdner && !abschnittGezeigt) {
                                    abschnittGezeigt = true
                                    item(key = "abschnitt-ordner") {
                                        Text(
                                            stringResource(Res.string.tour_folder_section),
                                            color = MpdMuted, fontSize = 10.sp, letterSpacing = 1.sp,
                                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                                item(key = w.key) {
                                    val t = w.tour
                                    val here = shared && t.usedIn.any { it.album == albumName }
                                    val others = if (shared) t.usedIn.filter { it.album != albumName } else t.usedIn
                                    TourRow(
                                        t, here = here, inFolder = w.imOrdner, others = others,
                                        gewaehlt = w.key in auswahl,
                                        doppelt = t.key in doppelt,
                                    ) {
                                        if (!busy) auswahl = if (w.key in auswahl) auswahl - w.key else auswahl + w.key
                                    }
                                }
                            }
                        }
                        status?.let {
                            Text(it, color = if (statusIsError) MpdRed else MpdMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tausch != null) {
                    TextButton(onClick = { tauschFrage = true }, enabled = !busy) {
                        Text(stringResource(Res.string.tour_swap))
                    }
                }
                if (darfLoeschen) {
                    TextButton(
                        onClick = { loeschFrage = true },
                        enabled = !busy && loeschbar.isNotEmpty(),
                    ) {
                        Text(
                            stringResource(Res.string.tour_delete_n, loeschbar.size.toString()),
                            color = if (!busy && loeschbar.isNotEmpty()) MpdRed else MpdMuted,
                        )
                    }
                }
                TextButton(onClick = { einfuegen() }, enabled = !busy && gewaehlt.isNotEmpty()) {
                    Text(stringResource(Res.string.tour_insert_n, gewaehlt.size.toString()))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )

    if (loeschFrage) {
        val bleiben = gewaehlt.size - loeschbar.size
        AlertDialog(
            onDismissRequest = { loeschFrage = false },
            title = { Text(stringResource(Res.string.tour_delete_title, loeschbar.size.toString())) },
            text = {
                Column {
                    loeschbar.take(6).forEach { w ->
                        Text(tourKurz(w.tour), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (nurHier(w.tour)) {
                            Text(
                                stringResource(Res.string.tour_delete_used_here),
                                color = MpdRed, fontSize = 11.sp,
                            )
                        }
                    }
                    if (loeschbar.size > 6) Text("…", fontSize = 13.sp)
                    if (bleiben > 0) {
                        Text(
                            stringResource(Res.string.tour_delete_kept, bleiben.toString()),
                            color = MpdMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Text(
                        stringResource(Res.string.tours_recycle_hint),
                        color = MpdMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { loeschFrage = false; loeschen() }) {
                    Text(stringResource(Res.string.tours_recycle), color = MpdRed)
                }
            },
            dismissButton = { TextButton(onClick = { loeschFrage = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }

    val paar = tausch
    if (tauschFrage && paar != null) {
        val (alt, neu) = paar
        AlertDialog(
            onDismissRequest = { tauschFrage = false },
            title = { Text(stringResource(Res.string.tour_swap_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.tour_swap_keep), color = MpdMuted, fontSize = 12.sp)
                    Text(tourKurz(neu.tour), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(Res.string.tour_swap_drop), color = MpdMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(tourKurz(alt.tour), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(Res.string.tour_swap_body),
                        color = MpdMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        stringResource(Res.string.tours_recycle_hint),
                        color = MpdMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tauschFrage = false
                    busy = true
                    statusIsError = false
                    status = Texte.t(Res.string.tour_copying)
                    scope.launch {
                        try {
                            // 1. Die bleibende Tour ins Album legen (oder sie
                            //    liegt schon im Ordner).
                            val (file, quelle) = if (neu.imOrdner) neu.tour.filename to null else {
                                val p = vm.api.placeTour(vm.baseUrl, neu.tour, albumName, album.summary.space, append = false)
                                p.file to p.sourceAlbum
                            }
                            // 2. Ihre Elemente uebernehmen den Platz der alten.
                            vm.tourElementeFuer(alt.tour.folder, alt.tour.filename)
                                .forEach { vm.ersetzeTourDatei(it.id, file, quelle) }
                            // 3. Speichern, dann erst die alte loeschen.
                            if (!vm.speichereSofort()) {
                                status = Texte.t(Res.string.tour_save_failed)
                                statusIsError = true
                                return@launch
                            }
                            vm.api.deleteTour(vm.baseUrl, alt.tour)
                            vm.meldung(Texte.t(Res.string.tour_swapped), kurz = true)
                            onDismiss()
                        } catch (e: Exception) {
                            status = Texte.t(Res.string.tour_error, e.message)
                            statusIsError = true
                            auswahl = emptySet()
                            neuLaden++
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.tour_swap)) }
            },
            dismissButton = { TextButton(onClick = { tauschFrage = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

/** „2024-05-01 07:02 · 🚴 Radtour · 23.4 km" — fuer Rueckfragen. */
private fun tourKurz(t: TourListEntry): String =
    listOf(
        listOf(t.date, startZeit(t).orEmpty()).filter { it.isNotEmpty() }.joinToString(" "),
        t.title?.takeIf { it.isNotBlank() } ?: t.label,
        t.dist?.let { "$it km" }.orEmpty(),
        t.duration.orEmpty(),
    ).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { t.filename }

/** Startzeit HH:mm in Ortszeit, null ohne Zeitstempel. */
private fun startZeit(t: TourListEntry): String? = runCatching {
    java.time.OffsetDateTime.parse(t.start!!).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .toLocalTime().toString().take(5)
}.getOrNull()

/**
 * Schluessel aller Touren, deren Aufzeichnung sich mit einer anderen
 * ueberschneidet — zwei Geraete, dieselbe Unternehmung. Gleiche Namen sind
 * kein Merkmal (Huawei und Komoot benennen verschieden), die Zeit schon.
 */
internal fun doppelteTouren(tours: List<TourListEntry>): Set<String> {
    data class Spanne(val key: String, val von: Long, val bis: Long)
    val spannen = tours.mapNotNull { t ->
        val von = runCatching { java.time.OffsetDateTime.parse(t.start!!).toEpochSecond() }.getOrNull()
            ?: return@mapNotNull null
        val dauer = t.durationS?.takeIf { it > 0 } ?: return@mapNotNull null
        Spanne(t.key, von, von + dauer)
    }.sortedBy { it.von }
    val out = HashSet<String>()
    for (i in spannen.indices) {
        var j = i + 1
        while (j < spannen.size && spannen[j].von < spannen[i].bis) {
            out += spannen[i].key
            out += spannen[j].key
            j++
        }
    }
    return out
}

@Composable
private fun TourRow(
    t: TourListEntry,
    here: Boolean,
    inFolder: Boolean,
    others: List<de.bgghome.mpd.api.TourUse> = emptyList(),
    gewaehlt: Boolean = false,
    doppelt: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (gewaehlt) MpdGoldBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(checked = gewaehlt, onCheckedChange = { onClick() })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(t.date, startZeit(t).orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
                        .ifEmpty { t.filename },
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (here) MpdMuted else Color.Unspecified,
                )
                if (doppelt) {
                    Text(
                        stringResource(Res.string.tour_double),
                        color = MpdRed, fontSize = 10.sp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .border(1.dp, MpdRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            val meta = buildList {
                (t.title?.takeIf { it.isNotBlank() } ?: t.label).takeIf { it.isNotEmpty() }?.let { add(it) }
                t.dist?.let { add("📍 $it km") }
                t.duration?.let { add("⏱ $it") }
                t.hrAvg?.let { add("♥ Ø$it") }
            }.joinToString("  ")
            if (meta.isNotEmpty()) Text(meta, color = MpdMuted, fontSize = 12.sp)
            if (others.isNotEmpty() || t.stub != null) {
                Text(
                    buildList {
                        others.forEach { add(Texte.t(Res.string.tour_used_in, it.label)) }
                        if (t.stub != null) add(Texte.t(Res.string.tour_stub))
                    }.joinToString(" · "),
                    color = MpdMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (here || inFolder) {
            Text(
                stringResource(if (here) Res.string.tour_already_inserted else Res.string.tour_in_album),
                color = MpdGoldLt, fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MpdGoldBg)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// -- Karten-Dialog -----------------------------------------------------------


// -- Mixed-Picker (Foto aus anderem Album) -----------------------------------

@Composable
internal fun MixedPickerDialog(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
    onInsert: (List<Pair<String, String>>) -> Unit, // (sourceAlbum, file)
) {
    val album = vm.openAlbum ?: return
    var albumList by remember { mutableStateOf<List<AlbumSummary>?>(null) }
    var browsing by remember { mutableStateOf<AlbumSummary?>(null) }
    var browsePhotos by remember { mutableStateOf<List<PdxElement>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val selection = remember { mutableStateOf<Set<Pair<String, String>>>(emptySet()) }

    LaunchedEffect(Unit) {
        runCatching { vm.api.albums(vm.baseUrl) }
            .onSuccess { list ->
                // Nur Alben desselben Space — source_album loest der Server
                // im selben Space auf (routers/albums.py).
                albumList = list.filter {
                    it.space == album.summary.space && it.name != album.summary.name
                }
            }
            .onFailure { error = it.message }
    }

    LaunchedEffect(browsing?.name) {
        browsePhotos = null
        browsing?.let { b ->
            runCatching { vm.api.album(vm.baseUrl, b.space, b.name) }
                .onSuccess { env ->
                    // Nur lokale Fotos der Quelle (keine Referenzketten) — Web-Filter
                    browsePhotos = env.content.elements.filter {
                        it.type == "photo" && it.file != null && it.sourceAlbum == null && it.error == null
                    }
                }
                .onFailure { error = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                browsing?.let { it.title.ifBlank { it.name } }
                    ?: stringResource(Res.string.fab_mixed),
            )
        },
        text = {
            when {
                error != null -> Text(stringResource(Res.string.common_load_failed, error.orEmpty()), color = MpdRed)

                browsing == null -> when {
                    albumList == null -> Box(
                        Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    albumList!!.isEmpty() -> Text(
                        stringResource(Res.string.mixed_no_albums), color = MpdMuted,
                    )

                    else -> LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                        items(albumList!!, key = { it.name }) { a ->
                            val selCount = selection.value.count { it.first == a.name }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { browsing = a }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    a.title.ifBlank { a.name },
                                    fontSize = 14.sp, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (selCount > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("$selCount", color = MpdGoldLt, fontSize = 12.sp)
                                        Spacer(Modifier.width(3.dp))
                                        Icon(MpdIcons.Check, stringResource(Res.string.mixed_selected), Modifier.size(12.dp), tint = MpdGoldLt)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> when {
                    browsePhotos == null -> Box(
                        Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(88.dp),
                        modifier = Modifier.fillMaxWidth().height(360.dp),
                    ) {
                        items(browsePhotos!!, key = { it.id }) { p ->
                            val key = browsing!!.name to p.file!!
                            val selected = key in selection.value
                            Box(
                                Modifier
                                    .padding(3.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        selection.value =
                                            if (selected) selection.value - key
                                            else selection.value + key
                                    },
                            ) {
                                AsyncImage(
                                    model = vm.baseUrl + (p.thumbnails?.sm ?: MpdApi.thumbnailPath(
                                        browsing!!.space, browsing!!.name, p.file!!, "sm",
                                    )),
                                    imageLoader = imageLoader,
                                    contentDescription = p.file,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (selected) {
                                    Box(
                                        Modifier.fillMaxSize().background(Color(0x662E75B6)),
                                        contentAlignment = Alignment.TopEnd,
                                    ) {
                                        Icon(
                                            MpdIcons.Check, stringResource(Res.string.mixed_selected), tint = Color.White,
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .size(22.dp)
                                                .background(PinBlue, CircleShape)
                                                .padding(4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val count = selection.value.size
            TextButton(
                enabled = count > 0,
                onClick = { onInsert(selection.value.toList()) },
            ) { Text(if (count > 0) stringResource(Res.string.mixed_insert_n, count) else stringResource(Res.string.common_insert)) }
        },
        dismissButton = {
            TextButton(onClick = { if (browsing != null) browsing = null else onDismiss() }) {
                Text(stringResource(if (browsing != null) Res.string.mixed_back_albums else Res.string.common_cancel))
            }
        },
    )
}

// -- Cover-Wechsler ----------------------------------------------------------

@Composable
internal fun CoverPickerDialog(
    vm: AppViewModel,
    imageLoader: ImageLoader,
    onDismiss: () -> Unit,
) {
    val album = vm.openAlbum ?: return
    val photos = remember(album.elements) {
        album.elements.filter { it.type == "photo" && it.thumbnails != null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cover_picker_title)) },
        text = {
            if (photos.isEmpty()) {
                Text(stringResource(Res.string.cover_picker_empty), color = MpdMuted)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                ) {
                    items(photos, key = { it.id }) { p ->
                        val isCurrent = p.file == album.summary.thumbnail
                        Box(
                            Modifier
                                .padding(3.dp)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black)
                                .clickable {
                                    vm.setAlbumCover(p) { err ->
                                        if (err != null) {
                                            vm.meldung(err)
                                        }
                                    }
                                    onDismiss()
                                },
                        ) {
                            AsyncImage(
                                model = vm.baseUrl + p.thumbnails!!.sm,
                                imageLoader = imageLoader,
                                contentDescription = p.file,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (isCurrent) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(0x552E75B6)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("aktuell", color = Color.White, fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) } },
    )
}

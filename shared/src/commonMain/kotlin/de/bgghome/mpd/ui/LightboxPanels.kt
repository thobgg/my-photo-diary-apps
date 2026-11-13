package de.bgghome.mpd.ui


import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.ExifData
import de.bgghome.mpd.api.PhotoEditParams
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.exif
import de.bgghome.mpd.api.renamePhoto
import de.bgghome.mpd.api.renamePreview
import de.bgghome.mpd.api.saveExif
import de.bgghome.mpd.bumpEditVersion
import de.bgghome.mpd.moduleAllowed
import de.bgghome.mpd.openTrailsAt
import de.bgghome.mpd.reloadAlbum

/*
 * Lightbox-Zusatzflaechen: EXIF-Panel (lightbox-exif.js) und Foto-Editor
 * (lightbox-editor.js). Editor v1 ohne Zuschnitt — Drehen 90°, Spiegeln,
 * Geraderichten ±45°, Helligkeit/Kontrast/Saettigung ±100; gespeichert wird
 * ueber POST /api/photo/…/edit (destruktiv, Server legt .bak an).
 */

// -- EXIF-Panel --------------------------------------------------------------
// Wie die Handy-Web-Lightbox (lb-sidebar mobil): Vollflaeche, kommt von
// unten (Hochwischen auf dem Foto oder ℹ️), Runterwischen am oberen
// Scrollrand schliesst (80 px, Web: sidebar-touchmove-Listener).
// Gruppen mit Lucide-Icons wie _metaH3 in lightbox-exif.js.

@Composable
internal fun ExifPanel(
    vm: AppViewModel,
    photo: Photo,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    /** Die Groesse setzt der Aufrufer. Ein Default mit fillMaxSize()
     *  waere fatal: in Compose gewinnt der ERSTE Groessen-Modifier der
     *  Kette, jede spaetere Angabe des Panels bliebe wirkungslos. */
    modifier: Modifier,
    /** true = rechte Sidebar: Wegwischen nach rechts statt nach unten. */
    sideways: Boolean = false,
) {
    val density = LocalDensity.current.density
    var exif by remember(photo.file) { mutableStateOf<ExifData?>(null) }
    var error by remember(photo.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(photo.file) {
        if (photo.isLocal) {
            // Geraete-Aufnahme: der Server kennt die Datei noch gar nicht,
            // /api/exif liefe ins Leere. Die Daten stehen in der Datei.
            val local = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO,
            ) { vm.plattform.lokaleExif(photo.originalUrl) }
            when {
                local == null -> error = Texte.t(Res.string.exif_err_unreadable)
                local.error != null -> error = local.error
                else -> exif = local
            }
        } else {
            runCatching { vm.api.exif(vm.baseUrl, photo.space, photo.album, photo.file) }
                .onSuccess { exif = it }
                .onFailure { error = it.message }
        }
    }

    val scrollState = rememberScrollState()
    var pull by remember { mutableStateOf(0f) }
    val closeConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource,
            ): Offset {
                // Ziehen ueber den oberen Rand hinaus sammelt sich in pull
                if (available.y > 0 && scrollState.value == 0) {
                    pull += available.y
                    if (pull > 80f * density) {
                        pull = 0f
                        onDismiss()
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pull = 0f
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier
            // Am Handy kommt das Blatt von unten und laesst oben einen
            // Streifen Foto stehen: der obere Bildschirmrand gehoert dem
            // System (erster Wisch holt die Balken, der zweite das
            // Benachrichtigungsmenue) — dort darf unser Griff nicht liegen.
            .then(
                // Seitenleiste: statusBarsPadding allein reicht hier NICHT —
                // in der Lightbox sind die Systemleisten versteckt, das Inset
                // ist also 0. Das Kameraloch bleibt trotzdem, und dafuer
                // liefert displayCutout auch im Immersive-Modus ein Mass.
                if (sideways) Modifier.statusBarsPadding().displayCutoutPadding()
                else Modifier
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            )
            .background(Color(0xFF141414))
            .navigationBarsPadding()
            .then(
                if (!sideways) Modifier
                else Modifier.pointerInput(Unit) {
                    // Sidebar: Zug nach rechts (dorthin, wo sie herkam) schliesst
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { acc = 0f },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            if (dx > 0) acc += dx else acc = 0f
                            if (acc > 80f * density) {
                                acc = Float.NEGATIVE_INFINITY
                                onDismiss()
                            }
                        },
                    )
                }
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(closeConnection)
                .verticalScroll(scrollState)
                // In der Seitenleiste bleibt links Platz fuer den Griff.
                .padding(start = if (sideways) 34.dp else 20.dp, end = 20.dp),
        ) {
            if (!sideways) {
                // Griffleiste wie bei Bottom-Sheets — und sie ist wirklich
                // anfassbar: der nestedScroll-Zug oben drueber greift nur, wenn
                // der Inhalt ueberhaupt scrollt. Passt das EXIF auf den Schirm,
                // waere das Blatt sonst nur mit System-Zurueck zu schliessen.
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 28 dp hoch statt 8: der Griff wird angefasst, nicht nur
                        // angeschaut — eine 4-dp-Leiste trifft niemand zuverlaessig.
                        .padding(vertical = 14.dp)
                        .pointerInput(sideways) {
                            var acc = 0f
                            detectDragGestures(
                                onDragStart = { acc = 0f },
                                onDrag = { change, d ->
                                    change.consume()
                                    // Zu geht es dorthin, wo das Blatt herkam:
                                    // unten am Handy, rechts in der Seitenleiste.
                                    val away = if (sideways) d.x else d.y
                                    if (away > 0) acc += away else acc = 0f
                                    if (acc > 60f * density) {
                                        acc = Float.NEGATIVE_INFINITY
                                        onDismiss()
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MpdMuted),
                    )
                }
            }
            // Kein Schliessen-Kreuz: das ist Browser-Idiom. Zu ist das Blatt
            // per Griffleiste (runterwischen bzw. seitwaerts zurueckziehen)
            // oder mit System-Zurueck — wie bei Google Fotos.
            Text(photo.file, color = MpdText, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))

            // Basis wie _buildBasicSection: Datei + Aufloesung
            ExifRow(stringResource(Res.string.exif_file), photo.file)
            // Wo steckt das Foto? (06.09.: „man will es nur wissen")
            // Eine Zeile Text statt eines Zeichens auf jeder zweiten Kachel:
            // im Bestand sind 49 % kuratiert und 51 % nicht — als Symbol im
            // Raster waere das kein Hinweis mehr, sondern ein Muster.
            // Bewusst kein Sprung ins Album: wissen, nicht wechseln.
            ExifRow(
                stringResource(Res.string.exif_album),
                when {
                    photo.isLocal -> stringResource(Res.string.exif_only_device)
                    photo.referenced -> photo.album
                    else -> stringResource(Res.string.exif_no_album)
                },
            )
            photo.resolution?.takeIf { it.width > 0 }?.let {
                ExifRow(stringResource(Res.string.exif_resolution), "${it.width} × ${it.height}")
            }
            // Dateigroesse kommt mit den EXIF (Wunsch 13.11.2026); die
            // Album-Antwort traegt sie nicht, darum erst nach dem Laden.
            exif?.fileSize?.takeIf { it > 0 }?.let {
                ExifRow(stringResource(Res.string.exif_size), humanBytes(it))
            }

            when {
                error != null -> Text(stringResource(Res.string.exif_load_failed, error.orEmpty()), color = MpdRed, fontSize = 14.sp)
                exif == null -> Box(
                    Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(26.dp)) }

                else -> {
                    ExifGroups(vm, exif!!)
                    // Bearbeiten wie im Web nur fuer JPEGs und schreibbare
                    // Spaces — und nie fuer Geraete-Aufnahmen: die schreibt
                    // der Server nicht, er kennt die Datei nicht einmal.
                    if (!readOnly && !photo.isLocal &&
                        photo.file.lowercase().matches(Regex(".*\\.jpe?g$"))
                    ) {
                        ExifEditGroup(vm, photo, exif!!, onDone = onDismiss)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (sideways) {
            // Die Leiste geht nach RECHTS weg, nicht nach unten — also
            // steht ihr Griff senkrecht an der Zugkante links, mittig.
            // Oben am Rand sass er beim Tab S8 Ultra direkt unterm
            // Kameraloch, und er zeigte die falsche Richtung an.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .height(140.dp)
                    .padding(horizontal = 9.dp)
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectDragGestures(
                            onDragStart = { acc = 0f },
                            onDrag = { change, d ->
                                change.consume()
                                if (d.x > 0) acc += d.x else acc = 0f
                                if (acc > 60f * density) {
                                    acc = Float.NEGATIVE_INFINITY
                                    onDismiss()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MpdMuted),
                )
            }
        }
    }
}

@Composable
private fun ExifGroupHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        Modifier.padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MpdBlue)
        Spacer(Modifier.width(8.dp))
        Text(label, color = MpdText, fontSize = 15.sp)
    }
}

@Composable
private fun ExifRow(
    label: String,
    value: String,
    /** Modul nicht freigeschaltet: kleines Schloss hinter dem Wert. */
    locked: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MpdMuted, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Text(value, color = if (onClick != null) MpdBlue else MpdText, fontSize = 14.sp)
        if (locked) {
            Icon(
                MpdIcons.LockClosed, stringResource(Res.string.exif_locked_module),
                Modifier.padding(start = 8.dp).size(14.dp), tint = MpdMuted,
            )
        }
    }
}

@Composable
private fun ExifGroups(vm: AppViewModel, exif: ExifData) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    if (exif.error != null) {
        Text(exif.error, color = MpdMuted, fontSize = 14.sp)
        return
    }

    exif.datetime?.let { raw ->
        // EXIF "2026:09:01 18:33:12" → "01.09.2026 · 18:33 Uhr"
        val m = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2})""").find(raw)
        ExifGroupHeader(MpdIcons.Calendar, stringResource(Res.string.exif_capture))
        if (m != null) {
            val (y, mo, d, h, min) = m.destructured
            // Datum im Locale-Format (de: 01.09.2026), Uhrzeit mit der
            // lokalen Nachsilbe („Uhr" nur im Deutschen).
            val datum = runCatching {
                java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
                    .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
            }.getOrDefault("$d.$mo.$y")
            ExifRow(stringResource(Res.string.exif_date), datum)
            ExifRow(stringResource(Res.string.exif_time), stringResource(Res.string.exif_time_value, "$h:$min"))
            // „Angebot vor Nutzung" (Trails-Konzept): das Modul taucht dort
            // auf, wo die Frage entsteht — beim Bild, nicht in einem Menue.
            ExifRow(
                stringResource(Res.string.exif_where), stringResource(Res.string.exif_dayroute),
                locked = !vm.moduleAllowed("trails"),
            ) { vm.openTrailsAt("$y-$mo-$d") }
        } else {
            ExifRow(stringResource(Res.string.exif_date), raw)
        }
    }

    val cam = exif.camera
    val hasCamera = cam != null || exif.lens != null || exif.settings != null
    if (hasCamera) {
        ExifGroupHeader(MpdIcons.Camera, stringResource(Res.string.exif_camera))
        cam?.let { ExifRow(stringResource(Res.string.exif_model), listOfNotNull(it.make, it.model).joinToString(" ")) }
        exif.lens?.let { ExifRow(stringResource(Res.string.exif_lens), it) }
        exif.settings?.let { s ->
            s.focalLength?.let { ExifRow(stringResource(Res.string.exif_focal), it) }
            s.aperture?.let { ExifRow(stringResource(Res.string.exif_aperture), it) }
            // Der Server liefert "0.00s", lokal steht hier "1/2000" — die
            // Einheit haengt die Zeile an, sonst stand dort "0.00s s".
            s.shutterSpeed?.let { ExifRow(stringResource(Res.string.exif_shutter), it.removeSuffix("s").trim() + " s") }
            s.iso?.let { ExifRow(stringResource(Res.string.exif_iso), "$it") }
        }
    }

    exif.gps?.let { gps ->
        if (gps.lat != null && gps.lon != null) {
            ExifGroupHeader(MpdIcons.Pin, stringResource(Res.string.exif_location))
            // Mini-Karte wie _buildGpsSection (.lb-map + Marker), unbewaffnet
            // — Tipp auf die Koordinaten oeffnet die Karten-App
            ExifMiniMap(gps.lat, gps.lon, Modifier.fillMaxWidth().height(170.dp))
            ExifRow(stringResource(Res.string.exif_coords), "%.5f, %.5f".format(gps.lat, gps.lon)) {
                runCatching { uriHandler.openUri(kartenLink(gps.lat, gps.lon)) }
            }
            gps.altitude?.let { ExifRow(stringResource(Res.string.exif_altitude), stringResource(Res.string.exif_altitude_value, "$it")) }
        }
    }

    exif.description?.let {
        ExifGroupHeader(MpdIcons.Chat, stringResource(Res.string.exif_description))
        Text(it, color = MpdText, fontSize = 14.sp, lineHeight = 22.sp)
    }
}


/**
 * „EXIF bearbeiten" (_buildExifEditSection): Datum/Zeit ueber die System-
 * Picker, GPS per Nadel auf der Karte (Tipp setzt den Pin), Dateiname mit
 * ISO-Vorschlag aus dem EXIF-Datum. Speichern = POST …/exif (schreibt
 * Datum+GPS, benennt optional um); Umbenennen = nur der Name, mit
 * Server-Vorschau und Kollisionshinweis.
 */
@Composable
private fun ExifEditGroup(
    vm: AppViewModel,
    photo: Photo,
    exif: ExifData,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Theme-Farben fuer Status-Setzungen in Callbacks/Coroutinen hochziehen
    val cMuted = MpdMuted
    val cGoldLt = MpdGoldLt
    val currentStem = photo.file.substringBeforeLast('.')
    val extension = photo.file.substringAfterLast('.', "jpg")

    // EXIF "2026:09:01 18:33:12" → Datum/Zeit-Felder + ISO-Namensvorschlag
    val exifMatch = exif.datetime
        ?.let { Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(it) }
    var date by remember(photo.file) {
        mutableStateOf(exifMatch?.let { m -> "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}" }.orEmpty())
    }
    var time by remember(photo.file) {
        mutableStateOf(exifMatch?.let { m -> "${m.groupValues[4]}:${m.groupValues[5]}" }.orEmpty())
    }
    val isoSuggestion = exifMatch?.let { m ->
        "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}_" +
            "${m.groupValues[4]}-${m.groupValues[5]}-${m.groupValues[6]}"
    }
    var stem by remember(photo.file) {
        mutableStateOf(isoSuggestion?.takeIf { it != currentStem } ?: currentStem)
    }
    var showMap by remember(photo.file) { mutableStateOf(false) }
    var pinLat by remember(photo.file) { mutableStateOf(exif.gps?.lat) }
    var pinLon by remember(photo.file) { mutableStateOf(exif.gps?.lon) }
    var alt by remember(photo.file) {
        mutableStateOf(exif.gps?.altitude?.toString().orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Color>?>(null) }
    var renameConfirm by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    var datePickerOpen by remember { mutableStateOf(false) }
    var timePickerOpen by remember { mutableStateOf(false) }
    fun pickDate() { datePickerOpen = true }
    fun pickTime() { timePickerOpen = true }
    if (datePickerOpen) DatumWaehlen(date, onDismiss = { datePickerOpen = false }) { neu ->
        date = neu
        // Web: Datumseingabe zieht den ISO-Namensvorschlag nach
        if (time.isNotEmpty()) stem = "${date}_${time.replace(":", "-")}-00"
        datePickerOpen = false
    }
    if (timePickerOpen) ZeitWaehlen(time, onDismiss = { timePickerOpen = false }) { neu ->
        time = neu
        if (date.isNotEmpty()) stem = "${date}_${time.replace(":", "-")}-00"
        timePickerOpen = false
    }

    fun afterWrite(renamedTo: String?) {
        vm.bumpEditVersion(photo.file)
        if (renamedTo != null) {
            // Neuer Dateiname: Modell ist ueberholt — Album frisch holen,
            // Panel schliessen (die Lightbox zeigt danach die neue Datei)
            vm.reloadAlbum()
            onDone()
        }
    }

    ExifGroupHeader(MpdIcons.Pencil, stringResource(Res.string.exif_edit))

    Row(Modifier.fillMaxWidth()) {
        EditFieldButton(stringResource(Res.string.exif_date), date.ifEmpty { "—" }, Modifier.weight(1f)) { pickDate() }
        Spacer(Modifier.width(8.dp))
        EditFieldButton(stringResource(Res.string.exif_time), time.ifEmpty { "—" }, Modifier.weight(1f)) { pickTime() }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.exif_gps), color = MpdMuted, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        TextButton(onClick = { showMap = !showMap }) {
            Icon(MpdIcons.Pin, null, Modifier.size(16.dp), tint = MpdBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (showMap) Res.string.exif_map_close else Res.string.exif_map_open),
                color = MpdBlue, fontSize = 14.sp,
            )
        }
    }
    if (showMap) {
        // Ortssuche wie mapSearchPlace im Web (Nominatim, limit=1) — eigener
        // Client OHNE Auth-Interceptor: der Bearer-Header der App darf nicht
        // an einen Fremddienst gehen.
        var searchQuery by remember { mutableStateOf("") }
        var searchBusy by remember { mutableStateOf(false) }
        var mapCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }

        fun searchPlace() {
            val q = searchQuery.trim()
            if (q.isEmpty() || searchBusy) return
            searchBusy = true
            scope.launch {
                try {
                    val result = de.bgghome.mpd.util.Nominatim.suche(q)
                        ?.let { it.lat to it.lon }
                    if (result != null) {
                        pinLat = result.first
                        pinLon = result.second
                        mapCenter = result
                        searchQuery = ""
                    } else {
                        status = Texte.t(Res.string.exif_place_not_found) to cGoldLt
                    }
                } catch (e: Exception) {
                    status = Texte.t(Res.string.exif_search_failed, e.message) to MpdRed
                } finally {
                    searchBusy = false
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.map_search_placeholder)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { searchPlace() }, enabled = !searchBusy) {
                if (searchBusy) CircularProgressIndicator(Modifier.size(18.dp))
                else Icon(MpdIcons.Search, stringResource(Res.string.exif_search), Modifier.size(20.dp), tint = MpdBlue)
            }
        }

        ExifPickMap(
            lat = pinLat, lon = pinLon,
            center = mapCenter,
            onPick = { la, lo -> pinLat = la; pinLon = lo },
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
        Text(
            if (pinLat != null && pinLon != null)
                stringResource(Res.string.exif_pin_set, "%.5f".format(pinLat), "%.5f".format(pinLon))
            else stringResource(Res.string.exif_pin_none),
            color = MpdMuted, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = alt,
            onValueChange = { v -> if (v.all { it.isDigit() || it == '-' || it == '.' }) alt = v },
            singleLine = true,
            label = { Text(stringResource(Res.string.exif_alt_label)) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = stem,
        onValueChange = { stem = it },
        singleLine = true,
        label = { Text(stringResource(Res.string.exif_filename)) },
        supportingText = {
            Text(
                stringResource(Res.string.exif_filename_current, extension, currentStem) +
                    (isoSuggestion?.takeIf { it != currentStem }
                        ?.let { stringResource(Res.string.exif_iso_suggestion) } ?: ""),
                color = MpdMuted, fontSize = 12.sp,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )

    status?.let { (text, color) ->
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }

    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        TextButton(
            enabled = !busy,
            onClick = {
                busy = true
                status = Texte.t(Res.string.status_saving) to cMuted
                scope.launch {
                    try {
                        val dt = if (date.isNotEmpty() && time.isNotEmpty()) "${date}T$time" else null
                        val renamed = vm.api.saveExif(
                            vm.baseUrl, photo.space, photo.album, photo.file,
                            datetime = dt,
                            lat = pinLat, lon = pinLon,
                            alt = alt.toDoubleOrNull(),
                            newStem = stem.trim().takeIf { it.isNotEmpty() && it != currentStem },
                        )
                        status = Texte.t(Res.string.status_saved) to MpdGreen
                        afterWrite(renamed)
                    } catch (e: Exception) {
                        status = (e.message ?: Texte.t(Res.string.status_save_error)) to MpdRed
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(stringResource(Res.string.common_save), color = MpdBlue) }

        TextButton(
            enabled = !busy && stem.trim().isNotEmpty() && stem.trim() != currentStem,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        renameConfirm = vm.api.renamePreview(
                            vm.baseUrl, photo.space, photo.album, photo.file, stem.trim(),
                        )
                    } catch (e: Exception) {
                        status = (e.message ?: Texte.t(Res.string.status_preview_failed)) to MpdRed
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(stringResource(Res.string.exif_rename), color = MpdText) }
    }

    renameConfirm?.let { (finalName, collision) ->
        AlertDialog(
            onDismissRequest = { renameConfirm = null },
            title = { Text(stringResource(Res.string.rename_q_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.rename_arrow, photo.file), color = MpdMuted, fontSize = 14.sp)
                    Text(finalName, color = MpdText, fontSize = 15.sp)
                    if (collision) {
                        Text(
                            stringResource(Res.string.rename_collision),
                            color = MpdGoldLt, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    renameConfirm = null
                    busy = true
                    status = Texte.t(Res.string.status_renaming) to cMuted
                    scope.launch {
                        try {
                            val renamed = vm.api.renamePhoto(
                                vm.baseUrl, photo.space, photo.album, photo.file, stem.trim(),
                            )
                            status = Texte.t(Res.string.status_renamed) to MpdGreen
                            afterWrite(renamed ?: finalName)
                        } catch (e: Exception) {
                            status = (e.message ?: Texte.t(Res.string.status_rename_failed)) to MpdRed
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.exif_rename), color = MpdBlue) }
            },
            dismissButton = {
                TextButton(onClick = { renameConfirm = null }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun EditFieldButton(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .background(Color(0xFF1A2535))
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = MpdMuted, fontSize = 11.sp)
        Text(value, color = MpdText, fontSize = 15.sp)
    }
}



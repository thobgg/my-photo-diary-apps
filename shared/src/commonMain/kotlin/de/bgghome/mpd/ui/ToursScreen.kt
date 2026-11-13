package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.TourListEntry
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.util.GpxTrack
import de.bgghome.mpd.ui.karte.GeoPunkt
import de.bgghome.mpd.ui.karte.KachelEbene
import de.bgghome.mpd.ui.karte.KachelKarte
import de.bgghome.mpd.ui.karte.KartenLinie
import de.bgghome.mpd.ui.karte.KartenPin
import de.bgghome.mpd.ui.karte.KartenZustand
import de.bgghome.mpd.util.parseGpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Tours-Modul — Nachbau von frontend/tours.html: globale Trackliste mit
 * Filtern (Sport-Chips, Jahr, Textsuche), Auswahl zeigt den Track auf der
 * Karte (magenta auf weisser Unterlage, Start gruen / Ende rot) mit
 * Detailkarte (Distanz, Dauer, Puls, Datei) und „In Album kopieren".
 *
 * Android-Anpassungen: breit (ab 840 dp) Liste neben Karte wie am Desktop,
 * schmal Liste → Detailseite (statt Web-Stapel mit Auto-Scroll). Bonus
 * gegenueber dem Web: Hoehenprofil (der GPX-Parser liefert es gratis).
 * Modul-Gating: /api/tours ist 403 fuer Nicht-Freigeschaltete und Demo.
 */

private val StartGreen = Color(0xFF43C98C)
private val EndRed = Color(0xFFF05A5A)

/** "all" wird beim Zeichnen uebersetzt; die Sport-Emoji sind Inhalt. */
private val SPORT_FILTERS = listOf("all" to "Alle", "cycle" to "🚴", "walk" to "🚶", "run" to "🏃")

/** Zustandsfilter wie die Tours-Seite des Webs (seit Server 3.0.488). */
private val STATE_FILTERS = listOf(
    "all" to Res.string.tours_filter_all,
    "open" to Res.string.tours_state_open,
    "used" to Res.string.tours_state_used,
    "stub" to Res.string.tours_state_stub,
)

@Composable
fun ToursScreen(vm: AppViewModel) {
    val colors = Mpd.colors
    val scope = rememberCoroutineScope()

    var tours by remember { mutableStateOf<List<TourListEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Auswahl ueber den Schluessel „Ordner/Datei" (der Name allein ist seit
    // 3.0.488 nicht mehr eindeutig) — so findet sie sich nach dem Neuladen
    // wieder, auch wenn die Tour beim Einfuegen in den Albumordner wandert.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    // Einfuegen und Loeschen: nur wer den Familienbestand kuratiert (Web
    // tours.html canCurate). Der Server prueft selbst; der Knopf soll nur
    // nicht in einen 403 fuehren.
    val canCurate = vm.canCurate("shared")

    // rememberSaveable: Filter und Suchtext ueberleben den Prozess-Tod.
    var sportFilter by rememberSaveable { mutableStateOf("all") }
    var stateFilter by rememberSaveable { mutableStateOf("all") }
    var yearFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(reload) {
        runCatching { vm.api.tours(vm.baseUrl) }
            .onSuccess { tours = it; error = null }
            // 403 oder fehlende Route (reiner Core): Modul-Satz statt „Not Found".
            .onFailure { if (tours == null) error = apiErrorText(it, de.bgghome.mpd.state.moduleLockedText(Texte.t(Res.string.module_tours_name))) }
    }
    val selected = tours?.firstOrNull { it.key == selectedKey }

    val filtered = remember(tours, sportFilter, stateFilter, yearFilter, query) {
        (tours ?: emptyList()).filter { t ->
            val used = t.usedIn.isNotEmpty()
            (sportFilter == "all" || t.sport.startsWith(sportFilter)) &&
                (stateFilter == "all" || (stateFilter == "open" && !used) ||
                    (stateFilter == "used" && used) || (stateFilter == "stub" && t.stub != null)) &&
                (yearFilter == null || t.date.startsWith(yearFilter!!)) &&
                (query.isBlank() || listOf(t.date, t.sport, t.label, t.title.orEmpty(), t.folder, t.filename)
                    .plus(t.usedIn.map { it.label })
                    .any { it.contains(query, true) })
        }
    }

    // Stummel in keinem Album, gerade in der Liste — Sammelloeschen wie im
    // Web (recycleAllStubs). Nur im Filter „Stummel" sichtbar.
    val stubsUnused = if (canCurate && stateFilter == "stub") filtered.filter { it.stub != null && it.usedIn.isEmpty() }
    else emptyList()
    var bulkAsk by remember { mutableStateOf(false) }
    var bulkProgress by remember { mutableStateOf<String?>(null) }

    val onChanged: (String?) -> Unit = { newKey -> selectedKey = newKey; reload++ }

    MpdScreen(
        backDescription = if (selected != null) stringResource(Res.string.tours_back_to_list)
        else stringResource(Res.string.tours_close),
        onClose = { if (selected != null) selectedKey = null else vm.toursOpen = false },
        // Die System-Geste schloss hier schon immer die ganze Ansicht, der
        // Knopf geht erst zur Liste zurueck — beibehalten.
        onBack = { vm.toursOpen = false },
        headerPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        header = {
            ScreenTitle("Tours", size = 20.sp)
            Spacer(Modifier.width(10.dp))
            tours?.let {
                Text(
                    pluralStringResource(Res.plurals.tours_track_count, it.size, it.size),
                    color = colors.muted, fontSize = 13.sp,
                )
            }
        },
    ) {
        LoadState(
            error = error, data = tours,
            errorContent = { ErrorCenter(it, MpdRed) },
            loading = { LoadingCenter() },
        ) { list ->
            val listColumn: @Composable (Modifier, TourListEntry?) -> Unit = { mod, active ->
                Column(mod) {
                    TourFilters(
                        list, sportFilter, yearFilter, query,
                        { sportFilter = it }, { yearFilter = it }, { query = it },
                    )
                    StateFilters(stateFilter) { stateFilter = it }
                    if (stubsUnused.isNotEmpty() || bulkProgress != null) {
                        BulkBar(
                            bulkProgress ?: Texte.t(Res.string.tours_stubs_unused, stubsUnused.size.toString()),
                            enabled = bulkProgress == null,
                        ) { bulkAsk = true }
                    }
                    TourList(filtered, active) { selectedKey = it.key }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 840.dp
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        listColumn(Modifier.width(380.dp).fillMaxHeight(), selected)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            selected?.let { TourDetail(vm, it, canCurate, onChanged) } ?: Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) { IconLabel(MpdIcons.Map, stringResource(Res.string.tours_pick_track), color = colors.muted) }
                        }
                    }
                } else {
                    val sel = selected
                    if (sel == null) listColumn(Modifier.fillMaxSize(), null)
                    else TourDetail(vm, sel, canCurate, onChanged)
                }
            }
        }
    }

    if (bulkAsk) {
        val batch = stubsUnused
        AlertDialog(
            onDismissRequest = { bulkAsk = false },
            title = { Text(stringResource(Res.string.tours_recycle_all_title, batch.size.toString())) },
            text = { Text(stringResource(Res.string.tours_recycle_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    bulkAsk = false
                    scope.launch {
                        var done = 0
                        bulkProgress = Texte.t(Res.string.tours_recycle_progress, "0", batch.size.toString())
                        for (t in batch) {
                            if (runCatching { vm.api.deleteTour(vm.baseUrl, t) }.isSuccess) done++
                            bulkProgress = Texte.t(Res.string.tours_recycle_progress, done.toString(), batch.size.toString())
                        }
                        bulkProgress = null
                        vm.meldung(Texte.t(Res.string.tours_recycled_n, done.toString(), batch.size.toString()), kurz = true)
                        onChanged(null)
                    }
                }) { Text(stringResource(Res.string.tours_recycle), color = MpdRed) }
            },
            dismissButton = { TextButton(onClick = { bulkAsk = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

@Composable
private fun StateFilters(state: String, onState: (String) -> Unit) {
    val colors = Mpd.colors
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        STATE_FILTERS.forEach { (key, label) ->
            val active = state == key
            Text(
                stringResource(label),
                color = if (active) StartGreen else colors.muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, if (active) StartGreen else colors.border, RoundedCornerShape(14.dp))
                    .clickable { onState(key) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun BulkBar(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Mpd.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, StubAmber, RoundedCornerShape(8.dp))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = colors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled) {
            Text(stringResource(Res.string.tours_recycle_all), color = if (enabled) MpdRed else colors.muted, fontSize = 13.sp)
        }
    }
}

private val StubAmber = Color(0xFFC8922A)

@Composable
private fun stubText(why: String): String = when (why) {
    "unlesbar" -> stringResource(Res.string.tours_stub_unlesbar)
    "punkte" -> stringResource(Res.string.tours_stub_punkte)
    "strecke" -> stringResource(Res.string.tours_stub_strecke)
    "dauer" -> stringResource(Res.string.tours_stub_dauer)
    else -> why
}

private fun stubTextNow(why: String): String = when (why) {
    "unlesbar" -> Texte.t(Res.string.tours_stub_unlesbar)
    "punkte" -> Texte.t(Res.string.tours_stub_punkte)
    "strecke" -> Texte.t(Res.string.tours_stub_strecke)
    "dauer" -> Texte.t(Res.string.tours_stub_dauer)
    else -> why
}

private fun TourListEntry.heading(): String = title?.takeIf { it.isNotBlank() } ?: label

@Composable
private fun TourFilters(
    tours: List<TourListEntry>,
    sport: String, year: String?, query: String,
    onSport: (String) -> Unit, onYear: (String?) -> Unit, onQuery: (String) -> Unit,
) {
    val colors = Mpd.colors
    val years = remember(tours) {
        tours.mapNotNull { it.date.take(4).takeIf { y -> y.length == 4 } }
            .distinct().sortedDescending()
    }
    var yearMenu by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SPORT_FILTERS.forEach { (key, label) ->
            val active = sport == key
            Text(
                if (key == "all") stringResource(Res.string.tours_filter_all) else label,
                color = if (active) StartGreen else colors.muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        1.dp, if (active) StartGreen else colors.border,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable { onSport(key) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .clickable { yearMenu = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val yearColor = if (year != null) StartGreen else colors.muted
                Text(year ?: stringResource(Res.string.tours_year), color = yearColor, fontSize = 13.sp)
                if (year == null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(MpdIcons.ChevronDown, stringResource(Res.string.tours_year_pick), Modifier.size(16.dp), tint = yearColor)
                }
            }
            DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.tours_all_years)) }, onClick = { onYear(null); yearMenu = false })
                years.forEach { y ->
                    DropdownMenuItem(text = { Text(y) }, onClick = { onYear(y); yearMenu = false })
                }
            }
        }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 13.sp),
            cursorBrush = SolidColor(colors.blue),
            // Ohne Modifier waere nur der getippte Text beruehrbar.
            modifier = Modifier.width(120.dp),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .width(120.dp),
                ) {
                    if (query.isEmpty()) Text(stringResource(Res.string.tours_search_placeholder), color = colors.muted, fontSize = 13.sp)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun TourList(
    tours: List<TourListEntry>,
    selected: TourListEntry?,
    onSelect: (TourListEntry) -> Unit,
) {
    val colors = Mpd.colors
    if (tours.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconLabel(MpdIcons.Search, stringResource(Res.string.tours_none_found), color = colors.muted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().rubberBandOverscroll()) {
        items(tours, key = { it.key }) { t ->
            val active = t.key == selected?.key
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(t) }
                    .background(if (active) colors.bluePale else Color.Transparent)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(formatTourDate(t.date).ifEmpty { t.filename },
                    color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                t.heading().takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.muted, fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.dist?.let { Text("📍 $it km", color = StartGreen, fontSize = 11.sp) }
                    t.duration?.let { Text("⏱ $it", color = colors.muted, fontSize = 11.sp) }
                    t.hrAvg?.let { Text("♥ Ø$it", color = colors.muted, fontSize = 11.sp) }
                    t.sizeKb?.let {
                        Text("$it KB", color = colors.muted.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }
                TourBadges(t)
            }
        }
    }
}

/** Abzeichen wie badges() im Web: in welchem Album / offen, Stummel,
 *  noch im alten Sammelordner. Ohne folder (alter Server) nichts. */
@Composable
private fun TourBadges(t: TourListEntry) {
    if (t.folder.isEmpty()) return
    val colors = Mpd.colors
    Row(
        Modifier.padding(top = 3.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (t.usedIn.isNotEmpty()) {
            t.usedIn.forEach { Badge(stringResource(Res.string.tour_used_in, it.label), StartGreen) }
        } else {
            Badge(stringResource(Res.string.tours_badge_open), colors.muted)
        }
        t.stub?.let { Badge("${stringResource(Res.string.tours_state_stub)} · ${stubText(it)}", StubAmber) }
        if (t.kind == "tours") Badge(stringResource(Res.string.tours_badge_old), colors.muted)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 10.sp, maxLines = 1,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun TourDetail(
    vm: AppViewModel,
    tour: TourListEntry,
    canCurate: Boolean,
    onChanged: (newKey: String?) -> Unit,
) {
    val colors = Mpd.colors
    val scope = rememberCoroutineScope()
    var askDelete by remember(tour.key) { mutableStateOf(false) }
    var track by remember(tour.filename) { mutableStateOf<GpxTrack?>(null) }
    var loadError by remember(tour.filename) { mutableStateOf<String?>(null) }
    var armed by remember(tour.filename) { mutableStateOf(false) }
    // KARTENART auch hier (16.09.2026). Das Tour-Element im Album hat den
    // Schalter laengst; in der Tours-Ansicht stand die Karte fest auf
    // Strassenkarte. Gerade fuer Touren — Wanderungen, Radtouren — ist das
    // Relief oft wichtiger als die Strassen.
    var topo by remember(tour.filename) { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(tour.filename) {
        try {
            // Mit Ordner (der Name allein ist seit 3.0.488 nicht eindeutig).
            // Pfadsegmente ueber den Adressbauer, nicht URLEncoder: der kodiert
            // fuers Formular (Leerzeichen -> "+"), und im Pfad liest der
            // Server "+" woertlich — jede Komoot-Tour mit Leerzeichen war 404.
            val gpx = vm.api.fetchText(vm.api.tourFileUrl(vm.baseUrl, tour))
            val parsed = withContext(Dispatchers.Default) { parseGpx(gpx) }
            if (parsed.points.isEmpty()) loadError = Texte.t(Res.string.tours_no_points)
            else track = parsed
        } catch (e: Exception) {
            loadError = e.message ?: Texte.t(Res.string.tours_gpx_unreadable)
        }
    }

    // Karte im Vollbild (15.09.2026: „ist es nicht moeglich zu den
    // Touren einen Fullscreen zu bekommen?"). Der Karteninhalt liegt als
    // Lambda mit Modifier vor, damit Klein- und Vollbildfassung dieselbe
    // Composable sind und nicht auseinanderlaufen koennen.
    var kartenVollbild by remember { mutableStateOf(false) }
    val kartenZustand = remember { KartenZustand() }
    var eingepasst by remember(track) { mutableStateOf(false) }
    val karte: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.clipToBounds(), contentAlignment = Alignment.Center) {
            val t = track
            when {
                loadError != null -> Text(loadError!!, color = MpdRed)
                t == null -> CircularProgressIndicator()
                else -> {
                    // Compose-Kachelkarte (ui/karte) statt osmdroid - erste
                    // Stelle, an der Android sie benutzt (22.09.2026).
                    val spur = remember(t) { t.points.map { GeoPunkt(it.lat, it.lon) } }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        if (!eingepasst) {
                            kartenZustand.passeEin(spur, constraints.maxWidth, constraints.maxHeight, 40)
                            eingepasst = true
                        }
                        KachelKarte(
                            kartenZustand, if (topo) KachelEbene.TOPO else KachelEbene.STANDARD,
                            Modifier.fillMaxSize(), interaktiv = armed,
                            linien = listOf(
                                KartenLinie(spur, Color.White.copy(alpha = 0.9f), 6f),
                                KartenLinie(spur, TrackMagenta, 3.5f),
                            ),
                            pins = listOf(
                                KartenPin(spur.first().lat, spur.first().lon, null, Color(0xFF43C98C), 7f),
                                KartenPin(spur.last().lat, spur.last().lon, null, Color(0xFFF05A5A), 7f),
                            ),
                        )
                    }
                    ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            karte(Modifier.fillMaxSize())
            VollbildKnopf { kartenVollbild = true }
            LayerToggle(topo, { topo = !topo }, Modifier.align(Alignment.TopStart))
        }

        // Detailkarte wie showDetail() im Web
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                listOf(formatTourDate(tour.date).ifEmpty { tour.filename }, tour.heading())
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            DetailRow(stringResource(Res.string.tours_distance), tour.dist?.let { "$it km" })
            DetailRow(stringResource(Res.string.tours_duration), tour.duration)
            DetailRow(stringResource(Res.string.tours_hr_avg), tour.hrAvg?.let { "$it bpm" })
            DetailRow(stringResource(Res.string.tours_hr_max), tour.hrMax?.let { "$it bpm" })
            DetailRow(stringResource(Res.string.tours_points), tour.points?.toString())
            DetailRow(stringResource(Res.string.tours_state_stub), tour.stub?.let { stubText(it) })
            DetailRow(stringResource(Res.string.tours_folder), tour.folder.ifEmpty { null })
            if (tour.folder.isNotEmpty()) {
                DetailRow(
                    stringResource(Res.string.tours_used_in),
                    tour.usedIn.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.label } ?: "—",
                    maxLines = 10,
                )
            }
            DetailRow(stringResource(Res.string.tours_file), tour.filename, small = true)

            track?.let { t ->
                if (t.hasEle) {
                    Text(
                        stringResource(Res.string.tours_elevation),
                        color = colors.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    TourElevationProfile(t)
                }
            }

            // „In Karten-App oeffnen" (13.11.2026): dieselbe GPX an OsmAnd
            // und Co. — fuer jeden, nicht nur Kuratoren.
            val dienste = LocalSystemDienste.current
            if (dienste.kannGpxOeffnen) {
                TextButton(
                    onClick = { scope.launch { dienste.oeffneGpx(vm, vm.api.tourFileUrl(vm.baseUrl, tour), tour.filename) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
                ) { IconLabel(MpdIcons.Compass, stringResource(Res.string.gpx_open_external), color = colors.text, iconSize = 16.dp, gap = 6.dp) }
            }

            if (canCurate) {
                TextButton(
                    onClick = { pickerOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.blue),
                ) { IconLabel(MpdIcons.Folder, stringResource(Res.string.tours_insert_into_album), color = colors.textOnAccent, iconSize = 16.dp, gap = 6.dp) }

                // Loeschen nur, solange kein Album die Tour benutzt — der
                // Server antwortet sonst 409. Ohne folder (alter Server) gibt
                // es den Weg gar nicht.
                if (tour.folder.isNotEmpty()) {
                    val used = tour.usedIn.isNotEmpty()
                    TextButton(
                        onClick = { askDelete = true },
                        enabled = !used,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .border(1.dp, if (used) colors.border else MpdRed, RoundedCornerShape(6.dp)),
                    ) {
                        IconLabel(
                            MpdIcons.Trash, stringResource(Res.string.tours_recycle),
                            color = if (used) colors.muted else MpdRed, iconSize = 16.dp, gap = 6.dp,
                        )
                    }
                    if (used) {
                        Text(
                            stringResource(Res.string.tours_recycle_blocked),
                            color = colors.muted, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        TourAlbumPicker(vm, tour, onDismiss = { pickerOpen = false }, onPlaced = onChanged)
    }
    if (askDelete) {
        val summary = buildString {
            append(tour.dist?.let { "$it km" } ?: stringResource(Res.string.tours_no_distance))
            tour.duration?.let { append(", ").append(it) }
            tour.stub?.let { append(" — ").append(stringResource(Res.string.tours_state_stub)).append(" (").append(stubText(it)).append(")") }
        }
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text(stringResource(Res.string.tours_recycle_title)) },
            text = {
                Column {
                    Text(
                        listOf(formatTourDate(tour.date), tour.heading()).filter { it.isNotEmpty() }.joinToString(" · "),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(summary, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        stringResource(Res.string.tours_recycle_hint),
                        color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = false
                    scope.launch {
                        try {
                            val recycled = vm.api.deleteTour(vm.baseUrl, tour)
                            vm.meldung(
                                Texte.t(if (recycled) Res.string.tours_recycled else Res.string.tours_deleted_no_bin),
                                kurz = true,
                            )
                            onChanged(null)
                        } catch (e: Exception) {
                            // 409: der Text des Servers nennt die Alben.
                            vm.meldung(Texte.t(Res.string.tours_not_deleted, e.message ?: Texte.t(Res.string.err_generic)))
                            onChanged(tour.key)
                        }
                    }
                }) { Text(stringResource(Res.string.tours_recycle), color = MpdRed) }
            },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
    if (kartenVollbild) {
        KarteVollbild(onClose = { kartenVollbild = false }) { mod -> karte(mod) }
    }
}

@Composable
private fun DetailRow(label: String, value: String?, small: Boolean = false, maxLines: Int = 1) {
    value ?: return
    val colors = Mpd.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = colors.muted, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(
            value, color = colors.text,
            fontSize = if (small) 11.sp else 13.sp,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * „In Album einfuegen" — Albumliste mit Suche, POST /api/tours/place mit
 * append: der Server verschiebt die offene Tour in den Albumordner und
 * haengt das Tour-Element ans Album (Web tours.html selectAlbum). Nur der
 * Familienbestand: dort liegen die Touren, und nur dort wird verschoben
 * statt kopiert (TOURS.md §2).
 */
@Composable
private fun TourAlbumPicker(
    vm: AppViewModel,
    tour: TourListEntry,
    onDismiss: () -> Unit,
    onPlaced: (newKey: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<de.bgghome.mpd.api.AlbumSummary>?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val usedNames = remember(tour) { tour.usedIn.map { it.album }.toSet() }

    LaunchedEffect(Unit) {
        runCatching { vm.api.albums(vm.baseUrl) }.onSuccess { list ->
            albums = list.filter { it.space == "shared" }
                .sortedWith(compareByDescending<de.bgghome.mpd.api.AlbumSummary> { it.year }.thenBy { it.title.lowercase() })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tours_insert_into_album)) },
        text = {
            Column {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Mpd.colors.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Mpd.colors.blue),
                    // Ohne Modifier waere nur der getippte Text beruehrbar.
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Mpd.colors.surfaceAlt)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            if (query.isEmpty()) Text(stringResource(Res.string.tours_album_search), color = Mpd.colors.muted, fontSize = 14.sp)
                            inner()
                        }
                    },
                )
                val shown = (albums ?: emptyList()).filter {
                    query.isBlank() || it.title.contains(query, true) || it.name.contains(query, true) ||
                        it.year.toString().contains(query)
                }
                LazyColumn(Modifier.fillMaxWidth().height(300.dp).padding(top = 6.dp)) {
                    items(shown, key = { "${it.space}/${it.name}" }) { a ->
                        val already = a.name in usedNames
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !busy && !already) {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val placed = vm.api.placeTour(vm.baseUrl, tour, a.name, a.space, append = true)
                                            vm.meldung(Texte.t(Res.string.tours_inserted, a.title.ifBlank { a.name }), kurz = true)
                                            onDismiss()
                                            // Verschoben → die Tour liegt jetzt im Albumordner.
                                            onPlaced(
                                                if (placed.action == "moved" || placed.action == "duplicate") "${a.name}/${placed.file}"
                                                else tour.key,
                                            )
                                        } catch (e: Exception) {
                                            vm.meldung(e.message ?: Texte.t(Res.string.err_generic))
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val c = if (already) Mpd.colors.muted else Mpd.colors.text
                            Text(a.year.toString(), color = Mpd.colors.muted, fontSize = 11.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                a.title.ifBlank { a.name }, color = c, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (already) Text(stringResource(Res.string.tours_already_in), color = StartGreen, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/** Hoehenprofil — Canvas aus MapElements wiederverwendet. */
@Composable
private fun TourElevationProfile(track: GpxTrack) {
    ElevationProfile(track)
}

/** "2024-03-15…" → Datum in der Schreibweise der Systemsprache
 *  (deutsch: 15.03.2024, wie zuvor fest verdrahtet). */
private fun formatTourDate(ds: String): String {
    if (ds.length < 10) return ds
    return runCatching {
        java.time.LocalDate.parse(ds.substring(0, 10)).format(
            java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault()),
        )
    }.getOrDefault(ds)
}

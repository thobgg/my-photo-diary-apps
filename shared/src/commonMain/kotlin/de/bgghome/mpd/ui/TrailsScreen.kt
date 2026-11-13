package de.bgghome.mpd.ui

import de.bgghome.mpd.ui.karte.GeoPunkt
import de.bgghome.mpd.ui.karte.KachelEbene
import de.bgghome.mpd.ui.karte.KachelKarte
import de.bgghome.mpd.ui.karte.KartenLinie
import de.bgghome.mpd.ui.karte.KartenPin
import de.bgghome.mpd.ui.karte.KartenZustand

import de.bgghome.mpd.shared.res.*
import org.jetbrains.compose.resources.StringResource
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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.TrailActivity
import de.bgghome.mpd.api.TrailDay
import de.bgghome.mpd.api.TrailHeatmap
import de.bgghome.mpd.api.TrailPlace
import de.bgghome.mpd.api.TrailStatus
import de.bgghome.mpd.api.TrailVisit
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Modul „Trails" — die eigene Standorthistorie (Dawarich-Abloesung,
 * Server-Phasen 1-6 vom 04.09.2026).
 *
 * BESONDERHEIT: Fuer dieses Modul gibt es KEINE Web-Vorlage. trails.html
 * ist der alte GPX-Ordner-Betrachter, mpd-trail.html spricht noch
 * Dawarich an — beide haben mit dem neuen Punktespeicher nichts zu tun.
 * Die App ist der erste Client; Vorlage war docs/API_CHANGES.md.
 *
 * Vier Bereiche, Handy zuerst gedacht (Wisch/Tipp statt Kopfzeilen-Knopf):
 *   Tag     — „Wo war ich?": Weg des Tages auf der Karte, darunter
 *             Besuche und Wege in der Reihenfolge des Tages.
 *   Orte    — wie oft war ich wo (top_places).
 *   Karte   — Heatmap ueber ein Jahr oder alles.
 *   Geraete — OwnTracks einrichten, Quellen verwalten, Archiv mitnehmen
 *             (ui/TrailsDevices.kt).
 *
 * Leitplanke aus dem Konzept: „maximale Datentreue" — angezeigt wird, was
 * der Speicher hergibt, samt Quelle. Nichts wird geschoent oder geraten.
 */

internal val TrailTrack = Color(0xFFE6007E)
internal val TrailVisitBlue = Color(0xFF2E75B6)

// Datum und Uhrzeit in der Schreibweise der Systemsprache (i18n 08.09.2026):
// deutsch weiterhin „14:30" und „08.09.2026", englisch „2:30 PM" und „8 Sept 2026".
private val HHMM: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(Locale.getDefault())
private val DMY: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(Locale.getDefault())
private val MONTH_YEAR: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private val TABS = listOf(
    MpdIcons.Calendar to Res.string.trails_tab_day, MpdIcons.Pin to Res.string.trails_tab_places,
    MpdIcons.Fire to Res.string.trails_tab_map, MpdIcons.Smartphone to Res.string.trails_tab_devices,
)

@Composable
fun TrailsScreen(vm: AppViewModel) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    // rememberSaveable: der Reiter ueberlebt den Prozess-Tod (Audit 08.09.).
    var tab by rememberSaveable { mutableStateOf(0) }

    var status by remember { mutableStateOf<TrailStatus?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    // Der Status traegt den Umfang (Punkte, Zeitspanne, Quellen) und wird
    // von drei Bereichen gebraucht — einmal laden, oben halten.
    LaunchedEffect(Unit) {
        runCatching { vm.api.trailsStatus(vm.baseUrl) }
            .onSuccess { status = it }
            .onFailure { statusError = trailsErrorText(it) }
    }

    MpdScreen(
        backDescription = if (tab != 0) stringResource(Res.string.trails_back_to_day)
        else stringResource(Res.string.trails_close),
        onClose = { if (tab != 0) tab = 0 else vm.trailsOpen = false },
        headerPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        header = {
            ScreenTitle("Trails", size = 20.sp)
            Spacer(Modifier.width(10.dp))
            status?.let {
                Text(
                    if (it.exists) pluralStringResource(Res.plurals.trails_points, it.points.toInt(), thousands(it.points))
                    else stringResource(Res.string.trails_empty_store),
                    color = colors.muted, fontSize = 13.sp,
                )
            }
        },
    ) {

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TABS.forEachIndexed { index, (icon, label) ->
                ChoicePill(stringResource(label), active = tab == index, icon = icon) { tab = index }
            }
        }

        if (statusError != null && status == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    statusError!!, color = MpdRed,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
                )
            }
            return@MpdScreen
        }

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> TrailsDayTab(vm, status)
                1 -> TrailsPlacesTab(vm) { date -> vm.trailsDate = date; tab = 0 }
                2 -> TrailsHeatTab(vm, status)
                else -> TrailsDevicesTab(vm, status) { status = it }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tag — „Wo war ich?"
// ---------------------------------------------------------------------------

@Composable
private fun TrailsDayTab(vm: AppViewModel, status: TrailStatus?) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    // vm.trailsDate erlaubt den Einstieg von aussen (Lightbox, Zeitstrahl)
    // und wird von der Ortsliste gesetzt.
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(vm.trailsDate) }.getOrDefault(today)) }
    var day by remember { mutableStateOf<TrailDay?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var deriving by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableStateOf(0) }
    /** Tipp auf einen Aufenthalt schiebt die Karte dorthin. Der Zaehler
     *  sorgt dafuer, dass auch derselbe Ort erneut anspringt. */
    var focus by remember { mutableStateOf<Pair<GeoPunkt, Int>?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    /** Besuch, dessen Ort gerade benannt wird (Stift). */
    var renameVisit by remember { mutableStateOf<TrailVisit?>(null) }
    /** Nachbartage MIT Aufzeichnung — nur gesucht, wenn der Tag leer ist. */
    var prevWithData by remember { mutableStateOf<LocalDate?>(null) }
    var nextWithData by remember { mutableStateOf<LocalDate?>(null) }
    val monthCache = remember { mutableMapOf<YearMonth, Map<String, Int>>() }
    val scrollState = rememberScrollState()

    // Datum aus dem ViewModel nachziehen, wenn die Ortsliste eins setzt.
    LaunchedEffect(vm.trailsDate) {
        runCatching { LocalDate.parse(vm.trailsDate) }.getOrNull()?.let { date = it }
    }

    LaunchedEffect(date, reloadTick) {
        // Gemerkt, damit der Tag einen Tabwechsel ueberlebt und ein
        // Aufruf von aussen („Wo war ich?") denselben Weg nimmt.
        vm.trailsDate = date.toString()
        loading = true
        error = null
        focus = null
        prevWithData = null
        nextWithData = null
        runCatching { vm.api.trailsDay(vm.baseUrl, date.toString(), tzMinutesAt(date)) }
            .onSuccess { day = it }
            .onFailure { error = trailsErrorText(it); day = null }
        loading = false
    }

    // Leerer Tag: die beiden naechsten Tage mit Aufzeichnung suchen, damit
    // man sich nicht Tag fuer Tag durch aufzeichnungsfreie Wochen klickt.
    LaunchedEffect(day, date) {
        val d = day
        if (d == null || d.points.isNotEmpty() || d.visits.isNotEmpty()) return@LaunchedEffect
        val first = status?.firstTst?.let { localDateOf(it) } ?: LocalDate.of(2000, 1, 1)
        val last = status?.lastTst?.let { localDateOf(it) } ?: today
        prevWithData = nearestDayWithData(vm, date, false, first, last, monthCache)
        nextWithData = nearestDayWithData(vm, date, true, first, last, monthCache)
    }

    val dateNav: @Composable () -> Unit = {
        Column {
            // ‹ Datum › — Tippflaechen 48 dp (Lektion aus dem Memories-Screen:
            // nackte Textpfeile waren „kaum tapbar").
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrailArrow("‹") { date = date.minusDays(1) }
                Text(
                    date.format(DMY),
                    color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { calendarOpen = true }
                        .padding(vertical = 12.dp),
                )
                TrailArrow("›", enabled = date < today) { date = date.plusDays(1) }
            }
            if (date != today) {
                Text(
                    stringResource(Res.string.trails_back_today),
                    color = colors.blue, fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { date = today }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }

    // Karte im Vollbild (15.09.2026) — derselbe Griff wie in Tours.
    var kartenVollbild by remember { mutableStateOf(false) }
    val mapArea: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
            val d = day
            when {
                loading -> CircularProgressIndicator()
                error != null -> Text(
                    error!!, color = MpdRed,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
                )
                d == null || (d.points.isEmpty() && d.visits.isEmpty()) -> EmptyDay(
                    prev = prevWithData,
                    next = nextWithData,
                    onPick = { date = it },
                )
                else -> TrailDayMap(d, focus)
            }
        }
    }

    val details: @Composable ColumnScope.() -> Unit = {
        day?.let { d ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                DaySummary(d)

                val entries = remember(d) { mergeDayEntries(d) }
                if (entries.isEmpty() && d.points.isNotEmpty()) {
                    // Rohpunkte da, aber noch nichts abgeleitet: der Nachtjob
                    // war noch nicht dran. Der Server macht das auf Zuruf.
                    Text(
                        stringResource(Res.string.trails_not_derived),
                        color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    AccentButton(if (deriving) stringResource(Res.string.trails_deriving) else stringResource(Res.string.trails_derive_now)) {
                        if (deriving) return@AccentButton
                        deriving = true
                        scope.launch {
                            runCatching {
                                vm.api.trailsDerive(vm.baseUrl, date.toString(), tzMinutesAt(date))
                            }.onFailure { error = trailsErrorText(it) }
                            deriving = false
                            reloadTick++
                        }
                    }
                }

                entries.forEachIndexed { index, entry ->
                    when (entry) {
                        is DayEntry.Stay -> VisitRow(
                            entry.visit,
                            number = entries.take(index + 1).count { it is DayEntry.Stay },
                            onFocus = {
                                focusTick++
                                focus = GeoPunkt(entry.visit.lat, entry.visit.lon) to focusTick
                                // Schmales Fenster: die Karte steht oben, also
                                // hinscrollen, sonst bewegt sie sich unsichtbar.
                                scope.launch { scrollState.animateScrollTo(0) }
                            },
                            onRename = entry.visit.placeRef?.let { { renameVisit = entry.visit } },
                        )
                        is DayEntry.Move -> ActivityRow(entry.activity)
                    }
                }

                if (d.points.isNotEmpty()) {
                    TrailsDayExport(vm, date)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Am Tablet quer nebeneinander wie bei Tours — eine 320-dp-Karte
        // in einer Scrollflaeche verschenkt dort das halbe Bild.
        if (maxWidth >= 840.dp) {
            Column(Modifier.fillMaxSize()) {
                dateNav()
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(400.dp)
                            .fillMaxHeight()
                            .verticalScroll(scrollState)
                            .rubberBandOverscroll(),
                        content = details,
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        mapArea(Modifier.fillMaxSize())
                        VollbildKnopf { kartenVollbild = true }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).rubberBandOverscroll(),
            ) {
                dateNav()
                Box(Modifier.fillMaxWidth().height(320.dp)) {
                    mapArea(Modifier.fillMaxSize())
                    VollbildKnopf { kartenVollbild = true }
                }
                details()
            }
        }
        if (kartenVollbild) {
            KarteVollbild(onClose = { kartenVollbild = false }) { mod -> mapArea(mod) }
        }
    }

    if (calendarOpen) {
        TrailsCalendarDialog(
            vm, date,
            onPick = { picked -> date = picked; calendarOpen = false },
            onDismiss = { calendarOpen = false },
        )
    }
    renameVisit?.let { visit ->
        PlaceNameDialog(
            vm, visit,
            onDismiss = { renameVisit = null },
            // Der Name gilt fuer alle Besuche des Ortes — den Tag neu laden
            // statt eine Zeile zu flicken (wie loadDay() im Web). Die
            // Orte-Liste laedt beim naechsten Oeffnen ihres Reiters frisch.
            onSaved = { renameVisit = null; reloadTick++ },
        )
    }
}

/** Leerer Tag — mit den Sprungmarken zu den Nachbartagen, die Daten haben. */
@Composable
private fun EmptyDay(prev: LocalDate?, next: LocalDate?, onPick: (LocalDate) -> Unit) {
    val colors = Mpd.colors
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconLabel(
            MpdIcons.Compass, stringResource(Res.string.trails_day_empty),
            color = colors.muted,
        )
        if (prev != null || next != null) {
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prev?.let {
                    Text(
                        "‹ " + it.format(DMY),
                        color = colors.blue, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(it) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                next?.let {
                    Text(
                        it.format(DMY) + " ›",
                        color = colors.blue, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(it) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Text(
                stringResource(Res.string.trails_next_recorded),
                color = colors.muted.copy(alpha = 0.7f), fontSize = 11.sp,
            )
        }
    }
}

/**
 * Naechster bzw. vorheriger Tag mit Punkten. Gefragt wird monatsweise ueber
 * `/days` (dieselbe Quelle wie der Kalender), Antworten werden gemerkt —
 * ohne das klickt man sich durch aufzeichnungsfreie Wochen einzeln durch.
 * Die Suche endet an den Raendern des Speichers, spaetestens nach 36
 * Monaten; ohne Deckel wuerde ein Loch von Jahren die Oberflaeche haengen
 * lassen.
 */
private suspend fun nearestDayWithData(
    vm: AppViewModel,
    from: LocalDate,
    forward: Boolean,
    first: LocalDate,
    last: LocalDate,
    cache: MutableMap<YearMonth, Map<String, Int>>,
): LocalDate? {
    var month = YearMonth.from(from)
    repeat(36) {
        if (forward && month.atDay(1) > last) return null
        if (!forward && month.atEndOfMonth() < first) return null

        val days = cache.getOrPut(month) {
            runCatching {
                vm.api.trailsDays(vm.baseUrl, month.year, month.monthValue, tzMinutesAt(month.atDay(1)))
            }.getOrDefault(emptyMap())
        }
        val candidates = days.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .filter { if (forward) it > from else it < from }
        val hit = if (forward) candidates.minOrNull() else candidates.maxOrNull()
        if (hit != null) return hit

        month = if (forward) month.plusMonths(1) else month.minusMonths(1)
    }
    return null
}

/** Zaehlerzeile unter der Karte — nur echte Werte, nichts geschaetzt. */
@Composable
private fun DaySummary(d: TrailDay) {
    val colors = Mpd.colors
    val meters = d.activities.sumOf { it.distanceM ?: 0.0 }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SummaryChip(pluralStringResource(Res.plurals.trails_points, d.pointsTotal, thousands(d.pointsTotal.toLong())))
        if (d.thinned) SummaryChip(stringResource(Res.string.trails_shown, d.points.size))
        if (d.visits.isNotEmpty()) SummaryChip(pluralStringResource(Res.plurals.trails_visits, d.visits.size, d.visits.size))
        if (d.activities.isNotEmpty()) SummaryChip(pluralStringResource(Res.plurals.trails_activities, d.activities.size, d.activities.size))
        if (meters > 0) SummaryChip(humanDistance(meters))
        // Datentreue: welche Quelle den Tag getragen hat, steht dran.
        d.points.map { it.source }.distinct().forEach { SummaryChip(sourceLabel(it)) }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(Res.string.trails_own_history),
        color = colors.muted.copy(alpha = 0.7f), fontSize = 11.sp,
    )
}

@Composable
private fun SummaryChip(text: String) {
    val colors = Mpd.colors
    Text(
        text, color = colors.muted, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceAlt)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

private sealed interface DayEntry {
    val start: Long
    data class Stay(val visit: TrailVisit) : DayEntry {
        override val start get() = visit.startTs
    }
    data class Move(val activity: TrailActivity) : DayEntry {
        override val start get() = activity.startTs
    }
}

private fun mergeDayEntries(d: TrailDay): List<DayEntry> =
    (d.visits.map { DayEntry.Stay(it) } + d.activities.map { DayEntry.Move(it) })
        .sortedBy { it.start }

@Composable
private fun VisitRow(
    visit: TrailVisit,
    number: Int,
    onFocus: () -> Unit,
    /** null = kein Stift: Besuche ohne Ortskennung (place_ref) kann der
     *  Server nicht benennen, er antwortet dort 404. */
    onRename: (() -> Unit)? = null,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val title = visitTitle(visit)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onFocus)
            .padding(vertical = 7.dp),
    ) {
        Text(
            number.toString(),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(TrailVisitBlue)
                .padding(top = 3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            // Eigener Name (custom) wie im Web (.tr-name-custom): fett.
            Text(
                title, color = colors.text, fontSize = 15.sp,
                fontWeight = if (visit.custom) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val place = listOfNotNull(
                visit.city?.takeIf { it.isNotBlank() },
                visit.country?.takeIf { it.isNotBlank() },
            ).joinToString(", ")
            if (place.isNotBlank()) {
                Text(place, color = colors.muted, fontSize = 12.sp)
            }
            Text(
                "${hhmm(visit.startTs, visit.tzOffset)} – ${hhmm(visit.endTs, visit.tzOffset)}" +
                    " · " + humanDuration(visit.endTs - visit.startTs) +
                    " · " + sourceLabel(visit.source),
                color = colors.muted, fontSize = 12.sp,
            )
        }
        // Stift am Besuch wie im Web (trails.html .tr-edit, „Ort benennen").
        if (onRename != null) {
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(
                    MpdIcons.Pencil, stringResource(Res.string.trails_place_rename),
                    Modifier.size(18.dp), tint = colors.muted,
                )
            }
        }
    }
}

/**
 * Eigener Ortsname — Gegenstueck zu editPlaceName() in trails.html
 * (Vertrag 08.09.2026, PUT /api/trails/places/name). Der Name haengt am
 * Ort (source, place_ref) und gilt fuer alle Besuche dort, auch kuenftige;
 * leer speichern nimmt ihn zurueck, der Kartendienst-Name erscheint
 * wieder. Im Web ist es ein Eingabefeld an Ort und Stelle (Enter
 * speichert); am Geraet ein kleiner Dialog mit Tastatur.
 */
@Composable
private fun PlaceNameDialog(
    vm: AppViewModel,
    visit: TrailVisit,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()
    val colors = Mpd.colors
    // Wie im Web: bei eigenem Namen steht er im Feld, sonst ist das Feld
    // leer und der Kartendienst-Name der Platzhalter.
    var name by remember { mutableStateOf(if (visit.custom) visit.placeName.orEmpty() else "") }
    var busy by remember { mutableStateOf(false) }
    val placeRef = visit.placeRef ?: return

    fun save(value: String?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val r = vm.api.setTrailsPlaceName(vm.baseUrl, visit.source, placeRef, value)
                vm.meldung(
                    Texte.t(
                        if (r.custom) Res.string.trails_place_name_saved
                        else Res.string.trails_place_name_cleared,
                    ),
                    kurz = true,
                )
                onSaved()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                vm.meldung(
                    Texte.t(Res.string.trails_place_name_failed, trailsErrorText(e)),
                )
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.trails_place_rename)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 80) name = it },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = {
                        Text(
                            visit.placeName?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.trails_place_name_hint),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (visit.custom) {
                    Text(
                        stringResource(Res.string.trails_place_custom),
                        color = colors.muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(name) }, enabled = !busy) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (visit.custom) {
                    TextButton(onClick = { save(null) }, enabled = !busy) {
                        Text(stringResource(Res.string.trails_place_name_reset))
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        },
    )
}

@Composable
private fun ActivityRow(activity: TrailActivity) {
    val colors = Mpd.colors
    val (icon, labelRes) = activityLabel(activity.type)
    val label = stringResource(labelRes)
    Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 2.dp, bottom = 2.dp)) {
        Text(icon, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                append(label)
                activity.distanceM?.takeIf { it > 0 }?.let { append(" · ").append(humanDistance(it)) }
                append(" · ").append(humanDuration(activity.endTs - activity.startTs))
            },
            color = colors.muted, fontSize = 12.sp,
        )
    }
}

/** Mitnahme fuer einen einzelnen Tag — GPX fuer Werkzeuge, GeoJSON fuer Karten. */
@Composable
private fun TrailsDayExport(vm: AppViewModel, date: LocalDate) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("gpx" to "GPX", "geojson" to "GeoJSON").forEach { (format, label) ->
            IconLabel(
                MpdIcons.Download, label, color = colors.blue, fontSize = 13.sp,
                iconSize = 15.dp, gap = 6.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            dienste.speichereDownload(
                                vm,
                                url = vm.api.trailsExportDayUrl(
                                    vm.baseUrl, date.toString(), tzMinutesAt(date), format,
                                ),
                                filename = "trails-$date.$format",
                                mime = if (format == "gpx") "application/gpx+xml" else "application/geo+json",
                            )
                            busy = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Tageskarte
// ---------------------------------------------------------------------------

@Composable
private fun TrailDayMap(day: TrailDay, focus: Pair<GeoPunkt, Int>? = null) {
    var armed by remember { mutableStateOf(false) }
    val segments = remember(day) { daySegments(day) }
    val visits = remember(day) { day.visits }
    val zustand = remember { KartenZustand() }
    var eingepasst by remember(day) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(-1) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val all = remember(day) { day.points.map { GeoPunkt(it.lat, it.lon) } + visits.map { GeoPunkt(it.lat, it.lon) } }
        if (!eingepasst && all.isNotEmpty()) {
            zustand.passeEin(all, constraints.maxWidth, constraints.maxHeight, 36, einzelZoom = 15)
            eingepasst = true
        }
        focus?.let { (point, tick) ->
            if (focused != tick) { focused = tick; zustand.setze(point.lat, point.lon, 16) }
        }
        KachelKarte(
            zustand, KachelEbene.STANDARD, Modifier.fillMaxSize(), interaktiv = armed,
            linien = segments.map { KartenLinie(it, Color.White.copy(alpha = 0.9f), 6f) } +
                segments.map { KartenLinie(it, TrailTrack, 3.5f) },
            pins = visits.mapIndexed { i, v -> KartenPin(v.lat, v.lon, "${i + 1}", TrailVisitBlue) },
        )
        ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
    }
}

/**
 * Der Tagesweg als Linienabschnitte fuer die Karte.
 *
 * Getrennt wird bei Quellenwechsel und ab einer halben Stunde Abstand —
 * das entspricht der Luecke, die auch der Server als Aufenthalt sieht.
 * Seit 08.09.2026 kommen Punkte innerhalb eines erkannten Aufenthalts
 * nicht mehr in die Linie („den Knaeuel wegkriegen") — die Regel
 * selbst steht testbar in util/TrailSegments.kt, hier wird nur in
 * Kartenpunkte uebersetzt.
 */
private fun daySegments(day: TrailDay): List<List<GeoPunkt>> =
    de.bgghome.mpd.util.tagesAbschnitte(day.points, day.visits)
        .map { seg -> seg.map { (lat, lon) -> GeoPunkt(lat, lon) } }

// ---------------------------------------------------------------------------
// Kalender — welche Tage haben ueberhaupt Punkte
// ---------------------------------------------------------------------------

@Composable
private fun TrailsCalendarDialog(
    vm: AppViewModel,
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    var month by remember { mutableStateOf(YearMonth.from(initial)) }
    var days by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(month) {
        loading = true
        days = runCatching {
            vm.api.trailsDays(
                vm.baseUrl, month.year, month.monthValue,
                tzMinutesAt(month.atDay(1)),
            )
        }.getOrDefault(emptyMap())
        loading = false
    }

    val maxCount = days.values.maxOrNull() ?: 1

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrailArrow("‹‹", size = 36.dp) { month = month.minusYears(1) }
                TrailArrow("‹", size = 36.dp) { month = month.minusMonths(1) }
                Text(
                    month.atDay(1).format(MONTH_YEAR),
                    fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                TrailArrow("›", size = 36.dp) { month = month.plusMonths(1) }
                TrailArrow("››", size = 36.dp) { month = month.plusYears(1) }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    stringArrayResource(Res.array.stats_weekday_labels).forEach {
                        Text(
                            it, color = colors.muted, fontSize = 11.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Vorlauf bis zum ersten Wochentag, dann Tag fuer Tag.
                val first = month.atDay(1)
                val lead = first.dayOfWeek.value - 1
                val cells = lead + month.lengthOfMonth()
                val rows = (cells + 6) / 7
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val index = row * 7 + col - lead
                            if (index < 0 || index >= month.lengthOfMonth()) {
                                Spacer(Modifier.weight(1f).height(44.dp))
                            } else {
                                val d = month.atDay(index + 1)
                                val n = days[d.toString()] ?: 0
                                CalendarCell(
                                    day = index + 1,
                                    count = n,
                                    maxCount = maxCount,
                                    selected = d == initial,
                                    modifier = Modifier.weight(1f),
                                ) { onPick(d) }
                            }
                        }
                    }
                }
                Text(
                    if (loading) stringResource(Res.string.trails_loading)
                    else pluralStringResource(Res.plurals.trails_days_recorded, days.size, days.size),
                    color = colors.muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.trails_close_dialog)) } },
    )
}

@Composable
private fun CalendarCell(
    day: Int,
    count: Int,
    maxCount: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    // Dichte als Fuellung: ein Tag mit 3.000 Punkten sieht anders aus als
    // einer mit 20 — genau dafuer liefert /days die Zahlen.
    val strength = if (count <= 0) 0f else (0.25f + 0.75f * (ln(1.0 + count) / ln(1.0 + maxCount)).toFloat())
    Box(
        modifier
            .height(44.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    count > 0 -> colors.blue.copy(alpha = 0.18f + 0.5f * strength)
                    else -> Color.Transparent
                },
            )
            .then(
                if (selected) Modifier.border(1.5.dp, colors.gold, RoundedCornerShape(6.dp))
                else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.toString(),
            color = if (count > 0) colors.text else colors.muted.copy(alpha = 0.5f),
            fontSize = 13.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Orte
// ---------------------------------------------------------------------------

@Composable
private fun TrailsPlacesTab(vm: AppViewModel, onOpenDay: (String) -> Unit) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    var places by remember { mutableStateOf<List<TrailPlace>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { vm.api.trailsPlaces(vm.baseUrl, limit = 200) }
            .onSuccess { places = it }
            .onFailure { error = trailsErrorText(it) }
    }

    LoadState(
        error = error, data = places,
        isEmpty = { it.isEmpty() },
        errorContent = { ErrorCenter(it, MpdRed) },
        loading = { LoadingCenter() },
        empty = {
            CenterBox {
                Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconLabel(MpdIcons.Pin, stringResource(Res.string.trails_no_places), color = colors.muted)
                    Text(
                        stringResource(Res.string.trails_places_nightly), color = colors.muted,
                        textAlign = TextAlign.Center, lineHeight = 20.sp,
                    )
                }
            }
        },
    ) { list ->
        val lastTemplate = stringResource(Res.string.trails_last, "%s")
        LazyColumn(Modifier.fillMaxSize().rubberBandOverscroll()) {
            itemsIndexed(list, key = { _, p -> "${p.source}/${p.placeRef}" }) { index, place ->
                val lastDay = place.lastTs?.let { localDateOf(it) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = lastDay != null) { lastDay?.let { onOpenDay(it.toString()) } }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}", color = colors.muted, fontSize = 12.sp,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            place.name?.takeIf { it.isNotBlank() }
                                ?: place.city?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.trails_place_unnamed),
                            color = colors.text, fontSize = 15.sp,
                            // Eigener Name fett, wie im Tagesreiter und im Web.
                            fontWeight = if (place.custom) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("${place.n}×")
                                append(" · ").append(humanDuration(place.seconds))
                                place.lastTs?.let {
                                    append(" · ").append(lastTemplate.format(localDateOf(it).format(DMY)))
                                }
                            },
                            color = colors.muted, fontSize = 12.sp,
                        )
                        // Google-Besuche haben keinen Namen, nur eine placeId —
                        // das ehrlich zeigen statt eine Adresse zu erfinden.
                        if (place.name.isNullOrBlank() && place.placeRef != null) {
                            Text(
                                "${sourceLabel(place.source)} · ${place.placeRef}",
                                color = colors.muted.copy(alpha = 0.6f), fontSize = 10.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Karte — Heatmap
// ---------------------------------------------------------------------------

@Composable
private fun TrailsHeatTab(vm: AppViewModel, status: TrailStatus?) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val years = remember(status) {
        val first = status?.firstTst?.let { localDateOf(it).year }
        val last = status?.lastTst?.let { localDateOf(it).year }
        if (first == null || last == null) emptyList() else (last downTo first).toList()
    }
    // Voreinstellung ist das juengste Jahr, NICHT „Alles": ueber den ganzen
    // Bestand rastert der Server eine halbe Million Punkte, und das waere
    // ausgerechnet der erste Blick auf diesen Tab.
    var year by remember(years) { mutableStateOf(years.firstOrNull()) }
    var heat by remember { mutableStateOf<TrailHeatmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(year, status) {
        // Ohne ersten Punkt gibt es nichts zu rastern — und ein from=0
        // waere dem Server ein Zeitraum ueber 50 Jahre (400).
        if (status?.firstTst == null) return@LaunchedEffect
        loading = true
        error = null
        // Ueber alles gerechnet wird die Zelle groesser (≈ 5 km), sonst
        // kommen bei einer halben Million Punkten zu viele Zellen zurueck.
        runCatching {
            vm.api.trailsHeatmap(
                vm.baseUrl, year,
                from = status.firstTst, to = (status.lastTst ?: 0) + 1,
                cell = if (year == null) 0.05 else 0.01,
            )
        }
            .onSuccess { heat = it }
            .onFailure { error = trailsErrorText(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoicePill(stringResource(Res.string.trails_all_years), active = year == null) { year = null }
            years.forEach { y -> ChoicePill(y.toString(), active = year == y) { year = y } }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val h = heat
            when {
                error != null -> Text(
                    error!!, color = MpdRed,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
                )
                status?.firstTst == null -> IconLabel(
                    MpdIcons.Fire, stringResource(Res.string.trails_heat_empty_store),
                    color = colors.muted, modifier = Modifier.padding(32.dp),
                )
                h == null || loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    if (year == null) {
                        Text(
                            stringResource(Res.string.trails_heat_rendering, thousands(status?.points ?: 0)),
                            color = colors.muted, fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                        )
                    }
                }
                h.cells.isEmpty() -> IconLabel(
                    MpdIcons.Fire, stringResource(Res.string.trails_heat_no_points),
                    color = colors.muted, modifier = Modifier.padding(32.dp),
                )
                else -> {
                    HeatMapView(h)
                    Text(
                        stringResource(Res.string.trails_heat_summary, thousands(h.total), thousands(h.cells.size.toLong())) +
                            (if (h.truncated) stringResource(Res.string.trails_heat_truncated) else ""),
                        color = Color.White, fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeatMapView(heat: TrailHeatmap) {
    val zustand = remember { KartenZustand() }
    var eingepasst by remember(heat) { mutableStateOf(false) }
    val maxCount = remember(heat) { heat.cells.maxOfOrNull { it.getOrElse(2) { 1.0 } } ?: 1.0 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!eingepasst) {
            zustand.passeEin(heat.cells.map { GeoPunkt(it[0], it[1]) }, constraints.maxWidth, constraints.maxHeight, 24, einzelZoom = 12)
            eingepasst = true
        }
        // Heatmap-Zellen als gefuellte Kreise. Der Radius kommt aus der
        // Zellgroesse in Grad - die Flecken wachsen beim Hineinzoomen mit dem
        // Gelaende mit. Farbe blau → gold → rot, logarithmisch, sonst frisst
        // der Wohnort alles andere auf.
        KachelKarte(zustand, KachelEbene.STANDARD, Modifier.fillMaxSize(), interaktiv = true) z@{ zumSchirm ->
            val sample = heat.cells.firstOrNull() ?: return@z
            val p0 = zumSchirm(GeoPunkt(sample[0], sample[1]))
            val p1 = zumSchirm(GeoPunkt(sample[0], sample[1] + heat.cell))
            val radius = max(2f, kotlin.math.abs(p1.x - p0.x) * 0.62f)
            heat.cells.forEach { cell ->
                val count = cell.getOrElse(2) { 1.0 }
                val pt = zumSchirm(GeoPunkt(cell[0], cell[1]))
                if (pt.x < -radius || pt.y < -radius || pt.x > size.width + radius || pt.y > size.height + radius) return@forEach
                val t = (ln(1.0 + count) / ln(1.0 + maxCount)).toFloat().coerceIn(0f, 1f)
                drawCircle(heatFarbe(t), radius, pt)
            }
        }
    }
}

internal fun heatFarbe(t: Float): Color {
    val blau = Color(0xFF2E75B6); val gold = Color(0xFFC8922A); val rot = Color(0xFFE74C3C)
    val basis = if (t < 0.5f) androidx.compose.ui.graphics.lerp(blau, gold, t / 0.5f)
    else androidx.compose.ui.graphics.lerp(gold, rot, (t - 0.5f) / 0.5f)
    return basis.copy(alpha = ((90 + 130 * t) / 255f).coerceIn(0f, 1f))
}

// ---------------------------------------------------------------------------
// Gemeinsame Bausteine und Formatierung
// ---------------------------------------------------------------------------

/** Pfeil mit 48-dp-Tippflaeche — nackte Textpfeile waren zu klein. */
@Composable
private fun TrailArrow(
    glyph: String,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (enabled) colors.text else colors.muted.copy(alpha = 0.35f),
            fontSize = 20.sp,
        )
    }
}

internal fun trailsErrorText(e: Throwable): String =
    apiErrorText(e, forbidden = Texte.t(Res.string.trails_forbidden))

/** Zeitzonen-Versatz in Minuten, wie er an DIESEM Tag hier galt — damit ein
 *  Sommertag im Winter nicht um eine Stunde verrutscht. */
internal fun tzMinutesAt(date: LocalDate): Int =
    ZoneId.systemDefault().rules.getOffset(date.atStartOfDay()).totalSeconds / 60

internal fun localDateOf(tst: Long): LocalDate =
    Instant.ofEpochSecond(tst).atZone(ZoneId.systemDefault()).toLocalDate()

/** Unix-Sekunde als „04.09.2026". */
internal fun dmyText(tst: Long): String = localDateOf(tst).format(DMY)

/** Uhrzeit in der Zone der Aufzeichnung (Google liefert sie mit), sonst
 *  in der Zone des Geraets. */
internal fun hhmm(tst: Long, tzOffsetMinutes: Int?): String {
    val zone = tzOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
    return Instant.ofEpochSecond(tst).atZone(zone).format(HHMM)
}

internal fun humanDuration(seconds: Long): String {
    if (seconds < 60) return "$seconds s"
    val minutes = seconds / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val restMinutes = minutes % 60
    if (hours < 24) return if (restMinutes == 0L) "$hours h" else "$hours h $restMinutes min"
    val days = hours / 24
    val restHours = hours % 24
    return if (restHours == 0L) "$days d" else "$days d $restHours h"
}

internal fun humanDistance(meters: Double): String =
    if (meters < 1000) "${meters.roundToInt()} m"
    else String.format(Locale.getDefault(), "%.1f km", meters / 1000)

internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1073741824.0)
}

internal fun thousands(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

/** Quellen so benennen, wie sie entstanden sind — Datentreue heisst auch,
 *  dass man sieht, wer einen Eintrag geliefert hat. */
internal fun sourceLabel(source: String): String = when {
    source == "owntracks" -> "OwnTracks"
    source == "overland" -> "Overland"
    source == "dawarich" -> "Dawarich"
    source == "mpd" -> "MPD"
    source.startsWith("google") -> "Google" + source.removePrefix("google").removePrefix(":")
        .let { if (it.isBlank()) "" else " ($it)" }
    else -> source
}

private fun visitTitle(visit: TrailVisit): String =
    visit.placeName?.takeIf { it.isNotBlank() }
        ?: when (visit.semantic) {
            "HOME" -> Texte.t(Res.string.trails_visit_home)
            "WORK" -> Texte.t(Res.string.trails_visit_work)
            "SEARCHED_ADDRESS" -> Texte.t(Res.string.trails_visit_searched)
            else -> null
        }
        ?: visit.city?.takeIf { it.isNotBlank() }
        ?: Texte.t(Res.string.trails_visit_generic)

/** Verkehrsmittel: Google-Typen und die drei groben Klassen der eigenen
 *  Ableitung (WALKING / CYCLING / IN_VEHICLE). Dawarich-Tracks tragen eine
 *  Zahl als Typ — dafuer bleibt es beim neutralen „Weg". */
internal fun activityLabel(type: String?): Pair<String, StringResource> = when (type?.uppercase()) {
    "WALKING", "ON_FOOT" -> "🚶" to Res.string.trails_act_walking
    "RUNNING" -> "🏃" to Res.string.trails_act_running
    "CYCLING" -> "🚴" to Res.string.trails_act_cycling
    "IN_PASSENGER_VEHICLE", "DRIVING" -> "🚗" to Res.string.trails_act_car
    // Eigene Ableitung aus dem Tempo: Zug, Bus, Auto sind nicht zu
    // unterscheiden — deshalb „Fahrzeug", nicht „Auto" (Server-Seite 08.09.).
    "IN_VEHICLE" -> "🚗" to Res.string.trails_act_vehicle
    "MOTORCYCLING" -> "🏍️" to Res.string.trails_act_motorcycle
    "IN_BUS" -> "🚌" to Res.string.trails_act_bus
    "IN_TRAIN" -> "🚆" to Res.string.trails_act_train
    "IN_SUBWAY" -> "🚇" to Res.string.trails_act_subway
    "IN_TRAM" -> "🚊" to Res.string.trails_act_tram
    "IN_FERRY" -> "⛴️" to Res.string.trails_act_ferry
    "FLYING" -> "✈️" to Res.string.trails_act_flight
    "SAILING" -> "⛵" to Res.string.trails_act_sailing
    "SKIING" -> "🎿" to Res.string.trails_act_ski
    "STILL" -> "⏸️" to Res.string.trails_act_still
    null, "", "UNKNOWN", "UNKNOWN_ACTIVITY_TYPE" -> "🧭" to Res.string.trails_act_way
    else -> "🧭" to Res.string.trails_act_way
}

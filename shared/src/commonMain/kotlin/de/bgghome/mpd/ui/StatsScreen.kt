package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import de.bgghome.mpd.api.StatsGeo
import de.bgghome.mpd.ui.karte.GeoPunkt
import de.bgghome.mpd.ui.karte.KachelEbene
import de.bgghome.mpd.ui.karte.KachelKarte
import de.bgghome.mpd.ui.karte.KartenZustand
import kotlin.math.ln
import kotlin.math.sqrt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.StatsResponse
import de.bgghome.mpd.api.TextStats
import de.bgghome.mpd.ui.theme.Mpd
import de.bgghome.mpd.api.tours
import de.bgghome.mpd.moduleAllowed

/*
 * Modul „Stats" — natives Gegenstueck zu stats.html/stats.js (dort
 * Chart.js). Sektionen wie das Web-Dashboard: Kopfzahlen, Heatmap
 * Monat/Jahr, Photos pro Jahr/Monat, Jahreszeiten, Wochentage,
 * Uhrzeiten, Kameras, GPS-Abdeckung, Dateigroessen, Speicher pro Jahr,
 * Top-Alben sowie die kompletten Tagebuch-Text-Statistiken inkl.
 * Score. Seit 13.11.2026 auch Weltkarte (Punkte/Heatmap auf der eigenen
 * Kachelkarte), Top Laender und die Tag/Woche-Ansicht je Jahr (Tipp auf
 * eine Zeile der Monat/Jahr-Heatmap, wie openDrilldown im Web). Der
 * Server scannt beim ersten Aufruf im Hintergrund (202 → Poll); Geo
 * braucht serverseitig einen MAPTILER_KEY, sonst sagt die Karte das.
 *
 * Layout (13.11.2026, „manche Darstellung in der App nicht sonderlich
 * schoen breitgezogen"): ein adaptives Raster statt einer Spalte — ab
 * zwei mal 380 dp stehen die Karten nebeneinander wie chart-grid-2 im
 * Web; Kopfzahlen, Heatmaps und Weltkarte nehmen die volle Breite.
 */

/** Die Jahreszeiten sind SCHLUESSEL der Server-Antwort (seasonCounts);
 *  die Beschriftung kommt in derselben Reihenfolge aus den Ressourcen. */
private val SEASON_ORDER = listOf("Frühling", "Sommer", "Herbst", "Winter")
private val STYLE_LABELS = mapOf(
    "default" to Res.string.stats_style_default, "info" to Res.string.stats_style_info,
    "quote" to Res.string.stats_style_quote, "note" to Res.string.stats_style_note,
    "heading" to Res.string.stats_style_heading,
)

/** Ganzzahl mit Tausendertrenner der Systemsprache (deutsch: 17.555). */
private fun groupedInt(n: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(n)

@Composable
fun StatsScreen(vm: AppViewModel) {
    val colors = Mpd.colors
    LaunchedEffect(Unit) { vm.stats.load() }

    // Sport-Jahresstatistik (03.09.2026): speist sich aus /api/tours —
    // nur laden, wenn das Tours-Modul freigeschaltet ist; Fehler still
    // (die Sektion fehlt dann einfach).
    var tours by remember { mutableStateOf<List<de.bgghome.mpd.api.TourListEntry>?>(null) }
    LaunchedEffect(Unit) {
        if (vm.moduleAllowed("tours")) {
            tours = runCatching { vm.api.tours(vm.baseUrl) }.getOrNull()
        }
    }

    MpdScreen(
        backDescription = stringResource(Res.string.stats_close),
        onClose = { vm.stats.open = false },
        headerPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        // Einzige Kopfzeile mit Theme-Schrift — beibehalten.
        header = { ScreenTitle("Statistiken", fontFamily = Mpd.style.fontFamily) },
    ) {
        LoadState(
            error = vm.stats.error, data = vm.stats.data,
            // Der Abstand sitzt hier an der Box, nicht am Text — so war es.
            errorContent = {
                CenterBox(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(it, color = MpdRed, fontSize = 14.sp)
                }
            },
            loading = {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        if (vm.stats.scanning) stringResource(Res.string.stats_scanning)
                        else stringResource(Res.string.stats_loading),
                        color = colors.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
        ) { stats -> StatsContent(stats, vm.stats.text, tours, vm.stats.geo, vm.stats.geoComputing, vm.stats.geoError) }
    }
}

@Composable
private fun StatsContent(
    stats: StatsResponse,
    text: TextStats?,
    tours: List<de.bgghome.mpd.api.TourListEntry>? = null,
    geo: StatsGeo? = null,
    geoComputing: Boolean = false,
    geoError: String? = null,
) {
    val colors = Mpd.colors
    val monthLetters = stringArrayResource(Res.array.stats_month_letters)
    val seasonLabels = stringArrayResource(Res.array.stats_season_labels)
    val weekdayLabels = stringArrayResource(Res.array.stats_weekday_labels)
    // Tag/Woche: aufgeklappt fuer genau ein Jahr (Web: openDrilldown).
    var drillYear by remember { mutableStateOf<String?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(380.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val years = stats.photosPerYear.keys.mapNotNull { it.toIntOrNull() }
                HeaderNumber(MpdIcons.Camera, groupedInt(stats.totalPhotos.toLong()), stringResource(Res.string.stats_photos))
                val size = if (stats.totalSizeMb >= 1024) {
                    "%.1f GB".format(stats.totalSizeMb / 1024.0)
                } else {
                    "%.0f MB".format(stats.totalSizeMb)
                }
                HeaderNumber(MpdIcons.Storage, size, stringResource(Res.string.stats_storage))
                if (years.isNotEmpty()) {
                    HeaderNumber(MpdIcons.Calendar, "${years.min()}–${years.max()}", stringResource(Res.string.stats_period))
                }
                if (text != null && text.totalTextElements > 0) {
                    HeaderNumber(
                        MpdIcons.Pencil,
                        groupedInt(text.totalWords.toLong()),
                        stringResource(Res.string.stats_words),
                    )
                }
            }
        }

        // ── 📅 Heatmap Monat/Jahr (Web: c-Monat/Jahr-Matrix) ─────────
        if (stats.photosPerYearMonth.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MonthYearHeatmap(stats, onYearTap = { y -> drillYear = if (drillYear == y) null else y })
            }
        }
        // ── 🗓️ Tag / Woche fuer das angetippte Jahr ──────────────────
        drillYear?.let { year ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                DayWeekHeatmap(stats, year, weekdayLabels, onClose = { drillYear = null })
            }
        }

        item {
            val years = stats.photosPerYear.toSortedMap()
            BarCard(
                title = stringResource(Res.string.stats_per_year),
                entries = years.map { (k, v) ->
                    (if (years.size <= 8) k else k.takeLast(2)) to v.toFloat()
                },
                barColor = colors.blue,
                labelEvery = if (years.size <= 12) 1 else 5,
            )
        }

        item {
            BarCard(
                title = stringResource(Res.string.stats_per_month),
                entries = (1..12).map {
                    monthLetters[it - 1] to (stats.photosPerMonth["$it"] ?: 0L).toFloat()
                },
                barColor = colors.blueMid,
                labelEvery = 1,
            )
        }

        item {
            BarCard(
                title = stringResource(Res.string.stats_seasons),
                entries = SEASON_ORDER.mapIndexed { i, key ->
                    seasonLabels[i] to (stats.seasonCounts[key] ?: 0L).toFloat()
                },
                barColor = colors.gold,
                labelEvery = 1,
            )
        }

        item {
            BarCard(
                title = stringResource(Res.string.stats_weekdays),
                entries = (0..6).map {
                    weekdayLabels[it] to (stats.weekdayCounts["$it"] ?: 0L).toFloat()
                },
                barColor = colors.blueMid,
                labelEvery = 1,
            )
        }

        item {
            BarCard(
                title = stringResource(Res.string.stats_hours),
                entries = (0..23).map { "$it" to (stats.hourCounts["$it"] ?: 0L).toFloat() },
                barColor = colors.blue,
                labelEvery = 6,
            )
        }

        if (stats.devices.isNotEmpty()) {
            item {
                StatCard(stringResource(Res.string.stats_cameras)) {
                    RankedBars(
                        stats.devices.entries.sortedByDescending { it.value }.take(8)
                            .map { it.key to it.value.toFloat() },
                    ) { "%,d".format(it.toLong()).replace(',', '.') }
                }
            }
        }

        if (stats.gpsCoveragePct.isNotEmpty()) {
            item {
                val g = stats.gpsCoveragePct.toSortedMap()
                BarCard(
                    title = stringResource(Res.string.stats_gps_per_year),
                    entries = g.map { (k, v) ->
                        (if (g.size <= 8) k else k.takeLast(2)) to v.toFloat()
                    },
                    barColor = colors.gold,
                    labelEvery = if (g.size <= 12) 1 else 5,
                    maxOverride = 100f,
                )
            }
        }

        if (stats.avgFilesizeMbPerYear.isNotEmpty()) {
            item {
                val a = stats.avgFilesizeMbPerYear.toSortedMap()
                BarCard(
                    title = stringResource(Res.string.stats_filesize_per_year),
                    entries = a.map { (k, v) ->
                        (if (a.size <= 8) k else k.takeLast(2)) to v.toFloat()
                    },
                    barColor = colors.blueMid,
                    labelEvery = if (a.size <= 12) 1 else 5,
                )
            }
        }

        // ── 🗺️ Foto-Weltkarte + 🌍 Top Laender (Web: /api/stats/geo) ──
        if (geo != null || geoComputing || geoError != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { WorldMapCard(geo, geoComputing, geoError) }
        }
        if (geo != null && geo.countryCounts.isNotEmpty()) {
            item {
                StatCard(stringResource(Res.string.stats_top_countries), trailing = stringResource(Res.string.stats_by_photo_count)) {
                    RankedBars(
                        geo.countryCounts.entries.sortedByDescending { it.value }.take(10)
                            .map { it.key to it.value.toFloat() },
                    ) { groupedInt(it.toLong()) }
                }
            }
        }

        if (stats.sizeMbPerYear.isNotEmpty()) {
            item {
                val s = stats.sizeMbPerYear.toSortedMap()
                BarCard(
                    title = stringResource(Res.string.stats_storage_per_year),
                    entries = s.map { (k, v) ->
                        (if (s.size <= 8) k else k.takeLast(2)) to (v / 1024.0).toFloat()
                    },
                    barColor = colors.blue,
                    labelEvery = if (s.size <= 12) 1 else 5,
                )
            }
        }

        if (stats.albumsSummary.isNotEmpty()) {
            item {
                StatCard(stringResource(Res.string.stats_top_albums)) {
                    RankedBars(
                        stats.albumsSummary.sortedByDescending { it.photoCount }.take(10)
                            .map { it.name to it.photoCount.toFloat() },
                    ) { "%,d".format(it.toLong()).replace(',', '.') }
                }
            }
        }

        // ── ✍️ Tagebuch-Texte (Web: stats_text) ──────────────────────
        if (text != null && text.totalTextElements > 0) {
            item {
                StatCard(stringResource(Res.string.stats_diary_texts)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        TextNumber(groupedInt(text.totalTextElements.toLong()), stringResource(Res.string.stats_texts))
                        TextNumber(groupedInt(text.totalWords.toLong()), stringResource(Res.string.stats_words))
                        TextNumber("${text.totalAlbumsWithText}", stringResource(Res.string.stats_albums_with_text))
                    }
                }
            }

            item {
                val y = text.perYear.filterKeys { it != "0" }.toSortedMap()
                if (y.isNotEmpty()) {
                    BarCard(
                        title = stringResource(Res.string.stats_writing_per_year),
                        entries = y.map { (k, v) ->
                            (if (y.size <= 8) k else k.takeLast(2)) to v.words.toFloat()
                        },
                        barColor = colors.gold,
                        labelEvery = if (y.size <= 12) 1 else 5,
                    )
                }
            }

            if (text.styleCounts.isNotEmpty()) {
                item {
                    StatCard(stringResource(Res.string.stats_text_styles)) {
                        val styleLabels = STYLE_LABELS.mapValues { stringResource(it.value) }
                        RankedBars(
                            text.styleCounts.entries.sortedByDescending { it.value }
                                .map { (styleLabels[it.key] ?: it.key) to it.value.toFloat() },
                        ) { groupedInt(it.toLong()) }
                    }
                }
            }

            if (text.topByScore.isNotEmpty()) {
                item {
                    StatCard(stringResource(Res.string.stats_diary_score)) {
                        RankedBars(
                            text.topByScore.map {
                                (it.title.ifEmpty { it.album }) to it.score.toFloat()
                            },
                        ) { "%.2f".format(it) }
                    }
                }
            }
        }

        // Sport-Jahresstatistik aus dem Tours-Modul (km + Anzahl je Jahr
        // und Sportart, clientseitig aggregiert)
        if (!tours.isNullOrEmpty()) {
            item { TourYearStats(tours) }
        }
    }
}

/** 🚴 Touren-Kilometer je Jahr und Sportart — Feature-Runde 03.09.2026. */
@Composable
private fun TourYearStats(tours: List<de.bgghome.mpd.api.TourListEntry>) {
    val colors = Mpd.colors
    val byYear = tours
        .filter { it.date.length >= 4 }
        .groupBy { it.date.take(4) }
        .toSortedMap(compareByDescending { it })
    if (byYear.isEmpty()) return

    StatCard(stringResource(Res.string.stats_tours_per_year)) {
        byYear.forEach { (year, list) ->
            val bySport = list.groupBy { it.sport.ifEmpty { "?" } }
            val parts = bySport.entries
                .sortedByDescending { it.value.size }
                .joinToString("   ") { (sport, entries) ->
                    val km = entries.sumOf { it.dist ?: 0.0 }
                    val icon = when (sport) {
                        "bike", "cycling" -> "🚴"
                        "hike", "hiking", "walk" -> "🚶"
                        "run", "running" -> "🏃"
                        else -> "🧭"
                    }
                    if (km > 0) "%s %d · %.0f km".format(icon, entries.size, km)
                    else "%s %d".format(icon, entries.size)
                }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(year, color = colors.muted, fontSize = 13.sp,
                    modifier = Modifier.width(46.dp))
                Text(parts, color = colors.text, fontSize = 13.sp)
            }
        }
    }
}

/** Heatmap Jahr × Monat: Intensitaet = Photoanzahl (Web-Sektion 📅).
 *  Tipp auf eine Zeile oeffnet Tag/Woche fuer das Jahr (Web: Klick auf
 *  die Zelle, openDrilldown). */
@Composable
private fun MonthYearHeatmap(stats: StatsResponse, onYearTap: (String) -> Unit = {}) {
    val colors = Mpd.colors
    val years = stats.photosPerYearMonth.keys
        .mapNotNull { it.toIntOrNull() }.sorted().map { "$it" }
    if (years.isEmpty()) return
    val max = stats.photosPerYearMonth.values
        .flatMap { it.values }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val cellColor = colors.blue

    StatCard(
        stringResource(Res.string.stats_month_year),
        trailing = if (stats.photosPerYearWeek.isNotEmpty()) stringResource(Res.string.stats_day_week_hint) else null,
    ) {
        Row {
            // Jahresspalte
            Column(Modifier.padding(end = 8.dp, top = 16.dp)) {
                years.forEach {
                    Box(
                        Modifier.height(16.dp).clickable { onYearTap(it) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(it, color = colors.muted, fontSize = 9.sp)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(16.dp)) {
                    stringArrayResource(Res.array.stats_month_letters).forEach {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(it, color = colors.muted, fontSize = 9.sp)
                        }
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height((years.size * 16).dp)
                        .pointerInput(years) {
                            detectTapGestures { off ->
                                val yi = (off.y / (size.height / years.size)).toInt()
                                years.getOrNull(yi)?.let(onYearTap)
                            }
                        },
                ) {
                    val cw = size.width / 12f
                    val ch = size.height / years.size
                    years.forEachIndexed { yi, year ->
                        val row = stats.photosPerYearMonth[year] ?: emptyMap()
                        for (m in 1..12) {
                            val v = row["$m"] ?: 0L
                            if (v > 0) {
                                val alpha = 0.15f + 0.85f * (v / max.toFloat())
                                drawRoundRect(
                                    color = cellColor.copy(alpha = alpha),
                                    topLeft = Offset((m - 1) * cw + 1f, yi * ch + 1f),
                                    size = Size(cw - 2f, ch - 2f),
                                    cornerRadius = CornerRadius(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 🗓️ Tag / Woche eines Jahres: 7 Zeilen (Mo..So) × Kalenderwochen,
 *  Zelle = Fotos an dem Tag (Web: openDrilldown, Schluessel „KW_Wochentag"). */
@Composable
private fun DayWeekHeatmap(
    stats: StatsResponse,
    year: String,
    weekdayLabels: List<String>,
    onClose: () -> Unit,
) {
    val colors = Mpd.colors
    val pyw = stats.photosPerYearWeek[year] ?: emptyMap()
    val cells = remember(pyw) {
        pyw.mapNotNull { (k, v) ->
            val (w, d) = k.split("_").let { (it.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (it.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null) }
            Triple(w, d, v)
        }
    }
    val maxWeek = (cells.maxOfOrNull { it.first } ?: 52).coerceAtLeast(52)
    val max = (cells.maxOfOrNull { it.third } ?: 1L).coerceAtLeast(1L)
    val cellColor = colors.blue
    StatCard(
        stringResource(Res.string.stats_day_week),
        trailing = stringResource(Res.string.stats_year_x, year),
        onClose = onClose,
    ) {
        if (cells.isEmpty()) {
            Text(stringResource(Res.string.stats_no_data), color = colors.muted, fontSize = 13.sp)
            return@StatCard
        }
        Row {
            Column(Modifier.padding(end = 6.dp)) {
                (0..6).forEach { d ->
                    Box(Modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
                        Text(weekdayLabels.getOrElse(d) { "" }, color = colors.muted, fontSize = 9.sp)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height((7 * 14).dp)) {
                    val cw = size.width / maxWeek
                    val ch = size.height / 7f
                    cells.forEach { (w, d, v) ->
                        if (w < 1 || w > maxWeek || d < 0 || d > 6 || v <= 0) return@forEach
                        val alpha = 0.15f + 0.85f * (v / max.toFloat())
                        drawRoundRect(
                            color = cellColor.copy(alpha = alpha),
                            topLeft = Offset((w - 1) * cw + 0.5f, d * ch + 1f),
                            size = Size((cw - 1f).coerceAtLeast(1f), ch - 2f),
                            cornerRadius = CornerRadius(1.dp.toPx()),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    val kw = stringResource(Res.string.stats_week_abbr)
                    listOf(1, 10, 20, 30, 40, 50).forEach { w ->
                        Box(Modifier.weight(1f)) {
                            Text("$kw $w", color = colors.muted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 🗺️ Foto-Weltkarte: geocodierte Orte als Punkte (Radius ~ Fotoanzahl)
 *  oder als Heatmap (Farbe wie Trails), auf der eigenen Kachelkarte. Die
 *  Karte ist erst nach Antippen bedienbar (sonst scrollt das Dashboard
 *  nicht mehr), Vollbild wie bei Tours. */
@Composable
private fun WorldMapCard(geo: StatsGeo?, computing: Boolean, error: String?) {
    val colors = Mpd.colors
    var heat by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var vollbild by remember { mutableStateOf(false) }
    val zustand = remember { KartenZustand() }
    var eingepasst by remember(geo) { mutableStateOf(false) }
    val summary = geo?.let {
        stringResource(
            Res.string.stats_map_summary,
            groupedInt(it.totalGpsPhotos), groupedInt(it.clusterCount), groupedInt(it.countryCounts.size.toLong()),
        )
    }
    StatCard(stringResource(Res.string.stats_world_map), trailing = summary) {
        when {
            geo == null && computing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text(stringResource(Res.string.stats_geo_computing), color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
            }
            geo == null -> Text(
                stringResource(Res.string.stats_geo_unavailable, error ?: ""),
                color = colors.muted, fontSize = 13.sp,
            )
            geo.mapPoints.isEmpty() -> Text(stringResource(Res.string.stats_no_data), color = colors.muted, fontSize = 13.sp)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    ModeChip(stringResource(Res.string.stats_map_dots), !heat) { heat = false }
                    ModeChip(stringResource(Res.string.stats_map_heat), heat) { heat = true }
                }
                val maxCount = remember(geo) { geo.mapPoints.maxOfOrNull { it.count } ?: 1L }
                val karte: @Composable (Modifier, Boolean) -> Unit = { mod, voll ->
                    BoxWithConstraints(mod.clip(RoundedCornerShape(8.dp))) {
                        if (!eingepasst) {
                            zustand.passeEin(geo.mapPoints.map { GeoPunkt(it.lat, it.lon) }, constraints.maxWidth, constraints.maxHeight, 24, einzelZoom = 6)
                            eingepasst = true
                        }
                        val dichte = androidx.compose.ui.platform.LocalDensity.current.density
                        KachelKarte(zustand, KachelEbene.STANDARD, Modifier.fillMaxSize(), interaktiv = voll || armed) z@{ zumSchirm ->
                            geo.mapPoints.forEach { p ->
                                val pt = zumSchirm(GeoPunkt(p.lat, p.lon))
                                if (pt.x < -60f || pt.y < -60f || pt.x > size.width + 60f || pt.y > size.height + 60f) return@forEach
                                if (heat) {
                                    // Logarithmisch wie bei Trails, sonst frisst der Wohnort alles.
                                    val t = (ln(1.0 + p.count) / ln(1.0 + maxCount)).toFloat().coerceIn(0f, 1f)
                                    drawCircle(heatFarbe(t), (10f + 22f * t) * dichte, pt)
                                } else {
                                    // Web: radius 4 + sqrt(count/max) * 18, blau mit weisser Kontur.
                                    val ratio = sqrt(p.count.toDouble() / maxCount).toFloat()
                                    val r = (4f + ratio * 18f) * dichte
                                    drawCircle(colors.blue.copy(alpha = 0.4f + ratio * 0.5f), r, pt)
                                    drawCircle(Color.White.copy(alpha = 0.8f), r, pt, style = Stroke(1f * dichte))
                                }
                            }
                        }
                        if (!voll) {
                            ArmOverlay(armed, { armed = true }, Modifier.fillMaxSize())
                            VollbildKnopf { vollbild = true }
                        }
                    }
                }
                karte(Modifier.fillMaxWidth().height(320.dp), false)
                if (vollbild) {
                    KarteVollbild(onClose = { vollbild = false }) { mod -> karte(mod, true) }
                }
            }
        }
    }
}

/** Kleiner Umschalt-Chip (Punkte/Heatmap), Optik wie die Filter in Tours. */
@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = Mpd.colors
    Text(
        label,
        color = if (active) colors.textOnAccent else colors.muted,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) colors.blue else Color.Transparent)
            .border(1.dp, if (active) colors.blue else colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Horizontale Rangliste mit Balken (Kameras, Top-Alben, Stile, Score). */
@Composable
private fun RankedBars(
    entries: List<Pair<String, Float>>,
    format: (Float) -> String,
) {
    val colors = Mpd.colors
    val max = entries.maxOfOrNull { it.second }?.coerceAtLeast(0.001f) ?: return
    entries.forEach { (name, value) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = colors.text,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1.1f),
            )
            Box(Modifier.weight(1f).height(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth((value / max).coerceIn(0.02f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.blue),
                )
            }
            Text(
                format(value),
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp).width(52.dp),
            )
        }
    }
}

@Composable
private fun HeaderNumber(icon: ImageVector, value: String, label: String) {
    val colors = Mpd.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        IconLabel(
            icon, value, color = colors.text, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, iconSize = 16.dp, gap = 6.dp,
        )
        Text(label, color = colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun TextNumber(value: String, label: String) {
    val colors = Mpd.colors
    Column {
        Text(value, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun StatCard(
    title: String,
    trailing: String? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = Mpd.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(trailing, color = colors.muted.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            if (onClose != null) {
                Box(
                    Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MpdIcons.Cross, stringResource(Res.string.stats_close_card), Modifier.size(14.dp), tint = colors.muted)
                }
            }
        }
        content()
    }
}

/** Zahl kompakt: 1234 → „1,2k", 47 → „47", 12.4 → „12". */
private fun shortNum(v: Float): String = when {
    v >= 10_000f -> "%.0fk".format(v / 1000f)
    v >= 1_000f -> "%.1fk".format(v / 1000f).replace('.', ',')
    v >= 10f || v == v.toLong().toFloat() -> "%.0f".format(v)
    else -> "%.1f".format(v).replace('.', ',')
}

/** Balkendiagramm mit Gitter, Wertelabels und Verlauf — Chart.js-Anmutung. */
@Composable
private fun BarCard(
    title: String,
    entries: List<Pair<String, Float>>,
    barColor: Color,
    labelEvery: Int = 5,
    maxOverride: Float? = null,
) {
    val colors = Mpd.colors
    if (entries.isEmpty() || entries.all { it.second <= 0f }) {
        StatCard(title) { Text(stringResource(Res.string.stats_no_data), color = colors.muted, fontSize = 13.sp) }
        return
    }
    val max = maxOverride ?: entries.maxOf { it.second }.coerceAtLeast(0.001f)
    val showValues = entries.size <= 13

    StatCard(title, trailing = stringResource(Res.string.stats_up_to, shortNum(max))) {
        // Wertezeile ueber den Balken (nur wenn sie lesbar bleibt)
        if (showValues) {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                entries.forEach { (_, v) ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (v > 0f) {
                            Text(shortNum(v), color = colors.muted, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        val gridColor = colors.border.copy(alpha = 0.6f)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            // Gitterlinien bei 25/50/75/100 %
            for (f in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val y = size.height * (1f - f)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            }
            val gap = 3.dp.toPx()
            val barW = (size.width - gap * (entries.size - 1)) / entries.size
            entries.forEachIndexed { i, (_, v) ->
                val h = (v / max).coerceIn(0f, 1f) * size.height
                if (h <= 0f) return@forEachIndexed
                drawRoundRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(barColor, barColor.copy(alpha = 0.55f)),
                        startY = size.height - h,
                        endY = size.height,
                    ),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            // Grundlinie
            drawLine(
                colors.border, Offset(0f, size.height),
                Offset(size.width, size.height), 1.5f,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            entries.forEachIndexed { i, (label, _) ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % labelEvery == 0) {
                        Text(label, color = colors.muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

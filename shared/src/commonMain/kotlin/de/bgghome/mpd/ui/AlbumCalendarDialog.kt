package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.ui.theme.Mpd
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import org.jetbrains.compose.resources.stringResource

/*
 * Kalender im Album — Port von _openCalendarDialog() aus album.html
 * (08.09.2026: „das ist bei Gestalten sehr geschickt").
 *
 * Ein kleines Monatsraster zum Wochentag-Nachschauen beim Schreiben:
 * „War der 14. ein Samstag?" Es oeffnet im Monat des ersten Album-Fotos,
 * markiert die Tage der Album-Spanne und heute, blaettert monatsweise
 * und springt mit zwei Knoepfen zurueck zum Album bzw. zu heute. Die
 * Spanne kommt wie im Web aus den ISO-Dateinamen der Fotos (JJJJ-MM-TT…),
 * nichts wird nachgeladen.
 *
 * Im Web sitzt der Einstieg in der Kopfzeile (Desktop + Tablet) UND im
 * mobilen Kebab; die App hat beides: Symbol in der TopAppBar, Eintrag im
 * ⋮-Menue.
 */

/** Erster und letzter Fototag des Albums aus den Dateinamen, oder null. */
internal fun albumDateRange(elements: List<PdxElement>): Pair<LocalDate, LocalDate>? {
    val re = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
    val days = elements.asSequence()
        .filter { it.type == "photo" }
        .mapNotNull { it.file }
        .mapNotNull { re.find(it) }
        .mapNotNull { m ->
            runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        .toList()
    if (days.isEmpty()) return null
    return days.min() to days.max()
}

@Composable
internal fun AlbumCalendarDialog(elements: List<PdxElement>, onDismiss: () -> Unit) {
    val colors = Mpd.colors
    val range = remember(elements) { albumDateRange(elements) }
    val today = remember { LocalDate.now() }
    var view by remember { mutableStateOf(YearMonth.from(range?.first ?: today)) }
    val locale = Locale.getDefault()

    // Wie im Web: die Spanne faerbt jeden Tag dazwischen, nicht nur Tage
    // mit Foto — es ist eine Reise, kein Lueckenmuster.
    fun inRange(d: LocalDate) = range != null && !d.isBefore(range.first) && !d.isAfter(range.second)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = { view = view.minusMonths(1) }) {
                    Icon(MpdIcons.ArrowBack, stringResource(Res.string.calendar_prev), Modifier.size(22.dp))
                }
                Text(
                    view.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) + " " + view.year,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                IconButton(onClick = { view = view.plusMonths(1) }) {
                    Icon(
                        MpdIcons.ArrowBack, stringResource(Res.string.calendar_next), Modifier.size(22.dp)
                            .graphicsLayer { scaleX = -1f },
                    )
                }
            }
        },
        text = {
            Column {
                // Wochentage, Montag zuerst (Web: 1.1.2024 = Montag).
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val wd = DayOfWeek.MONDAY.plus(i.toLong())
                        Text(
                            wd.getDisplayName(TextStyle.SHORT, locale),
                            Modifier.weight(1f), fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 6 Wochen à 7 Tage, wie im Web (42 Zellen), beginnend am
                // Montag vor dem Monatsersten.
                val first = view.atDay(1)
                val lead = (first.dayOfWeek.value + 6) % 7
                val start = first.minusDays(lead.toLong())
                for (week in 0 until 6) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (dow in 0 until 7) {
                            val d = start.plusDays((week * 7 + dow).toLong())
                            val out = d.month != view.month
                            val album = inRange(d)
                            val isToday = d == today
                            Box(
                                Modifier
                                    .weight(1f)
                                    .padding(vertical = 1.5.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            album -> colors.blue.copy(alpha = 0.18f)
                                            out -> androidx.compose.ui.graphics.Color.Transparent
                                            else -> colors.surfaceAlt
                                        },
                                    )
                                    .then(
                                        if (isToday) Modifier.border(
                                            2.dp, colors.blue, RoundedCornerShape(6.dp),
                                        ) else Modifier,
                                    )
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    d.dayOfMonth.toString(),
                                    fontSize = 13.sp,
                                    fontWeight = when {
                                        isToday -> FontWeight.Bold
                                        album -> FontWeight.SemiBold
                                        else -> FontWeight.Normal
                                    },
                                    color = when {
                                        out -> colors.muted.copy(alpha = 0.5f)
                                        album -> colors.blue
                                        else -> colors.text
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Legende wie im Web
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (range != null) {
                        Box(
                            Modifier.size(11.dp).clip(RoundedCornerShape(3.dp))
                                .background(colors.blue.copy(alpha = 0.5f)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.calendar_album_days), fontSize = 12.sp, color = colors.muted)
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        Modifier.size(11.dp).clip(RoundedCornerShape(3.dp))
                            .border(2.dp, colors.blue, RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.calendar_today), fontSize = 12.sp, color = colors.muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
        dismissButton = {
            Row {
                if (range != null) {
                    TextButton(onClick = { view = YearMonth.from(range.first) }) { Text(stringResource(Res.string.calendar_album)) }
                }
                TextButton(onClick = { view = YearMonth.from(today) }) { Text(stringResource(Res.string.calendar_today)) }
            }
        },
    )
}

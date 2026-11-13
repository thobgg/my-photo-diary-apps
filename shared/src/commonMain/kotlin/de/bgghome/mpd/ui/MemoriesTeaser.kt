package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.zeigeErinnerungen

/**
 * Goldener Hinweisstreifen unter dem Kopf der Albenuebersicht — 1:1 der
 * `memories-teaser` des Web (index.html, loadMemoriesTeaser): nur sichtbar,
 * wenn es HEUTE Erinnerungen gibt, Tipp oeffnet die Pinnwand.
 *
 * 26.09.2026: „kann man nicht bei Android/Windows oben auch noch die My
 * Memories Pille oder Button wie beim WebView platzieren — oder ist das
 * schwierig, da es beim Handy-Screen zu viele waere?" Ein Knopf IN der
 * Kopfzeile passt am Handy nicht (zwischen Marke und Avatar bleiben ~60 dp,
 * siehe AppHeader). Das Web hat dort auch keinen: es nimmt eine eigene
 * Zeile darunter, und die passt in jeder Breite.
 *
 * Der Zeitstrahl braucht ihn nicht, er hat oben das Erinnerungs-Karussell.
 */
@Composable
internal fun MemoriesTeaser(vm: AppViewModel) {
    val mem = vm.memories
    // Dieselben Tagesdaten wie Karussell und Pinnwand. Hat jemand auf der
    // Pinnwand zu einem anderen Tag geblaettert, bleibt der stehen — der
    // Streifen spricht aber nur von heute und schweigt dann.
    LaunchedEffect(Unit) {
        if (mem.data == null && !mem.loading) mem.load(0)
    }
    val data = mem.data ?: return
    if (mem.offsetDays != 0 || data.years.isEmpty()) return

    val total = if (data.total > 0) data.total else data.years.sumOf { it.photos.size }
    val anzahl = pluralStringResource(Res.plurals.memories_teaser_count, total, total)
    val text = if (data.years.size == 1) {
        val y = data.years.first()
        pluralStringResource(Res.plurals.memories_teaser_single, y.yearsAgo, y.yearsAgo, y.year.toString(), anzahl)
    } else {
        val agos = data.years.map { it.yearsAgo }.sorted()
        val joined = agos.dropLast(1).joinToString(", ") + " " +
            stringResource(Res.string.memories_teaser_and) + " " + agos.last()
        stringResource(Res.string.memories_teaser_multi, joined, anzahl)
    }

    val gold = Color(0xFFC8922A)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0x1AC8922A), Color(0x0F2E75B6)),
                ),
            )
            .clickable { vm.zeigeErinnerungen() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✦", color = gold, fontSize = 17.sp, modifier = Modifier.padding(end = 10.dp))
        // Handschrift nur hier und auf der Pinnwand — der Erinnerungs-
        // Moment (CI 1.2), wie .mt-text im Web.
        Text(
            text,
            color = Mpd.colors.text,
            fontFamily = Mpd.fonts.handwriting,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
        )
        Text("›", color = Mpd.colors.muted, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider(color = Mpd.colors.border)
}

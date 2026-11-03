package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.SearchResult
import de.bgghome.mpd.ui.theme.Mpd

/*
 * Modul „Suche" — Gegenstueck zu search.html/search.js (Ctrl+K-Suche des
 * Webs): Volltext ueber Albumtitel, Text-Elemente und Link-Beschriftungen.
 * Debounce/2-Zeichen-Minimum/Space-Filter wie im Web; der Index lebt auf
 * dem Server (202 = wird gerade gebaut). Treffer oeffnet das Album und
 * springt zum Element.
 */

@Composable
/**
 * @param asTab true = als Grundtaetigkeit unten in der Leiste. Dann
 *   traegt der App-Kopf darueber die Systemleisten-Abstaende, es gibt
 *   keinen Zurueck-Knopf (man wechselt den Tab) — und die Tastatur
 *   springt NICHT auf: ein Tab ist ein Ort, den man betritt, kein
 *   Eingabefeld, das man oeffnet (Google Fotos macht es genauso).
 */
fun SearchScreen(vm: AppViewModel, imageLoader: ImageLoader, asTab: Boolean = false) {
    val colors = Mpd.colors
    if (!asTab) BackHandler { vm.search.open = false }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (!asTab) focusRequester.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .then(
                if (asTab) Modifier
                else Modifier.statusBarsPadding().navigationBarsPadding()
            ),
    ) {
        // Kopf: zurueck + Suchfeld + ggf. Loeschen
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!asTab) BackButton(stringResource(Res.string.search_close)) { vm.search.open = false }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (vm.search.query.isEmpty()) {
                    Text(stringResource(Res.string.search_placeholder), color = colors.muted, fontSize = 16.sp)
                }
                BasicTextField(
                    value = vm.search.query,
                    onValueChange = { vm.search.perform(it) },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.blue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (vm.search.query.isNotEmpty()) {
                Icon(
                    MpdIcons.Cross, stringResource(Res.string.search_clear),
                    tint = colors.muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { vm.search.perform("") }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .size(20.dp),
                )
            }
        }

        // Space-Filter wie im Web (search.html)
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchChip(vm, "all", stringResource(Res.string.search_scope_all))
            SearchChip(vm, "shared", stringResource(Res.string.search_scope_shared))
            SearchChip(vm, "personal", stringResource(Res.string.search_scope_personal))
        }

        when {
            vm.search.error != null -> CenterNote("${vm.search.error}", MpdRed)

            vm.search.building -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(
                    stringResource(Res.string.search_building),
                    color = colors.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            vm.search.results == null -> CenterNote(
                stringResource(Res.string.search_hint),
                colors.muted,
            )

            vm.search.results!!.isEmpty() -> CenterNote(stringResource(Res.string.search_no_results), colors.muted)

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.search.results!!, key = { "${it.space}/${it.album}/${it.elementId ?: "album"}/${it.type}" }) {
                    SearchResultCard(vm, it, imageLoader)
                }
            }
        }
    }
}

@Composable
private fun CenterNote(text: String, color: Color) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun SearchChip(vm: AppViewModel, value: String, label: String) {
    val colors = Mpd.colors
    val active = vm.search.space == value
    Text(
        label,
        color = if (active) colors.textOnAccent else colors.text,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) colors.blue else colors.surface)
            .clickable {
                vm.search.space = value
                vm.search.perform(vm.search.query)
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SearchResultCard(vm: AppViewModel, r: SearchResult, imageLoader: ImageLoader) {
    val colors = Mpd.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .clickable { vm.openAlbumFromSearch(r.space, r.album, r.elementId) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbFile = r.contextPhoto ?: r.thumbnail
        if (thumbFile != null) {
            AsyncImage(
                model = vm.baseUrl + MpdApi.thumbnailPath(r.space, r.album, thumbFile, "sm"),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) { Icon(MpdIcons.Book, stringResource(Res.string.search_type_album), Modifier.size(22.dp), tint = colors.muted) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                highlightMatches(r.snippet.ifEmpty { r.text }, vm.search.query, colors.gold),
                color = colors.text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
            )
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    listOfNotNull(r.albumTitle, r.year?.toString()).joinToString(" · "),
                    color = colors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                )
                TypePill(r)
            }
        }
    }
}

@Composable
private fun TypePill(r: SearchResult) {
    val colors = Mpd.colors
    val (label, tint) = when (r.type) {
        "album" -> stringResource(Res.string.search_type_album) to colors.blue
        "link" -> stringResource(Res.string.search_type_link) to colors.blueMid
        else -> when (r.style) {
            "quote" -> stringResource(Res.string.search_type_quote) to colors.muted
            "note" -> stringResource(Res.string.search_type_note) to colors.gold
            "info" -> stringResource(Res.string.search_type_info) to colors.blue
            "heading" -> stringResource(Res.string.search_type_heading) to colors.gold
            else -> stringResource(Res.string.search_type_text) to colors.muted
        }
    }
    Text(
        label,
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Suchwoerter im Snippet hervorheben (Web: Keyword-Highlighting). */
private fun highlightMatches(text: String, query: String, color: Color): AnnotatedString {
    val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
    if (words.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (w in words) {
            var start = 0
            while (true) {
                val idx = text.indexOf(w, start, ignoreCase = true)
                if (idx < 0) break
                addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = color),
                    idx, idx + w.length,
                )
                start = idx + w.length
            }
        }
    }
}

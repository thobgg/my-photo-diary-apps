package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.PdxElement
import org.jetbrains.compose.resources.stringResource

// Dokument-, PDF- und Textdatei-Bloecke der Albumansicht.
// Herausgeloest aus AlbumViewScreen.kt (13.11.2026).

@Composable
internal fun DocumentBlock(vm: AppViewModel, elem: PdxElement, imageLoader: ImageLoader) {
    val datei = elem.file?.lowercase().orEmpty()
    val isPdf = datei.endsWith(".pdf")
    // TEXTDATEIEN SEIT PDX 1.5.1 (Server, 15.09.2026): document.file darf
    // jetzt auch .txt und .md sein, ausgeliefert als text/plain.
    //
    // Die App hat sie NICHT uebersprungen, wie die Vorwaertskompatibilitaet
    // es vorsieht — sie fielen in den Zweig „kein Vorschaubild", bekamen
    // die PDF-Karte mit „Tippen zum Oeffnen" und landeten im PDF-Betrachter,
    // der an einer Textdatei mit einer Fehlermeldung abbricht. Ein Weg, der
    // ins Leere fuehrt, ist schlechter als kein Weg.
    //
    // Bis die Klappzeile gebaut ist (Web zeigt den Text aufklappbar, aus
    // der Datei gelesen, Deckel 100 KB), steht hier eine ehrliche Karte
    // ohne Tipp-Moeglichkeit.
    val isText = datei.endsWith(".txt") || datei.endsWith(".md")
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        // Seit PDX 1.5.1 steht der Titel IM Element: in der PDF-Karte an
        // Stelle des Dateinamens, bei einer Textdatei in der Klappzeile.
        // Nur Bild-Dokumente behalten ihn darueber.
        if (!isText && !isPdf && elem.thumbnails != null) {
            elem.title?.let {
                Text(it, color = MpdText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        if (isText) {
            TextDokument(vm, elem)
        } else if (isPdf || elem.thumbnails == null) {
            // PDF-Karte wie im Web: Icon + Name + Tipp-Hinweis, oeffnet den Reader
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MpdSurface)
                    .border(1.dp, MpdBorder, RoundedCornerShape(8.dp))
                    .clickable { vm.openPdf(elem) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MpdIcons.Document, null, Modifier.padding(end = 12.dp).size(26.dp), tint = MpdText)
                Column {
                    // PDX 1.5.1: der Titel steht IN der Karte, an Stelle des
                    // Dateinamens — nicht mehr darueber.
                    Text(
                        elem.title?.takeIf { it.isNotBlank() } ?: elem.file.orEmpty(),
                        color = MpdText, fontSize = 15.sp,
                    )
                    Text(stringResource(Res.string.document_tap_open), color = MpdMuted, fontSize = 12.sp)
                }
            }
        } else {
            // Bild-Dokumente stehen wie im Web direkt im Album
            AsyncImage(
                model = vm.baseUrl + elem.thumbnails.xl,
                imageLoader = imageLoader,
                contentDescription = elem.title ?: elem.file,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// -- FAB-Menue + Einfuege-Dialoge -------------------------------------------

@Composable
internal fun PlaceholderCard(icon: String, title: String, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MpdSurface)
            .border(1.dp, MpdBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 24.sp, modifier = Modifier.padding(end = 12.dp))
        Column {
            Text(title, color = MpdText, fontSize = 15.sp)
            Text(hint, color = MpdMuted, fontSize = 12.sp)
        }
    }
}

/**
 * Textdatei im Album (PDX 1.5.1, Server 15.09.2026): zusammengeklappt eine
 * Zeile mit dem Titel, aufgeklappt der Text in Schreibmaschinenschrift.
 *
 * Drei Regeln von der Server-Seite, jede mit einem Grund:
 *
 *  - Der Inhalt wird AUS DER DATEI gelesen, nie in die album.json kopiert.
 *    Sonst gaebe es zwei Wahrheiten, und wer die Datei am Rechner aendert,
 *    saehe es im Album nicht.
 *  - Geholt wird ERST BEIM AUFKLAPPEN. Ein Album mit zehn Textdateien
 *    machte sonst zehn Abrufe, nur um geoeffnet zu werden.
 *  - Deckel 100 KB in der Albumansicht. Wer mehr sehen will, oeffnet die
 *    Datei im Browser; ein Album ist keine Textverarbeitung.
 *
 * Fehlt der `title`, steht hier eine ABGELEITETE Beschriftung: die erste
 * nicht-leere Zeile, sonst der Dateiname. Sie wird NICHT gespeichert —
 * genau davor warnt der Server-Eintrag. Wer sie als echten Titel
 * zurueckschriebe, gaebe jeder angefassten Datei einen Titel, den niemand
 * vergeben hat. Die App hat ohnehin keinen Weg, einem Dokument einen Titel
 * zu geben; das bleibt beim naechsten Ausbau zu beachten.
 */
@Composable
internal fun TextDokument(vm: AppViewModel, elem: PdxElement) {
    var offen by remember(elem.id) { mutableStateOf(false) }
    var inhalt by remember(elem.id) { mutableStateOf<String?>(null) }
    var kopfzeile by remember(elem.id) { mutableStateOf<String?>(null) }
    var fehler by remember(elem.id) { mutableStateOf<String?>(null) }

    val adresse = elem.documentUrl?.let { vm.baseUrl + it }
        ?: vm.mediaUrl("document", elem)

    // DIE ERSTE ZEILE IST DIE BESCHRIFTUNG, der Dateiname nur der letzte
    // Ausweg (16.09.2026). Zugeklappt kannte die App den Inhalt
    // aber noch nicht — geholt wird er erst beim Aufklappen —, also stand
    // dort immer der Dateiname. Bei „tag1_1992-06-27.txt" sagt der nichts.
    //
    // Deshalb hier ein winziger Vorgriff: die ersten 512 Byte per Range.
    // Das widerspricht der Regel nicht, die den VOLLEN Text meint; ein
    // halbes Kilobyte kostet im LAN nichts. Nur wenn gar kein `title`
    // gesetzt ist, sonst ist die Zeile ohnehin unnoetig.
    LaunchedEffect(elem.id, adresse) {
        if (!elem.title.isNullOrBlank() || adresse == null) return@LaunchedEffect
        kopfzeile = runCatching { vm.api.fetchTextHead(adresse) }.getOrNull()
    }

    LaunchedEffect(offen, elem.id) {
        if (!offen || inhalt != null || fehler != null) return@LaunchedEffect
        // DEN RUECKFALL NICHT VERGESSEN (16.09.2026). Erste Fassung nahm
        // nur `document_url` — und das Feld steht NICHT in der album.json,
        // der Server setzt es beim Ausliefern. Fehlt es, stand hier sofort
        // „Der Text liess sich nicht laden", ohne dass je ein Abruf lief.
        // `openPdf` hat denselben Rueckfall seit langem: die Adresse aus
        // Bestand, Album und Dateinamen selbst bauen. Genau die Sorte
        // Fehler, die ich anderen gerade nachgewiesen habe — ein Weg, der
        // still ins Leere fuehrt.
        val url = adresse ?: run {
            fehler = Texte.t(Res.string.document_text_failed)
            return@LaunchedEffect
        }
        try {
            val roh = vm.api.fetchText(url)
            inhalt = if (roh.length > TEXT_DECKEL) {
                roh.take(TEXT_DECKEL) + "\n\n" + Texte.t(Res.string.document_text_cut)
            } else roh
        } catch (e: Exception) {
            fehler = e.message?.takeIf { it.isNotBlank() }
                ?: Texte.t(Res.string.document_text_failed_reason, e.javaClass.simpleName)
        }
    }

    // Reihenfolge: gesetzter Titel, sonst die erste nicht-leere Zeile,
    // und erst wenn beides fehlt der Dateiname.
    val beschriftung = elem.title?.takeIf { it.isNotBlank() }
        ?: (inhalt ?: kopfzeile)
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?.trim()?.removePrefix("#")?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        ?: elem.file.orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MpdSurface)
            .border(1.dp, MpdBorder, RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { offen = !offen }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MpdIcons.Document, null, Modifier.padding(end = 12.dp).size(22.dp), tint = MpdText)
            Text(
                beschriftung,
                color = MpdText, fontSize = 15.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (offen) "▾" else "▸", color = MpdMuted, fontSize = 14.sp)
        }
        if (offen) {
            when {
                fehler != null -> Text(
                    fehler!!, color = MpdRed, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
                inhalt == null -> Box(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp)) }
                else -> Text(
                    // Markdown wird leicht ausgezeichnet — derselbe Darsteller
                    // wie bei den Tagebuchtexten (fett, kursiv, Code).
                    if (elem.file?.lowercase()?.endsWith(".md") == true) mdLight(inhalt!!)
                    else androidx.compose.ui.text.AnnotatedString(inhalt!!),
                    color = MpdText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** 100 KB wie im Web — ein Album ist keine Textverarbeitung. */
internal const val TEXT_DECKEL = 100 * 1024

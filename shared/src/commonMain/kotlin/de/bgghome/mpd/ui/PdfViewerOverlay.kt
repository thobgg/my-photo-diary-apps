package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import de.bgghome.mpd.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.compose.resources.stringResource

/**
 * PDF-Ansicht — Gegenstueck zum PDF-Reader-Modal des Webs (dort pdf.js).
 * Die Datei kommt mit Auth in den App-Cache, androids PdfRenderer zeichnet
 * die Seiten; gerendert wird pro Seite erst beim Scrollen dorthin.
 */
@Composable
fun PdfViewerOverlay(vm: AppViewModel) {
    val pdf = vm.openPdf ?: return
    BackHandler { vm.closePdf() }

    var renderer by remember(pdf.url) { mutableStateOf<PdfDokument?>(null) }
    var error by remember(pdf.url) { mutableStateOf<String?>(null) }

    LaunchedEffect(pdf.url) {
        try {
            val file = vm.api.downloadToCache(File(vm.plattform.cacheOrdner, "documents"), pdf.url)
            withContext(Dispatchers.IO) { renderer = PdfDokument(file) }
        } catch (e: Exception) {
            // DER GRUND GEHOERT IN DIE MELDUNG, nicht ins Log (16.09.2026).
            // Hier stand eine Diagnosezeile mit der Ausnahmeklasse — sie ist
            // mit allen anderen gefallen, aber der Befund war echt: `e.message`
            // ist bei Android oft null, und dann sah niemand, WARUM es
            // scheiterte. Ob eine Datei fehlt, ein Recht verweigert wurde oder
            // die Verbindung abbrach, ist der Unterschied zwischen einer
            // Diagnose und einem Achselzucken. Auf dem Bildschirm sieht es der
            // Nutzer — und niemand sonst.
            error = e.message?.takeIf { it.isNotBlank() }
                ?: Texte.t(Res.string.pdf_load_failed_reason, e.javaClass.simpleName)
        }
    }

    DisposableEffect(pdf.url) {
        onDispose {
            runCatching { renderer?.close() }
            renderer = null
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MpdIcons.Cross, stringResource(Res.string.common_close),
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
                    .clickable { vm.closePdf() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                pdf.title,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        val r = renderer
        LoadState(
            error = error, data = r,
            errorContent = { ErrorCenter(it, MaterialTheme.colorScheme.error, padding = 24.dp) },
            loading = { LoadingCenter() },
        ) { rend ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Die Seitengroesse muss vorkommen — siehe PdfPage.
                val dichte = LocalDensity.current
                val breitePx = with(dichte) { maxWidth.toPx() }
                val hoehePx = with(dichte) { maxHeight.toPx() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rend.seiten) { index ->
                        PdfPage(rend, index, breitePx, hoehePx, dichte.density)
                    }
                }
            }
        }
    }
}

/**
 * DIE SEITENGROESSE KAM NICHT VOR (15.09.2026, Befund von der Server-Seite,
 * der denselben Fehler im Web hatte und ihn uns erspart hat).
 *
 * Vorher wurde jede Seite auf feste 1600 px Breite gezeichnet — und die
 * Anzeige zog das Ergebnis danach noch einmal auf die Fensterbreite. Ein
 * Kassenbon, rund 80 mm breit, stand damit exakt so breit da wie ein
 * A4-Blatt, nur scrollend ueber den ganzen Schirm. Wie GROSS das Dokument
 * ist, spielte an keiner der beiden Stellen eine Rolle.
 *
 * Dazu ein Nebeneffekt, den das Web nicht hat: der Bon wurde um den Faktor
 * sieben vergroessert und belegte als Bitmap 24 MB — mehr als das groessere
 * A4 mit 14 MB. Bei mehreren Seiten in der Liste wird das eng.
 *
 * Jetzt gilt die Originalgroesse als OBERGRENZE, eingepasst wird nur, wenn
 * es sonst nicht hineinpasst. Der Server rechnet dafuer mit 96/72,
 * also der Punkt-zu-Pixel-Umrechnung, die im Browser „100 %" heisst. Auf
 * Android ist die Entsprechung die Bildschirmdichte: ein PDF-Punkt ist
 * 1/72 Zoll, ein dp 1/160 Zoll, also sind es dichte × 160/72 Bildpunkte
 * je Punkt. Damit erscheint eine Seite so gross, wie sie gedruckt waere,
 * solange sie passt.
 */
/**
 * Der Faktor, mit dem eine PDF-Seite gezeichnet wird.
 *
 * Originalgroesse als Obergrenze, eingepasst nur wenn noetig. Ein PDF-Punkt
 * ist 1/72 Zoll, ein dp 1/160 Zoll — also sind es `dichte × 160/72`
 * Bildpunkte je Punkt, damit die Seite so gross erscheint, wie sie gedruckt
 * waere. Passt sie so nicht, begrenzt die Fensterbreite oder -hoehe.
 *
 * Als eigene Funktion, damit sie pruefbar ist: der Fehler, den sie behebt,
 * war am Code unauffaellig und fiel erst an einem Kassenbon auf.
 */
internal fun seitenSkalierung(
    seiteBreite: Int,
    seiteHoehe: Int,
    fensterBreitePx: Float,
    fensterHoehePx: Float,
    dichte: Float,
): Float {
    if (seiteBreite <= 0 || seiteHoehe <= 0) return 1f
    val original = dichte * 160f / 72f
    return minOf(
        fensterBreitePx / seiteBreite,
        fensterHoehePx / seiteHoehe,
        original,
    ).coerceAtLeast(0.05f)
}

@Composable
private fun PdfPage(
    renderer: PdfDokument,
    index: Int,
    breitePx: Float,
    hoehePx: Float,
    dichte: Float,
) {
    // PdfRenderer erlaubt nur eine offene Seite zugleich → synchronized.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, renderer, index, breitePx, hoehePx) {
        value = withContext(Dispatchers.IO) { renderer.seite(index, breitePx, hoehePx, dichte) }
    }

    val bmp = bitmap
    if (bmp == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f / 1.414f).padding(8.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    } else {
        // Das Bitmap hat jetzt bereits die richtige Groesse fuer diesen
        // Schirm. Frueher stand hier fillMaxWidth mit FillWidth — das zog
        // auch ein korrekt gezeichnetes schmales Dokument wieder auf die
        // ganze Breite und machte die Rechnung oben zunichte. Ein Punkt
        // des Bitmaps ist ein Bildpunkt, und schmale Seiten stehen
        // zentriert.
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), Alignment.Center) {
            Image(
                bitmap = bmp,
                contentDescription = stringResource(Res.string.pdf_page, index + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(with(LocalDensity.current) { bmp.width.toDp() }),
            )
        }
    }
}

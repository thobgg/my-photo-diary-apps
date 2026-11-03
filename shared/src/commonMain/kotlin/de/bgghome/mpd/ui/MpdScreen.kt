package de.bgghome.mpd.ui

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.ui.theme.Mpd

/*
 * Das Geruest der Vollbild-Ansichten (08.09.2026, Struktur-Audit Stufe 2).
 *
 * Neun Screens (Trails, Tours, Beisteuern, Statistiken, Einstellungen,
 * Benutzer, Papierkorb, Erinnerungen, Suche) bauten dieselbe Kombination
 * einzeln: Column mit Hintergrund und Systemleisten-Abstand, darin eine
 * Zeile mit BackButton und Titel, dazu ein BackHandler mit derselben
 * Schliess-Logik. Jede Aenderung an der Regel — etwa die offene
 * Systemleisten-Frage — haette neunmal nachgezogen werden muessen.
 *
 * Hier steht sie einmal. BEWUSST uebernommen, nicht entschieden: die
 * Ansichten setzen statusBars- UND navigationBarsPadding (Erinnerungen
 * nur statusBars, Parameter). Wer die Systemleisten-Regel spaeter
 * beschliesst, aendert sie an dieser Stelle. Lightbox, PDF-Reader und
 * Karten laufen immersiv und gehen NICHT ueber dieses Geruest.
 *
 * Die Kopfzeile bekommt nur den BackButton gestellt; Titel, Zaehler und
 * Knoepfe rechts liefert jeder Screen selbst im [header]-Slot — die
 * Screens weichen dort in Schriftgroesse und Randabstand voneinander ab,
 * und diese Abweichungen sind erhalten (Parameter), nicht vereinheitlicht.
 */
@Composable
internal fun MpdScreen(
    /** Bedienhilfe-Text des Zurueck-Knopfs. */
    backDescription: String,
    /** Der Zurueck-Knopf. */
    onClose: () -> Unit,
    /** Die System-Zurueck-Geste; Vorgabe: dasselbe wie der Knopf. */
    onBack: () -> Unit = onClose,
    background: Color = Mpd.colors.bg,
    /** Statt der Flaeche eine Textur — die Pinnwand in Memories legt hier
     *  ihre Korkwand hinein (13.09.2026). Ist sie gesetzt, gewinnt sie. */
    backgroundBrush: androidx.compose.ui.graphics.Brush? = null,
    backTint: Color = Mpd.colors.text,
    headerPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
    /** Erinnerungen laesst den unteren Rand frei (eigene Fusszeile). */
    navigationBarsPadding: Boolean = true,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (backgroundBrush != null) Modifier.background(backgroundBrush)
                else Modifier.background(background),
            )
            .statusBarsPadding()
            .then(if (navigationBarsPadding) Modifier.navigationBarsPadding() else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(headerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(backDescription, tint = backTint, onClick = onClose)
            header()
        }
        content()
    }
}

/** Titel in der Kopfzeile — die Screens nutzen 19 oder 20 sp. */
@Composable
internal fun ScreenTitle(
    text: String,
    size: TextUnit = 19.sp,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
) {
    Text(
        text,
        color = Mpd.colors.text,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        fontFamily = fontFamily,
        modifier = modifier,
    )
}

// -- Laden / Fehler / Leer ---------------------------------------------------

/**
 * Die Weiche, die 23 Stellen von Hand bauten: Fehler zuerst, dann „noch
 * nicht geladen", dann „leer", sonst Inhalt. Wortlaut, Farben und Masse
 * liefert der Aufrufer — hier steht nur die Reihenfolge, damit sie
 * nirgends mehr stillschweigend anders ist.
 */
@Composable
internal fun <T : Any> LoadState(
    error: String?,
    data: T?,
    isEmpty: (T) -> Boolean = { false },
    errorContent: @Composable (String) -> Unit,
    loading: @Composable () -> Unit,
    empty: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    when {
        error != null -> errorContent(error)
        data == null -> loading()
        isEmpty(data) -> empty()
        else -> content(data)
    }
}

/** Box, die ihren Inhalt zentriert — Vorgabe: die ganze Flaeche. */
@Composable
internal fun CenterBox(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable BoxScope.() -> Unit,
) = Box(modifier, contentAlignment = Alignment.Center, content = content)

/** Ladekreis in der Mitte; [size] = null laesst die Material-Vorgabe. */
@Composable
internal fun LoadingCenter(modifier: Modifier = Modifier.fillMaxSize(), size: Dp? = null) =
    CenterBox(modifier) {
        CircularProgressIndicator(if (size != null) Modifier.size(size) else Modifier)
    }

/** Fehlertext in der Mitte, mit Abstand rundum. */
@Composable
internal fun ErrorCenter(
    text: String,
    color: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
    padding: Dp = 32.dp,
    fontSize: TextUnit = TextUnit.Unspecified,
) = CenterBox(modifier) {
    Text(text, color = color, fontSize = fontSize, modifier = Modifier.padding(padding))
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Karte im Vollbild — fuer Tours und Trails dieselbe Mechanik.
 *
 * 15.09.2026: „MPD Tours — ist es nicht moeglich zu den Touren
 * einen Fullscreen zu bekommen?" Die Karte hatte dort eine feste Hoehe von
 * 340 dp in einer scrollenden Spalte, in Trails 320 dp. Am Handy (832 dp
 * hoch) sind das vier Zehntel des Schirms; fuer eine Wanderkarte wenig.
 *
 * Umgesetzt als Dialog ueber der ganzen Flaeche, nicht als Ersatz der
 * Ansicht. Damit bleibt die Scrollposition der Seite darunter erhalten —
 * dieselbe Lehre wie bei der Lightbox, die bewusst ueber der Albumansicht
 * liegt und sie nicht ersetzt. Der Dialog bringt seinen eigenen
 * Zurueck-Weg mit, die Geste wirkt also ohne Zutun.
 */

/** Knopf in der Kartenecke, der ins Vollbild fuehrt. */
@Composable
internal fun BoxScope.VollbildKnopf(onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC1A1A1A))
            .clickable(onClick = onClick)
            .size(38.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MpdIcons.Maximize,
            stringResource(Res.string.map_fullscreen),
            Modifier.size(20.dp),
            tint = Color.White,
        )
    }
}

/**
 * Die Karte auf dem ganzen Schirm.
 *
 * @param karte bekommt den Modifier fuer die Flaeche — dieselbe Composable
 *   wie im Kleinen, damit es keine zweite Fassung gibt, die auseinanderlaeuft.
 */
@Composable
internal fun KarteVollbild(onClose: () -> Unit, karte: @Composable (Modifier) -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            karte(Modifier.fillMaxSize())
            // Oben links wie ueberall in der App, damit der Daumen ihn
            // dort findet, wo er ihn erwartet.
            Box(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                BackButton(
                    stringResource(Res.string.common_back),
                    tint = Color.White,
                    onClick = onClose,
                )
            }
        }
    }
}

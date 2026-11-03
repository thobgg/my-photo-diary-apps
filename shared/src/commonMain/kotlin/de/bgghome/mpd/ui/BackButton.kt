package de.bgghome.mpd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.bgghome.mpd.ui.theme.Mpd

/**
 * Zurueck-Knopf, einheitlich fuer alle Vollbild-Ansichten.
 *
 * Vorher stand in jeder Ansicht ein typografisches „‹" als Text. Die
 * Tippflaeche war zwar meist schon 48 dp, das Zeichen selbst aber duenn
 * und winzig — 05.09.: „checke in allen Ansichten, dass der
 * Zurueck-Knopf nicht noch dieses winzige ‹ ist". Jetzt ueberall derselbe
 * echte Pfeil (`MpdIcons.ArrowBack`) in der Strichstaerke der uebrigen
 * Symbole, in einer 48-dp-Flaeche.
 *
 * Ausdruecklich NICHT betroffen: die Blaetterpfeile fuer Datum und Monat
 * (Memories, Trails-Kalender). Die sind Navigation in der Zeit, kein
 * Zurueck — dort ist das Zeichenpaar ‹ › genau richtig.
 */
@Composable
internal fun BackButton(
    contentDescription: String,
    tint: Color = Mpd.colors.text,
    size: androidx.compose.ui.unit.Dp = 26.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MpdIcons.ArrowBack, contentDescription, Modifier.size(size), tint = tint)
    }
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.explain

/*
 * Kleine Bausteine, die mehrfach von Hand gebaut waren (08.09.2026,
 * Struktur-Audit Stufe 2). Hier liegt nur, was in Radius, Rand, Farbe
 * und Mass WIRKLICH identisch war — was sich unterschied (Suchchip ohne
 * Rand, Kopfzeilen-Chips mit Symbol, Anmeldefeld mit festen Farben),
 * bleibt bei seinem Screen, sonst waere das Zusammenlegen sichtbar.
 */

/**
 * Rueckfrage mit Titel, Text, einem Bestaetigen- und einem
 * Abbrechen-Knopf — vorher privat in TrailsDevices, jetzt fuer alle, die
 * genau diese Form haben. Dialoge mit rotem Knopf, Untertext oder
 * eigener Zeile bleiben AlertDialog.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, color = Mpd.colors.text, fontSize = 14.sp, lineHeight = 20.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/**
 * Blaue Beschriftung in einer umrandeten Pille — die Nebenhandlung
 * („Kopieren", „Weitergeben", „Archiv als ZIP"). War dreimal da:
 * SmallAction (Share), ContribAction (Beisteuern), OutlineButton (Trails).
 */
@Composable
internal fun OutlinePill(
    label: String,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    val color = if (enabled) colors.blue else colors.muted.copy(alpha = 0.4f)
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = color, fontSize = 13.sp)
    }
}

/**
 * Symbol vor einem Text in einer Zeile — ersetzt Strings wie „📥 Beisteuern"
 * (Regel vom 08.09.2026: Bedienung einfarbig, Inhalt bunt). Das Symbol
 * traegt die Textfarbe, wenn [tint] nichts anderes sagt.
 */
@Composable
internal fun IconLabel(
    icon: ImageVector,
    text: String,
    color: Color,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    iconSize: Dp = 18.dp,
    gap: Dp = 8.dp,
    tint: Color = color,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint)
        Spacer(Modifier.width(gap))
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines)
    }
}

/**
 * Auswahl-Pille: aktiv blau gefuellt, sonst nur Rand. Zwei Groessen, wie
 * sie schon da waren — die Trails-Reiter (14/13/12×7) und die Wahlfelder
 * im Beisteuern-Dialog ([compact]: 12/12/10×6).
 */
@Composable
internal fun ChoicePill(
    label: String,
    active: Boolean,
    compact: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val color = if (active) colors.textOnAccent else colors.muted
    Row(
        Modifier
            .clip(shape)
            .background(if (active) colors.blue else Color.Transparent)
            .border(1.dp, if (active) colors.blue else colors.border, shape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 6.dp else 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = color, fontSize = if (compact) 12.sp else 13.sp)
    }
}

/** Blauer Knopf ueber die volle Breite — die eine Haupthandlung einer Flaeche. */
@Composable
internal fun AccentButton(label: String, topPadding: Dp = 8.dp, onClick: () -> Unit) {
    val colors = Mpd.colors
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.blue),
    ) { Text(label, color = colors.textOnAccent) }
}

/**
 * Fehlertext fuer die Oberflaeche: 403 (oder eine fehlende Route, reiner
 * Core) bekommt den Satz, der zum Modul
 * passt ([forbidden]), jede andere Serverantwort ihr `detail`, alles
 * Uebrige die Ausnahme-Meldung. War zweimal da (Trails, Share), nur mit
 * verschiedenem 403-Satz — der bleibt je Aufrufer.
 *
 * Nicht dasselbe wie AppViewModel.explain(): das nimmt `detail` zuerst
 * und uebersetzt danach den Statuscode. Zwei Abbildungen, zwei Zwecke.
 */
internal fun apiErrorText(e: Throwable, forbidden: String): String = when {
    e is MpdHttpException && (e.code == 403 || e.routeMissing) -> forbidden
    e is MpdHttpException -> e.detail
    else -> e.message ?: de.bgghome.mpd.state.Texte.t(Res.string.err_generic)
}


/** Trennlinie der Album-Elemente (Stil wie im Web: bold, dashed, dots, space). */
@Composable
internal fun SeparatorLine(style: String, modifier: Modifier) {
    val lineColor = when (style) {
        "bold" -> MpdBlue.copy(alpha = 0.6f)
        "dashed" -> MpdBlue.copy(alpha = 0.45f)
        else -> MpdBorder
    }
    when (style) {
        "dots", "space" -> Spacer(modifier)
        "dashed" -> Canvas(modifier.height(2.dp)) {
            drawLine(
                color = lineColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = size.height,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
            )
        }

        else -> Box(modifier.height(2.dp).background(lineColor))
    }
}

/** Fehlertext fuer Teilen-/Beisteuern-Links: 403 heisst hier „nicht freigegeben". */
internal fun shareErrorText(e: Throwable): String =
    apiErrorText(e, forbidden = Texte.t(Res.string.share_forbidden))

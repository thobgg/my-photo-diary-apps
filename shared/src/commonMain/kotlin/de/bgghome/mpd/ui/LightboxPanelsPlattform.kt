package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.bgghome.mpd.AppViewModel

/*
 * Was die Lightbox-Seitenleisten je Plattform anders machen
 * (Aufteilung 22.09.2026): Karten, Datum/Zeit-Wahl, Foto-Editor.
 */

/** Adresse, die die Karten-App bzw. der Browser fuer einen Ort oeffnet. */
internal expect fun kartenLink(lat: Double, lon: Double): String

/** Kleine, nicht bedienbare Standortkarte. */
@Composable
internal expect fun ExifMiniMap(lat: Double, lon: Double, modifier: Modifier)

/** Karte, auf der ein Tipp den Pin setzt; [center] schwenkt (Suchtreffer). */
@Composable
internal expect fun ExifPickMap(
    lat: Double?,
    lon: Double?,
    center: Pair<Double, Double>?,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier,
)

/** Datum waehlen; [initial] als yyyy-MM-dd, Ergebnis ebenso. */
@Composable
internal expect fun DatumWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit)

/** Zeit waehlen; [initial] als HH:mm, Ergebnis ebenso. */
@Composable
internal expect fun ZeitWaehlen(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit)

/**
 * Geraete-Aufnahme ueberschreiben: Android holt die Zustimmung des Systems
 * (MediaStore.createWriteRequest) und schreibt dann die Bytes aus
 * vm.pendingLocalEdit; der Desktop hat keine Geraete-Aufnahmen.
 */
@Composable
internal expect fun LokalesSchreiben(vm: AppViewModel, onClose: () -> Unit)

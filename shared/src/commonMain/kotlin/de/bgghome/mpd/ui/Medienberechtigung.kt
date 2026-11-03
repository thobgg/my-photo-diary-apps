package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import de.bgghome.mpd.AppViewModel

/**
 * Aufnahmen vom Geraet gehoeren in denselben Strom (06.09.): ohne
 * sie ist ein Foto unsichtbar, bis das Backup es hochgeladen hat. Android
 * fragt einmal je Prozess nach der Mediathek-Berechtigung und laedt dann
 * die Geraetefotos; wer ablehnt, sieht eben nur die NAS. Der Desktop hat
 * keine Geraetefotos und tut nichts (Aufteilung 22.09.2026).
 */
@Composable
expect fun GeraetefotosAnfordern(vm: AppViewModel)

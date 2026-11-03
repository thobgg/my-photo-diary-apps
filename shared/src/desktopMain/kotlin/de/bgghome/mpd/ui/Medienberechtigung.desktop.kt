package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import de.bgghome.mpd.AppViewModel

/**
 * Keine Berechtigung noetig: die Ordner auf diesem PC hat der Nutzer selbst
 * gewaehlt. Beim Oeffnen des Zeitstrahls einmal einlesen (04.10.2026).
 */
@Composable
actual fun GeraetefotosAnfordern(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadLocalMedia() }
}

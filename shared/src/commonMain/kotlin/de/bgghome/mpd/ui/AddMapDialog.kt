package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.MapPin

/**
 * Karte einfuegen: Pins setzen auf einer osmdroid-Karte (Android). Der
 * Desktop hat noch keine Karte und sagt das (Aufteilung 22.09.2026).
 */
@Composable
expect fun AddMapDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onInsert: (title: String, pins: List<MapPin>) -> Unit,
)

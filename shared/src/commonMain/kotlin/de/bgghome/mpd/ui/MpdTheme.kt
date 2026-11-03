package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import de.bgghome.mpd.ui.theme.Mpd

/*
 * Farb-Kurzformen fuer die Screens — Delegates auf das Theme-System
 * (ui/theme/MpdTheme.kt, alle 9 Web-Themes). Frueher standen hier feste
 * Dark-Werte; seit dem Theme-Umbau folgen alle Screens automatisch dem
 * gewaehlten Theme. Neue Stellen greifen am besten direkt auf
 * `Mpd.colors` zu; diese Aliase bleiben fuer die bestehenden Screens.
 */
internal val MpdText: Color @Composable get() = Mpd.colors.text
internal val MpdMuted: Color @Composable get() = Mpd.colors.muted
internal val MpdBorder: Color @Composable get() = Mpd.colors.border
internal val MpdSurface: Color @Composable get() = Mpd.colors.surface
internal val MpdHeaderBg: Color @Composable get() = Mpd.colors.navy
internal val MpdBlue: Color @Composable get() = Mpd.colors.blue
internal val MpdBluePale: Color @Composable get() = Mpd.colors.bluePale
internal val MpdGold: Color @Composable get() = Mpd.colors.gold
internal val MpdGoldLt: Color @Composable get() = Mpd.colors.goldLt
internal val MpdGoldBg: Color @Composable get() = Mpd.colors.gold.copy(alpha = 0.10f)

// Statusfarben — bewusst themenunabhaengig (Erfolg/Fehler bleiben gruen/rot)
internal val MpdGreen = Color(0xFF4CAF50)
internal val MpdRed = Color(0xFFE05555)

/** Pin-Farben der Karten (auch fuer Listen ohne Karte, z.B. Tourenwahl). */


/** Pin-Blau der Karten als Compose-Farbe; Android nutzt PinBlueArgb (MapElements) fuer osmdroid. */
internal val PinBlue = Color(0xFF2E75B6)

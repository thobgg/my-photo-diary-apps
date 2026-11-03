package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

/*
 * Farbwelten des Erinnerungen-Schirms: Schluessel und Beschriftungen.
 * Gemeinsam, weil Kopfzeile und Einstellungen sie brauchen; die Malerei
 * selbst (Polaroids, Pinnwand) liegt in MemoriesScreen (Android).
 */
internal val MEMORIES_THEME_LABELS = mapOf(
    "auto" to "🎨 Auto", "creme" to "🎞 Creme", "dunkel" to "🌙 Dunkel",
    "sepia" to "📜 Sepia", "nordic" to "❄️ Nordic", "sunset" to "🌇 Sunset",
    "pinnwand" to "📌 Pinnwand",
)

/** Dieselben Beschriftungen als Ressourcen (i18n 08.09.2026). Die Map
 *  darueber bleibt, bis SettingsScreen und AppHeader hierauf umgestellt
 *  sind — dann faellt sie weg. */
internal val MEMORIES_THEME_LABEL_RES = mapOf(
    "auto" to Res.string.memories_theme_auto, "creme" to Res.string.memories_theme_creme,
    "dunkel" to Res.string.memories_theme_dark, "sepia" to Res.string.memories_theme_sepia,
    "nordic" to Res.string.memories_theme_nordic, "sunset" to Res.string.memories_theme_sunset,
)

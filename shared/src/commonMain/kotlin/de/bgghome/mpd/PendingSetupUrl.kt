package de.bgghome.mpd

/**
 * Die Serveradresse aus einem `mpd://setup?url=…`-Link, bis der
 * Einrichtungs-Bildschirm sie abholt (15.09.2026).
 *
 * Liegt im geteilten Modul, nicht in MainActivity: der Einrichtungs-
 * Bildschirm (SetupScreen) liest den Wert, und :shared darf die App-Huelle
 * nicht kennen (Aufteilung 22.09.2026).
 */
object PendingSetupUrl {
    var value = androidx.compose.runtime.mutableStateOf<String?>(null)
}

package de.bgghome.mpd.state

import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.util.tokenHash

/*
 * Gedaechtnis fuer eigene Teilen-/Beisteuern-Links — aus dem ViewModel
 * herausgeloest (08.09.2026, Struktur-Audit Stufe 2, Block 3).
 *
 * Warum ueberhaupt: der Server liefert den Klartext-Token nur einmal, in
 * der Antwort auf `create`, und speichert selbst bloss dessen Hash. Ohne
 * dieses Gedaechtnis kann man an einem bestehenden Link genau eines tun —
 * ihn widerrufen. Den QR-Code oder den Link selbst saehe man nie wieder.
 * Begruendung und Grenzen stehen bei SettingsStore.LinkArt.
 */
class LinkTokens(private val settings: SettingsStore) {

    fun store(art: SettingsStore.LinkArt, token: String) {
        settings.rememberToken(art, tokenHash(token), token)
    }

    /** Klartext-Token zu einem Listeneintrag, oder null: dann wurde der Link
     *  auf einem anderen Geraet angelegt (oder die App neu installiert). */
    fun tokenFor(art: SettingsStore.LinkArt, tokenHash: String): String? =
        settings.tokenFor(art, tokenHash)

    fun forget(art: SettingsStore.LinkArt, tokenHash: String) {
        settings.forgetToken(art, tokenHash)
    }

    /** Nach dem Laden einer Liste: Token zu Links wegwerfen, die es nicht
     *  mehr gibt — abgelaufen oder anderswo widerrufen. */
    fun prune(art: SettingsStore.LinkArt, lebendeHashes: Collection<String>) {
        settings.pruneTokens(art, lebendeHashes)
    }
}

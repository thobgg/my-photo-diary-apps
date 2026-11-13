package de.bgghome.mpd

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.state.serverUnreachable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.net.ssl.SSLException
import de.bgghome.mpd.api.appSettings
import de.bgghome.mpd.api.notifyPull
import de.bgghome.mpd.api.updateAppSettings
import de.bgghome.mpd.api.updateUserSettings
import de.bgghome.mpd.api.userSettings

// Konto und Sitzung: Anmeldung, Zertifikat, Rechte, Einstellungen, Benachrichtigungen, Abmelden.
// Erweiterungen von AppViewModel, herausgeloest aus AppViewModel.kt (13.11.2026).

fun AppViewModel.loadAppSettings() {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        runCatching { api.appSettings(base) }.onSuccess { appSettings = it }
    }
}

/** Teil-Update (nur die uebergebenen Schluessel); onDone(null) = ok. */
fun AppViewModel.updateAppSettings(
    updates: kotlinx.serialization.json.JsonObject,
    onDone: (String?) -> Unit = {},
) {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        try {
            appSettings = api.updateAppSettings(base, updates)
            onDone(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onDone(e.message ?: t(Res.string.vm_err_save_failed))
        }
    }
}

fun AppViewModel.loadUserSettings() {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        runCatching { api.userSettings(base) }.onSuccess { userSettings = it }
    }
}

fun AppViewModel.setMemoriesScope(scope: String, onDone: (String?) -> Unit = {}) {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        try {
            userSettings = api.updateUserSettings(
                base,
                kotlinx.serialization.json.buildJsonObject {
                    put("memories_scope", kotlinx.serialization.json.JsonPrimitive(scope))
                },
            )
            // Frisch geladene Erinnerungen passen nicht mehr zur Quelle —
            // gleich neu holen, die Pinnwand kann offen sein.
            memories.data = null
            memories.load()
            onDone(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            onDone(e.message ?: t(Res.string.err_generic))
        }
    }
}

suspend fun AppViewModel.foregroundNotifyLoop() {
    val baseUrl = settings.baseUrl ?: return
    val deviceId = settings.notifyDeviceId
    // Eigener, gespeicherter Zaehler des Banners (11.10.2026) — der des
    // NotifyWorkers bleibt stehen, wenn Android Benachrichtigungen
    // verbietet, und dann kam dieselbe Meldung bei jedem Oeffnen wieder.
    var cursor = maxOf(settings.notifyLastShownId, settings.notifyBannerSeenId)
    // Nach Neuinstallation/Neuanmeldung steht der Zaehler auf 0 — der
    // erste Durchlauf setzt nur die Basislinie, sonst poppt ein laengst
    // gelaufener Alarm als Banner auf („was mit xy", 03.09.).
    // Ebenso beim ersten Lauf mit dem eigenen Banner-Zaehler: sonst kaeme
    // die alte Kies-Meldung nach dem Update noch ein letztes Mal.
    var baseline = cursor == 0L || settings.notifyBannerSeenId == 0L
    while (true) {
        try {
            val page = api.notifyPull(baseUrl, deviceId, since = cursor, wait = 25)
            val fresh = page.notifications.filter { it.id > cursor }
            if (fresh.isNotEmpty()) {
                cursor = fresh.maxOf { it.id }
                settings.notifyBannerSeenId = cursor
                if (!baseline) {
                    fresh.lastOrNull { it.type == "alert" }?.let { msg ->
                        alertBanner = msg.title.ifBlank { "My Photo Diary" } to msg.body
                    }
                    plattform.kickNotify()
                }
            }
            baseline = false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            kotlinx.coroutines.delay(15_000)
        }
    }
}

fun AppViewModel.waehleAufzeichnung(level: String) {
    trailsRecordingLevel = level
    settings.trailsRecordingLevel = level
}

fun AppViewModel.setTheme(id: de.bgghome.mpd.ui.theme.MpdThemeId) {
    themeId = id
    settings.themeName = id.name
}

// -- Space-Filter der Albenliste (Hamburger „Anzeige", wie im Web) ------

/** null = alle Spaces; sonst "personal"/"shared". Gilt fuer Albenliste
 *  UND Zeitstrahl — bis 10.10.2026 nur fuer die Alben („wenn
 *  man bei Timeline von Ansicht Geteilt auf Persönlich umstellt, wird
 *  trotzdem alles angezeigt"). */

fun AppViewModel.hasRight(space: String, recht: String): Boolean {
    val karte = rights
    if (karte.isEmpty()) return fallbackRight(space, recht)
    return recht in karte[space].orEmpty()
}

internal fun AppViewModel.fallbackRight(space: String, recht: String): Boolean = when (recht) {
    "curate", "edit" -> de.bgghome.mpd.data.Regeln.darfKuratieren(role, isDemo, space)
    "contribute" -> de.bgghome.mpd.data.Regeln.darfBeitragen(role, isDemo, space)
    "share", "contrib_link" -> de.bgghome.mpd.data.Regeln.darfKuratieren(role, isDemo, space)
    "see" -> !isDemo || space == "personal"
    else -> false
}

/** Darf kuratieren: Alben gestalten, Dateien loeschen. */
fun AppViewModel.canCurate(space: String): Boolean = hasRight(space, "curate")

/** Darf beitragen: hochladen, einsortieren, Ausschuss wegwerfen. */
fun AppViewModel.canContribute(space: String): Boolean = hasRight(space, "contribute")

/** Darf teilen — eigene Regel, folgt keiner der beiden Stufen. */
fun AppViewModel.canShare(space: String): Boolean = hasRight(space, "share")

/** Darf Beisteuern-Links anlegen — haengt am Kurator-Recht. */
fun AppViewModel.canContribLink(space: String): Boolean = hasRight(space, "contrib_link")

internal fun AppViewModel.stateFor(who: WhoAmI): AppState =
    if (who.mustChangePassword) {
        AppState.MustChangePassword(who)
    } else {
        // Die Durchlauf-Zahl gehoert in den Kopf, und der steht auf
        // JEDEM Tab. Sie darf also nicht daran haengen, dass der
        // Zeitstrahl schon geladen wurde — genau daran ist die erste
        // Fassung gescheitert.
        AppState.LoggedIn(who).also { loadDurchlaufCount(who) }
    }

/**
 * Holt die Durchlauf-Zahl vom Server — ein Aufruf je Bestand, in dem
 * das Konto beitragen darf, `limit=1`, gezaehlt wird serverseitig.
 * Dasselbe Vorgehen wie im Web (_loadDurchlaufCount).
 *
 * Scheitert der Aufruf, bleibt die alte Zahl stehen: eine Pille, die
 * bei jedem Netzhaenger auf 0 springt, meldet „alles erledigt" und
 * ist schlechter als eine, die kurz veraltet ist.
 */

/** Beim App-Start: mit gespeichertem Cookie direkt anmelden, wenn moeglich. */
internal fun AppViewModel.checkStoredSession() {
    val base = settings.baseUrl
    if (base == null || settings.session() == null) {
        state = AppState.Setup
        return
    }
    viewModelScope.launch {
        state = AppState.Checking
        try {
            state = stateFor(api.whoami(base).also {
                settings.lastUser = it.user // Personenkuerzel fuer Worker
            })
        } catch (e: MpdHttpException) {
            // 401 = Sitzung abgelaufen — normale, stille Neuanmeldung
            if (e.code != 401) error = e.detail
            settings.clearSession()
            state = AppState.Setup
        } catch (e: IOException) {
            error = serverUnreachable(e)
            state = AppState.Setup
        }
    }
}

fun AppViewModel.login() {
    val base = normalizeUrl(serverUrl) ?: run {
        error = t(Res.string.vm_err_invalid_server)
        return
    }
    serverUrl = base
    viewModelScope.launch {
        busy = true
        error = null
        try {
            api.login(base, username.trim(), password)
            settings.baseUrl = base
            password = ""
            vergissKontodaten()   // nie mit den Daten eines anderen Kontos starten
            state = stateFor(api.whoami(base).also {
                settings.lastUser = it.user // Personenkuerzel fuer Worker
            })
        } catch (e: MpdHttpException) {
            error = e.detail
        } catch (e: SSLException) {
            // Unbekanntes Zertifikat: Fingerprint holen und ehrlich fragen,
            // statt die Pruefung still abzuschalten.
            try {
                pendingCert = api.fetchServerCert(base)
            } catch (fetchError: Exception) {
                error = t(Res.string.vm_err_tls_x, e.message)
            }
        } catch (e: IOException) {
            error = serverUnreachable(e)
        } finally {
            busy = false
        }
    }
}

/** Nutzer hat dem angezeigten Zertifikat vertraut → merken und erneut anmelden. */
fun AppViewModel.trustPendingCert() {
    settings.trustedCertSha256 = pendingCert?.sha256 ?: return
    pendingCert = null
    login()
}

fun AppViewModel.dismissPendingCert() {
    pendingCert = null
}

fun AppViewModel.switchHomeTab(tab: Int) {
    homeTab = tab
    settings.homeTab = tab
}

fun AppViewModel.moduleAllowed(key: String): Boolean {
    val who = (state as? AppState.LoggedIn)?.who ?: return true
    return de.bgghome.mpd.ui.moduleFree(key, who.modules, missingModules)
}

/** Modul oeffnen oder — wenn gesperrt — das Schaufenster zeigen. */
fun AppViewModel.tryOpenModule(key: String, open: () -> Unit) {
    if (moduleAllowed(key)) open() else lockedModuleInfo = key
}

fun AppViewModel.logout() {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        busy = true
        try {
            api.logout(base)
        } catch (_: IOException) {
            // Cookie ist lokal geloescht, das reicht
        } finally {
            busy = false
            vergissKontodaten()
            state = AppState.Setup
        }
    }
}

/**
 * Alles vergessen, was zum angemeldeten Konto gehoert: Albenliste,
 * Zeitstrahl, Erinnerungen, Suche, Statistik, Konten, Favoriten,
 * Einstellungen. Bis 03.11.2026 blieb das alles nach dem Abmelden
 * stehen — wer sich danach mit einem anderen Konto anmeldete (etwa dem
 * Demo-Konto), sah noch die Alben des vorherigen, bis etwas neu lud.
 * Der Server gab dem neuen Konto nichts davon heraus; die Anzeige war
 * trotzdem falsch. Geraetefotos (localItems) bleiben: Die gehoeren zum
 * Geraet, nicht zum Konto.
 */

internal fun AppViewModel.vergissKontodaten() {
    albums = null
    openAlbum = null
    pendingUndoDelete = null
    folderCandidates = null
    appSettings = null
    durchlaufCount = 0
    timelineItems.clear()
    timelineFeed.clear()
    timelineSelection.clear()
    gesichertAufGeraet = emptyList()
    timelineLightbox = null
    timelineFeedVersion++
    recycleBySpace.clear()
    editVersions.clear()
    missingModules.clear()
    memories.vergiss()
    search.vergiss()
    stats.vergiss()
    users.vergiss()
    favorites.vergiss()
}

// -- Gedaechtnis fuer eigene Teilen-/Beisteuern-Links -------------------
//
// Warum ueberhaupt: der Server liefert den Klartext-Token nur einmal, in
// der Antwort auf `create`, und speichert selbst bloss dessen Hash. Ohne
// dieses Gedaechtnis kann man an einem bestehenden Link genau eines tun —
// ihn widerrufen. Den QR-Code oder den Link selbst saehe man nie wieder.
// Begruendung und Grenzen stehen bei SettingsStore.LinkArt.

/**
 * Adresse fuer Links, die BEI ANDEREN aufgehen muessen — QR-Codes,
 * Weitergeben, Kopieren.
 *
 * Bevorzugt die oeffentliche Adresse, die der Server kennt
 * (`whoami.base_url`, seit 05.09.2026). Sonst die eingetragene: die ist
 * im LAN richtig und ausserhalb eben nicht — darauf weist der
 * QR-Bildschirm dann hin.
 *
 * Fuer API-Aufrufe gilt weiterhin [baseUrl]: dort ist die lokale Adresse
 * die schnellere und oft die einzige, die von hier aus funktioniert.
 */

fun AppViewModel.refreshAfterPasswordChange() {
    val base = settings.baseUrl ?: return
    viewModelScope.launch {
        state = try {
            stateFor(api.whoami(base))
        } catch (_: IOException) {
            AppState.Setup
        }
    }
}

fun AppViewModel.meldung(
    text: String,
    kurz: Boolean = false,
    /** Optionaler Knopf in der Snackbar („Öffnen") — [onAktion] laeuft beim Tipp. */
    aktion: String? = null,
    onAktion: (() -> Unit)? = null,
) {
    viewModelScope.launch {
        val result = snackbar.showSnackbar(
            text,
            actionLabel = aktion,
            duration = if (kurz) androidx.compose.material3.SnackbarDuration.Short
            else androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onAktion?.invoke()
    }
}

/** "192.168.1.2:5001" → "https://192.168.1.2:5001", ohne Schraegstrich am Ende. */
internal fun AppViewModel.normalizeUrl(input: String): String? {
    var url = input.trim().trimEnd('/')
    if (url.isEmpty()) return null
    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
    return url
}

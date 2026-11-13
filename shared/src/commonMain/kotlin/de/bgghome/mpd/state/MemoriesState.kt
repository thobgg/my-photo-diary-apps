package de.bgghome.mpd.state

import de.bgghome.mpd.shared.res.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.bgghome.mpd.api.MemoriesResponse
import de.bgghome.mpd.api.MpdApi
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import de.bgghome.mpd.api.memories

/*
 * Modul „memories" (GET /api/memories) — aus dem ViewModel herausgeloest
 * (08.09.2026, Struktur-Audit Stufe 2, Block 3). Seit 05.09. eine
 * Vollbild-Ansicht (kein Tab), erreichbar ueber das Kopfmenue und das
 * Erinnerungs-Karussell der Timeline. Was eine Erinnerung beim Antippen
 * tut (Album in der Lightbox oeffnen), bleibt im ViewModel.
 */
/**
 * Die Farbwelten in ihrer Reihenfolge — EINE Liste, sonst keine.
 *
 * Sie stand bis zum 13.09.2026 doppelt da: einmal hier als `order` beim
 * Weiterschalten, einmal in ui/MemoriesScreen.kt als Beschriftungs-Karte.
 * Die neue Pinnwand wurde nur in die Karte eingetragen — die Palette
 * schaltete weiter durch die alten sechs und erreichte sie nie. Vorgabe:
 * „am Tablet aendert sich in My Memories auch nur die Farben? keine
 * Pinnwand". Ein Test haelt die beiden jetzt zusammen
 * (MemoriesThemesTest).
 *
 * Die Beschriftungen bleiben in der Oberflaeche, die Reihenfolge gehoert
 * zum Zustand — deshalb steht sie hier und nicht dort.
 */
internal val MEMORIES_THEMES = listOf(
    "auto", "creme", "dunkel", "sepia", "nordic", "sunset", "pinnwand",
)

class MemoriesState(
    private val api: MpdApi,
    private val baseUrl: () -> String,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Karten je Jahr in der Pinnwand — Zahl (08.09.2026). Die
         *  Auswahl trifft der Server (Tagesabschnitte, dann Ort), hier
         *  wird nur gesagt, wie viele. */
        const val PRO_JAHR = 6
    }

    var open by mutableStateOf(false)
    var data by mutableStateOf<MemoriesResponse?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)

    /** Blaettern relativ zu heute (0 = heute, -1 = gestern …). */
    var offsetDays by mutableStateOf(0)

    /**
     * Der Kalendertag, an dem [data] geholt wurde.
     *
     * Bis 11.10.2026 luden Karussell und Pinnwand nur, wenn noch GAR
     * NICHTS da war. Android haelt die App aber tagelang im Speicher —
     * dann blieben die „Heute vor …"-Fotos des Tages stehen, an dem die
     * App zuletzt neu gestartet worden war. Nur das Antippen des
     * Morgengrusses lud frisch. Vorgabe: „die My Memories Anzeige die
     * nicht mehr geht, keine neuen Fotos heute vor … liefert". Der
     * Server lieferte laengst jeden Tag andere.
     */
    private var geladenAm: LocalDate? = null

    /** Nichts da, oder seit dem Laden hat ein neuer Tag begonnen. */
    val veraltet: Boolean get() = data == null || geladenAm != LocalDate.now()

    /** Beim Oeffnen und beim Zurueckkehren in die App: ist ein neuer Tag
     *  angebrochen, zurueck auf „heute" und neu holen. */
    fun ladeWennVeraltet() {
        if (loading || !veraltet) return
        load(0)
    }

    /** Farbwelt der Pinnwand (Web hatte mehrere Themes als Kaufargument —
     *  dort war der Umschalter allerdings halb kaputt: JS-Namen trafen die
     *  CSS-Namen nie. Hier funktioniert er.) */
    var theme by mutableStateOf(settings.memoriesTheme)
        private set

    fun chooseTheme(value: String) {
        theme = value
        settings.memoriesTheme = value
    }

    fun cycleTheme() {
        val next = MEMORIES_THEMES[(MEMORIES_THEMES.indexOf(theme) + 1).mod(MEMORIES_THEMES.size)]
        theme = next
        settings.memoriesTheme = next
    }

    fun load(offset: Int = offsetDays) {
        offsetDays = offset
        scope.launch {
            loading = true
            error = null
            try {
                val target = LocalDate.now().plusDays(offset.toLong())
                val date = "%02d-%02d".format(target.monthValue, target.dayOfMonth)
                data = api.memories(baseUrl(), date, limit = PRO_JAHR)
                geladenAm = LocalDate.now()
            } catch (e: MpdHttpException) {
                data = null
                error = if (e.code == 403 || e.routeMissing) {
                    Texte.t(Res.string.memories_err_locked)
                } else e.detail
            } catch (e: IOException) {
                data = null
                error = serverUnreachable(e)
            } finally {
                loading = false
            }
        }
    }

    /** Alles, was zu einem Konto gehoert, vergessen (Abmelden, Kontowechsel). */
    fun vergiss() {
        open = false
        data = null
        error = null
        loading = false
        offsetDays = 0
        geladenAm = null
    }
}

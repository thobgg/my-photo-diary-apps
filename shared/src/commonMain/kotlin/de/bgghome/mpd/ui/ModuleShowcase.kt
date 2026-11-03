package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import org.jetbrains.compose.resources.StringResource

import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.ui.theme.Mpd
import org.jetbrains.compose.resources.stringResource

/*
 * Schaufenster fuer gesperrte Module (03.09.2026; bis 03.11.2026 Teil von
 * „MPD Plus", seither schaltet der Verwalter Module je Konto frei — der
 * Server hat keine Lizenz mehr). Ein gesperrtes Modul verschwindet
 * NICHT aus dem Menue und wirft keinen 403-Fehlertext, sondern zeigt mit
 * 🔒 diesen Dialog — was das Modul kann und wie man es freischaltet.
 * Die Wahrheit ueber die Freischaltung kennt allein der Server
 * (require_module); die App liest /api/whoami.modules.
 */

internal data class ModuleInfo(
    val key: String,
    /** Einstweilen ein Material-Symbol — die Bildmarke des Moduls fehlt (CI v1.2, Bildmarken). */
    val icon: ImageVector,
    /** Ressourcen-Ids (i18n 08.09.2026): die Liste ist ein Top-Level-Wert ohne
     *  Composable-Kontext, aufgeloest wird beim Anzeigen. */
    val name: StringResource,
    val tagline: StringResource,
    val features: List<StringResource>,
)

// Zeitstrahl ist seit 03.09.2026 Grundpaket — kein Schaufenster-Eintrag mehr.
internal val MODULE_INFOS = listOf(
    ModuleInfo(
        "memories", MpdIcons.History, Res.string.module_memories_name,
        Res.string.module_memories_tagline,
        listOf(Res.string.module_memories_f1, Res.string.module_memories_f2, Res.string.module_memories_f3),
    ),
    ModuleInfo(
        "search", MpdIcons.Search, Res.string.module_search_name,
        Res.string.module_search_tagline,
        listOf(Res.string.module_search_f1, Res.string.module_search_f2, Res.string.module_search_f3),
    ),
    ModuleInfo(
        "stats", MpdIcons.BarChart, Res.string.module_stats_name,
        Res.string.module_stats_tagline,
        listOf(Res.string.module_stats_f1, Res.string.module_stats_f2, Res.string.module_stats_f3),
    ),
    ModuleInfo(
        "tours", MpdIcons.Tour, Res.string.module_tours_name,
        Res.string.module_tours_tagline,
        listOf(Res.string.module_tours_f1, Res.string.module_tours_f2, Res.string.module_tours_f3),
    ),
    ModuleInfo(
        "trails", MpdIcons.Compass, Res.string.module_trails_name,
        Res.string.module_trails_tagline,
        listOf(Res.string.module_trails_f1, Res.string.module_trails_f2, Res.string.module_trails_f3, Res.string.module_trails_f4),
    ),
).associateBy { it.key }

/**
 * Ist das Modul frei? Reine Funktion, damit der Test ohne ViewModel geht.
 *
 * - `timeline` ist Grundpaket (03.09.2026) — nie ein Schloss.
 * - `memories` ebenso, seit Server 20.09.2026 (Entscheidung: „heute vor X
 *   Jahren" gehoert in den freien Kern). Der Server nimmt es seitdem aus
 *   `modules` heraus; die App las das als „nicht freigeschaltet" und
 *   zeigte ab 26.09.2026 ein Schloss, sobald die Lizenzpruefung scharf
 *   war. Angekuendigt in API_CHANGES vom 20.09., hier nie nachgezogen.
 * - `modules == null`: der Server kennt das Feld nicht (vor 13.07.2026)
 *   → alles frei, wie bisher.
 * - sonst genau die Liste. `[]` heisst NICHTS frei — so meldet sich ein
 *   reiner „My Photo Diary Core" (seit 19.09.2026, Schnitt mit den
 *   installierten Modulen). Bis dahin las die App die leere Liste als
 *   „alles frei" und zeigte im Core Menuepunkte ins Leere.
 * - [missing]: Endpunkt fehlt nachweislich — gesperrt, egal was whoami sagt.
 */
private val GRUNDPAKET = setOf("timeline", "memories")

internal fun moduleFree(key: String, modules: List<String>?, missing: Collection<String>): Boolean {
    if (key in GRUNDPAKET) return true
    if (key in missing) return false
    return modules == null || key in modules
}

/** Dialog fuer ein gesperrtes Modul — vm.lockedModuleInfo traegt den Key. */
@Composable
fun ModuleLockedDialog(vm: AppViewModel) {
    val key = vm.lockedModuleInfo ?: return
    val info = MODULE_INFOS[key] ?: return
    val colors = Mpd.colors

    AlertDialog(
        onDismissRequest = { vm.lockedModuleInfo = null },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(info.icon, null, Modifier.size(24.dp), tint = colors.blue)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(info.name))
                Spacer(Modifier.width(8.dp))
                Icon(MpdIcons.LockClosed, stringResource(Res.string.module_locked_short), Modifier.size(16.dp), tint = colors.muted)
            }
        },
        text = {
            Column {
                Text(stringResource(info.tagline), color = colors.text, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(10.dp))
                info.features.forEach { feature ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("•", color = colors.blue, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(feature), color = colors.text, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.module_locked_body),
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.lockedModuleInfo = null }) { Text(stringResource(Res.string.common_understood)) }
        },
    )
}

package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppState
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.TrailArchiveFile
import de.bgghome.mpd.api.TrailDevice
import de.bgghome.mpd.api.TrailDeviceCreated
import de.bgghome.mpd.api.TrailSource
import de.bgghome.mpd.api.TrailStatus
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Trails, Bereich „Geraete" — alles, was den Speicher fuellt und wieder
 * verlaesst:
 *
 *   1. Aufzeichnung: OwnTracks in einem Tipp einrichten. Der Gedanke aus
 *      der Server-Doku: die App legt das Geraet an, baut daraus den
 *      Konfigurations-Link und uebergibt ihn per Intent — der Token wird
 *      nie abgetippt. Der Passwort-Weg bleibt technisch moeglich, wird
 *      aber nicht beworben (Konto-Passwort in fremder App, kein Widerruf
 *      je Geraet, Passwortwechsel reisst die Aufzeichnung ab).
 *   2. Quellen: was liegt drin und wie wird man es wieder los — der
 *      Umstiegsfall (Alt-Wege im Ueberlappungszeitraum) und der Rueckweg
 *      aus einem missglueckten Import. Endgueltig, darum Rueckfrage.
 *   3. Archiv: „kein Lock-in" ist eine Produktzusage — was hereinkommt,
 *      muss auch wieder heraus.
 */

private val DMY_HM: DateTimeFormatter = DateTimeFormatter
    .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
    .withLocale(java.util.Locale.getDefault())

@Composable
internal fun TrailsDevicesTab(
    vm: AppViewModel,
    status: TrailStatus?,
    onStatusChanged: (TrailStatus) -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<TrailDevice>?>(null) }
    var sources by remember { mutableStateOf<List<TrailSource>>(emptyList()) }
    var archive by remember { mutableStateOf<List<TrailArchiveFile>>(emptyList()) }
    var archiveBytes by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    var addOpen by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<TrailDeviceCreated?>(null) }
    var deleteDevice by remember { mutableStateOf<TrailDevice?>(null) }
    var deleteSource by remember { mutableStateOf<TrailSource?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Jede Liste fuer sich holen: Quellen und Archiv sind Server-Phasen 5
    // und 6 — auf einem aelteren Stand fehlen die Routen, das darf die
    // Geraeteliste nicht mitreissen.
    LaunchedEffect(reloadTick) {
        runCatching { vm.api.trailsDevices(vm.baseUrl) }
            .onSuccess { devices = it }
            .onFailure { devices = emptyList(); error = trailsErrorText(it) }
        runCatching { vm.api.trailsSources(vm.baseUrl) }.onSuccess { sources = it }
        runCatching { vm.api.trailsArchive(vm.baseUrl) }.onSuccess {
            archive = it.files
            archiveBytes = it.bytes
        }
        runCatching { vm.api.trailsStatus(vm.baseUrl) }.onSuccess(onStatusChanged)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .rubberBandOverscroll()
            .padding(horizontal = 14.dp),
    ) {
        error?.let {
            Text(
                it, color = MpdRed, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        // -- Aufzeichnung ---------------------------------------------------
        CollapsibleSection(
            stringResource(Res.string.trails_dev_recording),
            summary = devices?.let {
                if (it.isEmpty()) stringResource(Res.string.trails_dev_none)
                else pluralStringResource(Res.plurals.trails_dev_count, it.size, it.size)
            },
            initiallyOpen = true,
        ) {
            Text(
                stringResource(Res.string.trails_dev_intro),
                color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )

            LoadState(
                error = null, data = devices,
                isEmpty = { it.isEmpty() },
                errorContent = {},
                loading = { LoadingCenter(Modifier.fillMaxWidth().height(60.dp), size = 24.dp) },
                empty = {
                    Text(
                        stringResource(Res.string.trails_dev_empty),
                        color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                },
            ) { list ->
                list.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                device.name?.takeIf { it.isNotBlank() } ?: device.deviceId,
                                color = colors.text, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            val noTokenText = stringResource(Res.string.trails_dev_no_token)
                            val lastTemplate = stringResource(Res.string.trails_dev_last, "%s")
                            val seenTemplate = stringResource(Res.string.trails_dev_seen, "%s")
                            Text(
                                buildString {
                                    append(device.deviceId)
                                    if (!device.hasToken) append(noTokenText)
                                    device.lastTst?.let {
                                        append(lastTemplate.format(epochText(it.toDouble())))
                                    } ?: device.lastSeen?.let {
                                        append(seenTemplate.format(epochText(it)))
                                    }
                                },
                                color = colors.muted, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { deleteSource = null; deleteDevice = device },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MpdIcons.Trash, stringResource(Res.string.trails_dev_delete),
                                Modifier.size(18.dp), tint = MpdRed,
                            )
                        }
                    }
                }
            }

            AccentButton(stringResource(Res.string.trails_dev_add)) { addOpen = true }

            // Aufzeichnungsstufe (08.09.2026): „zu dem Thema muss schon
            // was konfiguriert werden, das duerfte jedem klar sein, der mit
            // der Zeitleiste umsteigen will — keine Rocket Science, aber paar
            // Werte, es geht ja auch um Akkuverbrauch". Drei Stufen statt
            // drei Zahlen; was nicht zur Wahl steht (Modus „Wesentliche
            // Aenderungen", ungenaue Fixes ab 100 m verwerfen, Lebenszeichen
            // alle 30 min), setzt jede Stufe gleich.
            //
            // MPD kann OwnTracks konfigurieren: ueber owntracks:///config
            // (Fernkonfiguration muss in OwnTracks an sein, ist Standard).
            // Der Link ohne Adresse und Token laesst die Anmeldung stehen und
            // setzt nur die Aufzeichnung — auch am schon gekoppelten Geraet.
            Text(
                stringResource(Res.string.trails_rec_title),
                color = Mpd.colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
            val level = vm.trailsRecordingLevel
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, label) in listOf(
                    "sparsam" to Res.string.trails_rec_sparsam,
                    "normal" to Res.string.trails_rec_normal,
                    "genau" to Res.string.trails_rec_genau,
                )) {
                    ChoicePill(stringResource(label), active = level == key, compact = true) {
                        vm.waehleAufzeichnung(key)
                    }
                }
            }
            Text(
                stringResource(
                    when (level) {
                        "sparsam" -> Res.string.trails_rec_hint_sparsam
                        "genau" -> Res.string.trails_rec_hint_genau
                        else -> Res.string.trails_rec_hint_normal
                    },
                ),
                color = Mpd.colors.muted, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            val fehltText = stringResource(Res.string.trails_ot_missing_short)
            TextButton(
                onClick = {
                    // owntracks://-Link: ohne die App wirft der UriHandler (Android:
                    // ActivityNotFound, Desktop: kein Handler) - dann die Meldung.
                    try { uriHandler.openUri(ownTracksRecordingLink(level)) } catch (_: Exception) { vm.meldung(fehltText) }
                },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(MpdIcons.Settings, null, Modifier.size(16.dp))
                Text(
                    stringResource(Res.string.trails_ot_resend),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                stringResource(Res.string.trails_ot_resend_hint),
                color = Mpd.colors.muted, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }

        // -- Quellen --------------------------------------------------------
        if (sources.isNotEmpty()) {
            CollapsibleSection(
                stringResource(Res.string.trails_sources_title),
                summary = sources.joinToString(", ") { sourceLabel(it.source) },
            ) {
                Text(
                    stringResource(Res.string.trails_sources_intro),
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                sources.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                sourceLabel(source.source),
                                color = colors.text, fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(
                                    Res.string.trails_source_counts,
                                    thousands(source.points), thousands(source.visits), thousands(source.activities),
                                ),
                                color = colors.muted, fontSize = 12.sp,
                            )
                            if (source.firstTs != null && source.lastTs != null) {
                                Text(
                                    "${dmyText(source.firstTs!!)} – ${dmyText(source.lastTs!!)}",
                                    color = colors.muted.copy(alpha = 0.7f), fontSize = 11.sp,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .clickable { deleteDevice = null; deleteSource = source },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                MpdIcons.Trash, stringResource(Res.string.trails_dev_delete),
                                Modifier.size(18.dp), tint = MpdRed,
                            )
                        }
                    }
                }
            }
        }

        // -- Archiv ---------------------------------------------------------
        CollapsibleSection(
            stringResource(Res.string.trails_archive_title),
            summary = if (archive.isEmpty()) stringResource(Res.string.trails_archive_none)
            else stringResource(Res.string.trails_archive_files, archive.size, humanBytes(archiveBytes)),
        ) {
            Text(
                stringResource(Res.string.trails_archive_intro),
                color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Text(
                if (archive.isNotEmpty()) {
                    stringResource(Res.string.trails_archive_files, archive.size, humanBytes(archiveBytes))
                } else {
                    stringResource(Res.string.trails_archive_empty)
                },
                color = colors.muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinePill(stringResource(Res.string.trails_archive_zip), enabled = !busy && archive.isNotEmpty(), icon = MpdIcons.Download) {
                    busy = true
                    scope.launch {
                        dienste.speichereDownload(
                            vm,
                            url = vm.api.trailsArchiveZipUrl(vm.baseUrl),
                            filename = "mpd-trails-archiv.zip",
                            mime = "application/zip",
                        )
                        busy = false
                    }
                }
                OutlinePill(stringResource(Res.string.trails_archive_rebuild), enabled = !busy, icon = MpdIcons.Refresh) {
                    busy = true
                    scope.launch {
                        runCatching { vm.api.trailsRebuildArchive(vm.baseUrl) }
                            .onSuccess {
                                vm.meldung(Texte.t(Res.string.trails_archive_rebuilt, it), kurz = true)
                                reloadTick++
                            }
                            .onFailure {
                                vm.meldung(trailsErrorText(it))
                            }
                        busy = false
                    }
                }
            }
            if (busy) {
                Text(
                    stringResource(Res.string.trails_archive_running),
                    color = colors.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // -- Umfang ---------------------------------------------------------
        status?.let { st ->
            CollapsibleSection(
                stringResource(Res.string.trails_store_title),
                summary = pluralStringResource(Res.plurals.trails_points, st.points.toInt(), thousands(st.points)),
            ) {
                val summaryText = stringResource(
                    Res.string.trails_store_summary,
                    thousands(st.points), thousands(st.visits), thousands(st.activities),
                )
                val rangeText = if (st.firstTst != null && st.lastTst != null) {
                    "\n" + stringResource(Res.string.trails_store_range, dmyText(st.firstTst!!), dmyText(st.lastTst!!))
                } else ""
                Text(
                    summaryText + rangeText,
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (addOpen) {
        AddDeviceDialog(
            vm,
            onDismiss = { addOpen = false },
            onCreated = { addOpen = false; created = it; reloadTick++ },
        )
    }
    created?.let { device ->
        OwnTracksSetupDialog(vm, device) { created = null }
    }
    deleteDevice?.let { device ->
        ConfirmDialog(
            title = stringResource(Res.string.trails_dev_remove_title),
            body = stringResource(Res.string.trails_dev_remove_body, device.name ?: device.deviceId),
            confirmLabel = stringResource(Res.string.trails_dev_remove),
            onDismiss = { deleteDevice = null },
        ) {
            scope.launch {
                runCatching { vm.api.trailsDeleteDevice(vm.baseUrl, device.deviceId) }
                    .onFailure {
                        vm.meldung(trailsErrorText(it))
                    }
                deleteDevice = null
                reloadTick++
            }
        }
    }
    deleteSource?.let { source ->
        DeleteSourceDialog(vm, source, onDismiss = { deleteSource = null }) { reloadTick++ }
    }
}

// ---------------------------------------------------------------------------
// OwnTracks einrichten
// ---------------------------------------------------------------------------

@Composable
private fun AddDeviceDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onCreated: (TrailDeviceCreated) -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()
    // Vorschlag aus dem Geraetenamen: „SM-S928B" → „sm-s928b".
    val suggested = remember {
        dienste.geraeteName.lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(64).ifBlank { "handy" }
    }
    var deviceId by remember { mutableStateOf(suggested) }
    val defaultName = stringResource(Res.string.trails_dev_default_name)
    var name by remember { mutableStateOf(dienste.geraeteName.ifBlank { defaultName }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trails_dev_setup_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.trails_dev_setup_intro),
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                DialogField(stringResource(Res.string.trails_dev_id), deviceId) {
                    deviceId = it.replace(Regex("[^A-Za-z0-9._-]"), "").take(64)
                }
                Spacer(Modifier.height(8.dp))
                DialogField(stringResource(Res.string.trails_dev_name), name) { name = it.take(80) }
                error?.let {
                    Text(it, color = MpdRed, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && deviceId.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { vm.api.trailsCreateDevice(vm.baseUrl, deviceId, name) }
                            .onSuccess { onCreated(it) }
                            .onFailure { error = trailsErrorText(it) }
                        busy = false
                    }
                },
            ) { Text(if (busy) "…" else stringResource(Res.string.trails_dev_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/**
 * Der Ein-Tipp-Weg: OwnTracks liest seine Einstellungen aus einem Link
 * `owntracks:///config?inline=<base64 des JSON>` (mode 3 = HTTP,
 * monitoring 1 = „Wesentliche Änderungen"). Klappt der Intent nicht, weil
 * OwnTracks fehlt, bleiben die Werte zum Abtippen stehen — der Token steht
 * NUR hier, ein zweites Mal gibt es ihn nicht.
 */
@Composable
private fun OwnTracksSetupDialog(
    vm: AppViewModel,
    device: TrailDeviceCreated,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val user = (vm.state as? AppState.LoggedIn)?.who?.user.orEmpty()
    val ingestUrl = vm.baseUrl.trimEnd('/') + device.ingestUrl
    var missing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trails_ot_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(Res.string.trails_ot_intro),
                    color = colors.text, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                SetupRow(vm, stringResource(Res.string.trails_ot_address), ingestUrl)
                SetupRow(vm, stringResource(Res.string.trails_ot_user), user)
                SetupRow(vm, stringResource(Res.string.trails_dev_id), device.deviceId)
                SetupRow(vm, stringResource(Res.string.trails_ot_password), device.token)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(Res.string.trails_ot_token_hint),
                    color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp,
                )
                if (missing) {
                    Text(
                        stringResource(Res.string.trails_ot_missing),
                        color = colors.muted, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val link = ownTracksConfigLink(ingestUrl, user, device.token, device.deviceId, vm.trailsRecordingLevel)
                try {
                    uriHandler.openUri(link)
                    onDismiss()
                } catch (_: Exception) {
                    missing = true
                }
            }) { Text(stringResource(Res.string.trails_ot_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.trails_ot_done)) } },
    )
}

/** `owntracks:///config?inline=<base64>` — base64 mit +/= URL-kodiert. */
internal fun ownTracksConfigLink(
    ingestUrl: String, user: String, token: String, deviceId: String,
    level: String = "normal",
): String {
    val config = buildString {
        append("""{"_type":"configuration","mode":3""")
        append(""","url":"""").append(jsonEscape(ingestUrl)).append('"')
        append(""","username":"""").append(jsonEscape(user)).append('"')
        append(""","password":"""").append(jsonEscape(token)).append('"')
        append(""","deviceId":"""").append(jsonEscape(deviceId)).append('"')
        append(',').append(ownTracksRecordingJson(level)).append('}')
    }
    return ownTracksLink(config)
}

/**
 * Die Aufzeichnungswerte je Stufe — im Setup-Link UND im Nachsende-Link
 * (08.09.2026: „es sollen nicht tausende Punkte gemacht werden,
 * wenn das Handy am Kabel den ganzen Tag neben dem PC lag" — „es muss
 * grundsaetzlich sinnvolle Einstellungen her"; dann: drei Stufen, weil
 * es „auch um Akkuverbrauch" geht). OwnTracks-Standard war 500 m Schwelle
 * ohne Genauigkeitsgrenze: ein Dorfspaziergang fiel durch, Mobilfunk-
 * Schaetzungen mit Kilometer-Streuung landeten im Bestand.
 *
 *  monitoring 1               — nur bei wesentlichem Ortswechsel
 *  locatorDisplacement        — ab so vielen Metern Bewegung melden
 *  locatorInterval            — Mindestabstand zwischen Pruefungen (s)
 *  ignoreInaccurateLocations  — Fixes ungenauer als 100 m verwerfen
 *  ping 30                    — alle 30 min ein Lebenszeichen
 */
internal fun ownTracksRecordingJson(level: String): String {
    // locatorPriority (Booklet): 0 HIGH_POWER = GPS, 1 BALANCED = WLAN/Zelle
    // mit gelegentlichem GPS, 2 LOW_POWER = Stadtteil-Genauigkeit. Balanced
    // ist der Akkukompromiss und der Standard des Modus „Wesentliche
    // Aenderungen"; nur „Genau" darf GPS dauerhaft anfordern — sonst
    // waeren 50 m / 20 s ein leeres Versprechen (09.09.2026).
    val (displacement, interval, priority) = when (level) {
        "sparsam" -> Triple(250, 300, 1)
        "genau" -> Triple(50, 20, 0)
        else -> Triple(100, 60, 1)
    }
    return """"monitoring":1,"locatorPriority":$priority,""" +
        """"locatorDisplacement":$displacement,"locatorInterval":$interval,""" +
        """"ignoreInaccurateLocations":100,"ping":30"""
}

/** Nur die Aufzeichnungswerte, ohne Adresse und Token: fuer ein OwnTracks,
 *  das schon angemeldet ist. */
internal fun ownTracksRecordingLink(level: String): String =
    ownTracksLink("""{"_type":"configuration",""" + ownTracksRecordingJson(level) + "}")

private fun ownTracksLink(configJson: String): String {
    val base64 = java.util.Base64.getEncoder().encodeToString(configJson.toByteArray(Charsets.UTF_8))
    return "owntracks:///config?inline=" + URLEncoder.encode(base64, "UTF-8")
}

private fun jsonEscape(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

@Composable
private fun SetupRow(vm: AppViewModel, label: String, value: String) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { dienste.kopiere(label, value) { vm.meldung(it, kurz = true) } }
            .padding(vertical = 5.dp),
    ) {
        Text(label, color = colors.muted, fontSize = 11.sp)
        Text(value, color = colors.text, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------------------
// Quelle loeschen — endgueltig, darum zwei Wege und eine Rueckfrage
// ---------------------------------------------------------------------------

@Composable
private fun DeleteSourceDialog(
    vm: AppViewModel,
    source: TrailSource,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val scope = rememberCoroutineScope()
    // false = nur die abgeleiteten Besuche/Wege im Zeitraum (Umstieg),
    // true = die ganze Quelle samt Rohpunkten (missglueckter Import).
    var wholeSource by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trails_src_delete_title, sourceLabel(source.source))) },
        text = {
            Column {
                ChoiceRow(
                    selected = !wholeSource,
                    title = stringResource(Res.string.trails_src_only_derived),
                    body = stringResource(Res.string.trails_src_only_derived_body, thousands(source.visits), thousands(source.activities)),
                ) { wholeSource = false }
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    selected = wholeSource,
                    title = stringResource(Res.string.trails_src_whole),
                    body = stringResource(Res.string.trails_src_whole_body, thousands(source.points)),
                ) { wholeSource = true }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(Res.string.trails_src_irreversible),
                    color = colors.gold, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            vm.api.trailsDeleteSource(
                                vm.baseUrl, source.source,
                                from = if (wholeSource) null else source.firstTs,
                                to = if (wholeSource) null else (source.lastTs ?: 0) + 1,
                            )
                        }
                            .onSuccess {
                                vm.meldung(Texte.t(Res.string.trails_src_deleted), kurz = true)
                            }
                            .onFailure {
                                vm.meldung(trailsErrorText(it))
                            }
                        busy = false
                        onDismiss()
                        onDone()
                    }
                },
            ) { Text(if (busy) "…" else stringResource(Res.string.trails_src_delete_final)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                1.dp, if (selected) colors.blue else colors.border, RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Icon(
            if (selected) MpdIcons.RadioOn else MpdIcons.RadioOff,
            if (selected) stringResource(Res.string.trails_choice_selected) else stringResource(Res.string.trails_choice_unselected),
            Modifier.size(18.dp), tint = colors.blue,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(body, color = colors.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Herunterladen
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// Kleinteile
// ---------------------------------------------------------------------------

/**
 * Abschnitt mit Klapp-Kopfzeile. Der Geraete-Tab traegt dreierlei —
 * Aufzeichnung, Quellen, Archiv —, und untereinander ergab das eine Wand.
 * Zugeklappt steht der Kurzstand in der Kopfzeile, man sieht also auch
 * dann, was drinsteckt. Offen ist zu Beginn nur die Aufzeichnung: das ist
 * der Grund, aus dem man hierherkommt.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String? = null,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Mpd.colors
    var open by remember { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = !open }
                .padding(top = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            if (!open && !summary.isNullOrBlank()) {
                Text(
                    summary, color = colors.muted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Icon(
                if (open) MpdIcons.ChevronDown else MpdIcons.ChevronRight,
                if (open) stringResource(Res.string.trails_collapse) else stringResource(Res.string.trails_expand),
                Modifier.size(18.dp), tint = colors.muted,
            )
        }
        if (open) content()
    }
}

@Composable
private fun DialogField(label: String, value: String, onChange: (String) -> Unit) {
    val colors = Mpd.colors
    Column {
        Text(label, color = colors.muted, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.blue),
            // Siehe ShareField: ohne Modifier faengt nur der Text die Tipper.
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceAlt)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) { inner() }
            },
        )
    }
}

private fun epochText(seconds: Double): String =
    Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.systemDefault()).format(DMY_HM)

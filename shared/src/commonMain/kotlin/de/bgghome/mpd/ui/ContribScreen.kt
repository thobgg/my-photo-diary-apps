package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppState
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.ContribEntry
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.coroutines.launch
import de.bgghome.mpd.api.contribCreate
import de.bgghome.mpd.api.contribList
import de.bgghome.mpd.api.contribRevoke

/*
 * Beisteuern — Gastlinks, mit denen andere Fotos in einen Sammelordner
 * hochladen, ohne Konto und ohne App.
 *
 * Fuer diesen Bereich gibt es KEINE Web-Vorlage: frontend/contrib.html
 * ist die Gastseite, eine Besitzer-Verwaltung existiert im Web nicht.
 * Die App ist hier erster Client (wie bei Trails), Vorlage war der
 * Vertrag vom 03.09.2026 in docs/API_CHANGES.md.
 *
 * Das Ereignis-Modell des Servers ist bewusst eng, und der Bildschirm
 * sagt das auch: Gaeste liefern Rohmaterial in einen SAMMELORDNER, nie
 * in ein Album (Ziel mit album.json → 400). Einsortiert wird danach
 * ganz normal ueber den Zeitstrahl. Deshalb steht hier „Sammelordner"
 * und nicht „Album" — der Name ist die halbe Erklaerung.
 */

private val EXPIRY_DAY_CHOICES = listOf(7, 14, 30, 90, 365)
private val MAX_FILE_CHOICES = listOf(100, 500, 2000)

@Composable
fun ContribScreen(vm: AppViewModel) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val linkLabel = stringResource(Res.string.contrib_link_label)
    val scope = rememberCoroutineScope()

    var contribs by remember { mutableStateOf<List<ContribEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var freshUrl by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf<ContribEntry?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadTick) {
        runCatching { vm.api.contribList(vm.baseUrl) }
            .onSuccess {
                contribs = it
                error = null
                // Token zu Links wegwerfen, die es nicht mehr gibt
                vm.linkTokens.prune(SettingsStore.LinkArt.CONTRIB, it.map { e -> e.tokenHash })
            }
            .onFailure { error = shareErrorText(it) }
    }

    MpdScreen(
        backDescription = stringResource(Res.string.contrib_close),
        onClose = { vm.contribOpen = false },
        headerPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        header = { ScreenTitle(stringResource(Res.string.contrib_title), size = 20.sp) },
    ) {

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .rubberBandOverscroll()
                .padding(horizontal = 14.dp),
        ) {
            Text(
                stringResource(Res.string.contrib_intro),
                color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
            )

            freshUrl?.let { url ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.bluePale)
                        .padding(10.dp),
                ) {
                    Text(stringResource(Res.string.contrib_fresh_title), color = colors.text, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)

                    // QR-Code: der einzige Weg, der ohne Konto, ohne App und
                    // ohne Tippen beim Gast ankommt. Wer auf einer Feier
                    // steht, diktiert keine URL. Weisser Grund und schwarze
                    // Module unabhaengig vom Theme — ein invertierter Code
                    // wird von vielen Kameras nicht gelesen.
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(QrPaper)
                            .padding(8.dp),
                    ) {
                        QrCodeImage(url, modifier = Modifier.size(200.dp))
                    }
                    if (isLocalOnlyAddress(vm.publicBaseUrl)) {
                        // Ehrlich sein, statt einen Code zu zeigen, der beim
                        // Gast stumm scheitert. Herstellen kann die App die
                        // Erreichbarkeit nicht — benennen schon.
                        Text(
                            stringResource(Res.string.contrib_qr_local_only),
                            color = colors.gold, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .padding(bottom = 8.dp),
                        )
                    } else {
                        Text(
                            stringResource(Res.string.contrib_qr_hint),
                            color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                                .padding(bottom = 8.dp),
                        )
                    }

                    Text(url, color = colors.text, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinePill(stringResource(Res.string.contrib_copy), icon = MpdIcons.Link) { dienste.kopiere(linkLabel, url) { vm.meldung(it, kurz = true) } }
                        OutlinePill(stringResource(Res.string.contrib_share), icon = MpdIcons.Share) { dienste.teileText(url) { vm.meldung(it, kurz = true) } }
                    }
                    Text(
                        stringResource(Res.string.contrib_fresh_hint),
                        color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.contrib_active_links), color = colors.text, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold)

            LoadState(
                error = error, data = contribs,
                isEmpty = { it.isEmpty() },
                errorContent = {
                    Text(
                        it, color = MpdRed, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                },
                loading = { LoadingCenter(Modifier.fillMaxWidth().height(60.dp), size = 24.dp) },
                empty = {
                    Text(
                        stringResource(Res.string.contrib_empty),
                        color = colors.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                },
            ) { list ->
                list.forEach { entry ->
                    val bekannt = vm.linkTokens.tokenFor(SettingsStore.LinkArt.CONTRIB, entry.tokenHash)
                    ContribRow(
                        meldung = { vm.meldung(it, kurz = true) },
                        entry = entry,
                        url = bekannt?.let { vm.publicBaseUrl + "/contrib/" + it },
                        onShowQr = {
                            qrUrl = vm.publicBaseUrl + "/contrib/" + bekannt
                        },
                        onRevoke = { revoke = entry },
                    )
                }
            }

            AccentButton(stringResource(Res.string.contrib_create_button), topPadding = 12.dp) { createOpen = true }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (createOpen) {
        CreateContribDialog(
            vm,
            onDismiss = { createOpen = false },
            onCreated = { url -> createOpen = false; freshUrl = url; reloadTick++ },
        )
    }
    // QR eines bestehenden Links — moeglich, weil dieses Geraet den
    // Klartext-Token gemerkt hat (der Server kennt nur den Hash).
    qrUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { qrUrl = null },
            title = { Text(stringResource(Res.string.contrib_qr_title)) },
            text = {
                Column {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(6.dp))
                            .background(QrPaper)
                            .padding(8.dp),
                    ) {
                        QrCodeImage(url, modifier = Modifier.size(220.dp))
                    }
                    if (isLocalOnlyAddress(vm.publicBaseUrl)) {
                        Text(
                            stringResource(Res.string.contrib_qr_dialog_local_only),
                            color = Mpd.colors.gold, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { dienste.teileText(url) { vm.meldung(it, kurz = true) } }) { Text(stringResource(Res.string.contrib_share)) }
            },
            dismissButton = { TextButton(onClick = { qrUrl = null }) { Text(stringResource(Res.string.contrib_close_dialog)) } },
        )
    }

    revoke?.let { entry ->
        ConfirmDialog(
            title = stringResource(Res.string.contrib_revoke_title),
            body = stringResource(Res.string.contrib_revoke_body, entry.folderName, entry.uploaded),
            confirmLabel = stringResource(Res.string.contrib_revoke),
            onDismiss = { revoke = null },
        ) {
            val hash = entry.tokenHash
            revoke = null
            scope.launch {
                runCatching { vm.api.contribRevoke(vm.baseUrl, hash) }
                    .onSuccess {
                        // Der Token hat hier nichts mehr zu suchen
                        vm.linkTokens.forget(SettingsStore.LinkArt.CONTRIB, hash)
                    }
                    .onFailure {
                        vm.meldung(shareErrorText(it))
                    }
                reloadTick++
            }
        }
    }
}

@Composable
private fun ContribRow(
    meldung: (String) -> Unit,
    entry: ContribEntry,
    url: String?,
    onShowQr: () -> Unit,
    onRevoke: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val expired = entry.expiresAt != null && entry.expiresAt!! * 1000 < System.currentTimeMillis()
    val full = entry.maxFiles > 0 && entry.uploaded >= entry.maxFiles

    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spaceShared = stringResource(Res.string.contrib_space_shared)
        val spacePersonal = stringResource(Res.string.contrib_space_personal)
        val rowFiles = stringResource(Res.string.contrib_row_files, entry.uploaded, entry.maxFiles)
        val expiredText = stringResource(Res.string.contrib_expired)
        val untilTemplate = stringResource(Res.string.contrib_until, "%s")
        val untilText: (String) -> String = { untilTemplate.format(it) }
        Column(Modifier.weight(1f)) {
            Text(
                entry.folderName,
                color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(if (entry.space == "shared") spaceShared else spacePersonal)
                    append(" · ").append(rowFiles)
                    entry.expiresAt?.let {
                        append(" · ")
                        append(if (expired) expiredText else untilText(epochDate(it)))
                    }
                },
                color = when {
                    expired -> colors.gold
                    full -> colors.gold
                    else -> colors.muted
                },
                fontSize = 12.sp,
            )
            if (full && !expired) {
                Text(
                    stringResource(Res.string.contrib_full),
                    color = colors.gold, fontSize = 11.sp,
                )
            }
        }
        // Nur fuer Links, die dieses Geraet angelegt hat: der Klartext-Token
        // liegt dann lokal, sonst kennt ihn niemand mehr.
        if (url != null && !expired) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onShowQr),
                contentAlignment = Alignment.Center,
            ) { Icon(MpdIcons.QrCode, stringResource(Res.string.contrib_show_qr), Modifier.size(20.dp), tint = colors.text) }
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .clickable { dienste.teileText(url, meldung) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MpdIcons.Link, stringResource(Res.string.contrib_share_link),
                    Modifier.size(18.dp), tint = colors.text,
                )
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRevoke),
            contentAlignment = Alignment.Center,
        ) { Icon(MpdIcons.Trash, stringResource(Res.string.contrib_withdraw), Modifier.size(18.dp), tint = MpdRed) }
    }
}

@Composable
private fun CreateContribDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val colors = Mpd.colors
    val scope = rememberCoroutineScope()
    val role = (vm.state as? AppState.LoggedIn)?.who?.role
    // Shared darf nur anlegen, wer kuratiert; die letzte Pruefung macht
    // ohnehin der Server, hier ist es nur die freundlichere Vorauswahl.
    // Beisteuern-Links haengen am KURATOR-Recht, nicht am Beitrags-Recht
    // (share.py prueft is_readonly) — im Familienbestand also nur der
    // Verwalter, obwohl ein Mitwirkender dort teilen darf. Kein
    // Widerspruch, sondern Absicht: ein Freigabelink laesst hineinsehen,
    // ein Beisteuern-Link laesst herein. Bestaetigt von der Server-Seite,
    // 06.09.2026. Wer den Bereich nicht darf, bekommt ihn nicht angeboten.
    // Beisteuern-Links haengen am Kurator-Recht — der Server fuehrt das
    // seit 3.0.272 als eigenes Recht `contrib_link`, statt es aus der Rolle
    // ableiten zu lassen.
    val mayShared = vm.canContribLink("shared")
    val mayPersonal = vm.canContribLink("personal")
    var space by remember {
        mutableStateOf(if (mayShared) "shared" else "personal")
    }
    var folder by remember { mutableStateOf("") }
    // Der Name ist Pflicht, und ohne ihn bleibt „Anlegen" grau. Statt darauf
    // zu hoffen, dass jemand das Feld findet, springt der Fokus beim Oeffnen
    // hinein — Tastatur da, Cursor blinkt, die Sache ist selbsterklaerend.
    val folderFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { folderFocus.requestFocus() } }
    var days by remember { mutableStateOf(14) }
    var maxFiles by remember { mutableStateOf(500) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dienste = LocalSystemDienste.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.contrib_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ShareField(
                    stringResource(Res.string.contrib_folder_name), folder, stringResource(Res.string.contrib_folder_hint),
                    focusRequester = folderFocus,
                ) {
                    // Ordnername: der Server legt ihn wirklich an, also
                    // keine Pfadtrenner und keine Spielereien.
                    folder = it.replace(Regex("""[/\\:*?"<>|]"""), "").take(80)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(Res.string.contrib_folder_note),
                    color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp,
                )

                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.contrib_section_space), color = colors.muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (mayShared) {
                        ChoicePill(stringResource(Res.string.contrib_space_shared), space == "shared", compact = true) { space = "shared" }
                    }
                    if (mayPersonal) {
                        ChoicePill(stringResource(Res.string.contrib_space_personal), space == "personal", compact = true) { space = "personal" }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.contrib_section_validity), color = colors.muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EXPIRY_DAY_CHOICES.forEach { d ->
                        ChoicePill(stringResource(Res.string.contrib_days_short, d), days == d, compact = true) { days = d }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.contrib_section_max), color = colors.muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MAX_FILE_CHOICES.forEach { n ->
                        ChoicePill("$n", maxFiles == n, compact = true) { maxFiles = n }
                    }
                }

                error?.let {
                    Text(it, color = MpdRed, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && folder.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            vm.api.contribCreate(vm.baseUrl, space, folder, days, maxFiles)
                        }
                            .onSuccess { created ->
                                // Token merken, sonst ist der QR-Code weg,
                                // sobald man diesen Bildschirm verlaesst.
                                vm.linkTokens.store(
                                    SettingsStore.LinkArt.CONTRIB, created.token,
                                )
                                // url kommt relativ ("/contrib/<token>")
                                onCreated(vm.publicBaseUrl + created.url)
                            }
                            .onFailure { error = shareErrorText(it) }
                        busy = false
                    }
                },
            ) {
                Text(
                    when {
                        busy -> "…"
                        // Ein ausgegrauter Knopf ohne Begruendung ist die
                        // schlechteste Rueckmeldung: er sagt „geht nicht"
                        // statt „hier fehlt noch etwas".
                        folder.isBlank() -> stringResource(Res.string.contrib_name_missing)
                        else -> stringResource(Res.string.contrib_create)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

package de.bgghome.mpd.ui

import org.jetbrains.compose.resources.StringResource

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.width
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.data.SettingsStore
import de.bgghome.mpd.api.MpdHttpException
import de.bgghome.mpd.api.ShareContact
import de.bgghome.mpd.api.ShareEntry
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import de.bgghome.mpd.api.contacts
import de.bgghome.mpd.api.shareCreate
import de.bgghome.mpd.api.shareList
import de.bgghome.mpd.api.shareRevoke
import de.bgghome.mpd.api.shareSendEmail
import de.bgghome.mpd.meldung

/*
 * Teilen, Besitzer-Seite — Nachbau von frontend/js/album-share.js.
 *
 * Der Server-Vertrag gibt die Form vor: der Klartext-Token existiert NUR
 * in der Antwort auf create, die Liste kennt bloss Hashes. Also kann man
 * an einem bestehenden Link nur eines tun — ihn widerrufen. Kopieren,
 * Weitergeben und E-Mail gibt es ausschliesslich am frisch erzeugten
 * Link. Das Web loest es genauso; der Hinweis darauf steht bei uns wie
 * dort direkt unter dem neuen Link.
 *
 * Derselbe Dialog dient Album- und Einzelfoto-Shares (Teilen v3): ist
 * `file` gesetzt, geht es um genau dieses Foto — die Liste zeigt dann
 * nur dessen Links.
 *
 * Android-Zutat gegenueber dem Web: „Weitergeben" oeffnet das
 * System-Teilen-Blatt. Das ist hier der natuerliche Weg zu WhatsApp,
 * Signal und Mail — die Web-Schaltflaeche „Vorschau in neuem Tab"
 * ergibt am Handy dagegen wenig Sinn und entfaellt.
 */

private val SHARE_DT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val SHARE_D: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Gueltigkeitsdauern wie im Web-Modal. */
private val EXPIRY_CHOICES = listOf<Pair<Int?, StringResource>>(
    null to Res.string.share_expiry_unlimited,
    7 to Res.string.share_expiry_7,
    30 to Res.string.share_expiry_30,
    365 to Res.string.share_expiry_365,
)

@Composable
fun ShareDialog(
    vm: AppViewModel,
    space: String,
    album: String,
    albumTitle: String,
    /** null = ganzes Album, sonst Einzelfoto-Share (Teilen v3). */
    file: String? = null,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val linkLabel = stringResource(Res.string.share_link_label)
    val revokedToast = stringResource(Res.string.share_revoked_toast)
    val scope = rememberCoroutineScope()

    var shares by remember { mutableStateOf<List<ShareEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Voreinstellung 30 Tage, nicht „unbegrenzt": der Standard ist die
    // Entscheidung — geaendert wird er so gut wie nie. Ein Link, an den
    // niemand mehr denkt, geht so von selbst zu; wer ihn dauerhaft will,
    // sagt es ausdruecklich. (Das Web steht noch auf unbegrenzt, dem
    // Server-Chat gemeldet.)
    var expiryDays by remember { mutableStateOf<Int?>(30) }
    var expiryMenu by remember { mutableStateOf(false) }
    /** Frisch erzeugter Link — nur jetzt im Klartext verfuegbar. */
    var freshUrl by remember { mutableStateOf<String?>(null) }
    var freshToken by remember { mutableStateOf<String?>(null) }
    var mailOpen by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        runCatching { vm.api.shareList(vm.baseUrl, space, album) }
            .onSuccess { list ->
                // Einzelfoto-Dialog zeigt nur die Links DIESES Fotos,
                // der Albumdialog nur die des ganzen Albums.
                shares = list.filter { if (file == null) it.file == null else it.file == file }
                error = null
                // Aufraeumen gegen die UNGEFILTERTE Antwort: die gefilterte
                // Liste kennt nur einen Ausschnitt, danach zu putzen wuerde
                // fremde Token wegwerfen.
                vm.linkTokens.prune(SettingsStore.LinkArt.SHARE, list.map { it.tokenHash })
            }
            .onFailure { error = shareErrorText(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (file != null) stringResource(Res.string.share_title_photo)
                else stringResource(Res.string.share_title_album, albumTitle),
                fontSize = 17.sp,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (file != null) stringResource(Res.string.share_intro_photo)
                    else stringResource(Res.string.share_intro_album),
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )

                freshUrl?.let { url ->
                    FreshLinkBlock(
                        url = url,
                        onCopy = { dienste.kopiere(linkLabel, url) { vm.meldung(it, kurz = true) } },
                        onShare = { dienste.teileText(url) { vm.meldung(it, kurz = true) } },
                        onMail = { mailOpen = true },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.share_active_links), color = colors.text, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium)

                LoadState(
                    error = error, data = shares,
                    isEmpty = { it.isEmpty() },
                    errorContent = {
                        Text(
                            it, color = MpdRed, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    },
                    loading = { LoadingCenter(Modifier.fillMaxWidth().height(52.dp), size = 22.dp) },
                    empty = {
                        Text(
                            stringResource(Res.string.share_empty),
                            color = colors.muted, fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    },
                ) { list ->
                    list.forEach { entry ->
                        ShareRow(
                            vm,
                            entry,
                            busy = revoking == entry.tokenHash,
                            url = vm.linkTokens.tokenFor(SettingsStore.LinkArt.SHARE, entry.tokenHash)
                                ?.let { vm.publicBaseUrl + "/share/" + it },
                        ) {
                            revoking = entry.tokenHash
                            scope.launch {
                                runCatching { vm.api.shareRevoke(vm.baseUrl, entry.tokenHash) }
                                    .onSuccess {
                                        vm.linkTokens.forget(
                                            SettingsStore.LinkArt.SHARE, entry.tokenHash,
                                        )
                                        vm.meldung(revokedToast, kurz = true)
                                    }
                                    .onFailure {
                                        vm.meldung(shareErrorText(it))
                                    }
                                revoking = null
                                reloadTick++
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                            .clickable { expiryMenu = true }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(EXPIRY_CHOICES.first { it.first == expiryDays }.second),
                            color = colors.text, fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(MpdIcons.ChevronDown, stringResource(Res.string.share_expiry_pick), Modifier.size(16.dp), tint = colors.text)
                    }
                    DropdownMenu(expanded = expiryMenu, onDismissRequest = { expiryMenu = false }) {
                        EXPIRY_CHOICES.forEach { (days, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = { expiryDays = days; expiryMenu = false },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            runCatching {
                                vm.api.shareCreate(vm.baseUrl, space, album, file, expiryDays)
                            }
                                .onSuccess { created ->
                                    freshToken = created.token
                                    freshUrl = vm.publicBaseUrl + "/share/" + created.token
                                    // Merken, damit der Link auch spaeter noch
                                    // kopiert und weitergegeben werden kann —
                                    // der Server kennt danach nur den Hash.
                                    vm.linkTokens.store(
                                        SettingsStore.LinkArt.SHARE, created.token,
                                    )
                                    reloadTick++
                                }
                                .onFailure {
                                    vm.meldung(shareErrorText(it))
                                }
                            busy = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.blue),
                ) {
                    if (busy) {
                        Text(stringResource(Res.string.share_creating), color = colors.textOnAccent)
                    } else {
                        IconLabel(
                            MpdIcons.Link, stringResource(Res.string.share_create),
                            color = colors.textOnAccent, iconSize = 16.dp, gap = 6.dp,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.share_close)) } },
    )

    if (mailOpen) {
        val token = freshToken
        if (token != null) {
            ShareMailDialog(vm, token, albumTitle, isPhoto = file != null) {
                mailOpen = false
            }
        }
    }
}

/** Zeile eines bestehenden Links — mehr als Widerrufen geht nicht, der
 *  Klartext ist weg (siehe Kopfkommentar). */
@Composable
private fun ShareRow(
    vm: AppViewModel,
    entry: ShareEntry,
    busy: Boolean,
    /** Nicht null, wenn dieses Geraet den Link angelegt hat und den
     *  Klartext-Token noch kennt — dann geht mehr als nur Widerrufen. */
    url: String?,
    onRevoke: () -> Unit,
) {
    val colors = Mpd.colors
    var confirm by remember { mutableStateOf(false) }
    val expired = entry.expiresAt != null && entry.expiresAt!! * 1000 < System.currentTimeMillis()

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val createdText = stringResource(Res.string.share_row_created, epochDateTime(entry.createdAt))
        val lastSeenText = entry.lastSeenAt?.let {
            stringResource(Res.string.share_row_last_seen, epochDateTime(it))
        } ?: stringResource(Res.string.share_row_never)
        val expiredText = stringResource(Res.string.share_row_expired)
        val expiresTemplate = stringResource(Res.string.share_row_expires, "%s")
        val expiresText: (String) -> String = { expiresTemplate.format(it) }
        val rowLinkLabel = stringResource(Res.string.share_link_label)
        Column(Modifier.weight(1f)) {
            Text(
                "#" + entry.tokenHash.take(10),
                color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                buildString {
                    append(createdText)
                    append(" · ")
                    append(lastSeenText)
                    entry.expiresAt?.let {
                        append(" · ")
                        append(if (expired) expiredText else expiresText(epochDate(it)))
                    }
                },
                color = if (expired) colors.gold else colors.muted,
                fontSize = 11.sp, lineHeight = 16.sp,
            )
            entry.file?.let {
                Text(it, color = colors.muted.copy(alpha = 0.7f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (url != null && !expired) {
            val dienste = LocalSystemDienste.current
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable { dienste.kopiere(rowLinkLabel, url) { vm.meldung(it, kurz = true) } },
                contentAlignment = Alignment.Center,
            ) { Icon(MpdIcons.Link, stringResource(Res.string.share_copy_link), Modifier.size(18.dp), tint = colors.text) }
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable { dienste.teileText(url) { vm.meldung(it, kurz = true) } },
                contentAlignment = Alignment.Center,
            ) { Icon(MpdIcons.Share, stringResource(Res.string.share_share), Modifier.size(18.dp), tint = colors.text) }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = !busy) { confirm = true },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) Text("…", fontSize = 15.sp)
            else Icon(MpdIcons.Trash, stringResource(Res.string.share_revoke_link), Modifier.size(18.dp), tint = MpdRed)
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(Res.string.share_revoke_title),
            body = stringResource(Res.string.share_revoke_body),
            confirmLabel = stringResource(Res.string.share_revoke),
            onDismiss = { confirm = false },
        ) { confirm = false; onRevoke() }
    }
}

/** Der frisch erzeugte Link — die einzige Gelegenheit, ihn zu bekommen. */
@Composable
private fun FreshLinkBlock(
    url: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onMail: () -> Unit,
) {
    val colors = Mpd.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.bluePale)
            .padding(10.dp),
    ) {
        Text(stringResource(Res.string.share_fresh_title), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            url,
            color = colors.text, fontSize = 12.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinePill(stringResource(Res.string.share_copy), icon = MpdIcons.Link, onClick = onCopy)
            OutlinePill(stringResource(Res.string.share_share), icon = MpdIcons.Share, onClick = onShare)
            OutlinePill(stringResource(Res.string.share_mail_pill), icon = MpdIcons.Mail, onClick = onMail)
        }
        Text(
            stringResource(Res.string.share_fresh_hint),
            color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** E-Mail-Versand ueber die Server-SMTP, mit Adressbuch-Vorschlaegen
 *  (`GET /api/contacts`) wie im Web-Untermodal. */
@Composable
private fun ShareMailDialog(
    vm: AppViewModel,
    token: String,
    albumTitle: String,
    isPhoto: Boolean,
    onDismiss: () -> Unit,
) {
    val colors = Mpd.colors
    val dienste = LocalSystemDienste.current
    val sentText = stringResource(Res.string.share_mail_sent)
    val scope = rememberCoroutineScope()
    var toEmail by remember { mutableStateOf("") }
    var toName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<List<ShareContact>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching { vm.api.contacts(vm.baseUrl) }.onSuccess { contacts = it }
    }
    val suggestions = remember(contacts, toEmail) {
        if (toEmail.isBlank()) contacts.take(5)
        else contacts.filter {
            it.email.contains(toEmail, true) || (it.name ?: "").contains(toEmail, true)
        }.take(5)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.share_mail_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    // Seit Server 2.0.246 nennt eine Einzelfoto-Mail das Album
                    // nirgends mehr — weder im Betreff noch im Text noch im
                    // Vorschaubild. Der Hinweis hier hat das mitzumachen, sonst
                    // verspricht er etwas, das nicht mehr verschickt wird.
                    if (isPhoto) stringResource(Res.string.share_mail_intro_photo)
                    else stringResource(Res.string.share_mail_intro_album, albumTitle),
                    color = colors.muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(10.dp))
                ShareField(stringResource(Res.string.share_mail_to), toEmail, stringResource(Res.string.share_mail_to_hint)) { toEmail = it.trim() }
                if (suggestions.isNotEmpty() && toEmail != suggestions.firstOrNull()?.email) {
                    Column(Modifier.heightIn(max = 132.dp).verticalScroll(rememberScrollState())) {
                        suggestions.forEach { contact ->
                            Text(
                                contact.name?.takeIf { it.isNotBlank() }
                                    ?.let { "$it · ${contact.email}" } ?: contact.email,
                                color = colors.blue, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        toEmail = contact.email
                                        if (toName.isBlank()) toName = contact.name.orEmpty()
                                    }
                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ShareField(stringResource(Res.string.share_mail_name), toName, "") { toName = it }
                Spacer(Modifier.height(8.dp))
                ShareField(stringResource(Res.string.share_mail_message), message, stringResource(Res.string.share_mail_message_hint)) {
                    message = it
                }
                error?.let {
                    Text(it, color = MpdRed, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && toEmail.contains("@"),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            vm.api.shareSendEmail(vm.baseUrl, token, toEmail, toName, message)
                        }
                            .onSuccess {
                                vm.meldung(sentText, kurz = true)
                                onDismiss()
                            }
                            .onFailure { error = shareErrorText(it) }
                        busy = false
                    }
                },
            ) { Text(if (busy) stringResource(Res.string.share_mail_sending) else stringResource(Res.string.share_mail_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
internal fun ShareField(
    label: String,
    value: String,
    placeholder: String,
    /** Zum Setzen des Anfangsfokus — ein Pflichtfeld soll man nicht suchen. */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    onChange: (String) -> Unit,
) {
    val colors = Mpd.colors
    Column {
        // 13sp statt 11sp: die Beschriftung eines Eingabefelds ist keine
        // Fussnote. Das Feld wurde schlicht uebersehen (05.09.2026)
        // und den ausgegrauten Knopf fuer einen Fehler gehalten — zu Recht.
        Text(label, color = colors.muted, fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
            cursorBrush = SolidColor(colors.blue),
            // Ohne diesen Modifier ist die BERUEHRBARE Flaeche nur so breit
            // wie der Text — bei leerem Feld also praktisch null. Die Box in
            // decorationBox ist bloss Dekoration und faengt keine Tipper.
            modifier = Modifier
                .fillMaxWidth()
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceAlt)
                        // Rahmen wie beim Anmeldefeld: ohne ihn sieht das
                        // Feld aus wie ein Hinweisbalken, nicht wie etwas,
                        // in das man schreibt.
                        .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = colors.muted, fontSize = 14.sp)
                    }
                    inner()
                }
            },
        )
    }
}

// -- Kleinkram --------------------------------------------------------------



// Sekunden kommen als Kommazahl vom Server (time.time()) — siehe die
// Warnung in api/ShareData.kt.
internal fun epochDateTime(seconds: Double?): String =
    seconds?.let {
        Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault()).format(SHARE_DT)
    } ?: "—"

internal fun epochDate(seconds: Double): String =
    Instant.ofEpochSecond(seconds.toLong()).atZone(ZoneId.systemDefault()).format(SHARE_D)

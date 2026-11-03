package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.pluralStringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.WhoAmI
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.compose.resources.stringResource

/*
 * Einstellungen — natives Gegenstueck zum Einstellungen-Modal des Web
 * (index.html _openSettingsModal): Sektion „Fuer dich" fuer alle,
 * „Global"/„Write Companion" nur fuer Admins (POST /api/settings ist
 * serverseitig ohnehin admin-only). Als eigener Screen statt Modal —
 * Handy-Muster, die Web-Referenz ist Desktop-gepraegt.
 */

private val COMPANION_PROVIDERS = listOf(
    "anthropic" to "Anthropic (Claude)",
    "groq" to "Groq (Llama)",
    "mistral" to "Mistral",
    "gemini" to "Google Gemini Flash",
)

@Composable
fun SettingsScreen(vm: AppViewModel, who: WhoAmI) {
    val colors = Mpd.colors

    // Vor allem anderen: der Passwort-Wechsel legt sich als eigener Screen
    // darueber. Frueher Ausstieg, damit sich die beiden Zurueck-Handler
    // nicht in die Quere kommen.
    if (vm.passwordChange.open) {
        PasswordChangeScreen(vm = vm, who = who, forced = false) {
            if (vm.passwordChange.done) {
                vm.meldung(Texte.t(Res.string.settings_pw_changed), kurz = true)
                vm.passwordChange.done = false
            }
            vm.passwordChange.open = false
        }
        return
    }

    LaunchedEffect(Unit) { vm.loadAppSettings(); vm.loadUserSettings() }

    val isAdmin = who.role == "admin"
    val app = vm.appSettings

    MpdScreen(
        backDescription = stringResource(Res.string.settings_close),
        onClose = { vm.settingsOpen = false },
        header = { ScreenTitle(stringResource(Res.string.settings_title)) },
    ) {

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SettingsSection(stringResource(Res.string.settings_section_you))
            // Nur mit Geraetefotos (Android): Backup, Speicher freigeben,
            // abgelehnte Aufnahmen, Geraeteordner. Der Desktop hat das nicht.
            if (vm.plattform.hatGeraetefotos) {
                SettingsActionRow(
                    icon = MpdIcons.CloudUpload,
                    name = stringResource(Res.string.backup_title),
                    hint = stringResource(Res.string.settings_backup_hint),
                    action = stringResource(Res.string.common_open),
                ) { vm.backupDialogOpen = true }
                // Speicherplatz freigeben (08.09.2026): Google Fotos' Kernhandgriff.
                // Nur Aufnahmen, die nachweislich auf der NAS liegen (dieselbe
                // Rechnung wie der gemischte Zeitstrahl), in den Papierkorb des
                // Geraets — die Rueckfrage stellt danach Android, endgueltig
                // wird es erst beim Leeren des Papierkorbs.
                LaunchedEffect(Unit) { if (vm.localItems.isEmpty()) vm.loadLocalMedia() }
                val frei = vm.gesichertAufGeraet
                var freiConfirm by remember { mutableStateOf(false) }
                val freiGroesse = humanBytes(vm.gesichertBytes)
                SettingsActionRow(
                    icon = MpdIcons.Trash,
                    name = stringResource(Res.string.settings_free_space_title),
                    hint = if (frei.isEmpty()) stringResource(Res.string.settings_free_space_none)
                    else pluralStringResource(Res.plurals.settings_free_space_hint_n, frei.size, frei.size, freiGroesse),
                    action = stringResource(Res.string.settings_free_space_action),
                    enabled = frei.isNotEmpty(),
                ) { freiConfirm = true }
                if (freiConfirm) {
                    ConfirmDialog(
                        title = stringResource(Res.string.settings_free_space_confirm_title),
                        body = pluralStringResource(Res.plurals.settings_free_space_confirm_n, frei.size, frei.size, freiGroesse),
                        confirmLabel = stringResource(Res.string.settings_free_space_action),
                        onDismiss = { freiConfirm = false },
                    ) {
                        freiConfirm = false
                        vm.speicherFreigeben()
                    }
                }
                // Abgelehnte Aufnahmen — nur wenn es welche gibt. Der Server
                // nimmt nicht jedes Format an (RAW etwa gar nicht); bis zum
                // 07.09.2026 wurde so eine Aufnahme bei JEDEM Lauf erneut
                // hochgeladen und wieder abgewiesen, und der Fehlertext dazu
                // verdeckte alles andere. Jetzt merkt die App sich die Absage
                // — und zeigt sie, statt sie zu verschlucken.
                var abgelehnt by remember {
                    mutableStateOf(vm.plattform.abgelehnteUploads())
                }
                if (abgelehnt.isNotEmpty()) {
                    SettingsActionRow(
                        name = stringResource(Res.string.settings_rejected_n, abgelehnt.size),
                        hint = stringResource(
                            Res.string.settings_rejected_hint,
                            abgelehnt.take(3).joinToString(", ") + (if (abgelehnt.size > 3) " …" else ""),
                        ),
                        action = stringResource(Res.string.settings_retry),
                    ) {
                        vm.plattform.abgelehnteErneut()
                        abgelehnt = emptyList()
                        vm.meldung(Texte.t(Res.string.settings_retry_done), kurz = true)
                    }
                }
                SettingsActionRow(
                    icon = MpdIcons.Smartphone,
                    name = stringResource(Res.string.folders_title),
                    hint = stringResource(Res.string.settings_folders_hint),
                    action = stringResource(Res.string.common_open),
                ) { vm.timelineFoldersOpen = true }
            }
            if (vm.plattform.hatLokaleOrdner) {
                SettingsActionRow(
                    icon = MpdIcons.Folder,
                    name = stringResource(Res.string.pc_folders_title),
                    hint = stringResource(Res.string.settings_pc_folders_hint),
                    action = stringResource(Res.string.common_open),
                ) { vm.timelineFoldersOpen = true }
            }
            SettingsActionRow(
                icon = MpdIcons.Palette,
                name = stringResource(Res.string.settings_theme),
                hint = stringResource(Res.string.settings_theme_hint),
                action = stringResource(Res.string.common_open),
            ) { vm.themePickerOpen = true }
            MemoriesThemeRow(vm)
            MemoriesScopeRow(vm)
            if (!who.isDemo) {
                // Demo-Konten weist der Server mit 403 ab — dann gar nicht
                // erst anbieten.
                SettingsActionRow(
                    icon = MpdIcons.Key,
                    name = stringResource(Res.string.pw_title),
                    hint = stringResource(Res.string.settings_pw_hint),
                    action = stringResource(Res.string.common_open),
                ) { vm.passwordChange.open = true }
            }

            if (isAdmin) {
                SettingsSection(stringResource(Res.string.settings_section_admin), badge = stringResource(Res.string.settings_badge_admin))
                SettingsActionRow(
                    icon = MpdIcons.Users,
                    name = stringResource(Res.string.users_title),
                    hint = stringResource(Res.string.settings_users_hint),
                    action = stringResource(Res.string.common_open),
                ) { vm.users.open = true }

                SettingsSection(stringResource(Res.string.settings_section_global), badge = stringResource(Res.string.settings_badge_admin))
                SettingsSwitchRow(
                    name = stringResource(Res.string.settings_share),
                    hint = stringResource(Res.string.settings_share_hint),
                    checked = app?.shareEnabled == true,
                    enabled = app != null,
                ) { on -> vm.saveSetting("share_enabled", JsonPrimitive(on)) }
                SettingsSwitchRow(
                    name = stringResource(Res.string.settings_demo),
                    hint = stringResource(Res.string.settings_demo_hint),
                    checked = app?.demoEnabled == true,
                    enabled = app != null,
                ) { on -> vm.saveSetting("demo_enabled", JsonPrimitive(on)) }
                SettingsSwitchRow(
                    name = stringResource(Res.string.settings_memories_push),
                    hint = stringResource(Res.string.settings_memories_push_hint),
                    checked = app?.memoriesPushEnabled == true,
                    enabled = app != null,
                ) { on -> vm.saveSetting("memories_push_enabled", JsonPrimitive(on)) }

                SettingsSection("Write Companion", badge = stringResource(Res.string.settings_badge_admin))
                CompanionEditor(vm)

            }

            // Fusszeile: Version
            val version = LocalSystemDienste.current.versionName
            // Branding wie der Ueber-Dialog des Web (index.html _openAboutDialog)
            Text(
                "My Photo Diary $version",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "where your memories stay yours.",
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "© 2026 Thomas Bugge",
                color = colors.muted,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Speichern-Kurzweg mit Rueckmeldung wie im Web (mpdToast) — als Snackbar. */
private fun AppViewModel.saveSetting(key: String, value: JsonPrimitive) {
    updateAppSettings(buildJsonObject { put(key, value) }) { err ->
        if (err != null) meldung(t(Res.string.settings_err_x, err))
    }
}

/** Farbwelt der Memories-Pinnwand — bewusst getrennt vom App-Theme
 *  (zwei verschiedene Dinge, Thomas 03.09.); sass vorher als zweite
 *  Kopfzeile im Memories-Tab und machte das Handy hochkant zu voll. */
@Composable
private fun MemoriesThemeRow(vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    SettingsRowScaffold(
        name = stringResource(Res.string.settings_memories_theme),
        hint = stringResource(Res.string.settings_memories_theme_hint),
        icon = MpdIcons.Image,
    ) {
        Box {
            TextButton(onClick = { menu = true }) {
                Text(MEMORIES_THEME_LABELS[vm.memories.theme] ?: "🎨 Auto")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MEMORIES_THEME_LABELS.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { menu = false; vm.memories.chooseTheme(key) },
                    )
                }
            }
        }
    }
}

/** „Erinnerungen aus": Familienalben / Meine Alben (PUT /api/settings/me). */
@Composable
private fun MemoriesScopeRow(vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    val scope = vm.userSettings?.memoriesScope
    SettingsRowScaffold(
        name = stringResource(Res.string.settings_memories_scope),
        hint = stringResource(Res.string.settings_memories_scope_hint),
        icon = MpdIcons.History,
    ) {
        Box {
            TextButton(enabled = scope != null, onClick = { menu = true }) {
                Text(
                    when (scope) {
                        null -> "…"
                        "personal" -> stringResource(Res.string.settings_scope_personal)
                        "both" -> stringResource(Res.string.settings_scope_both)
                        else -> stringResource(Res.string.settings_scope_shared)
                    },
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(
                    "both" to stringResource(Res.string.settings_scope_both),
                    "shared" to stringResource(Res.string.settings_scope_shared),
                    "personal" to stringResource(Res.string.settings_scope_personal),
                ).forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            if (key != scope) {
                                vm.setMemoriesScope(key) { err ->
                                    if (err != null) {
                                        vm.meldung(err)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(label: String, badge: String? = null) {
    val colors = Mpd.colors
    Row(
        Modifier.padding(start = 18.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(),
            color = colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                badge,
                color = colors.gold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SettingsRowScaffold(
    name: String,
    hint: String,
    /** Bedien-Symbol vor dem Namen (Regel 08.09.2026: kein Emoji im String). */
    icon: ImageVector? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = Mpd.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            if (icon != null) {
                IconLabel(
                    icon, name, color = colors.text, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, iconSize = 18.dp,
                )
            } else {
                Text(name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Text(hint, color = colors.muted, fontSize = 12.sp)
        }
        trailing()
    }
}

@Composable
private fun SettingsActionRow(
    name: String,
    hint: String,
    action: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsRowScaffold(name, hint, icon) {
        TextButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun SettingsInfoRow(name: String, hint: String) {
    SettingsRowScaffold(name, hint) {}
}

@Composable
private fun SettingsSwitchRow(
    name: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SettingsRowScaffold(name, hint) {
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** KI-Provider + API-Key (Web: Sektion „Write Companion"). */
@Composable
private fun CompanionEditor(vm: AppViewModel) {
    val colors = Mpd.colors
    val app = vm.appSettings
    var providerMenu by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }

    SettingsRowScaffold(
        stringResource(Res.string.settings_companion_provider),
        stringResource(Res.string.settings_companion_provider_hint),
    ) {
        Box {
            TextButton(onClick = { providerMenu = true }) {
                Text(COMPANION_PROVIDERS.firstOrNull { it.first == app?.companionProvider }?.second
                    ?: (app?.companionProvider ?: "…"))
            }
            DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                COMPANION_PROVIDERS.forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            providerMenu = false
                            vm.saveSetting("companion_provider", JsonPrimitive(key))
                        },
                    )
                }
            }
        }
    }

    val hintText = app?.companionApiKeyHint.orEmpty()
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        Text(
            if (hintText.isNotBlank()) stringResource(Res.string.settings_key_set_x, hintText) else stringResource(Res.string.settings_key_none),
            color = colors.muted,
            fontSize = 12.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(Res.string.settings_key_placeholder)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TextButton(
                enabled = keyInput.isNotBlank(),
                onClick = {
                    vm.updateAppSettings(
                        buildJsonObject { put("companion_api_key", JsonPrimitive(keyInput)) },
                    ) { err ->
                        vm.meldung(
                            if (err == null) Texte.t(Res.string.settings_key_saved)
                            else Texte.t(Res.string.settings_err_x, err),
                            kurz = true,
                        )
                        if (err == null) keyInput = ""
                    }
                },
            ) { Text(stringResource(Res.string.common_save)) }
        }
    }
}

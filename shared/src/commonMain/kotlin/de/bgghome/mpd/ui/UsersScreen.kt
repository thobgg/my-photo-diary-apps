package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*
import de.bgghome.mpd.state.Texte

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.AdminUser
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.api.adminCreateUser
import de.bgghome.mpd.api.adminDeleteUser
import de.bgghome.mpd.api.adminPatchUser
import de.bgghome.mpd.meldung

/*
 * Benutzerverwaltung — natives Gegenstueck zum „Benutzer"-Modal des Web
 * (index.html _openUserAdminModal, API routers/users_admin.py). Die
 * Schutzregeln (nicht sich selbst loeschen, letzten Admin nicht
 * degradieren/loeschen, Passwort ≥ 12) prueft der Server; die App
 * reicht Fehlertexte als Snackbar durch.
 */

private val ROLE_LABELS = listOf(
    "admin" to Res.string.role_admin,
    "editor" to Res.string.role_editor,
    "viewer" to Res.string.role_viewer,
)

/**
 * Was eine Rolle darf, in einem Satz — an der Stelle, an der man sie
 * vergibt (06.09.2026: „was bei den Rechten und Rechtserteilung").
 * Vorher stand dort nur der Name, und man musste wissen oder ausprobieren,
 * was daraus folgt.
 *
 * Gekuerzt aus routers/deps.py: is_readonly (kuratieren) und
 * can_contribute (beitragen); im gemeinsamen Bereich gestaltet nur der
 * Admin, im eigenen jeder ausser dem Betrachter.
 */
private val ROLE_HINTS = mapOf(
    "admin" to Res.string.role_hint_admin,
    "editor" to Res.string.role_hint_editor,
    "viewer" to Res.string.role_hint_viewer,
)

@Composable
private fun roleLabel(role: String): String =
    ROLE_LABELS.firstOrNull { it.first == role }?.let { stringResource(it.second) } ?: role

@Composable
fun UsersScreen(vm: AppViewModel) {
    val colors = Mpd.colors
    LaunchedEffect(Unit) { vm.users.load() }

    var newUserDialog by remember { mutableStateOf(false) }
    var passwordFor by remember { mutableStateOf<String?>(null) }
    var deleteFor by remember { mutableStateOf<String?>(null) }

    val data = vm.users.list

    MpdScreen(
        backDescription = stringResource(Res.string.users_close),
        onClose = { vm.users.open = false },
        header = {
            ScreenTitle(stringResource(Res.string.users_title), modifier = Modifier.weight(1f))
            TextButton(onClick = { newUserDialog = true }) { Text(stringResource(Res.string.users_new)) }
        },
    ) {
        LoadState(
            error = null, data = data,
            errorContent = {},
            loading = { LoadingCenter() },
        ) { users ->
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(users.users, key = { it.username }) { user ->
                    UserCard(
                        vm = vm,
                        user = user,
                        isSelf = user.username == users.self,
                        onPassword = { passwordFor = user.username },
                        onDelete = { deleteFor = user.username },
                    )
                }
                item {
                    Text(
                        stringResource(Res.string.users_hint),
                        color = colors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }

    if (newUserDialog) NewUserDialog(vm) { newUserDialog = false }
    passwordFor?.let { user -> PasswordDialog(vm, user) { passwordFor = null } }
    deleteFor?.let { user -> DeleteUserDialog(vm, user) { deleteFor = null } }
}

@Composable
private fun UserCard(
    vm: AppViewModel,
    user: AdminUser,
    isSelf: Boolean,
    onPassword: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Mpd.colors
    var name by remember(user.username, user.displayName) { mutableStateOf(user.displayName) }
    var roleMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                user.username + (if (isSelf) stringResource(Res.string.users_you) else "") +
                    (if (user.isDemo) stringResource(Res.string.users_demo) else ""),
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box {
                TextButton(onClick = { roleMenu = true }) { Text(roleLabel(user.role)) }
                DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                    ROLE_LABELS.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = {
                                Column(Modifier.padding(vertical = 2.dp)) {
                                    Text(stringResource(label))
                                    ROLE_HINTS[key]?.let {
                                        Text(
                                            stringResource(it),
                                            color = colors.muted,
                                            fontSize = 11.sp,
                                            modifier = Modifier.widthIn(max = 250.dp),
                                        )
                                    }
                                }
                            },
                            onClick = {
                                roleMenu = false
                                if (key != user.role) {
                                    vm.users.op({ base ->
                                        vm.api.adminPatchUser(
                                            base, user.username,
                                            buildJsonObject { put("role", JsonPrimitive(key)) },
                                        )
                                    }) { err ->
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(Res.string.users_first_name)) },
                singleLine = true,
            )
            if (name != user.displayName) {
                TextButton(onClick = {
                    vm.users.op({ base ->
                        vm.api.adminPatchUser(
                            base, user.username,
                            buildJsonObject { put("display_name", JsonPrimitive(name.trim())) },
                        )
                    }) { err ->
                        if (err != null) vm.meldung(err)
                    }
                }) { Icon(MpdIcons.Check, stringResource(Res.string.users_apply), Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onPassword) {
                Icon(MpdIcons.LockClosed, stringResource(Res.string.users_set_password), Modifier.size(18.dp))
            }
            TextButton(onClick = onDelete, enabled = !isSelf) {
                Icon(MpdIcons.Trash, stringResource(Res.string.common_delete), Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NewUserDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    var roleMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.users_new_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = username, onValueChange = { username = it.lowercase() },
                    label = { Text(stringResource(Res.string.users_login_name)) }, singleLine = true,
                )
                OutlinedTextField(
                    value = display, onValueChange = { display = it },
                    label = { Text(stringResource(Res.string.users_first_name_opt)) }, singleLine = true,
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.users_password_min)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.users_role), Modifier.padding(end = 8.dp))
                    Box {
                        TextButton(onClick = { roleMenu = true }) { Text(roleLabel(role)) }
                        DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                            ROLE_LABELS.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    onClick = { roleMenu = false; role = key },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank() && password.length >= 12,
                onClick = {
                    vm.users.op({ base ->
                        vm.api.adminCreateUser(
                            base,
                            buildJsonObject {
                                put("username", JsonPrimitive(username.trim()))
                                put("password", JsonPrimitive(password))
                                put("role", JsonPrimitive(role))
                                if (display.isNotBlank()) {
                                    put("display_name", JsonPrimitive(display.trim()))
                                }
                            },
                        )
                    }) { err ->
                        if (err == null) onDismiss()
                        else vm.meldung(err)
                    }
                },
            ) { Text(stringResource(Res.string.users_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun PasswordDialog(vm: AppViewModel, username: String, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.users_pw_title, username)) },
        text = {
            Column {
                Text(stringResource(Res.string.users_pw_body))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.users_pw_new_min)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.length >= 12,
                onClick = {
                    vm.users.op({ base ->
                        vm.api.adminPatchUser(
                            base, username,
                            buildJsonObject { put("password", JsonPrimitive(password)) },
                        )
                    }) { err ->
                        if (err == null) {
                            vm.meldung(Texte.t(Res.string.users_pw_set_done), kurz = true)
                            onDismiss()
                        } else {
                            vm.meldung(err)
                        }
                    }
                },
            ) { Text(stringResource(Res.string.users_pw_set)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun DeleteUserDialog(vm: AppViewModel, username: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.users_delete_title, username)) },
        text = {
            Text(
                stringResource(Res.string.users_delete_body),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                vm.users.op({ base -> vm.api.adminDeleteUser(base, username) }) { err ->
                    if (err == null) onDismiss()
                    else vm.meldung(err)
                }
            }) { Text(stringResource(Res.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

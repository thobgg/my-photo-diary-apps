package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.WhoAmI
import org.jetbrains.compose.resources.stringResource

/*
 * Passwort aendern — zwei Anlaesse, ein Bildschirm:
 *
 *  - erzwungen (`forced`): der Server hat `must_change_password` gesetzt.
 *    Solange das Flag steht, antwortet JEDE andere API mit 403; die App kann
 *    also nichts anderes anbieten. Darum Vollbild ohne Ausweg — ausser
 *    Abmelden, denn wer sein Startpasswort nicht kennt, muss hier wieder
 *    herauskommen, ohne die App zu deinstallieren.
 *  - freiwillig (aus den Einstellungen): derselbe Endpunkt steht jedem
 *    angemeldeten Konto offen.
 *
 * Optik ist der Anmelde-Look, nicht das App-Theme: es ist ein Konto-Vorgang,
 * und im erzwungenen Fall kommt er direkt nach der Anmeldung.
 * Vertrag: POST /api/account/change-password, API_CHANGES.md vom 04.09.2026.
 */
@Composable
fun PasswordChangeScreen(
    vm: AppViewModel,
    who: WhoAmI,
    forced: Boolean,
    onClose: () -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    // Fehler eines frueheren Anlaufs nicht in diesen hineinschleppen.
    DisposableEffect(Unit) {
        vm.passwordChange.error = null
        onDispose { vm.passwordChange.error = null }
    }

    if (!forced) BackHandler { onClose() }

    Box(
        modifier = Modifier.fillMaxSize().background(LoginBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp)
                .width(340.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
                .padding(start = 36.dp, end = 36.dp, top = 40.dp, bottom = 32.dp),
        ) {
            Text(
                if (forced) stringResource(Res.string.pw_title_forced) else stringResource(Res.string.pw_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            Text(
                if (forced) stringResource(Res.string.pw_forced_intro)
                else stringResource(Res.string.pw_signed_in_as, who.user),
                color = if (forced) TaglineGold else LabelGray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 26.dp),
            )

            LoginField(
                label = if (forced) stringResource(Res.string.pw_label_starter) else stringResource(Res.string.pw_label_current),
                value = oldPassword,
                onChange = { oldPassword = it; vm.passwordChange.error = null },
                enabled = !vm.passwordChange.busy,
                password = true,
            )
            LoginField(
                label = stringResource(Res.string.pw_label_new),
                value = newPassword,
                onChange = {
                    newPassword = it
                    vm.passwordChange.error = null
                    mismatch = false
                },
                enabled = !vm.passwordChange.busy,
                password = true,
            )
            LoginField(
                label = stringResource(Res.string.pw_label_repeat),
                value = repeated,
                onChange = { repeated = it; mismatch = false },
                enabled = !vm.passwordChange.busy,
                password = true,
            )

            // Laengenhinweis, solange er noch nicht erfuellt ist: der Server
            // lehnt kuerzere mit 400 ab, das soll niemanden ueberraschen.
            if (newPassword.isNotEmpty() && newPassword.length < vm.passwordChange.minLength) {
                Text(
                    stringResource(
                        Res.string.pw_hint_remaining,
                        vm.passwordChange.minLength - newPassword.length, vm.passwordChange.minLength,
                    ),
                    color = LabelGray,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }

            val canSubmit = !vm.passwordChange.busy && oldPassword.isNotEmpty() &&
                newPassword.length >= vm.passwordChange.minLength && repeated.isNotEmpty()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (canSubmit) FocusBlue else FocusBlue.copy(alpha = 0.5f))
                    .clickable(enabled = canSubmit) {
                        if (newPassword != repeated) {
                            mismatch = true
                        } else {
                            vm.passwordChange.change(oldPassword, newPassword) {
                                if (forced) vm.refreshAfterPasswordChange() else onClose()
                            }
                        }
                    }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.passwordChange.busy) "…" else stringResource(Res.string.pw_title),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(
                Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val message = when {
                    mismatch -> stringResource(Res.string.pw_err_mismatch)
                    else -> vm.passwordChange.error
                }
                message?.let {
                    Text(it, color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            Text(
                if (forced) stringResource(Res.string.pw_sign_out) else stringResource(Res.string.common_cancel),
                color = LabelGray,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clickable(enabled = !vm.passwordChange.busy) {
                        if (forced) vm.logout() else onClose()
                    }
                    .padding(vertical = 10.dp),
            )
        }
    }
}

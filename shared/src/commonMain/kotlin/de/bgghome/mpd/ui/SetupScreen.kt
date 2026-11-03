package de.bgghome.mpd.ui

import de.bgghome.mpd.api.Photo
import de.bgghome.mpd.shared.res.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.CertInfo
import org.jetbrains.compose.resources.stringResource

/*
 * Einrichtung — Look 1:1 von frontend/login.html: navyblauer Grund,
 * zentrierte Karte, goldene Tagline, Labels ueber den Feldern, blauer
 * Vollbreiten-Knopf, klickbarer Demo-Hinweis. Farben sind wie im Web fest
 * (die Login-Seite ist dort theme-unabhaengig).
 *
 * App-Zusatz gegenueber dem Web: das Serveradressen-Feld — Pflicht laut
 * Gesamtziel (SPK auf eigener NAS, keine feste Adresse im Code) — und die
 * Zertifikats-Vertrauensabfrage.
 */

internal val LoginBg = Color(0xFF2C3E50)
internal val CardBg = Color(0xFF1A2530)
internal val FieldBg = Color(0xFF253040)
internal val FieldBorder = Color(0xFF3A4A5A)
internal val FocusBlue = Color(0xFF2E75B6)
internal val LabelGray = Color(0xFFAAAAAA)
internal val TaglineGold = Color(0xFFC8922A)
internal val ErrorRed = Color(0xFFE74C3C)

@Composable
fun SetupScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    // KOPPLUNG PER QR-CODE (15.09.2026). Kam die App ueber
    // mpd://setup?url=… herein, steht die Adresse schon im Feld — gescannt
    // hat die Systemkamera, nicht MPD. Ein eigener Scanner ist
    // ausgeschlossen (Kamera-Regel, 11.09.), und ein Code mit nackter
    // Adresse half nicht: die Kamera oeffnete den Browser, abtippen musste
    // man trotzdem.
    //
    // Nur die Adresse wird uebernommen, nie Konto oder Passwort. Ein Link,
    // der jemanden anmelden koennte, waere ein Einfallstor — und der Code
    // haengt an einer Wand oder in einer Nachricht.
    val vorgabe = de.bgghome.mpd.PendingSetupUrl.value.value
    androidx.compose.runtime.LaunchedEffect(vorgabe) {
        if (!vorgabe.isNullOrBlank()) {
            vm.serverUrl = vorgabe
            de.bgghome.mpd.PendingSetupUrl.value.value = null
            vm.meldung(vm.t(Res.string.setup_from_link), kurz = true)
        }
    }
    Box(
        modifier = modifier.fillMaxSize().background(LoginBg),
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
                "My Photo Diary",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            Text(
                "where your memories stay yours.",
                color = TaglineGold,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
            )

            LoginField(
                label = stringResource(Res.string.setup_label_server),
                value = vm.serverUrl,
                onChange = { vm.serverUrl = it },
                enabled = !vm.busy,
                keyboardType = KeyboardType.Uri,
                placeholder = "https://192.168.1.2:5001",
            )
            if (vm.serverUrl.trim().startsWith("http://")) {
                Text(
                    stringResource(Res.string.setup_http_warning),
                    color = TaglineGold,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
            }
            LoginField(
                label = stringResource(Res.string.setup_label_user),
                value = vm.username,
                onChange = { vm.username = it },
                enabled = !vm.busy,
            )
            LoginField(
                label = stringResource(Res.string.setup_label_password),
                value = vm.password,
                onChange = { vm.password = it },
                enabled = !vm.busy,
                password = true,
            )

            // Blauer Vollbreiten-Knopf wie .login-box button
            val canSubmit = !vm.busy && vm.serverUrl.isNotBlank() &&
                vm.username.isNotBlank() && vm.password.isNotEmpty()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (canSubmit) FocusBlue else FocusBlue.copy(alpha = 0.5f))
                    .clickable(enabled = canSubmit) { vm.login() }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.busy) "…" else stringResource(Res.string.setup_sign_in),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Fehlerzeile mit fester Mindesthoehe wie .error
            Box(
                Modifier.fillMaxWidth().heightIn(min = 18.dp).padding(top = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                vm.error?.let {
                    Text(it, color = ErrorRed, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            DemoHint(
                enabled = !vm.busy,
                onClick = {
                    vm.username = "mpd-demo"
                    vm.password = "mpd-demo"
                },
            )
        }
    }

    vm.pendingCert?.let { cert ->
        CertTrustDialog(
            cert = cert,
            onTrust = { vm.trustPendingCert() },
            onDismiss = { vm.dismissPendingCert() },
        )
    }
}

/** Label + Eingabefeld im Web-Login-Stil (Label drueber, Feld mit Rand). */
@Composable
internal fun LoginField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Text(label, color = LabelGray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Color(0xFFE0E0E0), fontSize = 15.sp),
        cursorBrush = SolidColor(FocusBlue),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FieldBg)
            .border(
                1.dp,
                if (focused) FocusBlue else FieldBorder,
                RoundedCornerShape(6.dp),
            )
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, color = LabelGray.copy(alpha = 0.5f), fontSize = 15.sp)
                }
                inner()
            }
        },
    )
}

/** Gestrichelter Demo-Hinweis wie .demo-hint — Tap uebernimmt die Werte. */
@Composable
private fun DemoHint(enabled: Boolean, onClick: () -> Unit) {
    val dash = FieldBorder
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(FieldBg)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                    ),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            demoHintText(stringResource(Res.string.setup_demo_prefix)),
            color = LabelGray,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

private fun demoHintText(prefix: String): AnnotatedString = buildAnnotatedString {
    append(prefix)
    withStyle(SpanStyle(color = TaglineGold, fontWeight = FontWeight.SemiBold)) { append("mpd-demo") }
    append(" · ")
    withStyle(SpanStyle(color = TaglineGold, fontWeight = FontWeight.SemiBold)) { append("mpd-demo") }
}

/**
 * Vertrauensabfrage fuer selbstsignierte Zertifikate (typisch NAS im LAN).
 * Der Nutzer sieht den Fingerprint und entscheidet — die Pruefung wird nie
 * still abgeschaltet.
 */
@Composable
private fun CertTrustDialog(cert: CertInfo, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.setup_cert_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.setup_cert_body))
                Text(cert.subject, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(Res.string.setup_cert_valid_until, cert.validUntil), fontSize = 12.sp)
                Text(
                    stringResource(Res.string.setup_cert_sha, cert.fingerprintDisplay),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(Res.string.setup_cert_trust)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

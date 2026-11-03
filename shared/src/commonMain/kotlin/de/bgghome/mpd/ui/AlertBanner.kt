package de.bgghome.mpd.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.bgghome.mpd.AppViewModel
import kotlinx.coroutines.delay

/**
 * Waechter-Banner: erscheint oben, wenn bei geoeffneter App ein Alarm
 * eintrifft (Vordergrund-Long-Poll im ViewModel). Ausserhalb der App
 * uebernimmt die Systemmeldung auf dem „Wächter“-Kanal — das Banner ist
 * das In-App-Gegenstueck, weil Heads-up ueber der eigenen App leicht
 * untergeht (03.09.: „man muss nachgucken“).
 */
@Composable
fun AlertBannerOverlay(vm: AppViewModel) {
    val banner = vm.alertBanner ?: return
    val (title, body) = banner

    // Nach 12 s von selbst weg — ein Alarm soll auffallen, nicht kleben;
    // in der Benachrichtigungsleiste liegt er ohnehin dauerhaft.
    LaunchedEffect(banner) {
        delay(12_000)
        if (vm.alertBanner == banner) vm.alertBanner = null
    }

    Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { vm.alertBanner = null },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MpdIcons.Info, null, Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (body.isNotBlank()) {
                        Text(body, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    }
                }
            }
        }
    }
}

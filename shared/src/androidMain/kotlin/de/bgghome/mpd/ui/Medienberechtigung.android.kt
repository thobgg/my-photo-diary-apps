package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.loadLocalMedia

@Composable
actual fun GeraetefotosAnfordern(vm: AppViewModel) {
    val mediaContext = LocalContext.current
    val mediaPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val mediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants.values.any { it }) vm.loadLocalMedia() }
    LaunchedEffect(Unit) {
        val granted = mediaPermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(mediaContext, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        when {
            granted -> vm.loadLocalMedia()
            !vm.localMediaAsked -> {
                vm.localMediaAsked = true
                mediaLauncher.launch(mediaPermissions)
            }
        }
    }
}

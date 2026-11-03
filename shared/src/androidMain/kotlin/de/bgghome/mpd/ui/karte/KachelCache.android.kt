package de.bgghome.mpd.ui.karte

import coil3.PlatformContext
import java.io.File

actual fun kachelCacheOrdner(context: PlatformContext): File = File(context.cacheDir, "tiles")

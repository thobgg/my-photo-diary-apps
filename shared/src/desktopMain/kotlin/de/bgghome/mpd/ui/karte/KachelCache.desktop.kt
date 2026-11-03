package de.bgghome.mpd.ui.karte

import coil3.PlatformContext
import java.io.File

/** ~/.cache/mpd/tiles (XDG) bzw. %LOCALAPPDATA%\mpd\tiles. */
actual fun kachelCacheOrdner(context: PlatformContext): File {
    val basis = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".cache")
    return File(File(basis, "mpd"), "tiles")
}

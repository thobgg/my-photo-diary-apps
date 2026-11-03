package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
actual fun NoSystemOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        androidx.compose.foundation.LocalOverscrollConfiguration provides null,
        content = content,
    )
}

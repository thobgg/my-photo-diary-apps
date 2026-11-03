package de.bgghome.mpd.ui

import androidx.compose.runtime.Composable

/** Kein System-Overscroll auf dem Desktop — nichts abzuschalten. */
@Composable
actual fun NoSystemOverscroll(content: @Composable () -> Unit) = content()

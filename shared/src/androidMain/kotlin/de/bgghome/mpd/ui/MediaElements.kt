package de.bgghome.mpd.ui

import de.bgghome.mpd.shared.res.*

import android.content.Context
import androidx.compose.ui.backhandler.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Icon
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import de.bgghome.mpd.AppViewModel
import de.bgghome.mpd.api.PdxElement
import de.bgghome.mpd.ui.theme.Mpd
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import de.bgghome.mpd.closeVideo
import de.bgghome.mpd.mediaUrl

/*
 * Video- und Audio-Wiedergabe. Moeglich seit den Serveraenderungen vom
 * 01.09.2026: /api/video, /api/audio und /download beherrschen
 * Range-Requests (206) — damit funktioniert Spulen; die Authentifizierung
 * laeuft ueber den Bearer-Header (Fallback: Sitzungs-Cookie).
 * Wichtig laut Server-Doku: HEAD bleibt 405 — ExoPlayer nutzt GET.
 */

/**
 * @param onError wird gerufen, wenn das Abspielen scheitert.
 *
 * VORHER GESCHAH GAR NICHTS (16.09.2026: „Und Audio und Video?").
 * Beide Player hatten keinen Fehler-Zuhoerer: beim Video blieb der
 * Bildschirm schwarz, beim Audio blieb der Knopf auf Abspielen stehen und
 * ruehrte sich nicht. Ob die Datei fehlt, ein Recht verweigert wurde oder
 * das Format nicht geht, war nicht zu unterscheiden — und ein Protokoll,
 * in dem man es nachlesen koennte, gibt es zu Recht nicht mehr.
 *
 * Media3 liefert mit `errorCodeName` einen sprechenden Grund
 * (ERROR_CODE_IO_FILE_NOT_FOUND, ERROR_CODE_IO_BAD_HTTP_STATUS …). Der
 * gehoert auf den Bildschirm, wo ihn der Nutzer sieht und niemand sonst.
 */
@OptIn(UnstableApi::class)
private fun buildPlayer(
    context: Context,
    vm: AppViewModel,
    url: String,
    onError: (String) -> Unit = {},
): ExoPlayer {
    // DefaultDataSource waehlt nach Schema: http(s) fuer Server-Videos,
    // content:// fuer Aufnahmen, die noch auf dem Geraet liegen. Mit der
    // reinen HTTP-Factory scheiterte jedes lokale Video.
    val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory().setDefaultRequestProperties(vm.api.authHeaders()),
    )
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onError(error.errorCodeName)
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
}

/** Vollbild-Videoplayer — Gegenstueck zum Video-Modal des Webs. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerOverlay(vm: AppViewModel) {
    val url = vm.videoUrl ?: return
    BackHandler { vm.closeVideo() }

    val context = LocalContext.current
    var fehler by remember(url) { mutableStateOf<String?>(null) }
    val player = remember(url) {
        buildPlayer(context, vm, url, onError = { fehler = it })
            .apply { playWhenReady = true }
    }
    DisposableEffect(url) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
        )
        fehler?.let {
            Text(
                stringResource(Res.string.media_play_failed, it),
                color = Color(0xFFE05555),
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        Icon(
            MpdIcons.Cross, stringResource(Res.string.common_close),
            tint = Color.White,
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { vm.closeVideo() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
        )
    }
}

/** Inline-Audio-Player — Gegenstueck zum <audio controls> des Webs. */
@Composable
actual fun AudioElementBlock(vm: AppViewModel, elem: PdxElement) {
    val url = vm.mediaUrl("audio", elem) ?: return
    val context = LocalContext.current

    var player by remember(elem.id) { mutableStateOf<ExoPlayer?>(null) }
    var fehler by remember(elem.id) { mutableStateOf<String?>(null) }
    var playing by remember(elem.id) { mutableStateOf(false) }
    var positionMs by remember(elem.id) { mutableLongStateOf(0L) }
    var durationMs by remember(elem.id) { mutableLongStateOf(0L) }

    DisposableEffect(elem.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let {
                positionMs = it.currentPosition
                durationMs = it.duration.coerceAtLeast(0)
            }
            delay(250)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            // surface und blue: im Standard-Theme (kachel) dieselben Werte wie
            // die festen Zahlen vorher; text/muted/border weichen dort ab und
            // bleiben fest (Audit 08.09.2026).
            .background(Mpd.colors.surface)
            .border(1.dp, Color(0xFF333333), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        elem.title?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Mpd.colors.blue)
                    .clickable {
                        val p = player ?: buildPlayer(
                            context, vm, url,
                            onError = { fehler = it },
                        ).also { built ->
                            built.addListener(object : Player.Listener {
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    playing = isPlaying
                                }
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_READY) {
                                        durationMs = built.duration.coerceAtLeast(0)
                                    }
                                }
                            })
                            player = built
                        }
                        if (p.isPlaying) p.pause() else {
                            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                            p.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) MpdIcons.Pause else MpdIcons.Play,
                    stringResource(if (playing) Res.string.media_pause else Res.string.media_play),
                    Modifier.size(18.dp), tint = Color.White,
                )
            }
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { fraction ->
                    if (durationMs > 0) {
                        val target = (fraction * durationMs).toLong()
                        positionMs = target
                        player?.seekTo(target)
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(
                "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                color = Color(0xFF888888),
                fontSize = 12.sp,
            )
        }
        fehler?.let {
            Text(
                stringResource(Res.string.media_play_failed, it),
                color = MpdRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

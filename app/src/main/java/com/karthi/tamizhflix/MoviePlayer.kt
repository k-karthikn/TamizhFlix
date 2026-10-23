package com.karthi.tamizhflix

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.karthi.tamizhflix.data.HttpPageFetcher
import com.karthi.tamizhflix.ui.theme.TamizhFlixTheme
import com.karthi.tamizhflix.ui.theme.ThemeMode

class MoviePlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var fullscreen by mutableStateOf(false)
    private var orientationMode by mutableStateOf("Auto")
    private var playbackError by mutableStateOf<String?>(null)

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: run { finish(); return }
        val headers = mutableMapOf("User-Agent" to HttpPageFetcher.USER_AGENT)
        intent.getStringExtra("referer")?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        ).build().apply {
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError = "Could not play this link. Choose another server or use Open."
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        val savedMode = getSharedPreferences("appearance", MODE_PRIVATE).getString("mode", null)
        val themeMode = ThemeMode.entries.firstOrNull { it.name == savedMode } ?: ThemeMode.SYSTEM
        setContent { TamizhFlixTheme(themeMode) { PlayerScreen() } }
    }

    private fun applyFullscreen(value: Boolean) {
        fullscreen = value
        WindowCompat.setDecorFitsSystemWindows(window, !value)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (value) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStop() { super.onStop(); player.pause() }
    override fun onDestroy() {
        applyFullscreen(false)
        player.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PlayerScreen() {
        if (fullscreen) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { PlayerView(it).apply { this.player = this@MoviePlayerActivity.player } },
                    update = { it.player = player }, modifier = Modifier.fillMaxSize())
                Row(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                    FilledTonalButton(onClick = { applyFullscreen(false) }) {
                        Icon(painterResource(R.drawable.ic_action_expand), null)
                        Spacer(Modifier.width(6.dp))
                        Text("Exit full screen")
                    }
                }
                playbackError?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.error) }
            }
            return
        }
        Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(intent.getStringExtra("title") ?: "Movie", maxLines = 1) },
                    navigationIcon = { TextButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_action_back), null); Spacer(Modifier.width(4.dp)); Text("Close") } })
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Auto", "Lock", "Landscape").forEach { mode ->
                        FilterChip(selected = orientationMode == mode, onClick = {
                            orientationMode = mode
                            requestedOrientation = when (mode) {
                                "Lock" -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            }
                        }, label = { Text(mode) }, leadingIcon = { Icon(painterResource(if (mode == "Lock") R.drawable.ic_action_lock else R.drawable.ic_action_rotate), null) })
                    }
                }
            AndroidView(factory = { PlayerView(it).apply { this.player = this@MoviePlayerActivity.player } },
                update = { it.player = player }, modifier = Modifier.fillMaxWidth().weight(1f))
            playbackError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { applyFullscreen(true) }) {
                    Icon(painterResource(R.drawable.ic_action_expand), null); Spacer(Modifier.width(4.dp)); Text("Full screen")
                }
            }
        }
    }
}

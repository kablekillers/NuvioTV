package com.nuvio.tv.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AlbumTrack(val title: String, val url: String, val index: Int)

internal suspend fun loadAlbumTracks(recording: MusicResult): List<AlbumTrack> = withContext(Dispatchers.IO) {
    if (!recording.id.startsWith("ia:") && !recording.id.startsWith("audiuspl:")) return@withContext emptyList()
    val isAudius = recording.id.startsWith("audiuspl:")
    val identifier = recording.id.substringAfter(":")
    if (!Regex("^[a-zA-Z0-9_.-]{1,200}$").matches(identifier)) return@withContext emptyList()
    val url = recording.addonBaseUrl.trimEnd('/') +
        (if (isAudius) "/api/music/audius-album/" else "/api/music/album/") + URLEncoder.encode(identifier, "UTF-8")
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        if (connection.responseCode !in 200..299) return@withContext emptyList()
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tracks = root.optJSONArray("tracks") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until tracks.length()) {
                val item = tracks.optJSONObject(i) ?: continue
                val trackUrl = item.optString("url")
                if (!trackUrl.startsWith("https://archive.org/download/") &&
                    !trackUrl.startsWith("https://kable-killers-music.onrender.com/api/music/audius-track/")) continue
                add(AlbumTrack(item.optString("title", "Track ${i + 1}"), trackUrl, i))
            }
        }
    } finally {
        connection.disconnect()
    }
}

/** Music-specific audio-only player. ExoPlayer automatically advances the ordered playlist. */
@Composable
fun AlbumPlayerDialog(recording: MusicResult, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tracks by remember(recording.id) { mutableStateOf<List<AlbumTrack>>(emptyList()) }
    var loading by remember(recording.id) { mutableStateOf(true) }
    var error by remember(recording.id) { mutableStateOf<String?>(null) }
    var index by remember(recording.id) { mutableStateOf(0) }
    var isPlaying by remember(recording.id) { mutableStateOf(false) }
    val player = remember(recording.id) { ExoPlayer.Builder(context).build() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex.coerceAtLeast(0)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(recording.id) {
        loading = true
        error = null
        try {
            tracks = loadAlbumTracks(recording)
            if (tracks.isEmpty()) {
                error = "No playable audio tracks found for this recording."
            } else {
                player.setMediaItems(tracks.map { MediaItem.fromUri(it.url) })
                player.prepare()
                player.playWhenReady = false
            }
        } catch (_: Exception) {
            error = "Unable to load this album. Please try again."
        } finally {
            loading = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF101D33))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(recording.title, color = Color(0xFF19A8FF), fontWeight = FontWeight.Bold)
            if (loading) Text("Loading tracks…", color = Color.White)
            error?.let { Text(it, color = Color.White) }
            if (tracks.isNotEmpty()) {
                Text("Track ${index + 1} of ${tracks.size} · ${tracks.getOrNull(index)?.title.orEmpty()}",
                    color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() },
                        enabled = index > 0) { Text("Previous") }
                    Button(onClick = {
                        if (isPlaying) player.pause() else player.play()
                    }) { Text(if (isPlaying) "Pause" else "Play album") }
                    Button(onClick = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() },
                        enabled = index < tracks.lastIndex) { Text("Next") }
                }
                Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.forEachIndexed { position, track ->
                        Text(
                            text = "${position + 1}. ${track.title}",
                            color = if (position == index) Color(0xFF19A8FF) else Color.White,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { player.seekTo(position, 0L); player.play() }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
            Button(onClick = onDismiss) { Text("Close") }
        }
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private enum class TvMusicTab(val label: String) { Home("Home"), Radio("Radio"), Albums("Albums"), Search("Search"), Videos("Videos"), Library("Library") }

/** Kable Killers Music for TV, laid out like Nuvio One: black, pill tabs and rows of artwork. */
@Composable
fun TvMusicScreen() {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(TvMusicTab.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var home by remember { mutableStateOf<TvMusicHome?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<TvMusicItem>>(emptyList()) }
    var videos by remember { mutableStateOf<List<TvYouTubeVideo>>(emptyList()) }
    var openAlbum by remember { mutableStateOf<TvMusicItem?>(null) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    val player by TvMusicPlayer.state.collectAsState()
    val recent by TvMusicPlayer.recent.collectAsState()

    LaunchedEffect(Unit) {
        loading = true
        try {
            home = TvMusicApi.home()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message = "Music is unavailable right now. Try again in a moment."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(query, tab) {
        results = emptyList()
        videos = emptyList()
        val q = query.trim()
        if (q.isEmpty() || tab == TvMusicTab.Home || tab == TvMusicTab.Library) return@LaunchedEffect
        delay(500)
        try {
            when (tab) {
                TvMusicTab.Search -> results = coroutineScope {
                    listOf("kk_audius", "kablekillers_music_search", "kk_audius_albums", "kk_radio")
                        .map { id -> async { runCatching { TvMusicApi.catalog(id, q) }.getOrDefault(emptyList()) } }
                        .awaitAll().flatten().distinctBy { it.id }
                }
                TvMusicTab.Radio -> results = TvMusicApi.catalog("kk_radio", q)
                TvMusicTab.Albums -> results = coroutineScope {
                    listOf("kk_audius_albums", "kk_archive")
                        .map { id -> async { runCatching { TvMusicApi.catalog(id, q) }.getOrDefault(emptyList()) } }
                        .awaitAll().flatten().filter { it.isAlbum }
                }
                TvMusicTab.Videos -> videos = TvMusicApi.youtubeMusic(q)
                else -> Unit
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message = "Search isn't available right now."
        }
    }

    BackHandler(enabled = nowPlayingOpen || openAlbum != null) {
        if (nowPlayingOpen) nowPlayingOpen = false else openAlbum = null
    }

    fun open(item: TvMusicItem, siblings: List<TvMusicItem>) {
        if (item.isAlbum) {
            openAlbum = item
        } else {
            val playable = siblings.filterNot { it.isAlbum }
            TvMusicPlayer.playQueue(context, playable, playable.indexOf(item).coerceAtLeast(0))
            nowPlayingOpen = true
        }
    }

    Box(Modifier.fillMaxSize().background(HubBlack)) {
        val album = openAlbum
        when {
            nowPlayingOpen && player.current != null -> TvNowPlaying(player = player, onClose = { nowPlayingOpen = false })
            album != null -> TvAlbumPage(album = album, onPlay = { tracks, index ->
                TvMusicPlayer.playQueue(context, tracks, index)
                nowPlayingOpen = true
            })
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF0A4DFF), KkBlue, Color(0xFF7DE3FF)))),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.Black) }
                        Spacer(Modifier.width(14.dp))
                        Text("Kable Killers Music", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
                item(key = "tabs") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        items(TvMusicTab.entries, key = { it.name }) { option ->
                            HubPill(option.label, selected = tab == option) { tab = option; query = "" }
                        }
                    }
                }
                if (tab in listOf(TvMusicTab.Search, TvMusicTab.Radio, TvMusicTab.Albums, TvMusicTab.Videos)) {
                    item(key = "search") {
                        HubSearchField(
                            value = query,
                            placeholder = when (tab) {
                                TvMusicTab.Radio -> "Search 30,000+ radio stations"
                                TvMusicTab.Albums -> "Search albums and concerts"
                                TvMusicTab.Videos -> "Search music videos"
                                else -> "Songs, artists, albums or stations"
                            },
                            onValueChange = { query = it },
                            modifier = Modifier.width(720.dp),
                        )
                    }
                }
                message?.let { item(key = "msg") { HubMessage(it) } }
                if (loading && home == null) item(key = "loading") { HubMessage("Loading music…") }

                when (tab) {
                    TvMusicTab.Home -> {
                        val data = home
                        if (data != null && data.artists.isNotEmpty()) {
                            item(key = "artists") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                                    items(data.artists, key = { "a:" + it.name }) { artist ->
                                        HubCard(onClick = { query = artist.name; tab = TvMusicTab.Search }, corner = 48.dp) {
                                            Column(Modifier.width(110.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                HubArtwork(artist.image, Modifier.size(84.dp), corner = 42.dp)
                                                Spacer(Modifier.height(6.dp))
                                                Text(artist.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (recent.isNotEmpty()) {
                            item(key = "recent") { MusicShelf("Jump back in", recent) { item -> open(item, recent) } }
                        }
                        data?.sections?.forEach { section ->
                            item(key = "s:" + section.title) { MusicShelf(section.title, section.items) { item -> open(item, section.items) } }
                        }
                    }
                    TvMusicTab.Radio -> {
                        val stations = if (query.isBlank()) home?.sections?.firstOrNull { it.kind == "radio" }?.items.orEmpty() else results
                        musicGrid(stations) { open(it, stations) }
                    }
                    TvMusicTab.Albums -> {
                        val albums = if (query.isBlank()) home?.sections?.firstOrNull { it.kind == "albums" }?.items.orEmpty() else results
                        musicGrid(albums) { open(it, albums) }
                    }
                    TvMusicTab.Search -> musicGrid(results) { open(it, results) }
                    TvMusicTab.Library -> {
                        if (recent.isEmpty()) item(key = "lib") { HubMessage("Songs and stations you play show up here.") }
                        musicGrid(recent) { open(it, recent) }
                    }
                    TvMusicTab.Videos -> {
                        items(videos.chunked(4), key = { row -> row.joinToString { it.videoId } }) { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                row.forEach { video ->
                                    TvVideoCard(video, Modifier.weight(1f)) {
                                        TvMusicPlayer.pause()
                                        TvYouTubePlayerActivity.start(context, video.videoId)
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        // Mini player: always reachable at the bottom while music plays.
        val current = player.current
        if (current != null && !nowPlayingOpen) {
            Box(Modifier.align(Alignment.BottomEnd).padding(24.dp)) {
                HubCard(onClick = { nowPlayingOpen = true }, corner = 18.dp, focusedScale = 1.03f) {
                    Row(Modifier.width(460.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        HubArtwork(current.artwork, Modifier.size(56.dp), corner = 10.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(current.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                player.error ?: if (player.isBuffering) "Loading…" else current.artist.ifBlank { if (current.isLive) "Live radio" else "Now playing" },
                                color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, maxLines = 1,
                            )
                        }
                        Icon(if (player.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, contentDescription = null, tint = KkBlue)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.musicGrid(items: List<TvMusicItem>, onItem: (TvMusicItem) -> Unit) {
    items(items.chunked(6), key = { row -> "g:" + row.joinToString { it.id } }) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            row.forEach { item -> MusicTile(item, Modifier.weight(1f)) { onItem(item) } }
            repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun MusicShelf(title: String, items: List<TvMusicItem>, onItem: (TvMusicItem) -> Unit) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(items.take(25), key = { i, it -> "$i:${it.id}" }) { _, item ->
                MusicTile(item, Modifier.width(180.dp)) { onItem(item) }
            }
        }
    }
}

@Composable
private fun MusicTile(item: TvMusicItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        HubCard(onClick = onClick) {
            Box {
                HubArtwork(item.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
                if (item.isLive) {
                    Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53935)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                item.isLive -> "Live radio"
                item.isAlbum -> item.artist.ifBlank { "Album" }
                else -> item.artist.ifBlank { "Music" }
            },
            color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HubMessage(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
private fun TvAlbumPage(album: TvMusicItem, onPlay: (List<TvMusicItem>, Int) -> Unit) {
    var tracks by remember(album.id) { mutableStateOf<List<TvMusicItem>>(emptyList()) }
    var error by remember(album.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(album.id) {
        try {
            tracks = TvMusicApi.albumTracks(album)
            if (tracks.isEmpty()) error = "This album doesn't have a playable track list yet."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = "Couldn't load that album."
        }
    }
    Row(Modifier.fillMaxSize().padding(48.dp)) {
        Column(Modifier.width(360.dp)) {
            HubArtwork(album.artwork, Modifier.size(320.dp), corner = 18.dp)
            Spacer(Modifier.height(16.dp))
            Text(album.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
            Text(album.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            HubCard(onClick = { if (tracks.isNotEmpty()) onPlay(tracks, 0) }, corner = 26.dp, selected = true) {
                Row(Modifier.padding(horizontal = 26.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text("Play album", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(40.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            error?.let { item { HubMessage(it) } }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                HubCard(onClick = { onPlay(tracks, index) }, corner = 10.dp, focusedScale = 1.02f) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text("${index + 1}", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(40.dp))
                        Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvNowPlaying(player: TvMusicPlayerState, onClose: () -> Unit) {
    val track = player.current ?: return
    Box(Modifier.fillMaxSize()) {
        if (!track.artwork.isNullOrBlank()) {
            AsyncImage(model = track.artwork, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(70.dp).alpha(0.45f))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.4f)))))
        Row(Modifier.fillMaxSize().padding(64.dp), verticalAlignment = Alignment.CenterVertically) {
            HubArtwork(track.artwork, Modifier.size(420.dp), corner = 22.dp)
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING · KABLE KILLERS MUSIC", color = KkBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(10.dp))
                Text(track.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (track.isLive) "● LIVE · ${track.artist}".trimEnd(' ', '·') else track.artist,
                    color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp, maxLines = 1)
                player.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
                if (!track.isLive && player.durationMs > 0) {
                    Spacer(Modifier.height(24.dp))
                    val fraction = (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(0.9f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(KkBlue))
                    }
                    Row(Modifier.fillMaxWidth(0.9f).padding(top = 6.dp)) {
                        Text(formatMs(player.positionMs), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatMs(player.durationMs), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(Icons.Rounded.SkipPrevious, "Previous") { TvMusicPlayer.previous() }
                    if (!track.isLive) ControlButton(Icons.Rounded.Replay10, "Back 10 seconds") { TvMusicPlayer.seekBy(-10_000) }
                    ControlButton(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", big = true) { TvMusicPlayer.toggle() }
                    if (!track.isLive) ControlButton(Icons.Rounded.Forward10, "Forward 10 seconds") { TvMusicPlayer.seekBy(10_000) }
                    ControlButton(Icons.Rounded.SkipNext, "Next") { TvMusicPlayer.next() }
                    ControlButton(Icons.Rounded.Close, "Stop") { TvMusicPlayer.stop(); onClose() }
                }
                if (player.queue.size > 1) {
                    Spacer(Modifier.height(28.dp))
                    Text("Up next", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        itemsIndexed(player.queue, key = { i, t -> "q$i:${t.id}" }) { index, queued ->
                            HubCard(onClick = { TvMusicPlayer.jumpTo(index) }, selected = index == player.index) {
                                Column(Modifier.width(130.dp).padding(8.dp)) {
                                    HubArtwork(queued.artwork, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
                                    Text(queued.title, color = if (index == player.index) Color.Black else Color.White, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, big: Boolean = false, onClick: () -> Unit) {
    HubCard(onClick = onClick, corner = 40.dp, selected = big, focusedScale = 1.1f) {
        Icon(icon, contentDescription = description, tint = if (big) Color.Black else Color.White,
            modifier = Modifier.padding(if (big) 20.dp else 14.dp).size(if (big) 40.dp else 28.dp))
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

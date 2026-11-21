@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private enum class TvYtFeed(val label: String, val category: String?) {
    Trending("Trending", "all"), Following("Following", null), Music("Music", "music"), Gaming("Gaming", "gaming"),
    Sports("Sports", "sports"), News("News", "news"), Movies("Movies", "movies"), Comedy("Comedy", "comedy"),
    Science("Science", "science"), History("History", null), Search("Search", null),
}

private data class TvYtChannel(val id: String, val name: String)

/** Followed channels and watch history, stored on the TV. */
private object TvYouTubeLocal {
    private const val PREFS = "kk_tv_youtube"
    fun following(context: Context): List<TvYtChannel> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("following", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> TvYtChannel(o.getString("id"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    fun setFollowing(context: Context, list: List<TvYtChannel>) {
        val arr = JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("following", arr.toString()).apply()
    }

    fun history(context: Context): List<TvYouTubeVideo> = runCatching {
        parseVideos(JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", "[]")))
    }.getOrDefault(emptyList())

    fun addHistory(context: Context, video: TvYouTubeVideo) {
        val list = (listOf(video) + history(context).filterNot { it.videoId == video.videoId }).take(60)
        val arr = JSONArray().apply {
            list.forEach { v ->
                put(JSONObject().put("videoId", v.videoId).put("title", v.title).put("channel", v.channel)
                    .put("channelId", v.channelId).put("thumbnail", v.thumbnail).put("publishedAt", v.publishedAt)
                    .put("duration", v.duration ?: "").put("isLive", v.isLive))
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
    }
}

/** The YouTube tab for TV: official YouTube Data API feeds, played in YouTube's own player. */
@Composable
fun TvYouTubeScreen() {
    val context = LocalContext.current
    var feed by rememberSaveable { mutableStateOf(TvYtFeed.Trending) }
    var query by rememberSaveable { mutableStateOf("") }
    var channel by remember { mutableStateOf<TvYtChannel?>(null) }
    var following by remember { mutableStateOf(TvYouTubeLocal.following(context)) }
    var historyVersion by remember { mutableStateOf(0) }
    var videos by remember { mutableStateOf<List<TvYouTubeVideo>>(emptyList()) }
    var nextToken by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    suspend fun load(token: String?): TvYouTubePage {
        val ch = channel
        val q = query.trim()
        return when {
            ch != null -> TvYouTubeApi.channel(ch.id, token)
            feed == TvYtFeed.Search -> if (q.isEmpty()) TvYouTubePage(emptyList(), null) else TvYouTubeApi.search(q, token)
            feed.category != null -> TvYouTubeApi.trending(feed.category!!, token)
            feed == TvYtFeed.Following -> TvYouTubePage(
                following.take(12).flatMap { runCatching { TvYouTubeApi.channel(it.id).videos.take(6) }.getOrDefault(emptyList()) }
                    .sortedByDescending { it.publishedAt },
                null,
            )
            else -> TvYouTubePage(TvYouTubeLocal.history(context), null)
        }
    }

    LaunchedEffect(feed, query, channel, if (feed == TvYtFeed.History) historyVersion else 0) {
        if (feed == TvYtFeed.Search && query.isNotBlank()) delay(500)
        loading = true
        message = null
        try {
            val page = load(null)
            videos = page.videos
            nextToken = page.nextPageToken
            if (videos.isEmpty()) message = when {
                channel != null -> "No videos found."
                feed == TvYtFeed.Following -> "Open a video's channel and choose Follow. Its newest videos show up here."
                feed == TvYtFeed.History -> "Videos you watch show up here."
                feed == TvYtFeed.Search -> if (query.isBlank()) "Search for videos, channels or highlights." else "No videos found."
                else -> "No videos found."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            videos = emptyList()
            message = "YouTube isn't available right now. Try again in a moment."
        } finally {
            loading = false
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(nearEnd, nextToken) {
        val token = nextToken ?: return@LaunchedEffect
        if (!nearEnd || loading) return@LaunchedEffect
        try {
            val page = load(token)
            videos = (videos + page.videos).distinctBy { it.videoId }
            nextToken = page.nextPageToken
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            nextToken = null
        }
    }

    BackHandler(enabled = channel != null) { channel = null }

    fun play(video: TvYouTubeVideo) {
        TvYouTubeLocal.addHistory(context, video)
        historyVersion++
        TvMusicPlayer.pause()
        TvYouTubePlayerActivity.start(context, video.videoId)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF07090F)),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 60.dp),
    ) {
        item(key = "header") {
            val ch = channel
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SmartDisplay, contentDescription = null, tint = Color(0xFFFF0033), modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text(ch?.name ?: "YouTube", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ch != null) {
                    Spacer(Modifier.width(20.dp))
                    val followed = following.any { it.id == ch.id }
                    HubPill(if (followed) "Following" else "Follow", selected = !followed) {
                        following = if (followed) following.filterNot { it.id == ch.id } else listOf(ch) + following
                        TvYouTubeLocal.setFollowing(context, following)
                    }
                    Spacer(Modifier.width(10.dp))
                    HubPill("Back", selected = false) { channel = null }
                }
            }
        }
        if (channel == null) {
            item(key = "feeds") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    items(TvYtFeed.entries, key = { it.name }) { option ->
                        HubPill(option.label, selected = feed == option) { feed = option }
                    }
                }
            }
            if (feed == TvYtFeed.Search) {
                item(key = "search") {
                    HubSearchField(query, "Search YouTube", { query = it }, Modifier.width(720.dp).padding(bottom = 16.dp))
                }
            }
        }
        if (loading) item(key = "loading") { HubMessage("Loading…") }
        message?.let { item(key = "msg") { HubMessage(it) } }
        items(videos.chunked(4), key = { row -> row.joinToString { it.videoId } }) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { video ->
                    TvVideoCard(
                        video = video,
                        modifier = Modifier.weight(1f),
                        onChannel = if (video.channelId.isNotBlank()) ({ channel = TvYtChannel(video.channelId, video.channel) }) else null,
                    ) { play(video) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun TvVideoCard(
    video: TvYouTubeVideo,
    modifier: Modifier,
    onChannel: (() -> Unit)? = null,
    onPlay: () -> Unit,
) {
    Column(modifier) {
        HubCard(onClick = onPlay, corner = 12.dp, focusedScale = 1.05f) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(model = video.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                val badge = if (video.isLive) "LIVE" else formatIsoDuration(video.duration)
                if (badge != null) {
                    Text(badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(4.dp))
                            .background(if (video.isLive) Color(0xFFFF0033) else Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(video.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onChannel != null && video.channel.isNotBlank()) {
            HubCard(onClick = onChannel, corner = 8.dp, focusedScale = 1.03f, modifier = Modifier.padding(top = 4.dp)) {
                Text(video.channel, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        } else if (video.channel.isNotBlank()) {
            Text(video.channel, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
        }
    }
}

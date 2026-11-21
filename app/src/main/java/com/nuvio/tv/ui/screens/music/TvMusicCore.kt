package com.nuvio.tv.ui.screens.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The Kable Killers Music addon server (Render). */
internal const val KK_MUSIC_BASE = "https://kable-killers-music.onrender.com"

internal data class TvMusicItem(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String?,
    val isLive: Boolean,
    val isAlbum: Boolean,
    /** Direct audio URL for album tracks; null means resolve through the addon. */
    val url: String? = null,
)

internal data class TvMusicSection(val title: String, val kind: String, val items: List<TvMusicItem>)

internal data class TvMusicArtist(val name: String, val image: String)

internal data class TvMusicHome(val sections: List<TvMusicSection>, val artists: List<TvMusicArtist>)

internal object TvMusicApi {
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(if (path.startsWith("http")) path else KK_MUSIC_BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "KableKillersTV/Music")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun metaToItem(o: JSONObject): TvMusicItem {
        val id = o.optString("id")
        val raw = o.optString("name").removePrefix("📻").trim()
        val parts = raw.split(" — ", limit = 2)
        return TvMusicItem(
            id = id,
            title = parts[0].trim(),
            artist = parts.getOrNull(1)?.trim().orEmpty(),
            artwork = o.optString("poster").ifBlank { null },
            isLive = id.startsWith("radio:"),
            isAlbum = id.startsWith("audiuspl:") || id.startsWith("ia:"),
        )
    }

    private fun JSONArray.items(): List<TvMusicItem> =
        (0 until length()).mapNotNull { optJSONObject(it)?.let(::metaToItem) }.filter { it.id.isNotBlank() }

    suspend fun home(): TvMusicHome {
        val root = JSONObject(getText("/api/music/home"))
        val sections = root.optJSONArray("sections")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                TvMusicSection(s.optString("title"), s.optString("kind"), s.optJSONArray("items")?.items().orEmpty())
            }
        }.orEmpty().filter { it.items.isNotEmpty() }
        val artists = root.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { TvMusicArtist(it.optString("name"), it.optString("image")) }
            }
        }.orEmpty()
        return TvMusicHome(sections, artists)
    }

    suspend fun catalog(catalogId: String, search: String?): List<TvMusicItem> {
        val path = if (search.isNullOrBlank()) "/catalog/music/$catalogId.json"
        else "/catalog/music/$catalogId/search=${encode(search)}.json"
        return JSONObject(getText(path)).optJSONArray("metas")?.items().orEmpty()
    }

    suspend fun albumTracks(album: TvMusicItem): List<TvMusicItem> {
        val identifier = album.id.substringAfter(":")
        val path = if (album.id.startsWith("audiuspl:")) "/api/music/audius-album/" else "/api/music/album/"
        val tracks = JSONObject(getText(path + encode(identifier))).optJSONArray("tracks") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { i ->
            val t = tracks.optJSONObject(i) ?: return@mapNotNull null
            val url = t.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            TvMusicItem(
                id = "${album.id}#$i",
                title = t.optString("title").ifBlank { "Track ${i + 1}" },
                artist = album.artist.ifBlank { album.title },
                artwork = album.artwork,
                isLive = false,
                isAlbum = false,
                url = url,
            )
        }
    }

    suspend fun streamUrl(id: String): String? {
        val streams = JSONObject(getText("/stream/music/${encode(id)}.json")).optJSONArray("streams") ?: return null
        return (0 until streams.length()).firstNotNullOfOrNull { i ->
            streams.optJSONObject(i)?.optString("url")?.takeIf { it.startsWith("http") }
        }
    }

    suspend fun youtubeMusic(query: String): List<TvYouTubeVideo> =
        parseVideos(JSONObject(getText("/api/music/youtube/search?q=${encode(query)}")).optJSONArray("results"))
}

internal data class TvMusicPlayerState(
    val queue: List<TvMusicItem> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
) {
    val current: TvMusicItem? get() = queue.getOrNull(index)
}

/** App-wide audio player for the TV Music tab. Music keeps playing while you browse. */
internal object TvMusicPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var loadJob: Job? = null
    private val _state = MutableStateFlow(TvMusicPlayerState())
    val state: StateFlow<TvMusicPlayerState> = _state.asStateFlow()
    private val _recent = MutableStateFlow<List<TvMusicItem>>(emptyList())
    val recent: StateFlow<List<TvMusicItem>> = _recent.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            report()
            if (player?.isPlaying == true) handler.postDelayed(this, 500L)
        }
    }

    private fun ensure(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .build()
        .also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handler.removeCallbacks(ticker)
                    if (isPlaying) handler.post(ticker) else report()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    report()
                    if (playbackState == Player.STATE_ENDED) next()
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { it.copy(error = "This track couldn't be played.", isBuffering = false) }
                }
            })
            player = exo
        }

    private fun report() {
        val p = player ?: return
        _state.update {
            it.copy(
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0L,
            )
        }
    }

    private var appContext: Context? = null

    fun playQueue(context: Context, items: List<TvMusicItem>, start: Int) {
        if (items.isEmpty()) return
        appContext = context.applicationContext
        _state.update { it.copy(queue = items, index = start.coerceIn(0, items.lastIndex), error = null, positionMs = 0, durationMs = 0) }
        load()
    }

    private fun load() {
        val context = appContext ?: return
        val item = _state.value.current ?: return
        loadJob?.cancel()
        _state.update { it.copy(isBuffering = true, isPlaying = false, error = null) }
        _recent.update { list -> (listOf(item) + list.filterNot { it.id == item.id }).take(20) }
        loadJob = scope.launch {
            val url = item.url ?: runCatching { TvMusicApi.streamUrl(item.id) }.getOrNull()
            if (url == null) {
                _state.update { it.copy(isBuffering = false, error = "This track can't be played right now.") }
                return@launch
            }
            val p = ensure(context)
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        }
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() { player?.pause() }

    fun next() {
        val s = _state.value
        if (s.index < s.queue.lastIndex) {
            _state.update { it.copy(index = s.index + 1) }
            load()
        } else {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun previous() {
        val s = _state.value
        val p = player
        if ((p?.currentPosition ?: 0L) > 4_000L || s.index <= 0) {
            p?.seekTo(0L)
            return
        }
        _state.update { it.copy(index = s.index - 1) }
        load()
    }

    fun jumpTo(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        load()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        report()
    }

    fun stop() {
        loadJob?.cancel()
        handler.removeCallbacks(ticker)
        player?.stop()
        player?.clearMediaItems()
        _state.value = TvMusicPlayerState()
    }
}

// --- YouTube (official Data API through the music addon) ----------------------

internal data class TvYouTubeVideo(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumbnail: String,
    val publishedAt: String,
    val duration: String?,
    val isLive: Boolean,
)

internal data class TvYouTubePage(val videos: List<TvYouTubeVideo>, val nextPageToken: String?)

internal fun parseVideos(arr: JSONArray?): List<TvYouTubeVideo> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("videoId")
        if (!Regex("^[a-zA-Z0-9_-]{11}$").matches(id)) return@mapNotNull null
        TvYouTubeVideo(
            videoId = id,
            title = o.optString("title"),
            channel = o.optString("channel"),
            channelId = o.optString("channelId"),
            thumbnail = o.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
            publishedAt = o.optString("publishedAt"),
            duration = o.optString("duration").ifBlank { null },
            isLive = o.optBoolean("isLive"),
        )
    }
}

internal object TvYouTubeApi {
    private fun page(body: String): TvYouTubePage {
        val root = JSONObject(body)
        return TvYouTubePage(parseVideos(root.optJSONArray("results")), root.optString("nextPageToken").ifBlank { null })
    }

    private fun token(t: String?) = if (t.isNullOrBlank()) "" else "&pageToken=${TvMusicApi.encode(t)}"

    suspend fun trending(category: String, pageToken: String? = null) =
        page(TvMusicApi.getText("/api/youtube/trending?category=$category&region=US" + token(pageToken)))

    suspend fun search(query: String, pageToken: String? = null) =
        page(TvMusicApi.getText("/api/youtube/search?q=${TvMusicApi.encode(query)}" + token(pageToken)))

    suspend fun channel(channelId: String, pageToken: String? = null) =
        page(TvMusicApi.getText("/api/youtube/channel/${TvMusicApi.encode(channelId)}" + token(pageToken).replaceFirst('&', '?')))
}

internal fun formatIsoDuration(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val m = Regex("^P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").matchEntire(iso) ?: return null
    val h = (m.groupValues[2].toIntOrNull() ?: 0) + (m.groupValues[1].toIntOrNull() ?: 0) * 24
    val min = m.groupValues[3].toIntOrNull() ?: 0
    val s = m.groupValues[4].toIntOrNull() ?: 0
    if (h == 0 && min == 0 && s == 0) return null
    return if (h > 0) "%d:%02d:%02d".format(h, min, s) else "%d:%02d".format(min, s)
}

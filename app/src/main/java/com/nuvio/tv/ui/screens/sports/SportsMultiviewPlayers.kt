package com.nuvio.tv.ui.screens.sports

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.material3.Text
import kotlinx.coroutines.delay

/** One selected direct playable source; never infer a URL from an ESPN event ID. */
internal data class TvMultiviewTile(val id: String, val label: String, val url: String)

/**
 * TV-first 2-up/4-up grid. The D-pad focused tile is the only audible player.
 * The caller owns source selection and the full-screen dialog lifecycle.
 *
 * All tiles are siblings in one layout and keyed by tile id, so adding,
 * removing or replacing a tile never restarts the streams that stay on screen.
 */
@Composable
internal fun SportsMultiviewPlayers(
    tiles: List<TvMultiviewTile>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = tiles.take(4)
    if (visible.isEmpty()) return
    // 4-up tiles are a quarter of the screen, so cap adaptive streams at 720p;
    // 2-up tiles get up to 1080p. Fewer pixels = steadier decoding on onn sticks.
    val maxHeight = if (visible.size > 2) 720 else 1080
    val initialFocusId = focusedId?.takeIf { id -> visible.any { it.id == id } } ?: visible.first().id
    Layout(
        modifier = modifier,
        content = {
            visible.forEach { tile ->
                key(tile.id) {
                    TvMultiviewPlayer(
                        tile = tile,
                        maxHeight = maxHeight,
                        audible = tile.id == focusedId,
                        requestInitialFocus = tile.id == initialFocusId,
                        onFocus = { onFocus(tile.id) },
                    )
                }
            }
        }
    ) { measurables, constraints ->
        val count = measurables.size
        val cols = if (count == 1) 1 else 2
        val rows = (count + cols - 1) / cols
        val gap = 6.dp.roundToPx()
        val totalWidth = constraints.maxWidth
        val totalHeight = constraints.maxHeight
        val cellHeight = ((totalHeight - gap * (rows - 1)) / rows).coerceAtLeast(0)
        val placements = measurables.mapIndexed { index, measurable ->
            val row = index / cols
            val col = index % cols
            // A lone tile in the last row spans the full width (like the old 3-up layout).
            val itemsInRow = minOf(cols, count - row * cols)
            val cellWidth = ((totalWidth - gap * (itemsInRow - 1)) / itemsInRow).coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(cellWidth, cellHeight))
            Triple(placeable, col * (cellWidth + gap), row * (cellHeight + gap))
        }
        layout(totalWidth, totalHeight) {
            placements.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

/** Owns one ExoPlayer plus its auto-reconnect state. */
internal class MultiviewPlayerHandle private constructor(
    val url: String,
    val player: ExoPlayer,
) {
    var reconnecting by mutableStateOf(false)
        private set
    /** Bumped on every playback error so each failure schedules a retry. */
    var errorSerial by mutableStateOf(0)
        private set
    private var retryCount = 0
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retryCount = 0
                reconnecting = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            reconnecting = true
            errorSerial++
        }
    }

    init {
        player.addListener(listener)
    }

    /** Live streams drop; retry with back-off instead of leaving a black tile. */
    suspend fun reconnect() {
        if (released) return
        retryCount++
        delay((retryCount.coerceAtMost(5) * 2_000L))
        if (released) return
        if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
    }

    private var pausedInBackground = false

    fun pause() {
        if (released) return
        pausedInBackground = true
        player.playWhenReady = false
    }

    fun resumeLive() {
        if (released || !pausedInBackground) return
        pausedInBackground = false
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        // Jump back to the live edge after being paused in the background.
        if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
        player.playWhenReady = true
    }

    fun setMaxHeight(maxHeight: Int) {
        if (released) return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(maxHeight * 16 / 9, maxHeight)
            .build()
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.release()
    }

    companion object {
        fun create(context: Context, url: String, maxHeight: Int): MultiviewPlayerHandle {
            // Four players share a ~2 GB device: keep each buffer small but
            // enough to ride out short network hiccups on live sports.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 12_000,
                    /* maxBufferMs = */ 25_000,
                    /* bufferForPlaybackMs = */ 1_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            val player = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setMaxVideoSize(maxHeight * 16 / 9, maxHeight)
                        .build()
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    setMediaItem(MediaItem.fromUri(url))
                    volume = 0f
                    prepare()
                    playWhenReady = true
                }
            return MultiviewPlayerHandle(url, player)
        }
    }
}

@Composable
private fun TvMultiviewPlayer(
    tile: TvMultiviewTile,
    maxHeight: Int,
    audible: Boolean,
    requestInitialFocus: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val handle = remember(tile.url) { MultiviewPlayerHandle.create(context, tile.url, maxHeight) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    LaunchedEffect(handle, maxHeight) { handle.setMaxHeight(maxHeight) }

    // Pause on Home / screensaver and jump back to live when the app returns,
    // instead of decoding streams in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, handle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> handle.pause()
                Lifecycle.Event.ON_START -> handle.resumeLive()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val player = handle.player
    LaunchedEffect(audible, player) { player.volume = if (audible) 1f else 0f }
    LaunchedEffect(handle.errorSerial, handle) {
        if (handle.errorSerial > 0) handle.reconnect()
    }
    val focusRequester = remember { FocusRequester() }
    if (requestInitialFocus) {
        LaunchedEffect(Unit) {
            // Let the dialog attach before grabbing focus so D-pad starts in the grid.
            delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }
    var focused by remember { mutableStateOf(false) }
    val highlighted = focused || audible
    Box(
        modifier
            .background(Color.Black, RoundedCornerShape(8.dp))
            .border(
                if (highlighted) 3.dp else 1.dp,
                if (highlighted) Color(0xFFE5B84D) else Color.DarkGray,
                RoundedCornerShape(8.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onFocus)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    // Stop Google TV's screensaver/ambient mode while watching.
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(true)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view -> if (view.player !== player) view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize().padding(3.dp)
        )
        Text(
            text = (if (audible) "🔊  " else "") + tile.label +
                (if (handle.reconnecting) "  ·  Reconnecting…" else ""),
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

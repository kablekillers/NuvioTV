package com.nuvio.tv.ui.screens.sports

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** One selected direct playable source; never infer a URL from an ESPN event ID. */
internal data class TvMultiviewTile(val id: String, val label: String, val url: String)

/**
 * TV-first 2-up/4-up grid. The D-pad focused tile is the only audible player.
 * The caller owns source selection and the full-screen dialog lifecycle.
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.chunked(2).forEach { pair ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { tile ->
                    TvMultiviewPlayer(
                        tile = tile,
                        audible = tile.id == focusedId,
                        onFocus = { onFocus(tile.id) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvMultiviewPlayer(
    tile: TvMultiviewTile,
    audible: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(tile.id, tile.url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(tile.url))
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(audible, player) { player.volume = if (audible) 1f else 0f }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .background(Color.Black, RoundedCornerShape(8.dp))
            .border(
                if (focused || audible) 3.dp else 1.dp,
                if (focused || audible) Color(0xFFE5B84D) else Color.DarkGray,
                RoundedCornerShape(8.dp)
            )
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
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
    }
}

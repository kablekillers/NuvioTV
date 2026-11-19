package com.nuvio.tv.ui.screens.calendar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.CinemaGold
import com.nuvio.tv.ui.theme.cinemaFocusShine

private val Muted = Color(0xFF9E998F)
private val Card = Color.White.copy(alpha = 0.045f)
private val CardBorder = Color.White.copy(alpha = 0.07f)

/**
 * TV calendar: a "Coming up" strip of the next episodes, then every upcoming (or recently aired)
 * episode of Library shows grouped by day. OK opens the show.
 */
@Composable
fun CalendarScreen(
    onOpenShow: (CalendarEpisode) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var recent by rememberSaveable { mutableStateOf(false) }
    val list = if (recent) state.recent else state.upcoming
    val next = state.upcoming.filter { it.dayOffset <= 14 }.distinctBy { it.showId }.take(12)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF08080A)),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("YOUR SHOWS", color = CinemaGold.Base, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.6.sp)
                Text("Calendar", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (next.isNotEmpty() && !recent) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Coming up", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        items(next, key = { "${it.showId}_${it.date}" }) { ep -> ComingUpCard(ep) { onOpenShow(ep) } }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                Pill("Upcoming", selected = !recent) { recent = false }
                Pill("Recently aired", selected = recent) { recent = true }
            }
        }
        when {
            !state.loaded -> item { Text("Loading your shows…", color = Muted, fontSize = 15.sp) }
            list.isEmpty() -> item {
                Text(
                    if (recent) "No episodes of your shows aired in the last two weeks."
                    else "Nothing scheduled yet. Add shows to your Library and their new episodes will show up here.",
                    color = Muted, fontSize = 15.sp
                )
            }
            else -> list.groupBy { it.date }.forEach { (_, day) ->
                item(key = "day_${day.first().date}") {
                    Text(
                        day.first().dayLabel,
                        color = if (day.first().dayOffset == 0L) CinemaGold.Base else Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
                items(day, key = { "${it.showId}_${it.season}_${it.episode}_${it.date}" }) { ep -> EpisodeRow(ep) { onOpenShow(ep) } }
            }
        }
    }
}

@Composable
private fun focusScale(focused: Boolean, to: Float): Float {
    val s by animateFloatAsState(if (focused) to else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "cal_focus")
    return s
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, 1.06f)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused || selected) CinemaGold.brush else Brush.linearGradient(listOf(Color(0x12FFFFFF), Color(0x12FFFFFF))))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(text, color = if (focused || selected) CinemaGold.Ink else Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun EpisodeRow(ep: CalendarEpisode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, 1.02f)
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) CinemaGold.Base.copy(alpha = 0.12f) else Card)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
            .cinemaFocusShine(focused, strength = 0.12f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = ep.poster, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).height(82.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF17161A))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ep.showName, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(ep.code.takeIf { it.isNotBlank() }, ep.title).joinToString(" · "), color = Color(0xFFCFC9BE), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ComingUpCard(ep: CalendarEpisode, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = focusScale(focused, 1.08f)
    Column(
        Modifier
            .width(260.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF17161A))
                .border(if (focused) 3.dp else 0.dp, if (focused) CinemaGold.Soft else Color.Transparent, RoundedCornerShape(14.dp))
                .cinemaFocusShine(focused)
        ) {
            AsyncImage(model = ep.still ?: ep.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
            Box(
                Modifier.padding(10.dp).clip(CircleShape)
                    .background(if (ep.dayOffset == 0L) CinemaGold.brush else Brush.linearGradient(listOf(Color(0x99000000), Color(0x99000000))))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(ep.dayLabel, color = if (ep.dayOffset == 0L) CinemaGold.Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(ep.code, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
        }
        Text(ep.showName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ep.title?.let { Text(it, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(2.dp))
    }
}

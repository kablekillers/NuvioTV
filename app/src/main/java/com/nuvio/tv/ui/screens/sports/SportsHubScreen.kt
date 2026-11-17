package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val ElectricBlue = Color(0xFF19A8FF)
private val Background = Color(0xFF070F20)
private val Surface = Color(0xFF13243C)
private val SurfaceFocused = Color(0xFF1C3A60)
private val LiveRed = Color(0xFFFF4D5E)
private val Muted = Color(0xFFB4C2D6)

@Composable
fun SportsHubScreen(repository: SportsEventRepository = SportsEventRepository.shared) {
    var selectedLeague by remember { mutableStateOf(SportsLeague.NFL) }
    var board by remember { mutableStateOf<SportsScoreboard?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshFailed by remember { mutableStateOf(false) }
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(selectedLeague, lifecycleOwner) {
        board = repository.cached(selectedLeague)
        loading = board == null
        refreshFailed = false
        // Only poll while the screen is visible; stops when the app is backgrounded.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var first = true
            while (true) {
                try {
                    board = repository.scoreboard(selectedLeague, forceRefresh = !first)
                    refreshFailed = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    refreshFailed = true
                }
                loading = false
                first = false
                delay(SportsEventRepository.refreshIntervalMillis(board))
            }
        }
    }

    val events = board?.takeIf { it.league == selectedLeague }?.events.orEmpty()

    Column(
        modifier = Modifier.fillMaxSize().background(Background)
            .verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text("⚡ KABLE KILLERS SPORTS", color = ElectricBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(statusLine(board, refreshFailed, selectedLeague), color = Muted, fontSize = 15.sp)

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SportsLeague.entries.forEach { league ->
                LeagueTab(league, active = league == selectedLeague) {
                    if (league != selectedLeague) selectedLeague = league
                }
            }
        }

        when {
            loading && events.isEmpty() ->
                InfoPanel("Loading ${selectedLeague.label} scores…")
            refreshFailed && events.isEmpty() ->
                InfoPanel("Scores unavailable. Check your connection — we'll keep trying automatically.")
            events.isEmpty() ->
                InfoPanel("No ${selectedLeague.label} games on the schedule right now.")
            else -> {
                val sections = listOf(
                    "LIVE NOW" to SportsEventSections.live(events),
                    "UPCOMING" to SportsEventSections.upcoming(events),
                    "FINAL" to SportsEventSections.final(events),
                    "REPLAYS" to SportsEventSections.replays(events),
                    "HIGHLIGHTS" to SportsEventSections.highlights(events)
                )
                sections.forEach { (section, items) ->
                    if (items.isEmpty() && section != "LIVE NOW") return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(section, color = if (section == "LIVE NOW") LiveRed else ElectricBlue,
                            fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        if (items.isEmpty()) {
                            InfoPanel("No ${selectedLeague.label} games live right now.")
                        } else {
                            items.take(30).forEach { event ->
                                val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                                GameCard(event, requester) { selectedEventId = event.id }
                            }
                        }
                    }
                }
            }
        }
    }

    val selected = selectedEventId?.let { id -> events.firstOrNull { it.id == id } }
    if (selectedEventId != null) {
        val closeDetails = {
            val id = selectedEventId
            selectedEventId = null
            id?.let { cardFocus[it] }?.let { runCatching { it.requestFocus() } }
            Unit
        }
        if (selected == null) {
            // Game dropped off the provider's scoreboard (e.g. league switched); just close.
            LaunchedEffect(Unit) { closeDetails() }
        } else {
            GameDetailsDialog(selected, onDismiss = closeDetails)
        }
    }
}

private fun statusLine(board: SportsScoreboard?, refreshFailed: Boolean, league: SportsLeague): String {
    val base = "Live scores and schedules • Data: ESPN"
    val fetched = board?.takeIf { it.league == league }?.fetchedAtEpochMillis ?: return base
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fetched))
    return if (refreshFailed) "$base • Couldn't refresh — showing scores from $time"
    else "$base • Updated $time"
}

@Composable
private fun LeagueTab(league: SportsLeague, active: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .border(
                if (focused || active) 2.dp else 1.dp,
                if (focused) Color.White else if (active) ElectricBlue else Color.DarkGray,
                RoundedCornerShape(12.dp)
            )
            .background(if (active) Color(0xFF124B78) else Surface, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Text(league.label, color = Color.White, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun InfoPanel(message: String) {
    Text(
        message,
        color = Muted,
        modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(16.dp)
    )
}

@Composable
private fun StatusBadge(event: SportsEvent) {
    val (label, color) = when (event.status) {
        SportsEventStatus.LIVE -> "LIVE" to LiveRed
        SportsEventStatus.FINAL -> "FINAL" to Muted
        SportsEventStatus.POSTPONED -> "POSTPONED" to Color(0xFFFFB347)
        else -> return
    }
    Text(
        label,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Status text: provider detail for live/final games, local start time for upcoming ones. */
private fun whenText(event: SportsEvent): String = when (event.status) {
    SportsEventStatus.SCHEDULED -> event.startTimeEpochMillis?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: event.statusDetail ?: "Time TBD"
    else -> event.statusDetail ?: event.status.name.lowercase().replaceFirstChar { it.uppercase() }
}

@Composable
private fun GameCard(event: SportsEvent, focusRequester: FocusRequester, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focusRequester)
            .border(if (focused) 3.dp else 0.dp, if (focused) ElectricBlue else Color.Transparent, RoundedCornerShape(12.dp))
            .background(if (focused) SurfaceFocused else Surface, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusBadge(event)
            Text(
                event.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        event.scoreLine()?.let {
            Text(it, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        val details = buildList {
            add(whenText(event))
            if (event.status == SportsEventStatus.SCHEDULED) {
                val records = listOfNotNull(event.away, event.home)
                    .filter { !it.record.isNullOrBlank() }
                    .joinToString("  vs  ") { "${it.shortLabel} ${it.record}" }
                if (records.isNotBlank()) add(records)
            }
            if (event.broadcasts.isNotEmpty()) add("TV: ${event.broadcasts.joinToString(", ")}")
        }
        Text(details.joinToString(" • "), color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GameDetailsDialog(event: SportsEvent, onDismiss: () -> Unit) {
    // Dialog keeps remote focus inside the panel and closes on Back.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.7f)
                .background(Background, RoundedCornerShape(18.dp))
                .border(2.dp, ElectricBlue, RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusBadge(event)
                Text(event.league.label.uppercase(), color = ElectricBlue, fontWeight = FontWeight.Bold)
            }
            Text(event.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)

            val sides = if (event.home != null && event.away != null) listOfNotNull(event.away, event.home) else event.competitors
            sides.forEach { side ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            side.name + if (side.isHome) "  (Home)" else "",
                            color = Color.White, fontSize = 18.sp,
                            fontWeight = if (side.isWinner) FontWeight.Bold else FontWeight.Normal
                        )
                        side.record?.let { Text("Record $it", color = Muted, fontSize = 13.sp) }
                    }
                    if (!side.score.isNullOrBlank()) {
                        Text(side.score, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            DetailRow("When", whenText(event))
            event.venue?.let { DetailRow("Venue", it) }
            DetailRow("Watch on", event.broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Not listed yet")

            Spacer(Modifier.padding(top = 4.dp))
            var focused by remember { mutableStateOf(false) }
            Text(
                "Close",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .focusRequester(closeFocus)
                    .border(2.dp, if (focused) Color.White else ElectricBlue, RoundedCornerShape(10.dp))
                    .background(if (focused) ElectricBlue else Surface, RoundedCornerShape(10.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Muted, modifier = Modifier.widthIn(min = 90.dp))
        Text(value, color = Color.White)
    }
}

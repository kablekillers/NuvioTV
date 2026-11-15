package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

private val ElectricBlue = Color(0xFF19A8FF)
private val Background = Color(0xFF070F20)
private val Surface = Color(0xFF13243C)

@Composable
fun SportsHubScreen() {
    var selectedLeague by remember { mutableStateOf(SportsLeague.NFL) }
    var events by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val provider = remember { EspnSportsEventProvider() }

    LaunchedEffect(selectedLeague) {
        loading = true
        error = null
        events = emptyList()
        runCatching { provider.events(selectedLeague) }
            .onSuccess { events = it }
            .onFailure { error = "Schedules unavailable. Check your connection and try another league." }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background)
            .verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text("⚡ KABLE KILLERS SPORTS", color = ElectricBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Live scores and upcoming matchups • Schedule data: ESPN", color = Color.White, fontSize = 16.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SportsLeague.entries.forEach { league ->
                var focused by remember { mutableStateOf(false) }
                val active = league == selectedLeague
                Box(
                    modifier = Modifier
                        .border(if (focused || active) 2.dp else 1.dp,
                            if (focused || active) ElectricBlue else Color.DarkGray,
                            RoundedCornerShape(12.dp))
                        .background(if (active) Color(0xFF124B78) else Surface, RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .clickable { selectedLeague = league }
                        .padding(horizontal = 14.dp, vertical = 13.dp)
                ) {
                    Text(league.label, color = Color.White,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (loading) {
            Text("Loading ${selectedLeague.label} schedule…", color = Color.LightGray)
        } else if (error != null) {
            Text(error.orEmpty(), color = Color.LightGray)
        } else {
            val groups = listOf(
                "LIVE NOW" to SportsEventSections.live(events),
                "UPCOMING" to SportsEventSections.upcoming(events),
                "REPLAYS" to SportsEventSections.replays(events),
                "HIGHLIGHTS" to SportsEventSections.highlights(events)
            )
            groups.forEach { (section, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(section, color = ElectricBlue, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    if (items.isEmpty()) {
                        Text(
                            if (section == "REPLAYS" || section == "HIGHLIGHTS")
                                "No verified ${section.lowercase()} provider connected yet."
                            else "No ${section.lowercase()} events returned for ${selectedLeague.label}.",
                            color = Color.LightGray,
                            modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(16.dp)
                        )
                    } else {
                        items.take(20).forEach { event ->
                            val time = event.startTimeEpochMillis?.let {
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                            } ?: "Time TBD"
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(Surface, RoundedCornerShape(12.dp)).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(event.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("${event.status.name} • $time • ${event.providerName}", color = Color.LightGray)
                                Text("Playback source not connected", color = ElectricBlue, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

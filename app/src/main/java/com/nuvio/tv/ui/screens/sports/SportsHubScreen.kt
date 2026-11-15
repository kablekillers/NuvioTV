package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val ElectricBlue = Color(0xFF19A8FF)
private val Background = Color(0xFF070F20)
private val Surface = Color(0xFF13243C)
private val Leagues = listOf("NFL", "College Football", "NBA", "MLB", "NHL", "UFC")
private val Sections = listOf("LIVE NOW", "UPCOMING", "REPLAYS", "HIGHLIGHTS")

/**
 * First Sports Hub milestone: remote-focusable shell only.
 * No event or stream is represented as available until a verified provider is connected.
 */
@Composable
fun SportsHubScreen() {
    var selectedLeague by remember { mutableStateOf("NFL") }
    Column(
        modifier = Modifier.fillMaxSize().background(Background).verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text("⚡ KABLE KILLERS SPORTS", color = ElectricBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Your sports. One place.", color = Color.White, fontSize = 18.sp)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Leagues.forEach { league ->
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
                    Text(league, color = Color.White, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Sections.forEach { section ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(section, color = ElectricBlue, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "No verified ${section.lowercase()} events available for $selectedLeague yet.",
                    color = Color.LightGray,
                    modifier = Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(12.dp)).padding(18.dp)
                )
            }
        }
    }
}

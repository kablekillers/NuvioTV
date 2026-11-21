package com.nuvio.tv.ui.screens.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

private val ElectricBlue = Color(0xFF19A8FF)
private val Background = Color(0xFF070F20)
private val Surface = Color(0xFF13243C)

@Composable
fun MusicHubScreen(
    onOpenRecording: (MusicResult) -> Unit,
    viewModel: MusicHubViewModel = hiltViewModel()
) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MusicCategory.SONGS) }

    LaunchedEffect(search, category) {
        delay(350)
        viewModel.search(search.trim(), category)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background).padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("♫  KABLE KILLERS MUSIC", color = ElectricBlue, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Search songs and artists, explore recordings and albums, or listen to live radio.",
            color = Color.LightGray, fontSize = 16.sp)
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search artist, song title, or album") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MusicCategory.entries.forEach { option ->
                MusicCategoryTab(option, category == option) { category = option }
            }
        }
        if (viewModel.loading) {
            Text("Searching music sources…", color = Color.LightGray)
        } else if (viewModel.message != null) {
            Text(viewModel.message ?: "", color = Color.LightGray)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(viewModel.results, key = { it.id }) { result ->
                MusicResultRow(result) { onOpenRecording(result) }
            }
        }
    }
}

@Composable
private fun MusicCategoryTab(category: MusicCategory, active: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = category.label,
        color = Color.White,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(if (active) Color(0xFF124B78) else Surface, RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else ElectricBlue, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    )
}

@Composable
private fun MusicResultRow(result: MusicResult, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
            .background(Surface, RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF27435E), RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        AsyncImage(
            model = result.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp)
        )
        Column {
            Text(result.title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(result.category.label, color = ElectricBlue, fontSize = 13.sp)
        }
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.music

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

internal val KkBlue = Color(0xFF19A8FF)
internal val HubBlack = Color.Black
internal val HubSurface = Color(0xFF15171C)

/** A focusable TV card with the Kable Killers focus ring. */
@Composable
internal fun HubCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    selected: Boolean = false,
    focusedScale: Float = 1.06f,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.colors(
            containerColor = if (selected) Color.White else HubSurface,
            focusedContainerColor = if (selected) Color.White else Color(0xFF23262E),
        ),
        shape = CardDefaults.shape(RoundedCornerShape(corner)),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(3.dp, KkBlue), shape = RoundedCornerShape(corner)),
        ),
        scale = CardDefaults.scale(focusedScale = focusedScale),
    ) {
        Box(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun HubArtwork(url: String?, modifier: Modifier, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(HubSurface), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.25f))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A search box that opens the TV keyboard when selected. */
@Composable
internal fun HubSearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editing by remember { mutableStateOf(false) }
    Card(
        onClick = {
            inputFocus.requestFocus()
            keyboard?.show()
        },
        modifier = modifier,
        colors = CardDefaults.colors(containerColor = HubSurface, focusedContainerColor = Color(0xFF23262E)),
        shape = CardDefaults.shape(RoundedCornerShape(30.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(border = BorderStroke(3.dp, KkBlue), shape = RoundedCornerShape(30.dp)),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.6f))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.45f), fontSize = 17.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(if (editing) KkBlue else Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocus)
                        .onFocusChanged { editing = it.isFocused }
                        .onKeyEvent { event ->
                            val code = event.nativeKeyEvent.keyCode
                            (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_UP &&
                                run { keyboard?.hide(); true }
                        },
                )
            }
        }
    }
}

/** A pill-shaped tab button for the hub tab rows. */
@Composable
internal fun HubPill(label: String, selected: Boolean, onClick: () -> Unit) {
    HubCard(onClick = onClick, corner = 24.dp, selected = selected, focusedScale = 1.05f) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

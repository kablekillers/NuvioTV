package com.nuvio.tv.ui.screens.sports

import android.net.Uri
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Raw resource name of the Sports Hub intro video: `app/src/main/res/raw/sports_hub_intro.mp4`.
 * Looked up at runtime so the app still builds and Sports Hub opens normally without it.
 */
const val SPORTS_HUB_INTRO_RESOURCE = "sports_hub_intro"

private val SkipKeys = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
    KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_STOP
)

/** True when the given Android key code should skip the intro. */
internal fun isSportsIntroSkipKey(keyCode: Int): Boolean = keyCode in SkipKeys

/**
 * Fullscreen Sports Hub intro. Calls [onFinished] exactly once: when the video ends,
 * when the viewer presses OK / Back / any direction, if the video is missing or fails,
 * or if it hasn't started within a few seconds.
 */
@Composable
fun SportsHubIntro(onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    val done = remember { BooleanArray(1) }
    val close: () -> Unit = remember {
        {
            if (!done[0]) {
                done[0] = true
                finish()
            }
        }
    }
    val resId = remember {
        context.resources.getIdentifier(SPORTS_HUB_INTRO_RESOURCE, "raw", context.packageName)
    }
    if (resId == 0) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val started = remember { BooleanArray(1) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(4_000)
        if (!started[0]) close() // Never leave the viewer on a black screen.
    }

    // A full-window dialog covers the sidebar too and keeps remote input on the intro.
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (isSportsIntroSkipKey(event.nativeKeyEvent.keyCode)) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) close()
                    true // Swallow both down and up so the hub underneath doesn't react.
                } else {
                    false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    setOnPreparedListener { player ->
                        player.isLooping = false
                        started[0] = true
                        start()
                    }
                    setOnCompletionListener { close() }
                    setOnErrorListener { _, _, _ -> close(); true }
                    setVideoURI(Uri.parse("android.resource://${ctx.packageName}/$resId"))
                }
            },
            onRelease = { view -> runCatching { view.stopPlayback() } }
        )
    }
}
}

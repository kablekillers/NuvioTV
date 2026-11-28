@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** One note from Kable Killers to every customer (same file as Kable Killers Mobile). */
internal data class TvBoardMessage(val id: String, val title: String, val body: String, val alert: Boolean)

/**
 * The message board: notes posted in the Kable Killers store repo (mobile-messages.json) show on
 * every TV the next time the app is opened, and are checked again every 30 minutes. Each note
 * shows once; "Got it" hides it for good.
 */
internal object TvMessageBoard {
    private const val URL_BASE = "https://raw.githubusercontent.com/kablekillers/Kable-Killers-Store/main/mobile-messages.json"
    private const val PREFS = "kk_tv_message_board"

    suspend fun fetch(): List<TvBoardMessage> = withContext(Dispatchers.IO) {
        // A changing query skips GitHub's 5-minute cache, so a new note shows on the next open.
        val minute = System.currentTimeMillis() / 60_000L
        val connection = (URL("$URL_BASE?t=$minute").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val list = root.optJSONArray("messages") ?: return@withContext emptyList()
            val now = Instant.now()
            (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                val title = o.optString("title").trim()
                val body = o.optString("body").trim()
                if (id.isEmpty() || (title.isEmpty() && body.isEmpty())) return@mapNotNull null
                val expires = o.optString("expires").trim()
                if (expires.isNotEmpty()) {
                    val until = runCatching { Instant.parse(if ('T' in expires) expires else "${expires}T23:59:59Z") }.getOrNull()
                    if (until != null && !until.isAfter(now)) return@mapNotNull null
                }
                TvBoardMessage(id, title, body, o.optString("level").equals("alert", ignoreCase = true))
            }
        } finally {
            connection.disconnect()
        }
    }

    fun dismissed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("dismissed", emptySet()).orEmpty()

    fun dismiss(context: Context, ids: Set<String>) {
        val keep = (dismissed(context) + ids).toList().takeLast(60).toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("dismissed", keep).apply()
    }
}

@Composable
fun TvMessageBoardHost() {
    val context = LocalContext.current
    var showing by remember { mutableStateOf<List<TvBoardMessage>>(emptyList()) }
    LaunchedEffect(Unit) {
        delay(4_000)
        while (true) {
            if (showing.isEmpty()) {
                val dismissed = TvMessageBoard.dismissed(context)
                val fresh = runCatching { TvMessageBoard.fetch() }.getOrDefault(emptyList()).filter { it.id !in dismissed }
                if (fresh.isNotEmpty()) showing = fresh
            }
            delay(30 * 60_000L)
        }
    }
    if (showing.isEmpty()) return
    val dismiss = {
        TvMessageBoard.dismiss(context, showing.map { it.id }.toSet())
        showing = emptyList()
    }
    val alert = showing.any { it.alert }
    val accent = if (alert) Color(0xFFFF5A5F) else Color(0xFF19A8FF)
    val buttonFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(760.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF0B0F18)).padding(36.dp),
            ) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(accent).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(
                        if (alert) "IMPORTANT · KABLE KILLERS" else "MESSAGE FROM KABLE KILLERS",
                        color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.size(18.dp))
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    showing.forEach { message ->
                        Column {
                            if (message.title.isNotBlank()) {
                                Text(message.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.size(8.dp))
                            }
                            if (message.body.isNotBlank()) {
                                Text(message.body, color = Color.White.copy(alpha = 0.9f), fontSize = 19.sp, lineHeight = 26.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(26.dp))
                Card(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth().focusRequester(buttonFocus),
                    colors = CardDefaults.colors(containerColor = accent, focusedContainerColor = accent),
                    shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
                    border = CardDefaults.border(
                        focusedBorder = Border(border = BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(18.dp)),
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.02f),
                ) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }
    }
}

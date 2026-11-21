@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
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
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "What's new" box for Kable Killers TV, shown once after the app is updated to a new version
 * (never on a fresh install). Notes for each version live here; when a version has none, its
 * GitHub release notes are used.
 */
internal object TvWhatsNewNotes {
    val byVersion: Map<String, List<String>> = mapOf(
        "1.1.13" to listOf(
            "A What's new box like this one now appears after each update.",
            "Music and YouTube tabs in the sidebar, under Sports Hub.",
            "Night mode in the audio panel: Off, Low, Medium or High.",
            "Sync by ear in the subtitle panel fixes out-of-sync subtitles.",
            "Preview pictures while fast-forwarding or rewinding, now faster because they're made in the background.",
        ),
    )
}

private object TvWhatsNewStorage {
    private const val PREFS = "kk_tv_whats_new"
    private const val KEY = "last_seen_version"

    fun lastSeen(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun setLastSeen(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, version).apply()
    }

    fun wasUpdatedSinceInstall(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime + 60_000L
    }.getOrDefault(false)
}

private suspend fun releaseNotesFromGitHub(version: String): List<String> = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/kablekillers/NuvioTV/releases/tags/v$version").openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    try {
        if (connection.responseCode !in 200..299) return@withContext emptyList()
        val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("body").trim()
        val bullets = body.lines().map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") }
            .map { it.trimStart('-', '*', '•', ' ') }
            .filter { it.isNotBlank() }
        bullets.ifEmpty { listOfNotNull(body.takeIf { it.isNotEmpty() && it.length < 600 }) }
    } finally {
        connection.disconnect()
    }
}

@Composable
fun TvWhatsNewHost() {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    var notes by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(version) {
        val lastSeen = TvWhatsNewStorage.lastSeen(context)
        if (lastSeen == version) return@LaunchedEffect
        // Test builds install fresh, so they show the box once to make it testable.
        val isUpdate = lastSeen != null || TvWhatsNewStorage.wasUpdatedSinceInstall(context) || BuildConfig.DEBUG
        if (!isUpdate) {
            TvWhatsNewStorage.setLastSeen(context, version)
            return@LaunchedEffect
        }
        val list = TvWhatsNewNotes.byVersion[version]
            ?: runCatching { releaseNotesFromGitHub(version) }.getOrDefault(emptyList())
        if (list.isEmpty()) TvWhatsNewStorage.setLastSeen(context, version) else notes = list
    }

    val current = notes ?: return
    val dismiss = {
        TvWhatsNewStorage.setLastSeen(context, version)
        notes = null
    }
    val buttonFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(760.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF0B0F18))
                    .padding(36.dp),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF0A4DFF), Color(0xFF19A8FF))))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text("UPDATED TO $version", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.size(14.dp))
                Text("What's new in Kable Killers", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.size(18.dp))
                Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    current.forEach { note ->
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 9.dp).size(9.dp).clip(CircleShape).background(Color(0xFF19A8FF)))
                            Spacer(Modifier.width(14.dp))
                            Text(note, color = Color.White.copy(alpha = 0.9f), fontSize = 19.sp, lineHeight = 26.sp)
                        }
                    }
                }
                Spacer(Modifier.size(26.dp))
                Card(
                    onClick = dismiss,
                    modifier = Modifier.fillMaxWidth().focusRequester(buttonFocus),
                    colors = CardDefaults.colors(containerColor = Color(0xFF19A8FF), focusedContainerColor = Color(0xFF19A8FF)),
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

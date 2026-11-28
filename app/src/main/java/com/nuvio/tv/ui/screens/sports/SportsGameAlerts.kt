package com.nuvio.tv.ui.screens.sports

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject

/** What was last seen of a My Teams game, so each alert is shown once. */
internal data class SportsAlertSeen(
    val status: SportsEventStatus,
    val soonSent: Boolean = false,
    val startSent: Boolean = false,
    val closeSent: Boolean = false,
    val finalSent: Boolean = false,
    val startMillis: Long? = null,
)

internal data class SportsGameAlert(val eventId: String, val title: String, val body: String)

/**
 * Game alerts for My Teams (same rules as Kable Killers Mobile): about to start, started,
 * close late, final. Pure logic, no network or storage.
 */
internal object SportsGameAlerts {
    const val SOON_WINDOW_MILLIS = 20 * 60_000L

    private fun emoji(league: SportsLeague): String = when (league) {
        SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "🏈"
        SportsLeague.NBA, SportsLeague.WNBA, SportsLeague.COLLEGE_BASKETBALL -> "🏀"
        SportsLeague.MLB, SportsLeague.COLLEGE_BASEBALL -> "⚾"
        SportsLeague.NHL, SportsLeague.COLLEGE_HOCKEY -> "🏒"
        else -> "🏆"
    }

    private fun scoreLine(event: SportsEvent): String {
        val away = event.away
        val home = event.home
        return if (away != null && home != null && event.hasScores) {
            "${away.shortLabel} ${away.score} – ${home.shortLabel} ${home.score}"
        } else event.title
    }

    private fun margin(event: SportsEvent): Int? {
        if (!event.hasScores || event.competitors.size < 2) return null
        val a = event.competitors[0].score?.toIntOrNull() ?: return null
        val b = event.competitors[1].score?.toIntOrNull() ?: return null
        return kotlin.math.abs(a - b)
    }

    /** Late in the game and within one score, by sport. */
    fun isCloseLate(event: SportsEvent): Boolean {
        val detail = event.statusDetail?.lowercase() ?: return false
        val m = margin(event) ?: return false
        val ot = Regex("""\bot\b|overtime|\bso\b|shootout""").containsMatchIn(detail)
        return when (event.league) {
            SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL ->
                (ot || Regex("""\b4th\b""").containsMatchIn(detail)) && m <= 8
            SportsLeague.NBA, SportsLeague.WNBA ->
                (ot || Regex("""\b4th\b""").containsMatchIn(detail)) && m <= 6
            SportsLeague.COLLEGE_BASKETBALL ->
                (ot || Regex("""\b(2nd|4th)\b""").containsMatchIn(detail)) && m <= 6 && lastMinutes(detail, 5)
            SportsLeague.NHL, SportsLeague.COLLEGE_HOCKEY ->
                (ot || Regex("""\b3rd\b""").containsMatchIn(detail)) && m <= 1
            SportsLeague.MLB, SportsLeague.COLLEGE_BASEBALL ->
                Regex("""\b(8th|9th|1[0-9]th)\b""").containsMatchIn(detail) && m <= 2
            else -> false
        }
    }

    private fun lastMinutes(detail: String, minutes: Int): Boolean {
        val clock = Regex("""(\d{1,2}):(\d{2})""").find(detail) ?: return true
        return clock.groupValues[1].toInt() < minutes
    }

    fun evaluate(
        events: List<SportsEvent>,
        prefs: SportsPrefs,
        seen: Map<String, SportsAlertSeen>,
        nowMillis: Long,
    ): Pair<List<SportsGameAlert>, Map<String, SportsAlertSeen>> {
        val alerts = mutableListOf<SportsGameAlert>()
        val next = HashMap<String, SportsAlertSeen>()
        events.filter { SportsPreferences.involvesFavorite(it, prefs) }.forEach { event ->
            val key = event.league.name + "|" + event.id
            val before = seen[key]
            var now = (before ?: SportsAlertSeen(event.status)).copy(status = event.status, startMillis = event.startTimeEpochMillis)
            val icon = emoji(event.league)
            val start = event.startTimeEpochMillis
            when (event.status) {
                SportsEventStatus.SCHEDULED -> if (!now.soonSent && start != null && start - nowMillis in 0..SOON_WINDOW_MILLIS) {
                    val mins = ((start - nowMillis) / 60_000L).coerceAtLeast(1)
                    alerts += SportsGameAlert(event.id, "$icon ${event.title}", "Starts in $mins min${if (mins == 1L) "" else "s"}")
                    now = now.copy(soonSent = true)
                }
                SportsEventStatus.LIVE -> {
                    if (!now.startSent) {
                        val fresh = before != null || (start != null && nowMillis - start < 30 * 60_000L)
                        if (fresh) alerts += SportsGameAlert(event.id, "$icon ${event.title} is on", scoreLine(event))
                        now = now.copy(startSent = true, soonSent = true)
                    }
                    if (!now.closeSent && isCloseLate(event)) {
                        alerts += SportsGameAlert(event.id, "$icon Close game: ${event.title}", "${scoreLine(event)} · ${event.statusDetail.orEmpty()}")
                        now = now.copy(closeSent = true)
                    }
                }
                SportsEventStatus.FINAL -> if (!now.finalSent) {
                    if (before != null && before.status == SportsEventStatus.LIVE) {
                        alerts += SportsGameAlert(event.id, "$icon Final: ${event.title}", scoreLine(event))
                    }
                    now = now.copy(finalSent = true, startSent = true, soonSent = true)
                }
                else -> Unit
            }
            next[key] = now
        }
        return alerts to next
    }

    /** Every 4 minutes while a favorite plays, near the next start otherwise (max 30 minutes). */
    fun nextCheckDelayMillis(seen: Map<String, SportsAlertSeen>, nowMillis: Long): Long {
        if (seen.values.any { it.status == SportsEventStatus.LIVE }) return 4 * 60_000L
        val nextStart = seen.values.filter { it.status == SportsEventStatus.SCHEDULED }
            .mapNotNull { it.startMillis }.filter { it > nowMillis }.minOrNull() ?: return 30 * 60_000L
        val soon = nextStart - SOON_WINDOW_MILLIS + 60_000L
        val wait = if (soon > nowMillis) soon - nowMillis else (nextStart + 2 * 60_000L - nowMillis).coerceAtLeast(60_000L)
        return wait.coerceAtMost(30 * 60_000L)
    }

    private const val PREFS = "kk_tv_game_alerts"

    fun loadSeen(context: Context): Map<String, SportsAlertSeen> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("seen", null) ?: return emptyMap()
        val o = JSONObject(raw)
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            SportsAlertSeen(
                status = runCatching { SportsEventStatus.valueOf(v.optString("s")) }.getOrDefault(SportsEventStatus.UNKNOWN),
                soonSent = v.optBoolean("a"), startSent = v.optBoolean("b"),
                closeSent = v.optBoolean("c"), finalSent = v.optBoolean("d"),
                startMillis = v.optLong("t", 0L).takeIf { it > 0 },
            )
        }
    }.getOrDefault(emptyMap())

    fun saveSeen(context: Context, seen: Map<String, SportsAlertSeen>) {
        val o = JSONObject()
        seen.forEach { (k, v) ->
            o.put(k, JSONObject().put("s", v.status.name).put("a", v.soonSent).put("b", v.startSent)
                .put("c", v.closeSent).put("d", v.finalSent).put("t", v.startMillis ?: 0L))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("seen", o.toString()).apply()
    }
}

/**
 * Checks My Teams games in the background while the app is open and shows a short banner
 * (top right, over anything, including the player). It never takes focus.
 */
@Composable
fun SportsGameAlertsHost() {
    val context = LocalContext.current
    var banner by remember { mutableStateOf<SportsGameAlert?>(null) }
    LaunchedEffect(Unit) {
        SportsPreferences.init(context)
        delay(20_000)
        val repository = SportsEventRepository.shared
        while (true) {
            val prefs = SportsPreferences.state.value
            val leagues = SportsPreferences.favoriteLeagues(prefs)
            var wait = 30 * 60_000L
            if (leagues.isNotEmpty()) {
                val events = coroutineScope {
                    leagues.map { lg -> async { runCatching { repository.scoreboard(lg).events }.getOrDefault(emptyList()) } }
                        .awaitAll().flatten()
                }
                val now = System.currentTimeMillis()
                val (alerts, seen) = SportsGameAlerts.evaluate(events, prefs, SportsGameAlerts.loadSeen(context), now)
                SportsGameAlerts.saveSeen(context, seen)
                for (alert in alerts) {
                    banner = alert
                    delay(12_000)
                    banner = null
                    delay(600)
                }
                wait = SportsGameAlerts.nextCheckDelayMillis(seen, now)
            }
            delay(wait)
        }
    }
    val shown = banner ?: return
    // A popup overlays any screen (the player too) without taking space or focus.
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
    ) {
        Column(
            Modifier.padding(top = 28.dp, end = 32.dp).width(420.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xF00B0F18))
                .border(1.dp, Color(0x5519A8FF), RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text("MY TEAMS", color = Color(0xFF19A8FF), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            Text(shown.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
            Text("${shown.body} · Watch in Sports Hub → My Teams", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 2)
        }
    }
}

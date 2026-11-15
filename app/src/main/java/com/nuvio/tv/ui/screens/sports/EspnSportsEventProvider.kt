package com.nuvio.tv.ui.screens.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Read-only scoreboard metadata from ESPN's public website endpoint.
 * This is not a licensed playback API and must never be used to infer stream access.
 * Endpoint availability and usage terms must be reviewed before a production release.
 */
class EspnSportsEventProvider : SportsEventProvider {
    override suspend fun events(league: SportsLeague): List<SportsEvent> = withContext(Dispatchers.IO) {
        val path = when (league) {
            SportsLeague.NFL -> "football/nfl"
            SportsLeague.COLLEGE_FOOTBALL -> "football/college-football"
            SportsLeague.NBA -> "basketball/nba"
            SportsLeague.MLB -> "baseball/mlb"
            SportsLeague.NHL -> "hockey/nhl"
            SportsLeague.UFC -> "mma/ufc"
        }
        val connection = (URL("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "KableKillersTV/1.1.4 (sports scoreboard)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("Sports schedule unavailable (HTTP ${connection.responseCode})")
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            parseScoreboard(JSONObject(payload), league)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun parseScoreboard(root: JSONObject, league: SportsLeague): List<SportsEvent> {
            val events = root.optJSONArray("events") ?: return emptyList()
            return (0 until events.length()).mapNotNull { index ->
                val event = events.optJSONObject(index) ?: return@mapNotNull null
                val id = event.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = event.optString("name").takeIf { it.isNotBlank() }
                    ?: event.optString("shortName").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val competition = event.optJSONArray("competitions")?.optJSONObject(0)
                val status = competition?.optJSONObject("status") ?: event.optJSONObject("status")
                val state = status?.optJSONObject("type")?.optString("state").orEmpty()
                val statusName = status?.optJSONObject("type")?.optString("name").orEmpty()
                val mappedStatus = when {
                    statusName.contains("postpon", true) || statusName.contains("cancel", true) -> SportsEventStatus.POSTPONED
                    state.equals("in", true) -> SportsEventStatus.LIVE
                    state.equals("pre", true) -> SportsEventStatus.SCHEDULED
                    state.equals("post", true) -> SportsEventStatus.FINAL
                    else -> SportsEventStatus.UNKNOWN
                }
                val start = event.optString("date").takeIf { it.isNotBlank() }
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                val url = event.optJSONArray("links")?.optJSONObject(0)?.optString("href")
                    ?.takeIf { it.startsWith("https://") }
                SportsEvent(
                    id = "${league.name}:$id",
                    league = league,
                    title = title,
                    startTimeEpochMillis = start,
                    status = mappedStatus,
                    providerName = "ESPN",
                    detailUrl = url
                )
            }
        }
    }
}

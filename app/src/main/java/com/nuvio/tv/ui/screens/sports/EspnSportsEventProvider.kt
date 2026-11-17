package com.nuvio.tv.ui.screens.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
        val connection = (URL("https://site.api.espn.com/apis/site/v2/sports/${pathFor(league)}/scoreboard")
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
            parseScoreboard(payload, league)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun pathFor(league: SportsLeague) = when (league) {
            SportsLeague.NFL -> "football/nfl"
            SportsLeague.COLLEGE_FOOTBALL -> "football/college-football"
            SportsLeague.NBA -> "basketball/nba"
            SportsLeague.MLB -> "baseball/mlb"
            SportsLeague.NHL -> "hockey/nhl"
            SportsLeague.UFC -> "mma/ufc"
        }

        internal fun parseScoreboard(payload: String, league: SportsLeague): List<SportsEvent> {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
                ?: return emptyList()
            val events = root.arr("events") ?: return emptyList()
            return events.mapNotNull { element ->
                runCatching { parseEvent(element as? JsonObject ?: return@mapNotNull null, league) }.getOrNull()
            }
        }

        private fun parseEvent(event: JsonObject, league: SportsLeague): SportsEvent? {
            val id = event.str("id") ?: return null
            val title = event.str("name") ?: event.str("shortName") ?: return null
            val competitions = event.arr("competitions").orEmpty().mapNotNull { it as? JsonObject }
            // UFC cards list several fights; the main event is reported last.
            val competition = if (league == SportsLeague.UFC) competitions.lastOrNull() else competitions.firstOrNull()
            // For UFC the card-level status reflects prelims already underway.
            val status = if (league == SportsLeague.UFC) {
                event.obj("status") ?: competition?.obj("status")
            } else {
                competition?.obj("status") ?: event.obj("status")
            }
            val type = status?.obj("type")
            val state = type?.str("state").orEmpty()
            val statusName = type?.str("name").orEmpty()
            val mappedStatus = when {
                statusName.contains("postpon", true) || statusName.contains("cancel", true) ||
                    statusName.contains("delay", true) && !state.equals("in", true) -> SportsEventStatus.POSTPONED
                state.equals("in", true) -> SportsEventStatus.LIVE
                state.equals("pre", true) -> SportsEventStatus.SCHEDULED
                state.equals("post", true) -> SportsEventStatus.FINAL
                else -> SportsEventStatus.UNKNOWN
            }
            val start = event.str("date")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: event.str("date")?.let { raw ->
                    // ESPN sometimes omits seconds, e.g. "2026-11-17T01:15Z".
                    runCatching { Instant.parse(raw.replace(Regex("T(\\d{2}:\\d{2})Z$"), "T$1:00Z")).toEpochMilli() }.getOrNull()
                }
            val url = event.arr("links")?.firstOrNull()?.let { it as? JsonObject }?.str("href")
                ?.takeIf { it.startsWith("https://") }

            val competitors = competition?.arr("competitors").orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::parseCompetitor) }
                .takeIf { it.size == 2 }
                .orEmpty()

            val venue = competition?.obj("venue")?.let { venue ->
                val name = venue.str("fullName")
                val address = venue.obj("address")
                val place = listOfNotNull(address?.str("city"), address?.str("state")).joinToString(", ")
                listOfNotNull(name, place.takeIf { it.isNotBlank() }).joinToString(" • ").takeIf { it.isNotBlank() }
            }

            val broadcasts = buildList {
                competition?.arr("broadcasts").orEmpty().forEach { b ->
                    (b as? JsonObject)?.arr("names").orEmpty().forEach { n -> n.primitiveContent()?.let(::add) }
                }
                if (isEmpty()) {
                    competition?.arr("geoBroadcasts").orEmpty().forEach { g ->
                        (g as? JsonObject)?.obj("media")?.str("shortName")?.let(::add)
                    }
                }
            }.map { it.trim() }.filter { it.isNotBlank() }.distinct()

            return SportsEvent(
                id = "${league.name}:$id",
                league = league,
                title = title,
                startTimeEpochMillis = start,
                status = mappedStatus,
                providerName = "ESPN",
                detailUrl = url,
                statusDetail = type?.str("shortDetail") ?: type?.str("detail"),
                competitors = competitors,
                venue = venue,
                broadcasts = broadcasts
            )
        }

        private fun parseCompetitor(c: JsonObject): SportsCompetitor? {
            val team = c.obj("team")
            val athlete = c.obj("athlete")
            val name = team?.str("displayName") ?: team?.str("shortDisplayName")
                ?: athlete?.str("displayName") ?: athlete?.str("shortName") ?: return null
            val abbreviation = team?.str("abbreviation") ?: athlete?.str("shortName")
            val record = c.arr("records")?.firstOrNull()?.let { it as? JsonObject }?.str("summary")
            val score = when (val s = c["score"]) {
                is JsonPrimitive -> s.contentOrNull
                is JsonObject -> s.str("displayValue")
                else -> null
            }?.takeIf { it.isNotBlank() }
            return SportsCompetitor(
                name = name,
                abbreviation = abbreviation,
                score = score,
                record = record,
                isHome = c.str("homeAway").equals("home", true),
                isWinner = (c["winner"] as? JsonPrimitive)?.booleanOrNull == true
            )
        }

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = this[key] as? JsonArray
        private fun JsonObject.str(key: String) = this[key]?.primitiveContent()
        private fun JsonElement.primitiveContent(): String? =
            (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

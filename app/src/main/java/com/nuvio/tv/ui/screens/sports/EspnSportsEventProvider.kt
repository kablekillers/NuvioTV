package com.nuvio.tv.ui.screens.sports

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only scoreboard metadata from ESPN's public website endpoint.
 * This is not a licensed playback API and must never be used to infer stream access.
 * Endpoint availability and usage terms must be reviewed before a production release.
 */
class EspnSportsEventProvider : SportsEventProvider {
    override suspend fun events(league: SportsLeague): List<SportsEvent> = scoreboard(league)

    override suspend fun recentResults(league: SportsLeague, nowEpochMillis: Long): List<SportsEvent> = coroutineScope {
        (1L..3L).map { days ->
            Instant.ofEpochMilli(nowEpochMillis).atZone(ZoneId.systemDefault())
                .toLocalDate().minusDays(days).format(DateTimeFormatter.BASIC_ISO_DATE)
        }.distinct().map { date ->
            async {
                try {
                    SportsEventSections.final(scoreboard(league, date))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun scoreboard(league: SportsLeague, date: String? = null): List<SportsEvent> {
        val path = league.scoreboardPath ?: return emptyList()
        val options = buildList {
            if (league.isCollegeBasketball) add("groups=50&limit=100")
            if (date != null) add("dates=$date")
        }
        val query = options.joinToString("&", prefix = if (options.isEmpty()) "" else "?")
        val payload = get("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard$query")
        return withContext(Dispatchers.Default) { parseScoreboard(payload, league) }
    }

    override suspend fun boxScore(event: SportsEvent): SportsBoxScore? {
        // UFC summaries have no per-athlete stat tables.
        if (!event.league.usesTeamStats || event.status == SportsEventStatus.SCHEDULED) return null
        val payload = get("https://site.api.espn.com/apis/site/v2/sports/${event.league.scoreboardPath}/summary?event=${URLEncoder.encode(event.providerEventId, "UTF-8")}")
        return parseBoxScore(payload, event.id)
    }

    override suspend fun standings(league: SportsLeague): SportsStandings? {
        if (!league.usesTeamStats) return null
        return parseStandings(get("https://site.api.espn.com/apis/v2/sports/${league.scoreboardPath}/standings"), league)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "KableKillersTV/1.1.7 (sports scoreboard)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("Sports schedule unavailable (HTTP ${connection.responseCode})")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun parseScoreboard(payload: String, league: SportsLeague): List<SportsEvent> {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
                ?: error("Invalid sports scoreboard response")
            val events = root.arr("events") ?: error("Missing events in sports scoreboard")
            return events.flatMap { element ->
                val event = element as? JsonObject ?: return@flatMap emptyList()
                when (league) {
                    SportsLeague.ATP, SportsLeague.WTA -> tennisEvents(event, league)
                    SportsLeague.FORMULA_1 -> formulaOneSessions(event, league)
                    else -> listOfNotNull(parseEvent(event, league))
                }
            }.distinctBy { it.id }
        }

        private fun tennisEvents(tournament: JsonObject, league: SportsLeague): List<SportsEvent> {
            val tournamentEvent = parseEvent(tournament, league) ?: return emptyList()
            val matches = tournament.arr("groupings").orEmpty().flatMap { group ->
                (group as? JsonObject)?.arr("competitions").orEmpty().mapNotNull { entry ->
                    val match = entry as? JsonObject ?: return@mapNotNull null
                    val matchId = match.str("id") ?: return@mapNotNull null
                    val parsed = parseEvent(tournament, league, competition = match) ?: return@mapNotNull null
                    if (parsed.competitors.size != 2) return@mapNotNull null
                    parsed.copy(
                        id = "${tournamentEvent.id}:$matchId",
                        title = parsed.competitors.joinToString(" vs ") { it.name },
                        startTimeEpochMillis = if ((match["timeValid"] as? JsonPrimitive)?.booleanOrNull == false) null
                            else parseStart(match.str("startDate") ?: match.str("date")),
                        statusDetail = match.obj("status")?.obj("type")?.str("shortDetail"),
                    )
                }
            }
            return listOf(tournamentEvent) + matches
        }

        private fun formulaOneSessions(event: JsonObject, league: SportsLeague): List<SportsEvent> {
            val weekend = parseEvent(event, league) ?: return emptyList()
            val sessions = event.arr("competitions").orEmpty().mapNotNull { entry ->
                val session = entry as? JsonObject ?: return@mapNotNull null
                val sessionId = session.str("id") ?: return@mapNotNull null
                val sessionCode = session.obj("type")?.str("abbreviation") ?: return@mapNotNull null
                val label = when (sessionCode.lowercase()) {
                    "fp1" -> "Practice 1"
                    "fp2" -> "Practice 2"
                    "fp3" -> "Practice 3"
                    "qual" -> "Qualifying"
                    else -> sessionCode
                }
                parseEvent(event, league, competition = session)?.copy(
                    id = "${weekend.id}:$sessionId",
                    title = "${weekend.title} · $label",
                    startTimeEpochMillis = parseStart(session.str("date")),
                    catalogMatchTitle = event.str("shortName") ?: weekend.title,
                    racingSession = sessionCode,
                )
            }
            return sessions.ifEmpty { listOf(weekend) }
        }

        private fun parseEvent(
            event: JsonObject,
            league: SportsLeague,
            competition: JsonObject? = event.arr("competitions").orEmpty()
                .mapNotNull { it as? JsonObject }
                .let { if (league == SportsLeague.UFC) it.lastOrNull() else it.firstOrNull() },
        ): SportsEvent? {
            val id = event.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val title = (event.str("name") ?: event.str("shortName"))?.takeIf { it.isNotBlank() } ?: return null
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
            val url = event.arr("links")?.firstOrNull()?.let { it as? JsonObject }?.str("href")
                ?.takeIf { it.startsWith("https://") }

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
                startTimeEpochMillis = parseStart(event.str("date")),
                status = mappedStatus,
                providerName = "ESPN",
                detailUrl = url,
                statusDetail = type?.str("shortDetail") ?: type?.str("detail"),
                competitors = competitorsOf(competition, tennis = league.catalogSport == "tennis"),
                venue = venue,
                broadcasts = broadcasts
            )
        }

        private fun parseStart(raw: String?): Long? = raw?.let {
            val withSeconds = it.replace(Regex("T(\\d{2}:\\d{2})Z$"), "T$1:00Z")
            runCatching { Instant.parse(withSeconds).toEpochMilli() }.getOrNull()
        }

        private fun competitorsOf(competition: JsonObject?, tennis: Boolean): List<SportsCompetitor> =
            competition?.arr("competitors").orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { row -> parseCompetitor(row, tennis) } }
                .takeIf { it.size == 2 }.orEmpty().let { sides ->
                    if (tennis && sides.none { it.isHome }) sides.mapIndexed { index, side ->
                        side.copy(isHome = index == 1)
                    } else sides
                }

        /** Stats shown in a league table, in order; only those the league reports are used. */
        private val standingsColumns = listOf(
            "wins" to "W", "losses" to "L", "ties" to "T", "otLosses" to "OTL",
            "winPercent" to "PCT", "points" to "PTS", "gamesBehind" to "GB", "streak" to "STRK",
        )

        /** Parses ESPN standings: conferences (or divisions) that hold ranked team entries. */
        internal fun parseStandings(payload: String, league: SportsLeague): SportsStandings? {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
            val groups = mutableListOf<SportsStandingsGroup>()
            fun visit(node: JsonObject) {
                val entries = node.obj("standings")?.arr("entries").orEmpty().mapNotNull { it as? JsonObject }
                if (entries.isNotEmpty()) {
                    val statsOf = entries.map { entry ->
                        entry.arr("stats").orEmpty().mapNotNull { it as? JsonObject }
                            .associate { (it.str("name") ?: it.str("type")).orEmpty() to (it.str("displayValue") ?: it.str("value")).orEmpty() }
                    }
                    val present = standingsColumns.filter { (key, _) -> statsOf.any { !it[key].isNullOrBlank() } }
                    val rows = entries.mapIndexedNotNull { index, entry ->
                        val team = entry.obj("team") ?: return@mapIndexedNotNull null
                        val name = team.str("displayName") ?: team.str("shortDisplayName") ?: return@mapIndexedNotNull null
                        val logo = team.arr("logos")?.firstOrNull()?.let { it as? JsonObject }?.str("href")
                        SportsStandingsEntry(
                            teamName = name,
                            abbreviation = team.str("abbreviation"),
                            logoUrl = logo?.takeIf { it.startsWith("https://") },
                            values = present.map { (key, _) -> statsOf[index][key].orEmpty() },
                        )
                    }
                    if (rows.isNotEmpty()) {
                        groups += SportsStandingsGroup(
                            name = node.str("name") ?: node.str("abbreviation") ?: league.label,
                            columns = present.map { it.second },
                            entries = rows,
                        )
                    }
                }
                node.arr("children").orEmpty().forEach { child -> (child as? JsonObject)?.let(::visit) }
            }
            visit(root)
            return SportsStandings(league, groups).takeIf { groups.isNotEmpty() }
        }

        /** Parses `boxscore.players` from ESPN's game summary into per-team stat tables. */
        internal fun parseBoxScore(payload: String, eventId: String): SportsBoxScore? {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
            val teams = root.obj("boxscore")?.arr("players").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val team = entry.obj("team")
                val teamName = team?.str("displayName") ?: team?.str("shortDisplayName") ?: return@mapNotNull null
                val teamPrefix = listOfNotNull(team?.str("location"), team?.str("shortDisplayName"), teamName)
                val groups = entry.arr("statistics").orEmpty().mapNotNull { g ->
                    val group = g as? JsonObject ?: return@mapNotNull null
                    val labels = group.arr("labels").orEmpty().mapNotNull { it.primitiveContent() }
                    if (labels.isEmpty()) return@mapNotNull null
                    val players = group.arr("athletes").orEmpty().mapNotNull { a ->
                        val line = a as? JsonObject ?: return@mapNotNull null
                        if ((line["didNotPlay"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
                        val stats = line.arr("stats").orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                        if (stats.isEmpty() || stats.all { it.isBlank() }) return@mapNotNull null
                        val athlete = line.obj("athlete") ?: return@mapNotNull null
                        val name = athlete.str("displayName") ?: athlete.str("shortName") ?: return@mapNotNull null
                        SportsPlayerLine(
                            name = name,
                            position = athlete.obj("position")?.str("abbreviation"),
                            jersey = athlete.str("jersey"),
                            starter = (line["starter"] as? JsonPrimitive)?.booleanOrNull == true,
                            stats = stats
                        )
                    }
                    if (players.isEmpty()) return@mapNotNull null
                    SportsStatGroup(
                        title = groupTitle(group.str("text"), group.str("name"), teamPrefix),
                        labels = labels,
                        players = players,
                        totals = group.arr("totals").orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
                            .takeIf { it.size == labels.size && it.any { v -> v.isNotBlank() } }.orEmpty()
                    )
                }
                SportsTeamBox(teamName, team?.str("abbreviation"), groups)
            }
            return SportsBoxScore(eventId, teams).takeUnless { it.teams.isEmpty() || it.isEmpty }
        }

        /** "Washington Passing" -> "Passing"; falls back to the key name ("kickReturns" -> "Kick Returns"). */
        private fun groupTitle(text: String?, name: String?, teamPrefixes: List<String>): String? {
            text?.let { raw ->
                val stripped = teamPrefixes.sortedByDescending { it.length }
                    .firstOrNull { raw.startsWith("$it ", ignoreCase = true) }
                    ?.let { raw.substring(it.length).trim() } ?: raw
                if (stripped.isNotBlank()) return stripped
            }
            return name?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.replaceFirstChar { it.uppercase() }
        }

        private fun parseCompetitor(c: JsonObject, tennis: Boolean): SportsCompetitor? {
            val team = c.obj("team")
            val athlete = c.obj("athlete")
            val roster = c.obj("roster")
            val name = (team?.str("displayName") ?: team?.str("shortDisplayName")
                ?: athlete?.str("displayName") ?: athlete?.str("shortName") ?: roster?.str("displayName"))
                ?.takeIf { it.isNotBlank() } ?: return null
            val abbreviation = team?.str("abbreviation") ?: athlete?.str("shortName") ?: roster?.str("shortDisplayName")
            val record = c.arr("records")?.firstOrNull()?.let { it as? JsonObject }?.str("summary")
            val score = when (val s = c["score"]) {
                is JsonPrimitive -> s.contentOrNull
                is JsonObject -> s.str("displayValue")
                else -> null
            }?.takeIf { it.isNotBlank() } ?: c.arr("linescores")?.takeIf { tennis }.orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { (it["winner"] as? JsonPrimitive)?.booleanOrNull != null }
                .takeIf { it.isNotEmpty() }
                ?.count { (it["winner"] as? JsonPrimitive)?.booleanOrNull == true }
                ?.toString()
            return SportsCompetitor(
                name = name,
                abbreviation = abbreviation,
                score = score,
                record = record,
                isHome = c.str("homeAway").equals("home", true),
                isWinner = (c["winner"] as? JsonPrimitive)?.booleanOrNull == true,
                logoUrl = (team?.str("logo") ?: team?.arr("logos")?.firstOrNull()?.let { it as? JsonObject }?.str("href"))
                    ?.takeIf { it.startsWith("https://") },
            )
        }

        private fun JsonObject.obj(key: String) = this[key] as? JsonObject
        private fun JsonObject.arr(key: String) = this[key] as? JsonArray
        private fun JsonObject.str(key: String) = this[key]?.primitiveContent()
        private fun JsonElement.primitiveContent(): String? =
            (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

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

    /** Every feed of the league (men's + women's, ATP + WTA), each game tagged with its division. */
    private suspend fun scoreboard(league: SportsLeague, date: String? = null): List<SportsEvent> = coroutineScope {
        val results = league.feeds.map { feed ->
            async {
                try {
                    val options = buildList {
                        if (feed.allDivisionOne) add("groups=50&limit=200")
                        if (date != null) add("dates=$date")
                    }
                    val query = options.joinToString("&", prefix = if (options.isEmpty()) "" else "?")
                    val payload = get("https://site.api.espn.com/apis/site/v2/sports/${feed.path}/scoreboard$query")
                    Result.success(withContext(Dispatchers.Default) {
                        parseScoreboard(payload, league).map { it.copy(division = feed.division, feedPath = feed.path) }
                    })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
        }.awaitAll()
        // One feed failing (e.g. women's hockey off-season) shouldn't hide the other.
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }
    }

    override suspend fun boxScore(event: SportsEvent): SportsBoxScore? {
        // UFC summaries have no per-athlete stat tables.
        if (!event.league.usesTeamStats || event.status == SportsEventStatus.SCHEDULED) return null
        val path = event.feedPath ?: event.league.scoreboardPath ?: return null
        val payload = get("https://site.api.espn.com/apis/site/v2/sports/$path/summary?event=${URLEncoder.encode(event.providerEventId, "UTF-8")}")
        return parseBoxScore(payload, event.id)
    }

    override suspend fun leaders(event: SportsEvent): SportsLeaders? {
        if (!event.league.usesTeamStats) return null
        val path = event.feedPath ?: event.league.scoreboardPath ?: return null
        val payload = get("https://site.api.espn.com/apis/site/v2/sports/$path/summary?event=${URLEncoder.encode(event.providerEventId, "UTF-8")}")
        return parseLeaders(payload, event.id)
    }

    // ---- F1 from Jolpica (the free successor of the Ergast F1 API) ----

    /** Drivers' and constructors' championship. */
    private suspend fun f1Standings(): SportsStandings? = coroutineScope {
        val drivers = async { runCatching { get("$JOLPICA/current/driverStandings.json") }.getOrNull() }
        val teams = async { runCatching { get("$JOLPICA/current/constructorStandings.json") }.getOrNull() }
        val groups = listOfNotNull(
            drivers.await()?.let { parseJolpicaDriverStandings(it) },
            teams.await()?.let { parseJolpicaConstructorStandings(it) },
        )
        if (groups.isEmpty()) null else SportsStandings(SportsLeague.FORMULA_1, groups)
    }

    /** Full race / qualifying classification (times, gaps, points) for a finished F1 session. */
    override suspend fun detailedResults(event: SportsEvent): SportsResults? {
        if (event.league != SportsLeague.FORMULA_1 || event.status != SportsEventStatus.FINAL) return null
        val start = event.startTimeEpochMillis ?: return null
        val session = event.title.substringAfterLast(" · ", "Race")
        val qualifying = session.contains("Qualifying", ignoreCase = true) && !session.contains("Sprint", ignoreCase = true)
        if (!qualifying && session != "Race" && !session.equals("race", true)) return null
        val year = Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).year
        val races = runCatching { get("$JOLPICA/$year/races.json?limit=100") }.getOrNull() ?: return null
        val round = jolpicaRoundNear(races, start) ?: return null
        val payload = runCatching {
            get("$JOLPICA/$year/$round/" + if (qualifying) "qualifying.json" else "results.json")
        }.getOrNull() ?: return null
        return if (qualifying) parseJolpicaQualifying(payload) else parseJolpicaRace(payload)
    }

    override suspend fun standings(league: SportsLeague): SportsStandings? {
        if (league == SportsLeague.FORMULA_1) return f1Standings()
        if (!league.usesTeamStats || league.feeds.isEmpty()) return null
        val parts = coroutineScope {
            league.feeds.map { feed ->
                async {
                    runCatching {
                        parseStandings(get("https://site.api.espn.com/apis/v2/sports/${feed.path}/standings"), league)
                    }.getOrNull()?.let { feed to it }
                }
            }.awaitAll().filterNotNull()
        }
        if (parts.isEmpty()) return null
        if (parts.size == 1 && league.feeds.size == 1) return parts.first().second
        // Men's and women's tables side by side: "Men's · Big Ten", "Women's · Hockey East".
        return SportsStandings(
            league = league,
            groups = parts.flatMap { (feed, table) ->
                table.groups.map { group ->
                    group.copy(name = listOfNotNull(feed.division, group.name.takeIf { it.isNotBlank() }).joinToString(" · "))
                }
            },
            fetchedAtEpochMillis = parts.maxOf { it.second.fetchedAtEpochMillis },
        )
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
        private const val JOLPICA = "https://api.jolpi.ca/ergast/f1"

        private fun jolpicaRaces(payload: String): List<JsonObject> {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return emptyList()
            return root.obj("MRData")?.obj("RaceTable")?.arr("Races").orEmpty().mapNotNull { it as? JsonObject }
        }

        /** The round whose race weekend is within 4 days of the session start. */
        internal fun jolpicaRoundNear(payload: String, startEpochMillis: Long): String? =
            jolpicaRaces(payload).minByOrNull { race ->
                val date = race.str("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    ?: return@minByOrNull Long.MAX_VALUE
                kotlin.math.abs(date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() - startEpochMillis)
            }?.takeIf { race ->
                val date = race.str("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return@takeIf false
                kotlin.math.abs(date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() - startEpochMillis) <= 4 * 86_400_000L
            }?.str("round")

        private fun driverName(row: JsonObject): String? = row.obj("Driver")?.let {
            listOfNotNull(it.str("givenName"), it.str("familyName")).joinToString(" ").takeIf { n -> n.isNotBlank() }
        }

        internal fun parseJolpicaRace(payload: String): SportsResults? {
            val race = jolpicaRaces(payload).firstOrNull() ?: return null
            val rows = race.arr("Results").orEmpty().mapNotNull { r ->
                val row = r as? JsonObject ?: return@mapNotNull null
                val name = driverName(row) ?: return@mapNotNull null
                val time = row.obj("Time")?.str("time") ?: row.str("status").orEmpty()
                SportsResultRow(
                    listOf(row.str("positionText") ?: row.str("position") ?: "", name,
                        row.obj("Constructor")?.str("name").orEmpty(), time, row.str("points").orEmpty()),
                    highlight = row.str("position") == "1",
                )
            }
            return if (rows.isEmpty()) null else SportsResults("RACE RESULT", listOf("Pos", "Driver", "Team", "Time", "Pts"), rows, source = "Jolpica F1")
        }

        internal fun parseJolpicaQualifying(payload: String): SportsResults? {
            val race = jolpicaRaces(payload).firstOrNull() ?: return null
            val rows = race.arr("QualifyingResults").orEmpty().mapNotNull { r ->
                val row = r as? JsonObject ?: return@mapNotNull null
                val name = driverName(row) ?: return@mapNotNull null
                SportsResultRow(
                    listOf(row.str("position").orEmpty(), name, row.obj("Constructor")?.str("name").orEmpty(),
                        row.str("Q1").orEmpty(), row.str("Q2").orEmpty(), row.str("Q3").orEmpty()),
                    highlight = row.str("position") == "1",
                )
            }
            return if (rows.isEmpty()) null else SportsResults("QUALIFYING", listOf("Pos", "Driver", "Team", "Q1", "Q2", "Q3"), rows, source = "Jolpica F1")
        }

        private fun standingsList(payload: String): JsonObject? {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
            return root.obj("MRData")?.obj("StandingsTable")?.arr("StandingsLists")?.firstOrNull() as? JsonObject
        }

        internal fun parseJolpicaDriverStandings(payload: String): SportsStandingsGroup? {
            val entries = standingsList(payload)?.arr("DriverStandings").orEmpty().mapNotNull { r ->
                val row = r as? JsonObject ?: return@mapNotNull null
                val name = driverName(row) ?: return@mapNotNull null
                SportsStandingsEntry(name, row.obj("Driver")?.str("code"), null,
                    listOf(row.str("points").orEmpty(), row.str("wins").orEmpty()))
            }
            return if (entries.isEmpty()) null else SportsStandingsGroup("Drivers", listOf("PTS", "W"), entries)
        }

        internal fun parseJolpicaConstructorStandings(payload: String): SportsStandingsGroup? {
            val entries = standingsList(payload)?.arr("ConstructorStandings").orEmpty().mapNotNull { r ->
                val row = r as? JsonObject ?: return@mapNotNull null
                val name = row.obj("Constructor")?.str("name") ?: return@mapNotNull null
                SportsStandingsEntry(name, null, null, listOf(row.str("points").orEmpty(), row.str("wins").orEmpty()))
            }
            return if (entries.isEmpty()) null else SportsStandingsGroup("Constructors", listOf("PTS", "W"), entries)
        }

        internal fun parseScoreboard(payload: String, league: SportsLeague): List<SportsEvent> {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
                ?: return emptyList()
            val events = root.arr("events") ?: return emptyList()
            return events.flatMap { element ->
                val event = element as? JsonObject ?: return@flatMap emptyList()
                when (league) {
                    SportsLeague.TENNIS -> tennisEvents(event, league)
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
                broadcasts = broadcasts,
                results = runCatching { resultsOf(event, competition, league) }.getOrNull(),
            )
        }

        /** Number from ESPN ("66.0" → "66"), or the text as given. */
        private fun JsonObject.cell(key: String): String? {
            val raw = this[key]?.primitiveContent()?.takeIf { it.isNotBlank() } ?: return null
            return raw.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else raw } ?: raw
        }

        private fun nameOf(c: JsonObject): String? =
            (c.obj("athlete")?.str("displayName") ?: c.obj("athlete")?.str("shortName")
                ?: c.obj("roster")?.str("displayName") ?: c.obj("team")?.str("displayName"))?.takeIf { it.isNotBlank() }

        private fun isWinner(c: JsonObject) = (c["winner"] as? JsonPrimitive)?.booleanOrNull == true

        /** Results table for sports without team box scores, straight from the scoreboard data. */
        private fun resultsOf(event: JsonObject, competition: JsonObject?, league: SportsLeague): SportsResults? {
            if (league.usesTeamStats) return null
            val competitors = competition?.arr("competitors").orEmpty().mapNotNull { it as? JsonObject }
            return when (league.catalogSport) {
                "golf" -> {
                    // Leaderboard order as ESPN sends it; equal scores share a "T" position.
                    val rows = competitors.sortedBy { (it["order"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE }
                    val rounds = rows.maxOfOrNull { it.arr("linescores")?.size ?: 0 }?.coerceAtMost(4) ?: 0
                    var lastScore: String? = null
                    var lastPos = 0
                    val table = rows.take(60).mapIndexedNotNull { index, c ->
                        val name = nameOf(c) ?: return@mapIndexedNotNull null
                        val score = c.cell("score") ?: "–"
                        if (score != lastScore) { lastPos = index + 1; lastScore = score }
                        val tied = rows.count { it.cell("score") == score } > 1
                        val roundCells = (0 until rounds).map { r ->
                            (c.arr("linescores")?.getOrNull(r) as? JsonObject)?.cell("value") ?: "–"
                        }
                        SportsResultRow(listOf((if (tied) "T" else "") + lastPos, name, score) + roundCells, highlight = lastPos == 1)
                    }
                    if (table.isEmpty()) null
                    else SportsResults("LEADERBOARD", listOf("Pos", "Player", "To par") + (1..rounds).map { "R$it" }, table)
                }
                "motor_sports" -> {
                    val rows = competitors.sortedBy { (it["order"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE }
                    val table = rows.take(40).mapIndexedNotNull { index, c ->
                        val name = nameOf(c) ?: return@mapIndexedNotNull null
                        val car = c.obj("vehicle")?.str("number") ?: c.str("number")
                        val team = c.obj("vehicle")?.str("manufacturer") ?: c.obj("team")?.str("displayName")
                        SportsResultRow(listOf("${index + 1}", name, car.orEmpty(), team.orEmpty()), highlight = isWinner(c))
                    }
                    if (table.isEmpty()) null else {
                        val showCar = table.any { it.cells[2].isNotBlank() }
                        val showTeam = table.any { it.cells[3].isNotBlank() }
                        SportsResults(
                            "RESULTS",
                            listOfNotNull("Pos", "Driver", "No.".takeIf { showCar }, "Team".takeIf { showTeam }),
                            table.map { row ->
                                row.copy(cells = listOfNotNull(row.cells[0], row.cells[1], row.cells[2].takeIf { showCar }, row.cells[3].takeIf { showTeam }))
                            },
                        )
                    }
                }
                "tennis" -> {
                    if (competitors.size != 2) return null
                    val sets = competitors.maxOf { it.arr("linescores")?.size ?: 0 }
                    if (sets == 0) return null
                    SportsResults(
                        "SETS",
                        listOf("Player") + (1..sets).map { "S$it" },
                        competitors.mapNotNull { c ->
                            val name = nameOf(c) ?: return@mapNotNull null
                            val cells = (0 until sets).map { s -> (c.arr("linescores")?.getOrNull(s) as? JsonObject)?.cell("value") ?: "–" }
                            SportsResultRow(listOf(name) + cells, highlight = isWinner(c))
                        },
                    )
                }
                "fight" -> {
                    // Every bout on the card, main event first.
                    val fights = event.arr("competitions").orEmpty().mapNotNull { it as? JsonObject }.reversed()
                    val table = fights.mapNotNull { fight ->
                        val sides = fight.arr("competitors").orEmpty().mapNotNull { it as? JsonObject }
                        if (sides.size != 2) return@mapNotNull null
                        val names = sides.map { nameOf(it) ?: return@mapNotNull null }
                        val winner = sides.firstOrNull(::isWinner)?.let(::nameOf)
                        val result = fight.obj("status")?.obj("type")?.str("shortDetail")
                            ?: fight.obj("status")?.obj("type")?.str("detail") ?: ""
                        val weight = fight.obj("type")?.str("abbreviation") ?: fight.obj("type")?.str("text") ?: ""
                        SportsResultRow(listOf(names.joinToString(" vs "), weight, winner ?: "–", result), highlight = winner != null)
                    }
                    if (table.isEmpty()) null else SportsResults("FIGHT CARD", listOf("Fight", "Class", "Winner", "Result"), table)
                }
                else -> null
            }
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

        /** The game page's "leaders" block: per team, per stat category, the top players. */
        internal fun parseLeaders(payload: String, eventId: String): SportsLeaders? {
            val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
            val teams = root.arr("leaders").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val team = entry.obj("team")
                val teamName = team?.str("displayName") ?: team?.str("shortDisplayName") ?: return@mapNotNull null
                val categories = entry.arr("leaders").orEmpty().mapNotNull { c ->
                    val category = c as? JsonObject ?: return@mapNotNull null
                    val title = category.str("displayName") ?: category.str("shortDisplayName")
                        ?: category.str("name")?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.replaceFirstChar { it.uppercase() }
                        ?: return@mapNotNull null
                    val lines = category.arr("leaders").orEmpty().mapNotNull { l ->
                        val line = l as? JsonObject ?: return@mapNotNull null
                        val athlete = line.obj("athlete") ?: return@mapNotNull null
                        val name = athlete.str("displayName") ?: athlete.str("shortName") ?: return@mapNotNull null
                        val value = line.str("displayValue")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        SportsLeaderLine(name, athlete.obj("position")?.str("abbreviation"), value)
                    }.take(3)
                    if (lines.isEmpty()) null else SportsLeaderCategory(title, lines)
                }
                if (categories.isEmpty()) null else SportsTeamLeaders(teamName, team?.str("abbreviation"), categories)
            }
            return if (teams.isEmpty()) null else SportsLeaders(eventId, teams)
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

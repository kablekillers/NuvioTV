package com.nuvio.tv.ui.screens.sports

/** One ESPN feed a league pulls from, e.g. men's or women's college basketball. */
data class SportsFeed(
    val path: String,
    /** Label shown on each game when a league has several feeds ("Men's", "Women's", "ATP"). */
    val division: String? = null,
    /** ESPN only lists ranked/featured college games unless asked for all of Division I. */
    val allDivisionOne: Boolean = false,
)

/**
 * The Sports Hub's sports (top row, after "All"): American sports only.
 * A league can pull several ESPN feeds (men's + women's college games, ATP + WTA).
 * A league with no feeds is found only in add-on catalogs (WWE).
 */
enum class SportsLeague(
    val label: String,
    internal val feeds: List<SportsFeed>,
    internal val catalogSport: String,
    internal val shortLabel: String = label,
    internal val logoPath: String? = null,
    internal val usesTeamStats: Boolean = true,
) {
    NFL("NFL", listOf(SportsFeed("football/nfl")), "american_football", logoPath = "i/teamlogos/leagues/500/nfl.png"),
    NBA("NBA", listOf(SportsFeed("basketball/nba")), "basketball", logoPath = "i/teamlogos/leagues/500/nba.png"),
    MLB("MLB", listOf(SportsFeed("baseball/mlb")), "baseball", logoPath = "i/teamlogos/leagues/500/mlb.png"),
    NHL("NHL", listOf(SportsFeed("hockey/nhl")), "hockey", logoPath = "i/teamlogos/leagues/500/nhl.png"),
    COLLEGE_FOOTBALL("College Football", listOf(SportsFeed("football/college-football")), "american_football",
        shortLabel = "College Football", logoPath = "redesign/assets/img/icons/NCAASports_Football.png"),
    COLLEGE_BASKETBALL("College Basketball", listOf(
        SportsFeed("basketball/mens-college-basketball", "Men's", allDivisionOne = true),
        SportsFeed("basketball/womens-college-basketball", "Women's", allDivisionOne = true),
    ), "basketball", logoPath = "redesign/assets/img/icons/NCAASports_MensBasketball.png"),
    COLLEGE_HOCKEY("College Hockey", listOf(
        SportsFeed("hockey/mens-college-hockey", "Men's"),
        SportsFeed("hockey/womens-college-hockey", "Women's"),
    ), "hockey"),
    TENNIS("Tennis", listOf(SportsFeed("tennis/atp", "ATP"), SportsFeed("tennis/wta", "WTA")), "tennis",
        usesTeamStats = false),
    PGA("PGA Golf", listOf(SportsFeed("golf/pga")), "golf", "Golf", "i/teamlogos/leagues/500-dark/pgatour.png", false),
    UFC("Boxing / MMA / UFC", listOf(SportsFeed("mma/ufc", "UFC")), "fight", "Boxing/MMA/UFC",
        "i/teamlogos/leagues/500/ufc.png", usesTeamStats = false),
    FORMULA_1("Formula 1", listOf(SportsFeed("racing/f1")), "motor_sports", "F1", "i/teamlogos/leagues/500/f1.png", false),
    NASCAR("NASCAR Cup Series", listOf(SportsFeed("racing/nascar-premier")), "motor_sports",
        "NASCAR", "redesign/assets/img/icons/ESPN-icon-NASCAR.png", false),
    WNBA("WNBA", listOf(SportsFeed("basketball/wnba")), "basketball", logoPath = "i/teamlogos/leagues/500-dark/wnba.png"),
    MLS("MLS", listOf(SportsFeed("soccer/usa.1")), "football", logoPath = "i/leaguelogos/soccer/500/19.png"),
    COLLEGE_BASEBALL("College Baseball", listOf(SportsFeed("baseball/college-baseball")), "baseball"),
    LPGA("LPGA", listOf(SportsFeed("golf/lpga")), "golf", logoPath = "i/teamlogos/leagues/500-dark/lpga.png", usesTeamStats = false),
    COLLEGE_VOLLEYBALL("College Volleyball", listOf(
        SportsFeed("volleyball/womens-college-volleyball", "Women's"),
        SportsFeed("volleyball/mens-college-volleyball", "Men's"),
    ), "volleyball"),
    INDYCAR("IndyCar", listOf(SportsFeed("racing/irl")), "motor_sports",
        logoPath = "i/espn/teamlogos/500/indycar_series.png", usesTeamStats = false),
    WWE("WWE", emptyList(), "wrestling", usesTeamStats = false),
    ;

    /** First feed's ESPN path, or null for add-on-only sports. */
    val scoreboardPath: String? get() = feeds.firstOrNull()?.path

    internal val isCollegeBasketball: Boolean get() = this == COLLEGE_BASKETBALL
}

enum class SportsEventStatus { SCHEDULED, LIVE, FINAL, POSTPONED, UNKNOWN }
enum class SportsContentKind { EVENT, REPLAY, HIGHLIGHT }

/** One side of a matchup (team or fighter) exactly as reported by the provider. */
data class SportsCompetitor(
    val name: String,
    val abbreviation: String? = null,
    val score: String? = null,
    val record: String? = null,
    val isHome: Boolean = false,
    val isWinner: Boolean = false,
    val logoUrl: String? = null,
) {
    init {
        require(name.isNotBlank())
    }

    /** Short label for compact score lines, e.g. "KC". */
    val shortLabel: String get() = abbreviation?.takeIf { it.isNotBlank() } ?: name
}

data class SportsEvent(
    val id: String,
    val league: SportsLeague,
    val title: String,
    val startTimeEpochMillis: Long?,
    val status: SportsEventStatus,
    val kind: SportsContentKind = SportsContentKind.EVENT,
    val providerName: String,
    val detailUrl: String? = null,
    val authorizedPlaybackUrl: String? = null,
    /** Provider's own short status text, e.g. "Q3 4:12", "Final/OT", "Top 7th". */
    val statusDetail: String? = null,
    val competitors: List<SportsCompetitor> = emptyList(),
    val venue: String? = null,
    /** TV networks / services the provider lists as carrying the event. */
    val broadcasts: List<String> = emptyList(),
    val catalogMatchTitle: String? = null,
    val racingSession: String? = null,
    /** "Men's" / "Women's" / "ATP"… when the league pulls several feeds. */
    val division: String? = null,
    /** ESPN feed this game came from (for box scores in multi-feed leagues). */
    val feedPath: String? = null,
    /** Golf leaderboard / race order / tennis sets / fight results, when the provider has them. */
    val results: SportsResults? = null,
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(providerName.isNotBlank())
        require(authorizedPlaybackUrl == null || authorizedPlaybackUrl.startsWith("https://")) {
            "Playback URLs must be explicit HTTPS links from an authorized provider"
        }
    }

    /** The provider's own event id (without the league prefix). */
    val providerEventId: String get() = id.substringAfter(':')

    val home: SportsCompetitor? get() = competitors.firstOrNull { it.isHome }
    val away: SportsCompetitor? get() = competitors.firstOrNull { !it.isHome }

    /** True when the provider reported scores for both sides of a two-sided matchup. */
    val hasScores: Boolean
        get() = competitors.size == 2 && competitors.all { !it.score.isNullOrBlank() }

    /** "BUF 17 – 24 KC" style line, or null when no scores are reported. */
    fun scoreLine(): String? {
        val awaySide = away ?: return null
        val homeSide = home ?: return null
        if (!hasScores) return null
        return "${awaySide.shortLabel} ${awaySide.score} – ${homeSide.score} ${homeSide.shortLabel}"
    }
}

/** Providers must return only real, source-backed event data, never generated fixtures in production. */
interface SportsEventProvider {
    suspend fun events(league: SportsLeague): List<SportsEvent>

    suspend fun recentResults(league: SportsLeague, nowEpochMillis: Long): List<SportsEvent> = emptyList()

    /** Individual player stats for a game, or null when the provider has none (e.g. not started). */
    suspend fun boxScore(event: SportsEvent): SportsBoxScore? = null

    /** Each team's stat leaders: season numbers before the game, game numbers once it starts. */
    suspend fun leaders(event: SportsEvent): SportsLeaders? = null

    /** Fuller results from another source (e.g. F1 times and points), or null to use [SportsEvent.results]. */
    suspend fun detailedResults(event: SportsEvent): SportsResults? = null

    /** League tables, or null when the provider has none (e.g. UFC). */
    suspend fun standings(league: SportsLeague): SportsStandings? = null
}

/** One row of a league table. [values] line up with [SportsStandingsGroup.columns]. */
data class SportsStandingsEntry(
    val teamName: String,
    val abbreviation: String?,
    val logoUrl: String?,
    val values: List<String>,
)

data class SportsStandingsGroup(
    val name: String,
    val columns: List<String>,
    val entries: List<SportsStandingsEntry>,
)

data class SportsStandings(
    val league: SportsLeague,
    val groups: List<SportsStandingsGroup>,
    val fetchedAtEpochMillis: Long = 0L,
)

object SportsEventSections {
    fun live(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT && it.status == SportsEventStatus.LIVE
    }

    /** Scheduled games first by start time; postponed games stay visible with their status. */
    fun upcoming(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT &&
            (it.status == SportsEventStatus.SCHEDULED || it.status == SportsEventStatus.POSTPONED)
    }.sortedWith(
        compareBy<SportsEvent> { it.status == SportsEventStatus.POSTPONED }
            .thenBy(nullsLast()) { it.startTimeEpochMillis }
    )

    /** Completed games, most recent first. */
    fun final(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT && it.status == SportsEventStatus.FINAL
    }.sortedWith(compareByDescending(nullsFirst()) { it.startTimeEpochMillis })

    fun replays(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.REPLAY }
    fun highlights(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.HIGHLIGHT }
}

/** Until a vetted schedule provider is configured, no events are displayed as live. */
object UnconfiguredSportsEventProvider : SportsEventProvider {
    override suspend fun events(league: SportsLeague): List<SportsEvent> = emptyList()
}

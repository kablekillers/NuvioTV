package com.nuvio.tv.ui.screens.sports

/** Provider-independent sports metadata. Stream availability is never inferred from event status. */
enum class SportsLeague(
    val label: String,
    val scoreboardPath: String?,
    internal val catalogSport: String,
    internal val shortLabel: String = label,
    internal val logoPath: String? = null,
    internal val usesTeamStats: Boolean = true,
) {
    NFL("NFL", "football/nfl", "american_football", logoPath = "i/teamlogos/leagues/500/nfl.png"),
    COLLEGE_FOOTBALL("College Football", "football/college-football", "american_football",
        shortLabel = "College", logoPath = "redesign/assets/img/icons/NCAASports_Football.png"),
    NBA("NBA", "basketball/nba", "basketball", logoPath = "i/teamlogos/leagues/500/nba.png"),
    MLB("MLB", "baseball/mlb", "baseball", logoPath = "i/teamlogos/leagues/500/mlb.png"),
    NHL("NHL", "hockey/nhl", "hockey", logoPath = "i/teamlogos/leagues/500/nhl.png"),
    UFC("UFC", "mma/ufc", "fight", logoPath = "i/teamlogos/leagues/500/ufc.png", usesTeamStats = false),
    NCAA_MEN("NCAA Men's Basketball", "basketball/mens-college-basketball", "basketball",
        "NCAA Men", "redesign/assets/img/icons/NCAASports_MensBasketball.png"),
    NCAA_WOMEN("NCAA Women's Basketball", "basketball/womens-college-basketball", "basketball",
        "NCAA Women", "redesign/assets/img/icons/NCAASports_WomensBasketball.png"),
    WNBA("WNBA", "basketball/wnba", "basketball", logoPath = "i/teamlogos/leagues/500-dark/wnba.png"),
    ATP("ATP Tennis", "tennis/atp", "tennis", "ATP", usesTeamStats = false),
    WTA("WTA Tennis", "tennis/wta", "tennis", "WTA", usesTeamStats = false),
    PGA("PGA Tour", "golf/pga", "golf", "PGA", "i/teamlogos/leagues/500-dark/pgatour.png", false),
    LPGA("LPGA", "golf/lpga", "golf", logoPath = "i/teamlogos/leagues/500-dark/lpga.png", usesTeamStats = false),
    FORMULA_1("Formula 1", "racing/f1", "motor_sports", "F1", "i/teamlogos/leagues/500/f1.png", false),
    NASCAR("NASCAR Cup Series", "racing/nascar-premier", "motor_sports",
        "NASCAR", "redesign/assets/img/icons/ESPN-icon-NASCAR.png", false),
    INDYCAR("IndyCar", "racing/irl", "motor_sports",
        logoPath = "i/espn/teamlogos/500/indycar_series.png", usesTeamStats = false),
    MOTORSPORT("Motorsports", null, "motor_sports", "Motorsports", usesTeamStats = false),
    COMBAT("Boxing & MMA", null, "fight", "Boxing & MMA", usesTeamStats = false),
    SOCCER("Soccer", null, "football", usesTeamStats = false),
    RUGBY("Rugby", null, "rugby", usesTeamStats = false),
    AFL("AFL", null, "afl", usesTeamStats = false),
    CRICKET("Cricket", null, "cricket", usesTeamStats = false),
    DARTS("Darts", null, "darts", usesTeamStats = false),
    BILLIARDS("Billiards", null, "billiards", usesTeamStats = false),
    OTHER("Other Sports", null, "other", usesTeamStats = false),
    ;

    internal val isCollegeBasketball: Boolean get() = this == NCAA_MEN || this == NCAA_WOMEN
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

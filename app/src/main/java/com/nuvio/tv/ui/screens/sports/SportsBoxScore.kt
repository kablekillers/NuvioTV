package com.nuvio.tv.ui.screens.sports

/** Individual player stats for one game, exactly as the provider reports them. */
data class SportsBoxScore(
    val eventId: String,
    val teams: List<SportsTeamBox>,
    val fetchedAtEpochMillis: Long = 0L
) {
    val isEmpty: Boolean get() = teams.all { team -> team.groups.all { it.players.isEmpty() } }
}

data class SportsTeamBox(
    val name: String,
    val abbreviation: String?,
    val groups: List<SportsStatGroup>
) {
    val shortLabel: String get() = abbreviation?.takeIf { it.isNotBlank() } ?: name
}

/** A stat table, e.g. "Passing" (NFL) or the single player table (NBA). */
data class SportsStatGroup(
    val title: String?,
    val labels: List<String>,
    val players: List<SportsPlayerLine>,
    val totals: List<String> = emptyList()
)

data class SportsPlayerLine(
    val name: String,
    val position: String? = null,
    val jersey: String? = null,
    val starter: Boolean = false,
    val stats: List<String>
)

/**
 * Each team's top players by stat category, as ESPN reports them on the game page:
 * season numbers before the game starts, game numbers once it's under way.
 */
data class SportsLeaders(
    val eventId: String,
    val teams: List<SportsTeamLeaders>,
    val fetchedAtEpochMillis: Long = 0L,
)

data class SportsTeamLeaders(
    val name: String,
    val abbreviation: String?,
    val categories: List<SportsLeaderCategory>,
) {
    val shortLabel: String get() = abbreviation?.takeIf { it.isNotBlank() } ?: name
}

/** "Passing Yards" → [Josh Allen (QB) · "3,731 YDS, 28 TD"] */
data class SportsLeaderCategory(val title: String, val leaders: List<SportsLeaderLine>)

data class SportsLeaderLine(val name: String, val position: String?, val value: String)

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

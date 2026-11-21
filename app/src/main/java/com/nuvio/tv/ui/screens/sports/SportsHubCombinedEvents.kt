package com.nuvio.tv.ui.screens.sports

/** Cross-league event grouping; does not infer playable streams from scores or schedules. */
data class SportsHubCombinedFeed(
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>
)

object SportsHubCombinedEvents {
    const val UPCOMING_WINDOW_MILLIS = 60L * 60L * 1000L

    fun combine(
        scoreboards: Collection<SportsScoreboard>,
        nowEpochMillis: Long,
        upcomingWindowMillis: Long = UPCOMING_WINDOW_MILLIS
    ): SportsHubCombinedFeed {
        require(upcomingWindowMillis >= 0L)
        val events = scoreboards.flatMap { it.events }
            .filter { it.kind == SportsContentKind.EVENT }
            .distinctBy { it.league to it.id }
        val live = events.filter { it.status == SportsEventStatus.LIVE }
            .sortedWith(compareBy<SportsEvent> { it.startTimeEpochMillis ?: Long.MAX_VALUE }
                .thenBy { it.league.name }.thenBy { it.title })
        val upcoming = events.filter {
            val start = it.startTimeEpochMillis
            it.status == SportsEventStatus.SCHEDULED && start != null &&
                start >= nowEpochMillis && start - nowEpochMillis <= upcomingWindowMillis
        }.sortedWith(compareBy<SportsEvent> { it.startTimeEpochMillis ?: Long.MAX_VALUE }
            .thenBy { it.league.name }.thenBy { it.title })
        return SportsHubCombinedFeed(live, upcoming)
    }
}

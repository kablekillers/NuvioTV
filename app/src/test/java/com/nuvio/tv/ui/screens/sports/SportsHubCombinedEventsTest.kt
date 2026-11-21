package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsHubCombinedEventsTest {
    private val now = 1_800_000_000_000L

    private fun event(
        id: String,
        league: SportsLeague,
        status: SportsEventStatus,
        start: Long? = now,
        kind: SportsContentKind = SportsContentKind.EVENT
    ) = SportsEvent(
        id = id,
        league = league,
        title = "Game $id",
        startTimeEpochMillis = start,
        status = status,
        kind = kind,
        providerName = "Test provider"
    )

    private fun board(league: SportsLeague, vararg events: SportsEvent) =
        SportsScoreboard(league, events.toList(), now)

    @Test
    fun combinesLiveAndUpcomingAcrossLeagues() {
        val feed = SportsHubCombinedEvents.combine(
            listOf(
                board(SportsLeague.NFL,
                    event("nfl:1", SportsLeague.NFL, SportsEventStatus.LIVE),
                    event("nfl:2", SportsLeague.NFL, SportsEventStatus.FINAL)),
                board(SportsLeague.NBA,
                    event("nba:3", SportsLeague.NBA, SportsEventStatus.SCHEDULED, now + 20_000L),
                    event("nba:4", SportsLeague.NBA, SportsEventStatus.LIVE, kind = SportsContentKind.REPLAY))
            ), now
        )
        assertEquals(listOf("nfl:1"), feed.live.map { it.id })
        assertEquals(listOf("nba:3"), feed.upcoming.map { it.id })
    }

    @Test
    fun upcomingWindowIsInclusiveAndExcludesPastPostponedAndUnknown() {
        val league = SportsLeague.NHL
        val feed = SportsHubCombinedEvents.combine(
            listOf(board(league,
                event("past", league, SportsEventStatus.SCHEDULED, now - 1L),
                event("now", league, SportsEventStatus.SCHEDULED, now),
                event("hour", league, SportsEventStatus.SCHEDULED, now + 3_600_000L),
                event("later", league, SportsEventStatus.SCHEDULED, now + 3_600_001L),
                event("postponed", league, SportsEventStatus.POSTPONED, now + 1_000L),
                event("unknown", league, SportsEventStatus.SCHEDULED, null)
            )), now
        )
        assertEquals(listOf("now", "hour"), feed.upcoming.map { it.id })
    }

    @Test
    fun repeatedIdsWithinLeagueAreDeduplicated() {
        val game = event("nfl:42", SportsLeague.NFL, SportsEventStatus.LIVE)
        val feed = SportsHubCombinedEvents.combine(
            listOf(board(SportsLeague.NFL, game), board(SportsLeague.NFL, game)), now
        )
        assertEquals(1, feed.live.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWindowIsRejected() {
        SportsHubCombinedEvents.combine(emptyList(), now, -1L)
    }
}

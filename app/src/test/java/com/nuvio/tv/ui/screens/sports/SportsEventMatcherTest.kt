package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsEventMatcherTest {
    private val game = SportsEvent(
        id = "espn-test", league = SportsLeague.NFL,
        title = "Green Bay Packers at Chicago Bears",
        startTimeEpochMillis = null, status = SportsEventStatus.SCHEDULED,
        providerName = "ESPN",
        competitors = listOf(
            SportsCompetitor("Green Bay Packers"),
            SportsCompetitor("Chicago Bears")
        )
    )

    @Test fun bothTeamsRequired() {
        assertTrue(SportsEventMatcher.score(game, "Green Bay Packers vs Chicago Bears LIVE") > 0)
        assertEquals(0, SportsEventMatcher.score(game, "Green Bay Packers vs Minnesota Vikings"))
        assertEquals(0, SportsEventMatcher.score(game, "NFL Live Channel"))
    }

    @Test fun punctuationAndCaseAreIgnored() {
        assertTrue(SportsEventMatcher.score(game, "CHICAGO BEARS - GREEN BAY PACKERS (HD)") > 0)
    }

    @Test fun unrelatedTitlesDoNotMatch() {
        assertEquals(0, SportsEventMatcher.score(game, "NBA basketball live 24/7"))
    }
}

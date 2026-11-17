package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsEventSectionsTest {
    private fun event(
        id: String,
        status: SportsEventStatus,
        kind: SportsContentKind = SportsContentKind.EVENT,
        time: Long? = null
    ) = SportsEvent(id, SportsLeague.NFL, "Game $id", time, status, kind, "Verified test provider")

    @Test fun liveDoesNotIncludeScheduledOrFinal() {
        val events = listOf(event("1", SportsEventStatus.SCHEDULED), event("2", SportsEventStatus.LIVE), event("3", SportsEventStatus.FINAL))
        assertEquals(listOf("2"), SportsEventSections.live(events).map { it.id })
    }

    @Test fun upcomingIsChronologicalAndExcludesReplays() {
        val events = listOf(
            event("later", SportsEventStatus.SCHEDULED, time = 200),
            event("replay", SportsEventStatus.SCHEDULED, SportsContentKind.REPLAY),
            event("earlier", SportsEventStatus.SCHEDULED, time = 100),
            event("unknown-time", SportsEventStatus.SCHEDULED)
        )
        assertEquals(listOf("earlier", "later", "unknown-time"), SportsEventSections.upcoming(events).map { it.id })
    }

    @Test fun replaysAndHighlightsRemainSeparate() {
        val events = listOf(event("r", SportsEventStatus.FINAL, SportsContentKind.REPLAY), event("h", SportsEventStatus.FINAL, SportsContentKind.HIGHLIGHT))
        assertEquals(1, SportsEventSections.replays(events).size)
        assertEquals(1, SportsEventSections.highlights(events).size)
    }

    @Test fun noConfiguredProviderDoesNotInventLiveGames() {
        assertTrue(SportsEventSections.live(emptyList()).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInsecurePlaybackUrl() {
        SportsEvent("x", SportsLeague.NHL, "Match", null, SportsEventStatus.LIVE, providerName = "Provider", authorizedPlaybackUrl = "http://example.com/video")
    }

    @Test fun finalSectionIsMostRecentFirstAndExcludesLive() {
        val events = listOf(
            event("old", SportsEventStatus.FINAL, time = 100),
            event("live", SportsEventStatus.LIVE, time = 300),
            event("recent", SportsEventStatus.FINAL, time = 200),
            event("no-time", SportsEventStatus.FINAL)
        )
        assertEquals(listOf("recent", "old", "no-time"), SportsEventSections.final(events).map { it.id })
    }

    @Test fun postponedGamesStayVisibleAfterScheduledOnes() {
        val events = listOf(
            event("postponed", SportsEventStatus.POSTPONED, time = 50),
            event("scheduled", SportsEventStatus.SCHEDULED, time = 100),
            event("unknown", SportsEventStatus.UNKNOWN, time = 10)
        )
        assertEquals(listOf("scheduled", "postponed"), SportsEventSections.upcoming(events).map { it.id })
    }
}

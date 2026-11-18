package com.nuvio.tv.ui.screens.sports

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class SportsEventRepositoryTest {
    private class FakeProvider : SportsEventProvider {
        var calls = 0
        var fail = false
        var status = SportsEventStatus.SCHEDULED
        override suspend fun events(league: SportsLeague): List<SportsEvent> {
            calls++
            if (fail) error("offline")
            return listOf(SportsEvent("${league.name}:$calls", league, "Game $calls", null, status, providerName = "Test"))
        }
    }

    @Test fun freshCacheIsReusedAndStaleCacheRefetches() = runTest {
        var now = 0L
        val provider = FakeProvider()
        val repo = SportsEventRepository(provider, clock = { now }, maxAgeMillis = 60_000)

        val first = repo.scoreboard(SportsLeague.NBA)
        now = 59_000
        assertSame(first, repo.scoreboard(SportsLeague.NBA))
        assertEquals(1, provider.calls)

        now = 61_000
        repo.scoreboard(SportsLeague.NBA)
        assertEquals(2, provider.calls)
    }

    @Test fun forceRefreshAlwaysFetches() = runTest {
        val provider = FakeProvider()
        val repo = SportsEventRepository(provider, clock = { 0L })
        repo.scoreboard(SportsLeague.NHL)
        repo.scoreboard(SportsLeague.NHL, forceRefresh = true)
        assertEquals(2, provider.calls)
    }

    @Test fun leaguesAreCachedSeparately() = runTest {
        val provider = FakeProvider()
        val repo = SportsEventRepository(provider, clock = { 0L })
        repo.scoreboard(SportsLeague.NFL)
        assertNull(repo.cached(SportsLeague.MLB))
        assertEquals(SportsLeague.NFL, repo.cached(SportsLeague.NFL)?.league)
    }

    @Test fun failedRefreshKeepsLastRealScores() = runTest {
        val provider = FakeProvider()
        val repo = SportsEventRepository(provider, clock = { 0L })
        val good = repo.scoreboard(SportsLeague.NFL)
        provider.fail = true
        try {
            repo.scoreboard(SportsLeague.NFL, forceRefresh = true)
            fail("expected failure")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertSame(good, repo.cached(SportsLeague.NFL))
    }

    @Test fun refreshesFasterWhileGamesAreLive() = runTest {
        val provider = FakeProvider().apply { status = SportsEventStatus.LIVE }
        val repo = SportsEventRepository(provider, clock = { 0L })
        val live = repo.scoreboard(SportsLeague.NBA)
        assertEquals(30_000L, SportsEventRepository.refreshIntervalMillis(live))
        assertEquals(120_000L, SportsEventRepository.refreshIntervalMillis(null))
    }

    private class BoxProvider : SportsEventProvider {
        var calls = 0
        override suspend fun events(league: SportsLeague) = emptyList<SportsEvent>()
        override suspend fun boxScore(event: SportsEvent): SportsBoxScore? {
            calls++
            return SportsBoxScore(event.id, listOf(SportsTeamBox("A", "A", listOf(
                SportsStatGroup(null, listOf("PTS"), listOf(SportsPlayerLine("P", stats = listOf("$calls"))))
            ))))
        }
    }

    private fun game(status: SportsEventStatus) =
        SportsEvent("NBA:9", SportsLeague.NBA, "A at B", null, status, providerName = "Test")

    @Test fun liveBoxScoresRefreshButFinalOnesStayCached() = runTest {
        var now = 0L
        val provider = BoxProvider()
        val repo = SportsEventRepository(provider, clock = { now })
        repo.boxScore(game(SportsEventStatus.LIVE))
        now = 10_000
        repo.boxScore(game(SportsEventStatus.LIVE))
        assertEquals(1, provider.calls)
        now = 45_000
        repo.boxScore(game(SportsEventStatus.LIVE))
        assertEquals(2, provider.calls)
        now = 10_000_000
        repo.boxScore(game(SportsEventStatus.FINAL))
        assertEquals(2, provider.calls)
    }
}

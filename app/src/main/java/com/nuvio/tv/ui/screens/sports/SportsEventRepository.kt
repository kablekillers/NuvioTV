package com.nuvio.tv.ui.screens.sports

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Last successful result for a league, with the time it was fetched. */
data class SportsScoreboard(
    val league: SportsLeague,
    val events: List<SportsEvent>,
    val fetchedAtEpochMillis: Long
) {
    val hasLiveGames: Boolean get() = SportsEventSections.live(events).isNotEmpty()
}

/**
 * In-memory, per-league cache in front of a [SportsEventProvider].
 *
 * Switching leagues reuses a fresh cached result instantly; failures never discard
 * previously fetched (real) data. Nothing is persisted, so no profile data is involved.
 */
class SportsEventRepository(
    private val provider: SportsEventProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<SportsLeague, SportsScoreboard>()

    suspend fun cached(league: SportsLeague): SportsScoreboard? = mutex.withLock { cache[league] }

    /**
     * Returns the cached scoreboard when it is newer than [maxAgeMillis], otherwise fetches.
     * Throws if the fetch fails; the caller may still show [cached] data.
     */
    suspend fun scoreboard(league: SportsLeague, forceRefresh: Boolean = false): SportsScoreboard {
        if (!forceRefresh) {
            mutex.withLock { cache[league] }?.let { existing ->
                if (clock() - existing.fetchedAtEpochMillis < maxAgeMillis) return existing
            }
        }
        val fresh = SportsScoreboard(league, provider.events(league), clock())
        mutex.withLock { cache[league] = fresh }
        return fresh
    }

    private val recentResultsCache = mutableMapOf<SportsLeague, SportsScoreboard>()

    suspend fun recentResults(league: SportsLeague): List<SportsEvent> {
        val now = clock()
        mutex.withLock {
            recentResultsCache[league]?.takeIf { now - it.fetchedAtEpochMillis < STANDINGS_MAX_AGE_MILLIS }
        }?.let { return it.events }
        val events = provider.recentResults(league, now)
        if (events.isNotEmpty()) {
            mutex.withLock { recentResultsCache[league] = SportsScoreboard(league, events, clock()) }
        }
        return events
    }

    private val boxScores = mutableMapOf<String, SportsBoxScore>()

    /**
     * Player stats for a game. Finished games are cached for the session; live games
     * are re-fetched once the cached copy is older than [LIVE_BOX_SCORE_MAX_AGE_MILLIS].
     */
    suspend fun boxScore(event: SportsEvent, forceRefresh: Boolean = false): SportsBoxScore? {
        if (!forceRefresh) {
            mutex.withLock { boxScores[event.id] }?.let { cached ->
                val age = clock() - cached.fetchedAtEpochMillis
                if (event.status == SportsEventStatus.FINAL || age < LIVE_BOX_SCORE_MAX_AGE_MILLIS) return cached
            }
        }
        val fresh = provider.boxScore(event)?.copy(fetchedAtEpochMillis = clock()) ?: return null
        mutex.withLock {
            if (boxScores.size >= MAX_CACHED_BOX_SCORES) boxScores.remove(boxScores.keys.first())
            boxScores[event.id] = fresh
        }
        return fresh
    }

    private val standingsCache = mutableMapOf<SportsLeague, SportsStandings>()

    /** League tables change slowly; a copy up to 10 minutes old is reused. */
    suspend fun standings(league: SportsLeague, forceRefresh: Boolean = false): SportsStandings? {
        if (!forceRefresh) {
            mutex.withLock { standingsCache[league] }?.let { cached ->
                if (clock() - cached.fetchedAtEpochMillis < STANDINGS_MAX_AGE_MILLIS) return cached
            }
        }
        val fresh = provider.standings(league)?.copy(fetchedAtEpochMillis = clock()) ?: return null
        mutex.withLock { standingsCache[league] = fresh }
        return fresh
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 60_000L
        const val STANDINGS_MAX_AGE_MILLIS = 10 * 60_000L
        const val LIVE_BOX_SCORE_MAX_AGE_MILLIS = 30_000L
        private const val MAX_CACHED_BOX_SCORES = 40

        /** Refresh more often while games are live, slower otherwise. */
        fun refreshIntervalMillis(board: SportsScoreboard?): Long =
            if (board?.hasLiveGames == true) 30_000L else 120_000L

        /** Shared across screen visits so returning to Sports Hub is instant. */
        val shared: SportsEventRepository by lazy { SportsEventRepository(EspnSportsEventProvider()) }
    }
}

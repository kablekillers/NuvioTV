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

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 60_000L

        /** Refresh more often while games are live, slower otherwise. */
        fun refreshIntervalMillis(board: SportsScoreboard?): Long =
            if (board?.hasLiveGames == true) 30_000L else 120_000L

        /** Shared across screen visits so returning to Sports Hub is instant. */
        val shared: SportsEventRepository by lazy { SportsEventRepository(EspnSportsEventProvider()) }
    }
}

package com.nuvio.tv.ui.screens.sports

/** Provider-independent sports metadata. Stream availability is never inferred from event status. */
enum class SportsLeague(val label: String) {
    NFL("NFL"), COLLEGE_FOOTBALL("College Football"), NBA("NBA"),
    MLB("MLB"), NHL("NHL"), UFC("UFC")
}

enum class SportsEventStatus { SCHEDULED, LIVE, FINAL, POSTPONED, UNKNOWN }
enum class SportsContentKind { EVENT, REPLAY, HIGHLIGHT }

data class SportsEvent(
    val id: String,
    val league: SportsLeague,
    val title: String,
    val startTimeEpochMillis: Long?,
    val status: SportsEventStatus,
    val kind: SportsContentKind = SportsContentKind.EVENT,
    val providerName: String,
    val detailUrl: String? = null,
    val authorizedPlaybackUrl: String? = null
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(providerName.isNotBlank())
        require(authorizedPlaybackUrl == null || authorizedPlaybackUrl.startsWith("https://")) {
            "Playback URLs must be explicit HTTPS links from an authorized provider"
        }
    }
}

/** Providers must return only real, source-backed event data, never generated fixtures in production. */
interface SportsEventProvider {
    suspend fun events(league: SportsLeague): List<SportsEvent>
}

object SportsEventSections {
    fun live(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT && it.status == SportsEventStatus.LIVE
    }
    fun upcoming(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT && it.status == SportsEventStatus.SCHEDULED
    }.sortedWith(compareBy(nullsLast()) { it.startTimeEpochMillis })
    fun replays(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.REPLAY }
    fun highlights(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.HIGHLIGHT }
}

/** Until a vetted schedule provider is configured, no events are displayed as live. */
object UnconfiguredSportsEventProvider : SportsEventProvider {
    override suspend fun events(league: SportsLeague): List<SportsEvent> = emptyList()
}

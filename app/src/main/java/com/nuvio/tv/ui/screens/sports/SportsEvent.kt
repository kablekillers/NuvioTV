package com.nuvio.tv.ui.screens.sports

/** Provider-independent sports metadata. Stream availability is never inferred from event status. */
enum class SportsLeague(val label: String) {
    NFL("NFL"), COLLEGE_FOOTBALL("College Football"), NBA("NBA"),
    MLB("MLB"), NHL("NHL"), UFC("UFC")
}

enum class SportsEventStatus { SCHEDULED, LIVE, FINAL, POSTPONED, UNKNOWN }
enum class SportsContentKind { EVENT, REPLAY, HIGHLIGHT }

/** One side of a matchup (team or fighter) exactly as reported by the provider. */
data class SportsCompetitor(
    val name: String,
    val abbreviation: String? = null,
    val score: String? = null,
    val record: String? = null,
    val isHome: Boolean = false,
    val isWinner: Boolean = false
) {
    init {
        require(name.isNotBlank())
    }

    /** Short label for compact score lines, e.g. "KC". */
    val shortLabel: String get() = abbreviation?.takeIf { it.isNotBlank() } ?: name
}

data class SportsEvent(
    val id: String,
    val league: SportsLeague,
    val title: String,
    val startTimeEpochMillis: Long?,
    val status: SportsEventStatus,
    val kind: SportsContentKind = SportsContentKind.EVENT,
    val providerName: String,
    val detailUrl: String? = null,
    val authorizedPlaybackUrl: String? = null,
    /** Provider's own short status text, e.g. "Q3 4:12", "Final/OT", "Top 7th". */
    val statusDetail: String? = null,
    val competitors: List<SportsCompetitor> = emptyList(),
    val venue: String? = null,
    /** TV networks / services the provider lists as carrying the event. */
    val broadcasts: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(providerName.isNotBlank())
        require(authorizedPlaybackUrl == null || authorizedPlaybackUrl.startsWith("https://")) {
            "Playback URLs must be explicit HTTPS links from an authorized provider"
        }
    }

    val home: SportsCompetitor? get() = competitors.firstOrNull { it.isHome }
    val away: SportsCompetitor? get() = competitors.firstOrNull { !it.isHome }

    /** True when the provider reported scores for both sides of a two-sided matchup. */
    val hasScores: Boolean
        get() = competitors.size == 2 && competitors.all { !it.score.isNullOrBlank() }

    /** "BUF 17 – 24 KC" style line, or null when no scores are reported. */
    fun scoreLine(): String? {
        val awaySide = away ?: return null
        val homeSide = home ?: return null
        if (!hasScores) return null
        return "${awaySide.shortLabel} ${awaySide.score} – ${homeSide.score} ${homeSide.shortLabel}"
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

    /** Scheduled games first by start time; postponed games stay visible with their status. */
    fun upcoming(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT &&
            (it.status == SportsEventStatus.SCHEDULED || it.status == SportsEventStatus.POSTPONED)
    }.sortedWith(
        compareBy<SportsEvent> { it.status == SportsEventStatus.POSTPONED }
            .thenBy(nullsLast()) { it.startTimeEpochMillis }
    )

    /** Completed games, most recent first. */
    fun final(events: List<SportsEvent>) = events.filter {
        it.kind == SportsContentKind.EVENT && it.status == SportsEventStatus.FINAL
    }.sortedWith(compareByDescending(nullsFirst()) { it.startTimeEpochMillis })

    fun replays(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.REPLAY }
    fun highlights(events: List<SportsEvent>) = events.filter { it.kind == SportsContentKind.HIGHLIGHT }
}

/** Until a vetted schedule provider is configured, no events are displayed as live. */
object UnconfiguredSportsEventProvider : SportsEventProvider {
    override suspend fun events(league: SportsLeague): List<SportsEvent> = emptyList()
}

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview

internal object SportsRecordingClassifier {
    private const val FULL_REPLAY_SECONDS = 30 * 60
    private val sportNames = SportsLeague.entries.map { it.catalogSport }.toSet()
    private val sportTags = mapOf("mlb" to "baseball", "nhl" to "hockey", "nfl" to "american_football",
        "ncaaf" to "american_football", "nba" to "basketball", "wnba" to "basketball",
        "ncaam" to "basketball", "ncaaw" to "basketball", "ncaab" to "basketball")
    private val streamsId = Regex(
        """^recap:streamed_(american_football|motor_sports|basketball|baseball|hockey|football|tennis|golf|fight|rugby|cricket|afl|darts)_""",
        RegexOption.IGNORE_CASE,
    )
    private val leagueLabels = Regex(
        """\b(?:mlb|nba|wnba|nfl|ncaaf|ncaam|ncaaw|ncaab|nhl|ahl|khl|echl|pwhl)\b|\b(?:college football|(?:ncaa |college )?women'?s (?:college )?basketball|(?:ncaa |college )?men'?s (?:college )?basketball)\b""",
    )
    private val highlightTitle = Regex(
        """\b(?:highlights?|condensed(?: game)?|recaps?|top plays|best plays|all goals|goals? (?:&|and) highlights|mini ?match|in \d{1,2} min(?:ute)?s?)\b""",
        RegexOption.IGNORE_CASE,
    )
    // Genres and descriptions: Sports Streams tags every recording "Recap" (full
    // games included), so only explicit highlight words count there.
    private val highlightWords = Regex(
        """\b(?:highlights?|condensed(?: game)?|top plays|best plays|all goals)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val fullTitle = Regex(
        """\b(?:full (?:game|match|fight|race|event|replay|broadcast)|complete (?:game|match)|whole game|replays?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val durationLine = Regex("""^\s*(?:duration|runtime)\s*:\s*([^\r\n]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val recordedDuration = Regex("""^\s*Recorded\s*[·•]\s*([^\r\n]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val recapStreamLabel = Regex("""^Recap\b""", RegexOption.IGNORE_CASE)
    private val durationUnits = Regex(
        """(?:(\d{1,4})\s*(?:hours?|hrs?|h)\s*)?(?:(\d{1,4})\s*(?:minutes?|mins?|m)\s*)?(?:(\d{1,4})\s*(?:seconds?|secs?|s))?""",
        RegexOption.IGNORE_CASE,
    )
    private val durationClock = Regex("""(?:([0-9]{1,3}):)?([0-9]{1,3}):([0-5][0-9])""")

    fun appliesTo(league: SportsLeague): Boolean = league == SportsLeague.NHL ||
        league == SportsLeague.NBA || league == SportsLeague.COLLEGE_FOOTBALL || league.isCollegeBasketball

    fun accepts(
        addonId: String,
        catalog: CatalogDescriptor,
        item: MetaPreview,
        league: SportsLeague,
        score: Int,
    ): Boolean {
        val genres = item.genres.map { it.trim().lowercase().replace('-', '_').replace(' ', '_') }
        val sport = streamsId.find(item.id)?.groupValues?.get(1)?.lowercase()
            ?: genres.firstNotNullOfOrNull { sportTags[it] ?: it.takeIf(sportNames::contains) }
        if (sport != null && sport != league.catalogSport) return false

        val label = (item.genres.joinToString(" ") + " " + item.name).lowercase()
            .replace('’', '\'').replace('-', ' ').replace('_', ' ')
        val labels = leagueLabels.findAll(label).map { it.value }.toSet()
        if (labels.any { !labelMatches(it, league) }) return false
        if (labels.any { it != "ncaab" }) return true

        val scoped = SportsAddonCatalogs.isScopedCatalog(catalog, league)
        if (league == SportsLeague.NHL && (scoped || sport == "hockey" && isSportsStreams(addonId))) return true
        if (league == SportsLeague.NBA && addonId == SportsAddonCatalogs.NUVIO_LIVE && scoped) return true
        // A basketball or football catalog can mix college and professional games.
        return score > 0 && (sport != null || scoped || labels.isNotEmpty())
    }

    private fun labelMatches(label: String, league: SportsLeague): Boolean = when (label) {
        "nba" -> league == SportsLeague.NBA
        "nhl" -> league == SportsLeague.NHL
        "ncaaf", "college football" -> league == SportsLeague.COLLEGE_FOOTBALL
        "ncaaw" -> league == SportsLeague.NCAA_WOMEN
        "ncaam" -> league == SportsLeague.NCAA_MEN
        "ncaab" -> league.isCollegeBasketball
        else -> when {
            "women" in label -> league == SportsLeague.NCAA_WOMEN
            "men" in label -> league == SportsLeague.NCAA_MEN
            else -> false
        }
    }

    fun kind(addonId: String, catalog: CatalogDescriptor, item: MetaPreview): SportsContentKind {
        val seconds = durationSeconds(item.runtime) ?: durationSeconds(
            item.description?.let { durationLine.find(it)?.groupValues?.get(1) },
        ) ?: durationSeconds(item.description?.let { recordedDuration.find(it)?.groupValues?.get(1) })
        return when {
            seconds != null && seconds > FULL_REPLAY_SECONDS -> SportsContentKind.REPLAY
            seconds != null -> SportsContentKind.HIGHLIGHT
            highlightTitle.containsMatchIn(item.name) || item.genres.any { highlightWords.containsMatchIn(it) } ->
                SportsContentKind.HIGHLIGHT
            fullTitle.containsMatchIn(item.name) || item.genres.any { fullTitle.containsMatchIn(it) } -> SportsContentKind.REPLAY
            // Description hints ("Full match replay", "Extended highlights") when the title has none.
            item.description?.let { highlightWords.containsMatchIn(it) && !fullTitle.containsMatchIn(it) } == true ->
                SportsContentKind.HIGHLIGHT
            item.description?.let { fullTitle.containsMatchIn(it) } == true -> SportsContentKind.REPLAY
            isSportsStreams(addonId) && catalog.id == "sports_recaps" -> SportsContentKind.REPLAY
            addonId == SportsAddonCatalogs.NUVIO_LIVE && catalog.id == "nuvio_sports_replays_hockey" ->
                SportsContentKind.HIGHLIGHT
            // A list named for highlights (e.g. StremVerse "Match Highlights") holds short clips.
            highlightTitle.containsMatchIn(catalog.name) || highlightTitle.containsMatchIn(catalog.id.replace('_', ' ')) ->
                SportsContentKind.HIGHLIGHT
            else -> SportsContentKind.REPLAY
        }
    }

    fun streamLabel(addonId: String?, label: String, subtitle: String?): String {
        if (!isSportsStreams(addonId) || !recapStreamLabel.containsMatchIn(label)) return label
        val seconds = subtitle?.let { recordedDuration.find(it)?.groupValues?.get(1) }?.let(::durationSeconds)
        if (seconds == null || seconds < FULL_REPLAY_SECONDS) return label
        return recapStreamLabel.replaceFirst(label, "Replay")
    }

    private fun isSportsStreams(id: String?) = id == SportsAddonCatalogs.SPORTS_STREAMS || id == "community.sports.fly"

    internal fun durationSeconds(raw: String?): Int? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val seconds = durationClock.matchEntire(value)?.let { match ->
            val (h, m, s) = match.destructured
            (h.toIntOrNull() ?: 0) * 3600 + m.toInt() * 60 + s.toInt()
        } ?: durationUnits.matchEntire(value)?.let { match ->
            val (h, m, s) = match.destructured
            (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        } ?: value.takeIf { it.length <= 4 }?.toIntOrNull()?.times(60)
        return seconds?.takeIf { it > 0 }
    }
}

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.Addon
import java.text.Normalizer
import java.util.Locale

/**
 * An add-on catalog entry is the source of truth for the playable ID.
 * Never substitute an ESPN event ID into an add-on stream endpoint.
 */
data class SportsCatalogMatch(
    val addon: Addon,
    val catalogId: String,
    val contentType: String,
    val streamId: String,
    val title: String,
    val score: Int,
    val kind: SportsContentKind = SportsContentKind.EVENT
)

internal object SportsEventMatcher {
    private val noise = setOf("fc", "cf", "the", "vs", "v", "at", "live", "stream", "hd", "4k", "watch")
    private val tokenSeparator = Regex("[^a-z0-9]+")
    private val practice = Regex("""\b(?:fp|(?:free\s+)?practice\s*)([1-3])\b""", RegexOption.IGNORE_CASE)
    private val qualifying = Regex("""\b(?:qualifying|qual|shootout)\b""", RegexOption.IGNORE_CASE)

    private fun tokens(value: String): Set<String> =
        tokenSeparator.split(Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD))
            .filterTo(mutableSetOf()) { it.length > 1 && it !in noise }

    private fun catalogSession(title: String, titleTokens: Set<String>): String? {
        val practiceNumber = practice.find(title)?.groupValues?.get(1)
        if (practiceNumber != null) return "fp$practiceNumber"
        if ("practice" in titleTokens) return null
        val isQualifying = qualifying.containsMatchIn(title)
        val isSprint = "sprint" in titleTokens
        return when {
            isSprint && isQualifying -> "sprintqual"
            isSprint -> "sprint"
            isQualifying -> "qual"
            else -> "race"
        }
    }

    private fun normalizedSession(session: String): String =
        when (val normalized = session.lowercase().filter(Char::isLetterOrDigit)) {
            "sprintshootout", "sprintqualifying", "sprintqual", "sq" -> "sprintqual"
            else -> normalized
        }

    private data class MatchEvent(
        val title: Set<String>,
        val sides: Pair<Set<String>, Set<String>>?,
        val session: String?,
    )

    // A replay catalog can contain many titles and run against a full league schedule.
    // Prepare the schedule once and tokenize each catalog title once per result.
    class Prepared(events: List<SportsEvent>) {
        private val matches = events.map { event ->
            val sides = if (event.competitors.size >= 2) {
                val first = tokens(event.competitors[0].name)
                val second = tokens(event.competitors[1].name)
                (first - second) to (second - first)
            } else null
            MatchEvent(
                title = if (sides == null) tokens(event.catalogMatchTitle ?: event.title) else emptySet(),
                sides = sides,
                session = event.racingSession?.let(::normalizedSession),
            )
        }
        private val hasRacingSessions = matches.any { it.session != null }

        fun score(catalogTitle: String): Int {
            if (matches.isEmpty()) return 0
            val title = tokens(catalogTitle)
            if (title.isEmpty()) return 0
            val session = if (hasRacingSessions) catalogSession(catalogTitle, title) else null
            return matches.maxOf { event ->
                if (event.session != null && event.session != session) 0 else scoreTitle(event, title)
            }
        }
    }

    private fun scoreTitle(event: MatchEvent, title: Set<String>): Int {
        event.sides?.let { (first, second) ->
            val a = first.count { it in title }
            val b = second.count { it in title }
            if (a == 0 || b == 0) return 0
            return 50 + (a * 10) + (b * 10)
        }
        if (event.title.size < 2) return 0
        val overlap = event.title.count { it in title }
        return if (overlap >= 2 && overlap * 3 >= event.title.size * 2) 40 + overlap * 10 else 0
    }

    fun score(event: SportsEvent, catalogTitle: String): Int = Prepared(listOf(event)).score(catalogTitle)
}

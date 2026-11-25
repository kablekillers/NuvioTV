package com.nuvio.tv.ui.screens.sports

/**
 * Live-TV channel add-ons (CNCBridge and similar) list channels ("ESPN",
 * "FOX 5 New York", "NFL Network"), not games, so their items never match a
 * game title. This matches a channel item to the networks actually carrying the
 * game (ESPN's broadcast list) plus the league's own channels.
 */
internal object SportsChannelMatcher {
    private val aliases = mapOf(
        "nflnet" to "nflnetwork",
        "nfln" to "nflnetwork",
        "fs1" to "foxsports1",
        "fs2" to "foxsports2",
        "btn" to "bigtennetwork",
        "secn" to "secnetwork",
        "accn" to "accnetwork",
        "cbssn" to "cbssportsnetwork",
        "mlbn" to "mlbnetwork",
        "nhln" to "nhlnetwork",
        "espnplus" to "espn",
        "abcespn" to "abc",
    )

    private fun leagueChannels(league: SportsLeague): List<String> = when (league) {
        SportsLeague.NFL -> listOf("nflnetwork", "nflredzone", "redzone")
        SportsLeague.NBA -> listOf("nbatv")
        SportsLeague.MLB -> listOf("mlbnetwork")
        SportsLeague.NHL -> listOf("nhlnetwork")
        SportsLeague.COLLEGE_FOOTBALL, SportsLeague.COLLEGE_BASKETBALL, SportsLeague.COLLEGE_HOCKEY,
        SportsLeague.COLLEGE_BASEBALL, SportsLeague.COLLEGE_VOLLEYBALL ->
            listOf("secnetwork", "bigtennetwork", "accnetwork", "espnu")
        SportsLeague.UFC -> listOf("ufcfightpass")
        else -> emptyList()
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace("+", " plus").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    private fun compact(text: String): String = words(text).joinToString("")

    /** Compact channel names to look for, e.g. "espn", "foxsports1", "nflnetwork". */
    fun terms(league: SportsLeague, events: List<SportsEvent>): Set<String> {
        val broadcast = events.flatMap { it.broadcasts }
            .flatMap { it.split('/', ',', '&') }
            .map { compact(it) }
            .filter { it.length >= 2 }
            .map { aliases[it] ?: it }
        return (broadcast + leagueChannels(league)).toSet()
    }

    /** Search words for channel catalogs that only answer searches. */
    fun searchQueries(league: SportsLeague, events: List<SportsEvent>): List<String> =
        events.flatMap { it.broadcasts }.flatMap { it.split('/', ',', '&') }.map { it.trim() }
            .filter { it.length >= 2 }.distinct().take(6)

    private val excluded = Regex("""\b(news|weather|replays?|highlights?|recaps?|condensed)\b""", RegexOption.IGNORE_CASE)

    fun matches(channelName: String, terms: Set<String>): Boolean {
        if (terms.isEmpty() || excluded.containsMatchIn(channelName)) return false
        val name = words(channelName)
        // A term matches a run of whole words: "fox" fits "FOX 5 New York" but not "Foxtel".
        for (start in name.indices) {
            val run = StringBuilder()
            for (end in start until minOf(name.size, start + 4)) {
                run.append(name[end])
                if (run.toString() in terms) return true
            }
        }
        return false
    }
}

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor

/**
 * Manifest-derived sports catalog routing. Never construct stream IDs from ESPN IDs:
 * a catalog item must first supply its own ID, then that ID can be passed to the
 * existing StreamRepository for the matching content type.
 *
 * This registry contains public manifest identifiers only. Personalized add-on
 * URLs and credentials are deliberately not embedded in the application.
 */
object SportsAddonCatalogs {
    const val SPORTS_STREAMS = "community.sports.streamed.premium"
    private const val FREE_SPORTS_STREAMS = "community.sports.fly"
    const val NUVIO_LIVE = "community.nuvio.live-sports"
    const val STREMVERSE = "com.stremverse.org"

    data class CatalogRoute(val id: String, val type: String, val category: String)

    fun routes(addon: Addon, league: SportsLeague, replays: Boolean = false): List<CatalogRoute> {
        if (!addon.enabled) return emptyList()
        val catalogs = if (league !in originalLeagues || addon.id == FREE_SPORTS_STREAMS) {
            expandedRoutes(addon, league, replays)
        } else {
            originalRoutes(addon, league, replays)
        }
        return catalogs.map { CatalogRoute(it.id, it.apiType, if (replays) "replay" else "event") }
    }

    private fun originalRoutes(addon: Addon, league: SportsLeague, replays: Boolean): List<CatalogDescriptor> {
        val candidates = when (addon.id) {
            SPORTS_STREAMS -> if (replays) listOf("sports_recaps") else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "sports_american_football"
                    SportsLeague.NBA -> "sports_basketball"
                    SportsLeague.MLB -> "sports_baseball"
                    SportsLeague.NHL -> "sports_hockey"
                    SportsLeague.UFC -> "sports_fight"
                    else -> null
                }, "sports_live", "sports_today"
            )
            NUVIO_LIVE -> if (replays) when (league) {
                SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> listOf("nuvio_sports_replays_american_football")
                SportsLeague.NBA -> listOf("nuvio_sports_replays_basketball")
                SportsLeague.MLB -> listOf("nuvio_sports_replays_baseball")
                SportsLeague.NHL -> listOf("nuvio_sports_replays_hockey")
                SportsLeague.UFC -> listOf("nuvio_sports_replays")
                else -> emptyList()
            } else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "nuvio_sports_american_football"
                    SportsLeague.NBA -> "nuvio_sports_basketball"
                    SportsLeague.MLB -> "nuvio_sports_baseball"
                    SportsLeague.NHL -> "nuvio_sports_hockey"
                    SportsLeague.UFC -> "nuvio_sports_mma"
                    else -> null
                }, "nuvio_sports_live", "nuvio_sports_upcoming"
            )
            STREMVERSE -> if (replays) listOf("stremverse_replays")
                else listOf("stremverse_xtra_events", "stremverse_live_events")
            else -> null
        }
        return if (candidates != null) {
            addon.catalogs.filter { it.id in candidates && it.apiType in setOf("tv", "sport") }
        } else {
            additionalCatalogs(addon, replays)
        }
    }

    private val originalLeagues = setOf(
        SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL, SportsLeague.NBA,
        SportsLeague.MLB, SportsLeague.NHL, SportsLeague.UFC,
    )

    private val expandedProviders = setOf(SPORTS_STREAMS, FREE_SPORTS_STREAMS, NUVIO_LIVE, STREMVERSE)
    private val generalCatalogs = setOf(
        "sports_live", "sports_today", "sports_recaps",
        "nuvio_sports_live", "nuvio_sports_today", "nuvio_sports_upcoming", "nuvio_sports_replays",
        "stremverse_xtra_events", "stremverse_live_events", "stremverse_replays",
    )

    private fun expandedRoutes(addon: Addon, league: SportsLeague, replays: Boolean): List<CatalogDescriptor> {
        if (addon.id !in expandedProviders) {
            return additionalCatalogs(addon, replays).filter {
                league.scoreboardPath != null || isScopedCatalog(it, league)
            }
        }
        return addon.catalogs.filter { catalog ->
            catalog.apiType in eventTypes &&
                replayCatalog.containsMatchIn("${catalog.id} ${catalog.name}") == replays &&
                (isScopedCatalog(catalog, league) || league.scoreboardPath != null && catalog.id in generalCatalogs)
        }
    }

    internal fun isScopedCatalog(catalog: CatalogDescriptor, league: SportsLeague): Boolean {
        val sport = catalog.id.lowercase().replace('-', '_')
            .removePrefix("nuvio_").removePrefix("sports_").removePrefix("replays_")
        return sport == league.catalogSport || when (league.catalogSport) {
            "motor_sports" -> sport in setOf("motorsport", "motorsports", "motor_sport")
            "fight" -> sport in setOf("mma", "boxing", "mma_boxing")
            "football" -> sport == "soccer"
            "other" -> sport in setOf("others", "other_sports")
            else -> false
        }
    }

    private val additionalProviders = listOf("nebula", "pencari", "penguplay", "tennis", "cncbridge", "cncverse", "plavio", "plaviolive")
    private val eventTypes = setOf("sport", "sports", "tv", "channel", "livetv", "live-tv", "live_tv")
    private val replayCatalog = Regex(
        """(?:^|[^a-z])(?:replays?|highlights?|recaps?)(?:[^a-z]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private fun additionalCatalogs(addon: Addon, replays: Boolean): List<CatalogDescriptor> {
        val identity = (addon.id + " " + addon.name).lowercase().filter(Char::isLetterOrDigit)
        // CNCBridge extensions can have distinct IDs; inspect catalog metadata too.
        val knownProvider = additionalProviders.any { it in identity }
        return addon.catalogs.filter { catalog ->
            val label = (catalog.id + " " + catalog.name).lowercase()
            val sportsCatalog = Regex(
                """sports?|nfl|redzone|football|basketball|baseball|hockey|ufc|ncaa|live.?tv|channels?|events?""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(label)
            val isRecording = replayCatalog.containsMatchIn(label)
            (knownProvider || sportsCatalog) &&
                (if (replays) isRecording else !isRecording) &&
                catalog.apiType in (eventTypes + setOf("movie", "series")) &&
                addon.resources.any { it.name == "stream" &&
                    (it.types.isEmpty() || catalog.apiType in it.types) }
        }
    }

    fun supportsStream(addon: Addon, type: String, id: String): Boolean {
        if (!addon.enabled || id.isBlank()) return false
        return addon.resources.any { resource ->
            resource.name == "stream" &&
                (resource.types.isEmpty() || type in resource.types) &&
                (resource.idPrefixes ?: addon.idPrefixes).let { prefixes ->
                    prefixes.isEmpty() || prefixes.any(id::startsWith)
                }
        }
    }
}

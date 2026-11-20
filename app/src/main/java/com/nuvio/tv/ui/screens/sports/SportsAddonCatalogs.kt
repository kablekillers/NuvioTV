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
    const val NUVIO_LIVE = "community.nuvio.live-sports"
    const val STREMVERSE = "com.stremverse.org"

    data class CatalogRoute(val id: String, val type: String, val category: String)

    fun routes(addon: Addon, league: SportsLeague, replays: Boolean = false): List<CatalogRoute> {
        if (!addon.enabled) return emptyList()
        val candidates = when (addon.id) {
            SPORTS_STREAMS -> if (replays) listOf("sports_recaps") else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "sports_american_football"
                    SportsLeague.NBA -> "sports_basketball"
                    SportsLeague.MLB -> "sports_baseball"
                    SportsLeague.NHL -> "sports_hockey"
                    SportsLeague.UFC -> "sports_fight"
                }, "sports_live", "sports_today"
            )
            NUVIO_LIVE -> if (replays) when (league) {
                SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> listOf("nuvio_sports_replays_american_football")
                SportsLeague.NBA -> listOf("nuvio_sports_replays_basketball")
                SportsLeague.MLB -> listOf("nuvio_sports_replays_baseball")
                SportsLeague.NHL -> listOf("nuvio_sports_replays_hockey")
                SportsLeague.UFC -> listOf("nuvio_sports_replays")
            } else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "nuvio_sports_american_football"
                    SportsLeague.NBA -> "nuvio_sports_basketball"
                    SportsLeague.MLB -> "nuvio_sports_baseball"
                    SportsLeague.NHL -> "nuvio_sports_hockey"
                    SportsLeague.UFC -> "nuvio_sports_mma"
                }, "nuvio_sports_live", "nuvio_sports_upcoming"
            )
            STREMVERSE -> if (replays) listOf("stremverse_replays")
                else listOf("stremverse_xtra_events", "stremverse_live_events")
            else -> null
        }
        // Respect the user's actual installed manifest: don't query absent catalogs.
        val catalogs = if (candidates != null) {
            addon.catalogs.filter { it.id in candidates && it.apiType in setOf("tv", "sport") }
        } else {
            browseCatalogs(addon).filter { catalog ->
                catalog.apiType !in onDemandTypes &&
                    replayCatalog.containsMatchIn("${catalog.id} ${catalog.name}") == replays
            }
        }
        return catalogs.map { CatalogRoute(it.id, it.apiType, if (replays) "replay" else "event") }
    }

    private val onDemandTypes = setOf("movie", "series")
    private val sportsTypes = setOf("sport", "sports")
    private val sportsCatalog = Regex(
        """(?:^|[^a-z])(?:sports?|tennis|basketball|baseball|hockey|football|mma|ufc|nfl|nba|mlb|nhl|ncaa|atp|wta)(?:[^a-z]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val replayCatalog = Regex(
        """(?:^|[^a-z])(?:replays?|highlights?|recaps?)(?:[^a-z]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val sportsProviders = listOf("nebula", "pencari", "penguplay", "tennis")

    fun browseCatalogs(addon: Addon): List<CatalogDescriptor> {
        if (!addon.enabled) return emptyList()
        val provider = (addon.id + " " + addon.name).lowercase().filter(Char::isLetterOrDigit)
        val hasSportsProvider = addon.id in setOf(SPORTS_STREAMS, NUVIO_LIVE, STREMVERSE) ||
            sportsProviders.any { it in provider }
        return addon.catalogs.filter { catalog ->
            val type = catalog.apiType
            val sportsRow = sportsCatalog.containsMatchIn("${catalog.id} ${catalog.name}")
            val pencariSportsOnDemand = type in onDemandTypes &&
                "pencari" in provider && preferredGenre(catalog) != null
            val liveTv = type in setOf("tv", "channel", "livetv", "live-tv", "live_tv")
            canFetch(catalog) &&
                addon.resources.any { it.name == "stream" && (it.types.isEmpty() || type in it.types) } &&
                (type in sportsTypes || sportsRow || pencariSportsOnDemand ||
                    liveTv && (hasSportsProvider || preferredGenre(catalog) != null))
        }
    }

    fun genre(catalog: CatalogDescriptor): String? {
        val genre = catalog.extra.firstOrNull { it.name == "genre" } ?: return null
        return preferredGenre(catalog) ?: if (genre.isRequired) {
            genre.options.orEmpty().firstOrNull { it.equals("all", ignoreCase = true) }
                ?: genre.defaultValue?.takeIf { it.isNotBlank() }
                ?: genre.options.orEmpty().firstOrNull()
        } else null
    }

    private fun preferredGenre(catalog: CatalogDescriptor): String? =
        catalog.extra.firstOrNull { it.name == "genre" }?.options.orEmpty().firstOrNull {
            it.trim().lowercase() in setOf("sports", "sport", "sports channels", "live sports")
        }

    private fun canFetch(catalog: CatalogDescriptor): Boolean = catalog.extra.none { extra ->
        extra.isRequired && when (extra.name) {
            "skip" -> false
            "genre" -> genre(catalog) == null
            else -> true
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

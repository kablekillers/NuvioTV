package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.Addon

/**
 * Manifest-derived sports catalog routing. Never construct stream IDs from ESPN IDs:
 * a catalog item must first supply its own ID, then that ID can be passed to the
 * existing StreamRepository for the matching content type.
 *
 * This registry contains public manifest identifiers only. Personalized add-on
 * URLs and credentials are deliberately not embedded in the application.
 */
internal object SportsAddonCatalogs {
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
            else -> emptyList()
        }
        // Respect the user's actual installed manifest: don't query absent catalogs.
        return addon.catalogs.filter { it.id in candidates && it.apiType in setOf("tv", "sport") }
            .map { CatalogRoute(it.id, it.apiType, if (replays) "replay" else "event") }
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

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Fetches real catalog entries from the user's enabled, installed sports add-ons.
 * This is discovery only: the returned ID is later passed to the existing stream
 * screen, which resolves streams through the normal add-on/player pipeline.
 */
class SportsAddonDiscovery @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    suspend fun find(event: SportsEvent, replays: Boolean = false): List<SportsCatalogMatch> = discover(event, replays).matches

    data class DiscoveryResult(
        val matches: List<SportsCatalogMatch>,
        val browse: List<SportsCatalogMatch>,
        val catalogsChecked: Int,
        val catalogItems: Int,
        val catalogErrors: Int
    )

    suspend fun discover(event: SportsEvent, replays: Boolean = false): DiscoveryResult {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        val found = mutableListOf<SportsCatalogMatch>()
        val browse = mutableListOf<SportsCatalogMatch>()
        var catalogsChecked = 0
        var catalogItems = 0
        var catalogErrors = 0
        for (addon in addons) {
            for (route in SportsAddonCatalogs.routes(addon, event.league, replays)) {
                val descriptor = addon.catalogs.firstOrNull { it.id == route.id } ?: continue
                // Required genre is present on StremVerse replays; use All to
                // avoid silently requesting an invalid bare catalog URL.
                val genre = descriptor.extra.firstOrNull { it.name == "genre" && it.isRequired }
                val extra = if (genre != null && "All" in genre.options.orEmpty())
                    mapOf("genre" to "All") else emptyMap()
                // Nuvio replay catalogs require skip even on page zero.
                val skipRequired = descriptor.extra.any { it.name == "skip" && it.isRequired }
                val args = if (skipRequired) extra + ("skip" to "0") else extra
                try {
                    catalogsChecked++
                    val response = catalogRepository.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = route.id,
                        catalogName = descriptor.name,
                        type = route.type,
                        extraArgs = args
                    ).first { it !is NetworkResult.Loading }
                    if (response is NetworkResult.Success) {
                        catalogItems += response.data.items.size
                        response.data.items.forEach { item ->
                            if (!SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)) return@forEach
                            val score = SportsEventMatcher.score(event, item.name)
                            val candidate = SportsCatalogMatch(addon, route.id, item.apiType, item.id, item.name, score)
                            if (score > 0) found += candidate
                            else if (route.id.contains("basketball", ignoreCase = true) && event.league == SportsLeague.NBA ||
                                route.id.contains("baseball", ignoreCase = true) && event.league == SportsLeague.MLB ||
                                route.id.contains("hockey", ignoreCase = true) && event.league == SportsLeague.NHL ||
                                route.id.contains("football", ignoreCase = true) && event.league in listOf(SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL) ||
                                (route.id.contains("fight", ignoreCase = true) || route.id.contains("mma", ignoreCase = true)) && event.league == SportsLeague.UFC) {
                                browse += candidate
                            }
                        }
                    } else catalogErrors++
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    catalogErrors++ // One unavailable provider must not block the other add-ons.
                }
            }
        }
        return DiscoveryResult(
            found.distinctBy { Triple(it.addon.baseUrl, it.contentType, it.streamId) }.sortedByDescending { it.score },
            browse.distinctBy { Triple(it.addon.baseUrl, it.contentType, it.streamId) }.take(20),
            catalogsChecked, catalogItems, catalogErrors
        )
    }
}

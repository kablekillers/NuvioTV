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
    suspend fun find(event: SportsEvent, replays: Boolean = false): List<SportsCatalogMatch> {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        val found = mutableListOf<SportsCatalogMatch>()
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
                        response.data.items.forEach { item ->
                            val score = SportsEventMatcher.score(event, item.name)
                            if (score > 0 && SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)) {
                                found += SportsCatalogMatch(
                                    addon, route.id, item.apiType, item.id, item.name, score
                                )
                            }
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // One unavailable provider must not block the other add-ons.
                }
            }
        }
        return found.distinctBy { Triple(it.addon.baseUrl, it.contentType, it.streamId) }
            .sortedByDescending { it.score }
    }
}

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    internal data class CatalogSource(val addon: Addon, val catalog: CatalogDescriptor)

    internal val sources = addonRepository.getInstalledAddons()

    internal suspend fun browse(source: CatalogSource): List<SportsCatalogMatch> {
        val (addon, catalog) = source
        val result = loadCatalog(addon, catalog)
        check(result is NetworkResult.Success)
        return result.data.items.mapNotNull { item ->
            if (!SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)) return@mapNotNull null
            SportsCatalogMatch(addon, catalog.id, item.apiType, item.id, item.name, score = 0)
        }.distinctBy { it.contentType to it.streamId }
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor): NetworkResult<CatalogRow>? {
        val args = buildMap {
            SportsAddonCatalogs.genre(catalog)?.let { put("genre", it) }
            if (catalog.extra.any { it.name == "skip" && it.isRequired }) put("skip", "0")
        }
        return withTimeoutOrNull(10_000) {
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = catalog.apiType,
                extraArgs = args
            ).first { it !is NetworkResult.Loading }
        }
    }

    suspend fun find(event: SportsEvent, replays: Boolean = false): List<SportsCatalogMatch> = discover(event, replays).matches

    data class DiscoveryResult(
        val matches: List<SportsCatalogMatch>,
        val browse: List<SportsCatalogMatch>,
        val catalogsChecked: Int,
        val catalogItems: Int,
        val catalogErrors: Int
    )

    suspend fun discover(event: SportsEvent, replays: Boolean = false): DiscoveryResult {
        val addons = sources.first().filter { it.enabled }
        val found = mutableListOf<SportsCatalogMatch>()
        val browse = mutableListOf<SportsCatalogMatch>()
        var catalogsChecked = 0
        var catalogItems = 0
        var catalogErrors = 0
        for (addon in addons) {
            for (route in SportsAddonCatalogs.routes(addon, event.league, replays)) {
                val descriptor = addon.catalogs.firstOrNull { it.id == route.id && it.apiType == route.type } ?: continue
                try {
                    catalogsChecked++
                    val response = loadCatalog(addon, descriptor)
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

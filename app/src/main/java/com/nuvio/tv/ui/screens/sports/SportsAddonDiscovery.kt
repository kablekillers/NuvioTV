package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Resolves catalog IDs from enabled add-ons for the existing stream picker. */
class SportsAddonDiscovery @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    data class DiscoveryResult(
        val matches: List<SportsCatalogMatch>,
        val browse: List<SportsCatalogMatch>,
        val catalogsChecked: Int,
        val catalogItems: Int,
        val catalogErrors: Int
    )

    private data class CatalogMatches(
        val matches: List<SportsCatalogMatch> = emptyList(),
        val browse: List<SportsCatalogMatch> = emptyList(),
        val itemCount: Int = 0,
        val failed: Boolean = false,
    )

    suspend fun discover(event: SportsEvent, replays: Boolean = false): DiscoveryResult =
        discover(event.league, listOf(event), replays, browsingLeague = false)

    suspend fun discoverLeague(league: SportsLeague, events: List<SportsEvent>, replays: Boolean): List<SportsCatalogMatch> =
        discover(league, events, replays, browsingLeague = true).matches

    private suspend fun discover(
        league: SportsLeague,
        events: List<SportsEvent>,
        replays: Boolean,
        browsingLeague: Boolean,
    ): DiscoveryResult = withContext(Dispatchers.Default) {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        val matcher = SportsEventMatcher.Prepared(events)
        val limit = Semaphore(4)
        val requests = addons.flatMap { addon ->
            SportsAddonCatalogs.routes(addon, league, replays).mapNotNull { route ->
                val catalog = addon.catalogs.firstOrNull { it.id == route.id && it.apiType == route.type }
                    ?: return@mapNotNull null
                val args = catalogArgs(catalog) ?: return@mapNotNull null
                async {
                    val pages = searchQueries(catalog, league, events).map { query ->
                        limit.withPermit {
                            inspectCatalog(addon, catalog,
                                if (query == null) args else args + ("search" to query),
                                league, matcher, replays, browsingLeague)
                        }
                    }
                    CatalogMatches(
                        matches = pages.flatMap { it.matches },
                        browse = pages.flatMap { it.browse },
                        itemCount = pages.sumOf { it.itemCount },
                        failed = pages.isNotEmpty() && pages.all { it.failed },
                    )
                }
            }
        }
        val pages = requests.awaitAll()
        val matches = pages.flatMap { it.matches }.sortedByDescending { it.score }.distinctBy { it.identity() }
        DiscoveryResult(
            matches = if (browsingLeague) matches.groupBy { it.kind }.values.flatMap { it.take(40) } else matches,
            browse = pages.flatMap { it.browse }.distinctBy { it.identity() }.take(20),
            catalogsChecked = pages.size,
            catalogItems = pages.sumOf { it.itemCount },
            catalogErrors = pages.count { it.failed },
        )
    }

    private suspend fun inspectCatalog(
        addon: Addon,
        catalog: CatalogDescriptor,
        args: Map<String, String>,
        league: SportsLeague,
        matcher: SportsEventMatcher.Prepared,
        replays: Boolean,
        browsingLeague: Boolean,
    ): CatalogMatches = coroutineScope {
        try {
            val response = withTimeoutOrNull(10_000) {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    extraArgs = args,
                ).first { it !is NetworkResult.Loading }
            }
            if (response !is NetworkResult.Success) return@coroutineScope CatalogMatches(failed = true)
            val scoped = SportsAddonCatalogs.isScopedCatalog(catalog, league)
            val useRecordingMetadata = replays && SportsRecordingClassifier.appliesTo(league)
            val found = mutableListOf<SportsCatalogMatch>()
            val browse = mutableListOf<SportsCatalogMatch>()
            for (item in response.data.items) {
                ensureActive()
                if (!SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)) continue
                val score = matcher.score(item.name)
                if (useRecordingMetadata && !SportsRecordingClassifier.accepts(addon.id, catalog, item, league, score)) continue
                val kind = if (replays) SportsRecordingClassifier.kind(addon.id, catalog, item) else SportsContentKind.EVENT
                val candidate = SportsCatalogMatch(addon, catalog.id, item.apiType, item.id, item.name, score, kind)
                when {
                    score > 0 || browsingLeague && (useRecordingMetadata || scoped || channelCatalog(catalog, item.name)) -> found += candidate
                    !browsingLeague && scoped -> browse += candidate
                }
            }
            CatalogMatches(found, browse, response.data.items.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CatalogMatches(failed = true)
        }
    }

    private fun channelCatalog(catalog: CatalogDescriptor, title: String): Boolean =
        Regex("""(?i)channel|live.?tv|nfl.?network|red.?zone|espn|sports.?tv""")
            .containsMatchIn(catalog.id + " " + catalog.name + " " + title)

    private fun catalogArgs(catalog: CatalogDescriptor): Map<String, String>? {
        val required = catalog.extra.filter { it.isRequired }
        if (required.any { it.name !in setOf("genre", "skip", "search") }) return null
        val genre = required.firstOrNull { it.name == "genre" }
        val genreValue = genre?.options?.firstOrNull { it.equals("all", ignoreCase = true) }
        if (genre != null && genreValue == null) return null
        return buildMap {
            genreValue?.let { put("genre", it) }
            if (required.any { it.name == "skip" }) put("skip", "0")
        }
    }

    /** Search-only sports catalogs need real game and league queries. */
    private fun searchQueries(
        catalog: CatalogDescriptor,
        league: SportsLeague,
        events: List<SportsEvent>,
    ): List<String?> {
        if (catalog.extra.none { it.isRequired && it.name == "search" }) return listOf(null)
        val leagueTerms = when (league) {
            SportsLeague.NFL -> listOf("NFL", "NFL Network", "NFL RedZone")
            SportsLeague.COLLEGE_FOOTBALL -> listOf("College Football", "NCAA Football", "Wisconsin Badgers")
            SportsLeague.NBA -> listOf("NBA")
            SportsLeague.MLB -> listOf("MLB")
            SportsLeague.NHL -> listOf("NHL")
            SportsLeague.UFC -> listOf("UFC")
            else -> listOf(league.label)
        }
        return (events.take(3).flatMap { event ->
            listOf(event.title) + event.competitors.map { it.name }
        } + leagueTerms).map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().take(8)
    }

    private fun SportsCatalogMatch.identity() = Triple(addon.baseUrl, contentType, streamId)
}

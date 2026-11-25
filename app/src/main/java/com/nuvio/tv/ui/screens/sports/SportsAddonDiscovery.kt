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
    ): DiscoveryResult {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        // Keyed by game IDs (not scores), so live score refreshes and re-opening
        // the same game reuse the last search instead of hitting every add-on again.
        val key = listOf(
            league.name, replays.toString(), browsingLeague.toString(),
            events.map { it.id }.sorted().joinToString(","),
            addons.map { it.baseUrl }.sorted().joinToString(","),
        ).joinToString("|")
        cachedResult(key)?.let { return it }
        val result = discoverUncached(addons, league, events, replays, browsingLeague)
        // Don't keep a result where every add-on failed (e.g. network blip).
        if (result.catalogsChecked == 0 || result.catalogErrors < result.catalogsChecked) {
            storeResult(key, result)
        }
        return result
    }

    private suspend fun discoverUncached(
        addons: List<Addon>,
        league: SportsLeague,
        events: List<SportsEvent>,
        replays: Boolean,
        browsingLeague: Boolean,
    ): DiscoveryResult = withContext(Dispatchers.Default) {
        val matcher = SportsEventMatcher.Prepared(events)
        val limit = Semaphore(8)
        val requests = addons.flatMap { addon ->
            SportsAddonCatalogs.routes(addon, league, replays).mapNotNull { route ->
                val catalog = addon.catalogs.firstOrNull { it.id == route.id && it.apiType == route.type }
                    ?: return@mapNotNull null
                val args = catalogArgs(catalog) ?: return@mapNotNull null
                async {
                    val pages = searchQueries(catalog, league, events).map { query ->
                        async {
                            limit.withPermit {
                                inspectCatalog(addon, catalog,
                                    if (query == null) args else args + ("search" to query),
                                    league, matcher, replays, browsingLeague)
                            }
                        }
                    }.awaitAll()
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

    /**
     * Dedicated continuous NFL channel discovery, matching the Mobile 0.9.1 flow.
     * This is independent of ESPN fixtures and only returns stream-capable IDs
     * supplied by enabled add-on manifests.
     */
    /** Last NFL channel list, shown instantly while a fresh search isn't needed. */
    fun cachedNflChannels(): List<SportsCatalogMatch>? =
        nflChannelCache?.takeIf { System.currentTimeMillis() - it.first < CACHE_TTL_MILLIS }?.second

    suspend fun findNflChannels(): List<SportsCatalogMatch> {
        cachedNflChannels()?.let { return it }
        val found = findNflChannelsUncached()
        if (found.isNotEmpty()) nflChannelCache = System.currentTimeMillis() to found
        return found
    }

    private suspend fun findNflChannelsUncached(): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val addons = addonRepository.getInstalledAddons().first().filter { it.enabled }
        val limit = Semaphore(8)
        val requests = addons.flatMap { addon ->
            addon.catalogs.filter { catalog ->
                val label = (catalog.id + " " + catalog.name + " " + addon.id + " " + addon.displayName)
                catalog.apiType in setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv") &&
                    !Regex("replays?|highlights?|recaps?", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                    Regex("nfl|sport|live|channel|tv|cncbridge|cncverse|plavio", RegexOption.IGNORE_CASE)
                        .containsMatchIn(label)
            }.take(10).mapNotNull { catalog ->
                val args = catalogArgs(catalog) ?: return@mapNotNull null
                async {
                    val queries: List<String?> =
                        if (catalog.extra.any { it.isRequired && it.name == "search" })
                            listOf("NFL", "NFL Network", "NFL RedZone", "RedZone", "Football")
                        else listOf(null)
                    queries.map { query -> async {
                        limit.withPermit {
                            try {
                                val response = withTimeoutOrNull(10_000) {
                                    catalogRepository.getCatalog(
                                        addonBaseUrl = addon.baseUrl,
                                        addonId = addon.id,
                                        addonName = addon.displayName,
                                        catalogId = catalog.id,
                                        catalogName = catalog.name,
                                        type = catalog.apiType,
                                        extraArgs = if (query == null) args else args + ("search" to query),
                                    ).first { it !is NetworkResult.Loading }
                                }
                                if (response !is NetworkResult.Success) emptyList()
                                else response.data.items.mapNotNull { item ->
                                    ensureActive()
                                    if (nflChannelLabel(item.name) == null ||
                                        !SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)
                                    ) null else SportsCatalogMatch(
                                        addon, catalog.id, item.apiType, item.id, item.name,
                                        100, SportsContentKind.EVENT
                                    )
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    } }.awaitAll().flatten()
                }
            }
        }
        requests.awaitAll().flatten().distinctBy { it.identity() }
            .sortedWith(compareBy({ if (nflChannelLabel(it.title) == "NFL RedZone") 0 else 1 }, { it.addon.displayName }))
            .take(20)
    }

    private fun nflChannelLabel(title: String): String? {
        if (Regex("replay|highlight|recap|condensed", RegexOption.IGNORE_CASE).containsMatchIn(title)) return null
        val normalized = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            "nflredzone" in normalized || "redzonenfl" in normalized -> "NFL RedZone"
            "nflnetwork" in normalized || "nflchannel" in normalized -> "NFL Network"
            else -> null
        }
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

    companion object {
        /** Results stay fresh long enough to cover open stream → back → pick again. */
        private const val CACHE_TTL_MILLIS = 5 * 60_000L
        private const val MAX_CACHED_RESULTS = 40
        private val resultCache = LinkedHashMap<String, Pair<Long, DiscoveryResult>>()
        @Volatile private var nflChannelCache: Pair<Long, List<SportsCatalogMatch>>? = null

        private fun cachedResult(key: String): DiscoveryResult? = synchronized(resultCache) {
            val entry = resultCache[key] ?: return null
            if (System.currentTimeMillis() - entry.first > CACHE_TTL_MILLIS) {
                resultCache.remove(key)
                null
            } else entry.second
        }

        private fun storeResult(key: String, result: DiscoveryResult) = synchronized(resultCache) {
            resultCache.remove(key)
            resultCache[key] = System.currentTimeMillis() to result
            while (resultCache.size > MAX_CACHED_RESULTS) {
                resultCache.remove(resultCache.keys.first())
            }
        }
    }
}

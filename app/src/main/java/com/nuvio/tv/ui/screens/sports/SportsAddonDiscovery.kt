package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        val useRecordingMetadata = replays && SportsRecordingClassifier.appliesTo(league)
        // Live channels (CNCBridge etc.) carrying these games: their items are channel names, not game titles.
        val channelTerms = if (replays) emptySet() else SportsChannelMatcher.terms(league, events)
        val channelQueries = if (replays) emptyList() else SportsChannelMatcher.searchQueries(league, events)
        val deepQueries = deepSearchQueries(league, events)
        val requests = addons.flatMap { addon ->
            val listed = SportsAddonCatalogs.isListedProvider(addon)
            SportsAddonCatalogs.routes(addon, league, replays).mapNotNull { catalog ->
                val required = catalog.extra.filter { it.isRequired }
                if (required.any { it.name !in setOf("search", "genre", "skip") }) return@mapNotNull null
                val genres = SportsAddonCatalogs.genreValues(catalog, league)
                if (genres.isEmpty()) return@mapNotNull null
                val searchRequired = required.any { it.name == "search" }
                val searchTerms: List<String?> = if (searchRequired) {
                    // Unlisted live TV add-ons are only asked for the networks, not every team.
                    if (listed) (deepQueries + channelQueries).distinct() else channelQueries
                } else listOf(null)
                if (searchTerms.isEmpty()) return@mapNotNull null
                val skipRequired = required.any { it.name == "skip" }
                async {
                    val firstPages = genres.flatMap { genre -> searchTerms.map { genre to it } }.map { (genre, term) ->
                        async {
                            limit.withPermit {
                                fetchItems(addon, catalog, catalogArgs(genre, term, if (skipRequired) 0 else null))
                            }
                        }
                    }.awaitAll()
                    val loaded = firstPages.filterNotNull()
                    // Long live lists (hundreds of events) page with "skip": read a few more
                    // pages so games further down the list aren't missed.
                    val morePages = if (!searchRequired && catalog.extra.any { it.name == "skip" } &&
                        loaded.size == 1 && loaded.first().size >= 20
                    ) {
                        buildList {
                            var skip = loaded.first().size
                            repeat(3) {
                                val page = limit.withPermit {
                                    fetchItems(addon, catalog, catalogArgs(genres.first(), null, skip))
                                } ?: return@buildList
                                add(page)
                                if (page.size < 20) return@buildList
                                skip += page.size
                            }
                        }
                    } else emptyList()
                    val items = (loaded + morePages).flatten().distinctBy { it.apiType to it.id }
                    // Boxing/MMA/UFC "Events" tab: fight-night lists (boxing included) aren't on ESPN's UFC schedule.
                    val unscoredAllowed = if (league.scoreboardPath == null || browsingLeague && !replays && league == SportsLeague.UFC) {
                        SportsAddonCatalogs.isScopedCatalog(catalog, league)
                    } else {
                        (replays && isLeagueCatalog(catalog.id, league)) ||
                            (!replays && SportsAddonCatalogs.isCncBridgeScopedCatalog(addon, catalog, league))
                    }
                    val scoped = SportsAddonCatalogs.isScopedCatalog(catalog, league)
                    val found = mutableListOf<SportsCatalogMatch>()
                    val browse = mutableListOf<SportsCatalogMatch>()
                    for (item in items) {
                        ensureActive()
                        if (!SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)) continue
                        // Replays: drop recordings labelled for another sport (MLB "Kansas City" in NFL).
                        if (replays && !useRecordingMetadata && SportsAddonCatalogs.namesOtherSport(
                                (item.genres + item.id.replace('_', ' ') + item.name +
                                    catalog.id.replace('_', ' ') + catalog.name).joinToString(" "),
                                league,
                            )
                        ) continue
                        val gameScore = matcher.score(item.name)
                        // A channel showing the game ranks after real game matches.
                        val score = if (gameScore == 0 && SportsChannelMatcher.matches(item.name, channelTerms)) 1 else gameScore
                        val kind = if (replays) SportsRecordingClassifier.kind(addon.id, catalog, item) else SportsContentKind.EVENT
                        val candidate = SportsCatalogMatch(addon, catalog.id, item.apiType, item.id, item.name, score, kind)
                        if (useRecordingMetadata) {
                            if (SportsRecordingClassifier.accepts(addon.id, catalog, item, league, score)) found += candidate
                        } else if (score > 0 || unscoredAllowed) {
                            found += candidate
                        } else if (!browsingLeague && scoped) {
                            browse += candidate
                        }
                    }
                    CatalogMatches(
                        matches = found,
                        browse = browse,
                        itemCount = items.size,
                        failed = firstPages.isNotEmpty() && loaded.isEmpty(),
                    )
                }
            }
        }
        val pages = requests.awaitAll()
        // Premium add-ons first (Sports Streams, Flix-Streams, StremVerse, Nebula), best match first within each.
        val matches = pages.flatMap { it.matches }
            .sortedWith(compareBy({ it.priority() }, { -it.score }))
            .distinctBy { it.identity() }
        DiscoveryResult(
            matches = if (useRecordingMetadata) matches.groupBy { it.kind }.values.flatMap { it.take(40) } else matches.take(40),
            browse = pages.flatMap { it.browse }.sortedBy { it.priority() }.distinctBy { it.identity() }.take(20),
            catalogsChecked = pages.size,
            catalogItems = pages.sumOf { it.itemCount },
            catalogErrors = pages.count { it.failed },
        )
    }

    /** One catalog page, or null when the add-on failed or timed out. */
    private suspend fun fetchItems(
        addon: Addon,
        catalog: CatalogDescriptor,
        args: Map<String, String>,
    ): List<MetaPreview>? = try {
        val response = withTimeoutOrNull(15_000) {
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
        (response as? NetworkResult.Success)?.data?.items
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun catalogArgs(genre: String?, search: String?, skip: Int?): Map<String, String> = buildMap {
        genre?.let { put("genre", it) }
        search?.let { put("search", it) }
        skip?.let { put("skip", it.toString()) }
    }

    /**
     * Broader provider searches: a game may be indexed by its matchup, one team,
     * an abbreviated team name, or its competition rather than ESPN's title.
     * Returned items still require stream support and league/event filtering.
     */
    internal fun deepSearchQueries(league: SportsLeague, events: List<SportsEvent>): List<String> {
        val competition = when (league) {
            SportsLeague.COLLEGE_FOOTBALL -> listOf("College Football", "NCAA Football")
            SportsLeague.COLLEGE_BASKETBALL -> listOf("NCAA Basketball", "College Basketball", "Women's College Basketball")
            SportsLeague.COLLEGE_HOCKEY -> listOf("College Hockey", "NCAA Hockey")
            SportsLeague.COLLEGE_BASEBALL -> listOf("College Baseball", "NCAA Baseball")
            SportsLeague.COLLEGE_VOLLEYBALL -> listOf("College Volleyball", "NCAA Volleyball")
            SportsLeague.TENNIS -> listOf("Tennis", "ATP", "WTA")
            SportsLeague.PGA -> listOf("PGA Tour", "Golf")
            SportsLeague.MLS -> listOf("MLS")
            SportsLeague.WWE -> listOf("WWE", "Raw", "SmackDown")
            SportsLeague.NFL -> listOf("NFL")
            SportsLeague.NBA -> listOf("NBA")
            SportsLeague.MLB -> listOf("MLB")
            SportsLeague.NHL -> listOf("NHL")
            SportsLeague.UFC -> listOf("UFC", "Boxing", "MMA")
            else -> listOf(league.label)
        }
        val liveFirst = events.sortedBy {
            when (it.status) {
                SportsEventStatus.LIVE -> 0
                SportsEventStatus.SCHEDULED -> 1
                else -> 2
            }
        }
        val matches = liveFirst.flatMap { event ->
            listOf(event.title, event.catalogMatchTitle.orEmpty()) +
                event.competitors.flatMap { listOf(it.name, it.abbreviation.orEmpty()) }
        }
        return (matches + competition).map { it.trim() }
            .filter { it.length >= 3 }.distinct().take(18)
    }

    private fun isLeagueCatalog(id: String, league: SportsLeague): Boolean = when (league) {
        SportsLeague.NBA -> id.endsWith("_basketball")
        SportsLeague.MLB -> id.endsWith("_baseball")
        SportsLeague.NHL -> id.endsWith("_hockey")
        else -> false
    }

    private fun SportsCatalogMatch.priority() = SportsAddonPriority.rank(addon.displayName, addon.name, addon.id)

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
                val label = "${catalog.id} ${catalog.name}"
                catalog.apiType in setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv") &&
                    !Regex("replays?|highlights?|recaps?", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                    (Regex("nfl|sport|live|channel|tv", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
                        Regex("sport|nfl|live|cncbridge|cncverse|ustv|usatv|plavio|flix", RegexOption.IGNORE_CASE)
                            .containsMatchIn(addon.id + " " + addon.name))
            }.take(10).mapNotNull { catalog ->
                val required = catalog.extra.filter { it.isRequired }
                if (required.any { it.name !in setOf("search", "genre", "skip") }) return@mapNotNull null
                val genres = SportsAddonCatalogs.genreValues(catalog, SportsLeague.NFL)
                if (genres.isEmpty()) return@mapNotNull null
                val queries: List<String?> = if (required.any { it.name == "search" }) {
                    listOf("NFL", "NFL Network", "NFL RedZone", "RedZone", "Football")
                } else listOf(null)
                val skip = if (required.any { it.name == "skip" }) 0 else null
                async {
                    genres.flatMap { genre -> queries.map { genre to it } }.map { (genre, query) ->
                        async {
                            limit.withPermit { fetchItems(addon, catalog, catalogArgs(genre, query, skip)) }.orEmpty()
                                .mapNotNull { item ->
                                    if (nflChannelLabel(item.name) == null ||
                                        !SportsAddonCatalogs.supportsStream(addon, item.apiType, item.id)
                                    ) null else SportsCatalogMatch(
                                        addon, catalog.id, item.apiType, item.id, item.name,
                                        100, SportsContentKind.EVENT
                                    )
                                }
                        }
                    }.awaitAll().flatten()
                }
            }
        }
        requests.awaitAll().flatten().distinctBy { it.identity() }
            .sortedWith(compareBy(
                { if (nflChannelLabel(it.title) == "NFL RedZone") 0 else 1 },
                { it.priority() },
                { it.addon.displayName },
            ))
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

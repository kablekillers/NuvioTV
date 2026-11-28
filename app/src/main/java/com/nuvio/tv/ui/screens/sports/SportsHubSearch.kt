package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Sports Hub search (same as Kable Killers Mobile): "packers", "brewers",
 * "espn2", "ufc"… across ESPN games, US channels, live events and every
 * sports / live TV add-on list that can search.
 */
class SportsHubSearch @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
) {
    /** Asks every add-on list that supports searching (sports / live TV types only, no movies). */
    suspend fun addons(query: String): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val jobs = addonRepository.getInstalledAddons().first().filter { it.enabled }.flatMap { addon ->
            addon.catalogs.filter { catalog ->
                catalog.apiType in sportsTypes &&
                    catalog.extra.any { it.name == "search" } &&
                    catalog.extra.none { it.isRequired && it.name !in setOf("search", "skip") } &&
                    addon.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.apiType in it.types) }
            }.take(8).map { catalog ->
                async {
                    val skipRequired = catalog.extra.any { it.name == "skip" && it.isRequired }
                    val items = requests.withPermit {
                        withTimeoutOrNull(12_000) {
                            try {
                                val response = catalogRepository.getCatalog(
                                    addonBaseUrl = addon.baseUrl,
                                    addonId = addon.id,
                                    addonName = addon.displayName,
                                    catalogId = catalog.id,
                                    catalogName = catalog.name,
                                    type = catalog.apiType,
                                    extraArgs = buildMap {
                                        put("search", query)
                                        if (skipRequired) put("skip", "0")
                                    },
                                ).first { it !is NetworkResult.Loading }
                                (response as? NetworkResult.Success)?.data?.items
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                    } ?: return@async emptyList()
                    items.filter { SportsAddonCatalogs.supportsStream(addon, it.apiType, it.id) }.map {
                        SportsCatalogMatch(
                            addon, catalog.id, it.apiType, it.id, it.name,
                            if (matches(query, it.name)) 2 else 1, SportsContentKind.EVENT
                        )
                    }
                }
            }
        }
        jobs.awaitAll().flatten()
            .distinctBy { Triple(it.addon.baseUrl, it.contentType, it.streamId) }
            // Premium add-ons first; within each, titles that contain what was typed first.
            .sortedWith(compareBy({ SportsAddonPriority.rank(it.addon.displayName, it.addon.name, it.addon.id) }, { -it.score }))
            .take(80)
    }

    companion object {
        private val sportsTypes = setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv", "events", "event")
        private val requests = Semaphore(8)

        private fun words(text: String): List<String> =
            text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

        /** Every word typed appears somewhere in the text ("green bay", "espn 2" also finds "ESPN2"). */
        fun matches(query: String, text: String): Boolean {
            val q = words(query)
            if (q.isEmpty()) return false
            val hay = words(text)
            val compact = hay.joinToString("")
            return q.all { w -> hay.any { it.startsWith(w) } } || compact.contains(q.joinToString(""))
        }

        fun games(query: String, events: List<SportsEvent>): List<SportsEvent> =
            events.distinctBy { it.league to it.id }
                .filter { event ->
                    matches(
                        query,
                        (listOf(event.title, event.league.label, event.catalogMatchTitle.orEmpty()) +
                            event.competitors.flatMap { listOf(it.name, it.abbreviation.orEmpty()) } +
                            event.broadcasts).joinToString(" "),
                    )
                }
                .sortedBy {
                    when (it.status) {
                        SportsEventStatus.LIVE -> 0
                        SportsEventStatus.SCHEDULED -> 1
                        else -> 2
                    }
                }

        fun channels(query: String, channels: List<SportsTvChannel>): List<SportsTvChannel> =
            channels.filter { channel -> matches(query, channel.name) || channel.sources.any { matches(query, it.title) } }

        fun events(query: String, events: List<SportsLiveEvent>): List<SportsLiveEvent> =
            events.filter { event -> matches(query, event.title + " " + event.sport) || event.sources.any { matches(query, it.title) } }
    }
}

package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.data.remote.dto.MetaPreviewDto
import com.nuvio.tv.domain.model.Addon
import java.text.Normalizer
import java.util.Locale

/**
 * An add-on catalog entry is the source of truth for the playable ID.
 * Never substitute an ESPN event ID into an add-on stream endpoint.
 */
internal data class SportsCatalogMatch(
    val addon: Addon,
    val catalogId: String,
    val contentType: String,
    val streamId: String,
    val title: String,
    val score: Int
)

internal object SportsEventMatcher {
    private val noise = setOf("fc", "cf", "the", "vs", "v", "at", "live", "stream", "hd", "4k", "watch")

    private fun tokens(value: String): Set<String> =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .filter { it.length > 1 && it !in noise }
            .toSet()

    /**
     * Require both teams for ordinary two-sided games. One matching team alone
     * is unsafe: multiple simultaneous games can share a team or league name.
     * UFC cards without two competitors require a full event-title overlap.
     */
    fun score(event: SportsEvent, catalogTitle: String): Int {
        val titleTokens = tokens(catalogTitle)
        if (titleTokens.isEmpty()) return 0
        val sides = event.competitors.take(2)
        if (sides.size == 2) {
            val first = tokens(sides[0].name)
            val second = tokens(sides[1].name)
            if (first.isEmpty() || second.isEmpty()) return 0
            val a = first.intersect(titleTokens).size
            val b = second.intersect(titleTokens).size
            if (a == 0 || b == 0) return 0
            return 50 + (a * 10) + (b * 10)
        }
        val eventTokens = tokens(event.title)
        if (eventTokens.size < 2) return 0
        val overlap = eventTokens.intersect(titleTokens).size
        return if (overlap >= 2 && overlap * 3 >= eventTokens.size * 2) 40 + overlap * 10 else 0
    }

    fun matches(
        event: SportsEvent,
        addon: Addon,
        route: SportsAddonCatalogs.CatalogRoute,
        entries: List<MetaPreviewDto>
    ): List<SportsCatalogMatch> {
        if (!addon.enabled) return emptyList()
        return entries.mapNotNull { entry ->
            val id = entry.id?.trim().orEmpty()
            val title = entry.name?.trim().orEmpty()
            val type = entry.type?.takeIf { it.isNotBlank() } ?: route.type
            if (title.isEmpty() || type != route.type ||
                !SportsAddonCatalogs.supportsStream(addon, type, id)
            ) return@mapNotNull null
            val confidence = score(event, title)
            if (confidence == 0) null else SportsCatalogMatch(addon, route.id, type, id, title, confidence)
        }.sortedByDescending { it.score }
    }
}

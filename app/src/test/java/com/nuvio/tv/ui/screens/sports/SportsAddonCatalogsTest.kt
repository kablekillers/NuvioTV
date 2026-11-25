package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAddonCatalogsTest {
    @Test
    fun discoversEventCatalogsForTheAdditionalProviders() {
        val providers = listOf(
            addon("org.nebulastreams.sports", "Nebula Sports", catalog("events", "sports")),
            addon("example.pencari", "Pencari Sports", catalog("live", "tv")),
            addon("com.penguplay", "PenguPlay", catalog("live-tv", "channel")),
            addon("com.sleeyax.dlive-addon", "Tennis Channel", catalog("dlive_catalog", "tv", "Tennis")),
        )

        providers.forEach { addon ->
            val route = SportsAddonCatalogs.routes(addon, SportsLeague.NBA).single()
            assertEquals(addon.catalogs.single().apiType, route.type)
            assertEquals(addon.catalogs.single().id, route.id)
            assertTrue(SportsAddonCatalogs.routes(addon.copy(enabled = false), SportsLeague.NBA).isEmpty())
        }
    }

    @Test
    fun separatesLiveAndReplayCatalogsAndExcludesMovies() {
        val addon = addon(
            "org.nebulastreams.sports", "Nebula Sports",
            catalog("live", "sports"), catalog("nba-replays", "sports"), catalog("sports-films", "movie"),
        )

        assertEquals(listOf("live"), SportsAddonCatalogs.routes(addon, SportsLeague.NBA).map { it.id })
        assertEquals(
            listOf("nba-replays"),
            SportsAddonCatalogs.routes(addon, SportsLeague.NBA, replays = true).map { it.id },
        )
    }

    @Test
    fun preservesCurrentLeagueCatalogs() {
        val addon = addon(
            SportsAddonCatalogs.SPORTS_STREAMS, "Sports Streams",
            catalog("sports_basketball", "sport"), catalog("sports_live", "sport"), catalog("sports_hockey", "sport"),
        )

        assertEquals(
            listOf("sports_basketball", "sports_live"),
            SportsAddonCatalogs.routes(addon, SportsLeague.NBA).map { it.id },
        )
    }

    @Test
    fun newSportsKeepSpecificCatalogsAndUseGeneralCatalogsOnlyWithSchedules() {
        val addon = addon(
            "community.sports.fly", "Sports Streams",
            catalog("sports_live", "sport"), catalog("sports_basketball", "sport"),
            catalog("sports_tennis", "sport"), catalog("sports_golf", "sport"),
            catalog("sports_motor_sports", "sport"), catalog("sports_darts", "sport"),
        )
        assertEquals(listOf("sports_live", "sports_basketball"), SportsAddonCatalogs.routes(addon, SportsLeague.COLLEGE_BASKETBALL).map { it.id })
        assertEquals(listOf("sports_live", "sports_tennis"), SportsAddonCatalogs.routes(addon, SportsLeague.TENNIS).map { it.id })
        assertEquals(listOf("sports_live", "sports_golf"), SportsAddonCatalogs.routes(addon, SportsLeague.PGA).map { it.id })
    }

    private fun catalog(id: String, type: String, name: String = id) =
        CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = name)

    private fun addon(id: String, name: String, vararg catalogs: CatalogDescriptor) = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://example.com",
        catalogs = catalogs.toList(),
        types = emptyList(),
        resources = listOf(AddonResource("stream", catalogs.map { it.apiType }, null))
    )
}

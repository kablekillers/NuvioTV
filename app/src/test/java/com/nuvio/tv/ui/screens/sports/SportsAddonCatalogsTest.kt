package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAddonCatalogsTest {
    @Test
    fun findsInstalledSportsCatalogsWithoutAnEspnEvent() {
        val nebula = addon("org.nebulastreams.sports", "Nebula Sports", catalog("events", "sports"))
        val pencari = addon("example.pencari", "Pencari Sports", catalog("live", "tv"))
        val tennis = addon("com.sleeyax.dlive-addon", "Tennis Channel", catalog("dlive_catalog", "tv", "Tennis"))

        listOf(nebula, pencari, tennis).forEach { addon ->
            assertEquals(addon.catalogs, SportsAddonCatalogs.browseCatalogs(addon))
            assertTrue(SportsAddonCatalogs.browseCatalogs(addon.copy(enabled = false)).isEmpty())
        }
        assertEquals("sports", SportsAddonCatalogs.routes(nebula, SportsLeague.NBA).single().type)
    }

    @Test
    fun usesConfiguredSportsGenreAndOmitsMovieCatalogs() {
        val live = catalog("live-tv", "tv").copy(
            extra = listOf(CatalogExtra("genre", isRequired = true, options = listOf("All", "Sports Channels")))
        )
        val addon = addon("com.penguplay", "PenguPlay", live, catalog("movies", "movie"))

        assertEquals(listOf(live), SportsAddonCatalogs.browseCatalogs(addon))
        assertEquals("Sports Channels", SportsAddonCatalogs.genre(live))
    }

    @Test
    fun pencariSportGenreIsBrowsableOutsideGameDetails() {
        val popular = catalog("top", "movie", "Popular").copy(
            extra = listOf(CatalogExtra("genre", options = listOf("Action", "Sport")))
        )
        val addon = addon("org.pencarimovie.addon.server", "PencariMovie", popular)

        assertEquals(listOf(popular), SportsAddonCatalogs.browseCatalogs(addon))
        assertEquals("Sport", SportsAddonCatalogs.genre(popular))
        assertTrue(SportsAddonCatalogs.routes(addon, SportsLeague.NBA).isEmpty())
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

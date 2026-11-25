package com.nuvio.tv.ui.screens.sports

import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.MetaPreview
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class SportsRecordingClassifierTest {
    private val recaps = CatalogDescriptor(ContentType.UNKNOWN, "sport", "sports_recaps", "Sport Recaps")
    private val hockey = CatalogDescriptor(ContentType.UNKNOWN, "tv", "nuvio_sports_replays_hockey", "Hockey Replays")

    @Test
    fun durationPlacesFullRecordingsInReplaysEvenInTheRecapsCatalog() {
        val replay = item("recap:streamed_hockey_vegas_dallas", "Vegas Golden Knights vs Dallas Stars",
            genres = listOf("hockey", "Recap"), description = "Recorded 20 Nov 2026\nDuration: 4h 12m")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, replay, SportsLeague.NHL, 0))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, replay))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, replay))
    }

    @Test
    fun shortHockeyClipsShowAsHighlightsEvenInAReplayCatalog() {
        val clip = item("h1", "Vegas vs Dallas", runtime = "00:09:48")
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, clip))
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, clip.copy(runtime = null)))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, clip.copy(runtime = null)))
    }

    @Test
    fun baseballCannotEnterCollegeTabsEvenWhenTeamWordsCoincide() {
        val collegeLeagues = listOf(SportsLeague.COLLEGE_FOOTBALL, SportsLeague.COLLEGE_BASKETBALL)
        for (league in collegeLeagues) {
            val mlb = item("recap:streamed_baseball_cincinnati_pittsburgh", "Cincinnati vs Pittsburgh")
            assertFalse(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, mlb, league, 90))
            assertFalse(SportsRecordingClassifier.accepts("mixed", recaps, mlb.copy(id = "untyped", genres = listOf("MLB")), league, 90))
        }
    }

    @Test
    fun collegeRecordingsFollowFootballAndTheCorrectBasketballDivision() {
        val football = item("recap:streamed_american_football_temple_rice", "Temple vs Rice", runtime = "4h 15m")
        val basketball = item("recap:streamed_basketball_usc_tarleton", "USC vs Tarleton State",
            genres = listOf("basketball", "NCAAW"), runtime = "3h 46m")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, football, SportsLeague.COLLEGE_FOOTBALL, 80))
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, basketball, SportsLeague.COLLEGE_BASKETBALL, 80))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, basketball))
    }

    @Test
    fun hockeyRecapsCannotEnterNbaWithMatchingCityNames() {
        val hockeyRecaps = listOf(
            item("recap:streamed_hockey_toronto_los_angeles", "Toronto Maple Leafs vs Los Angeles Kings",
                runtime = "4h 14m"),
            item("untyped", "Boston Bruins vs Los Angeles Kings", genres = listOf("hockey")),
        )
        assertTrue(SportsRecordingClassifier.appliesTo(SportsLeague.NBA))
        for (recording in hockeyRecaps) {
            assertFalse(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, recording, SportsLeague.NBA, 90))
            assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, recording, SportsLeague.NHL, 0))
        }
    }

    @Test
    fun nbaRecapsUseDurationAndRejectOtherBasketballLeagues() {
        val replay = item("recap:streamed_basketball_boston_los_angeles", "Boston Celtics vs Los Angeles Lakers Recap",
            genres = listOf("NBA"), runtime = "3h 14m")
        val addonId = SportsAddonCatalogs.SPORTS_STREAMS
        assertTrue(SportsRecordingClassifier.accepts(addonId, recaps, replay, SportsLeague.NBA, 0))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(addonId, recaps, replay))
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(addonId, recaps, replay.copy(runtime = "9m 23s")))
        for (otherLeague in listOf("NCAAM", "NCAAW", "WNBA")) {
            assertFalse(SportsRecordingClassifier.accepts(addonId, recaps, replay.copy(genres = listOf(otherLeague)), SportsLeague.NBA, 90))
        }
        val nuvioBasketball = CatalogDescriptor(ContentType.UNKNOWN, "tv", "nuvio_sports_replays_basketball", "Basketball Replays")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.NUVIO_LIVE, nuvioBasketball,
            replay.copy(genres = emptyList()), SportsLeague.NBA, 0))
    }

    @Test
    fun onlyFullSportsStreamsRecordingsGetTheReplayDisplayLabel() {
        val addonId = SportsAddonCatalogs.SPORTS_STREAMS
        val label = "Recap (CA EN: SPORTSNET EAST)"
        val subtitle = "Recorded · 4h 14m"
        assertEquals("Replay (CA EN: SPORTSNET EAST)", SportsRecordingClassifier.streamLabel(addonId, label, subtitle))
        assertEquals(label, SportsRecordingClassifier.streamLabel(addonId, label, "Recorded · 9m 23s"))
        assertEquals(label, SportsRecordingClassifier.streamLabel(addonId, label, null))
        assertEquals(label, SportsRecordingClassifier.streamLabel("addon.other", label, subtitle))
    }

    private fun item(id: String, name: String, genres: List<String> = emptyList(), runtime: String? = null, description: String? = null) =
        MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = description,
            releaseInfo = null, imdbRating = null, genres = genres, runtime = runtime,
        )
}

package com.nuvio.tv.ui.screens.sports

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsHubIntroTest {
    @Test fun okBackAndEveryDirectionSkipTheIntro() {
        listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        ).forEach { assertTrue("key $it", isSportsIntroSkipKey(it)) }
    }

    @Test fun volumeKeysStillAdjustVolume() {
        assertFalse(isSportsIntroSkipKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(isSportsIntroSkipKey(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test fun introUsesFixedResourceName() {
        assertEquals("sports_hub_intro", SPORTS_HUB_INTRO_RESOURCE)
    }

    @Test fun originalLeaguesKeepDistinctFallbackIcons() {
        val original = listOf(
            SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL, SportsLeague.NBA,
            SportsLeague.MLB, SportsLeague.NHL, SportsLeague.UFC,
        )
        assertEquals(original.size, original.map { it.glyph() }.toSet().size)
    }

    @Test fun expandedLeaguesUseTheirLeagueLogosAndSportFallbacks() {
        assertTrue(SportsLeague.NCAA_MEN.logoPath.orEmpty().endsWith("NCAASports_MensBasketball.png"))
        assertTrue(SportsLeague.NCAA_WOMEN.logoPath.orEmpty().endsWith("NCAASports_WomensBasketball.png"))
        assertTrue(SportsLeague.PGA.logoPath.orEmpty().endsWith("pgatour.png"))
        assertEquals(SportGlyph.BASKETBALL, SportsLeague.NCAA_WOMEN.glyph())
        assertEquals(SportGlyph.GOLF, SportsLeague.PGA.glyph())
        assertEquals(SportGlyph.TENNIS, SportsLeague.WTA.glyph())
        assertEquals(SportGlyph.RACING, SportsLeague.FORMULA_1.glyph())
    }
}

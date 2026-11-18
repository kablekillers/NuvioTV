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

    @Test fun everyLeagueHasItsOwnIcon() {
        assertEquals(SportsLeague.entries.size, SportsLeague.entries.map { it.glyph() }.toSet().size)
    }
}

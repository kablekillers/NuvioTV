package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicContentTypeTest {
    @Test
    fun `music remains music in addon requests`() {
        assertEquals(ContentType.MUSIC, ContentType.fromString("music"))
        assertEquals(ContentType.MUSIC, ContentType.fromString(" MUSIC "))
        assertEquals("music", ContentType.MUSIC.toApiString())
        assertEquals("music", ContentType.MUSIC.toApiString("movie"))
    }

    @Test
    fun `existing content types still map normally`() {
        assertEquals(ContentType.MOVIE, ContentType.fromString("movie"))
        assertEquals(ContentType.SERIES, ContentType.fromString("series"))
        assertEquals("channel", ContentType.CHANNEL.toApiString())
        assertEquals("tv", ContentType.TV.toApiString())
    }
}

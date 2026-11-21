package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicContentTypeTest {
    @Test
    fun `music type is recognized and round trips to addon API`() {
        assertEquals(ContentType.MUSIC, ContentType.fromString("music"))
        assertEquals(ContentType.MUSIC, ContentType.fromString(" MUSIC "))
        assertEquals("music", ContentType.MUSIC.toApiString())
        assertEquals("music", ContentType.MUSIC.toApiString("movie"))
    }

    @Test
    fun `unknown custom types still preserve caller supplied fallback`() {
        assertEquals(ContentType.UNKNOWN, ContentType.fromString("custom"))
        assertEquals("custom", ContentType.UNKNOWN.toApiString("custom"))
    }
}

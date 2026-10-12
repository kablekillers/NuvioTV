package com.nuvio.tv

import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpCacheCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun removesOldResponseWithoutMakingNetworkRequests() {
        withCachedResponse("max-age=31536000") { cache, client, server, request ->
            HttpCacheCleanup.removeOldResponses(cache, Long.MAX_VALUE)
            client.newCall(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()).execute().use {
                assertEquals(504, it.code)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun preservesRecentResponseAndOpenResponseBody() {
        withCachedResponse("max-age=31536000") { cache, client, server, request ->
            HttpCacheCleanup.removeOldResponses(cache, 0L)
            client.newCall(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()).execute().use {
                assertNotNull(it.cacheResponse)
                cache.evictAll()
                // Owner API eviction leaves an already-open playback/HTTP reader usable.
                assertEquals("cached content", it.body!!.string())
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun leavesRevalidationOnlyEntriesAloneWithoutContactingServer() {
        withCachedResponse("no-cache") { cache, _, server, _ ->
            HttpCacheCleanup.removeOldResponses(cache, Long.MAX_VALUE)
            assertTrue(cache.urls().hasNext())
            assertEquals(1, server.requestCount)
        }
    }

    private fun withCachedResponse(
        cacheControl: String,
        test: (Cache, OkHttpClient, MockWebServer, Request) -> Unit
    ) {
        MockWebServer().use { server ->
            server.start()
            Cache(temporary.newFolder(), 1024L * 1024).use { cache ->
                val client = OkHttpClient.Builder().cache(cache).build()
                server.enqueue(MockResponse().setHeader("Cache-Control", cacheControl).setBody("cached content"))
                val request = Request.Builder().url(server.url("/cached")).build()
                client.newCall(request).execute().use { assertEquals("cached content", it.body!!.string()) }
                cache.flush()
                test(cache, client, server, request)
            }
        }
    }
}

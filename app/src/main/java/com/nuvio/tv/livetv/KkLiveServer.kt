package com.nuvio.tv.livetv

import android.content.Context
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serves Kable Killers' add-on channels to Live TV, on this device only (same as Mobile):
 *  - /kk/playlist.m3u : every US channel the add-ons carry, as a playlist.
 *  - /kk/ch/<channel> : sends the player on to a working link for that channel.
 * Live TV's player resolves these channels itself before playing (so add-on headers are kept);
 * the redirect is for anything that opens the link directly.
 */
@Singleton
class KkLiveServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: KkLiveChannels,
    private val addonRepository: AddonRepository,
    // Lazy: Live TV's repository is only built when the playlist is first added or refreshed,
    // not at every app start.
    private val liveRepositoryLazy: dagger.Lazy<LiveTvRepository>,
    private val livePrefs: LiveTvPreferences,
) {
    private var server: Server? = null
    private val addMutex = kotlinx.coroutines.sync.Mutex()

    fun start() {
        if (server != null) return
        val file = File(context.filesDir, "livetv/kk_channels.json")
        channels.save = { text -> file.parentFile?.mkdirs(); file.writeText(text) }
        channels.load = { file.takeIf { it.exists() }?.readText() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // When the links are found (first scan, or a refresh), Live TV re-reads the playlist.
        channels.onScanned = {
            scope.launch {
                runCatching {
                    livePrefs.playlists.first().firstOrNull { it.url == KkLiveChannels.PLAYLIST_URL }
                        ?.let { liveRepositoryLazy.get().refreshPlaylist(it.id) }
                }
            }
        }
        server = runCatching { Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) } }.getOrNull()
        scope.launch { followAddons() }
        scope.launch { keepPlaylist() }
    }

    /**
     * Live TV must never sit on "No playlists yet": whenever this TV has no playlist at all (a new
     * profile on a fresh TV, a sign-in that arrived before the add-ons, or the playlist removed
     * with nothing else left), the Kable Killers playlist goes back in. It doesn't wait for the
     * add-ons: its channels fill in when they arrive ([followAddons] re-reads it then).
     */
    @OptIn(FlowPreview::class)
    private suspend fun keepPlaylist() {
        livePrefs.playlists
            .map { it.isEmpty() }
            .distinctUntilChanged()
            .debounce(3_000)
            .filter { it }
            .collect {
                runCatching {
                    addMutex.withLock {
                        if (livePrefs.playlists.first().isEmpty()) {
                            liveRepositoryLazy.get().addPlaylist(KkLiveChannels.PLAYLIST_NAME, KkLiveChannels.PLAYLIST_URL)
                            context.getSharedPreferences("kk_livetv", Context.MODE_PRIVATE)
                                .edit().putBoolean("playlist_added", true).apply()
                        }
                    }
                }
            }
    }

    /**
     * Whenever the profile's add-ons change: make sure the "Kable Killers" playlist is in Live TV
     * (added once; after that it's the user's to keep or remove) and download its channels again.
     */
    @OptIn(FlowPreview::class)
    private suspend fun followAddons() {
        val flags = context.getSharedPreferences("kk_livetv", Context.MODE_PRIVATE)
        addonRepository.getInstalledAddons()
            .map { channels.addonsKey(it) }
            .distinctUntilChanged()
            .filter { it.isNotEmpty() }
            .debounce(4_000)
            .collect { key ->
                runCatching {
                    val added = addMutex.withLock {
                        val none = livePrefs.playlists.first().none { it.url == KkLiveChannels.PLAYLIST_URL }
                        if (none && !flags.getBoolean("playlist_added", false)) {
                            liveRepositoryLazy.get().addPlaylist(KkLiveChannels.PLAYLIST_NAME, KkLiveChannels.PLAYLIST_URL)
                            flags.edit().putBoolean("playlist_added", true).putString("addons_key", key).apply()
                            true
                        } else false
                    }
                    val playlist = livePrefs.playlists.first().firstOrNull { it.url == KkLiveChannels.PLAYLIST_URL }
                    if (added) Unit
                    else if (playlist != null && flags.getString("addons_key", null) != key) {
                        // Only when the add-ons actually changed (not on every app start).
                        flags.edit().putString("addons_key", key).apply()
                        // Live TV re-reads the playlist when this scan finishes (onScanned).
                        channels.channels(force = true)
                    }
                }
            }
    }

    private inner class Server : NanoHTTPD("127.0.0.1", KkLiveChannels.PORT) {
        override fun serve(session: IHTTPSession): Response {
            val path = session.uri.orEmpty()
            return when {
                path == "/kk/playlist.m3u" -> {
                    val body = runBlocking {
                        withTimeoutOrNull(85_000) { runCatching { channels.playlist() }.getOrNull() }
                    }
                    if (body == null) {
                        // Live TV keeps the channels it had and tries again later.
                        newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Your add-ons' channels aren't ready yet")
                    } else {
                        newFixedLengthResponse(Response.Status.OK, "audio/x-mpegurl", body)
                    }
                }
                path.startsWith("/kk/ch/") -> {
                    val slug = path.removePrefix("/kk/ch/").trim('/')
                    val stream = runBlocking { withTimeoutOrNull(30_000) { channels.resolve(slug).firstOrNull() } }
                    if (stream == null) {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No working link for this channel right now")
                    } else {
                        newFixedLengthResponse(Response.Status.TEMPORARY_REDIRECT, MIME_PLAINTEXT, "").apply {
                            addHeader("Location", stream.url)
                        }
                    }
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
            }
        }
    }
}

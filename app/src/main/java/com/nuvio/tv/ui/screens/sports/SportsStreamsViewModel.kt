package com.nuvio.tv.ui.screens.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsStreamsViewModel @Inject constructor(
    private val discovery: SportsAddonDiscovery,
    private val liveTv: SportsLiveTv,
    private val hubSearch: SportsHubSearch,
    private val liveTvRepositoryLazy: dagger.Lazy<com.nuvio.tv.livetv.data.LiveTvRepository>,
) : ViewModel() {
    // Built only when US Channels is opened (not for every Sports Hub visit).
    private val liveTvRepository get() = liveTvRepositoryLazy.get()

    /** Live TV channels and guide listings, for "On now" in US Channels. */
    val liveChannels by lazy { liveTvRepository.channels }
    val guidePrograms by lazy { liveTvRepository.programs }

    /** What's on now for each US channel name (from the Live TV guide), by channel name. */
    fun nowOn(
        names: List<String>,
        channels: List<com.nuvio.tv.livetv.model.LiveChannel>,
        programs: Map<String, List<com.nuvio.tv.livetv.model.EpgProgram>>,
        nowMs: Long,
    ): Map<String, String> {
        if (names.isEmpty() || channels.isEmpty()) return emptyMap()
        val byKey = HashMap<String, com.nuvio.tv.livetv.model.LiveChannel>()
        channels.forEach { ch -> com.nuvio.tv.core.links.LinkMemory.channelKey(ch.name)?.let { byKey.putIfAbsent(it, ch) } }
        val out = HashMap<String, String>()
        val missing = ArrayList<String>()
        names.forEach { name ->
            val ch = com.nuvio.tv.core.links.LinkMemory.channelKey(name)?.let { byKey[it] } ?: return@forEach
            val list = programs[ch.key]
            if (list == null) missing += ch.key
            else list.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }?.let { out[name] = it.title }
        }
        if (missing.isNotEmpty()) liveTvRepository.loadChannelsNow(missing)
        return out
    }

    var matches by androidx.compose.runtime.mutableStateOf<List<SportsCatalogMatch>>(emptyList())
        private set
    var browse by androidx.compose.runtime.mutableStateOf<List<SportsCatalogMatch>>(emptyList())
        private set
    var diagnostics by androidx.compose.runtime.mutableStateOf("")
        private set
    var loading by androidx.compose.runtime.mutableStateOf(false)
        private set
    var error by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var loadedEventId: String? = null

    suspend fun browseLeague(league: SportsLeague, events: List<SportsEvent>, replays: Boolean): List<SportsCatalogMatch> =
        discovery.discoverLeague(league, events, replays)

    suspend fun findNflChannels(): List<SportsCatalogMatch> = discovery.findNflChannels()

    fun cachedNflChannels(): List<SportsCatalogMatch>? = discovery.cachedNflChannels()

    fun cachedUsChannels(): List<SportsTvChannel>? = liveTv.peekChannels()
    suspend fun usChannels(): List<SportsTvChannel> = liveTv.usChannels()
    fun cachedLiveEvents(): List<SportsLiveEvent>? = liveTv.peekEvents()
    suspend fun liveEvents(): List<SportsLiveEvent> = liveTv.liveEvents()
    suspend fun searchAddons(query: String): List<SportsCatalogMatch> = hubSearch.addons(query)

    fun lookup(event: SportsEvent) {
        // Coming back from the stream picker re-opens the same game: keep what we found.
        if (event.id == loadedEventId && error == null) return
        loadedEventId = event.id
        job?.cancel()
        matches = emptyList()
        browse = emptyList()
        diagnostics = ""
        error = null
        loading = true
        job = viewModelScope.launch {
            try {
                val isFinal = event.status == SportsEventStatus.FINAL || event.kind == SportsContentKind.REPLAY
                // Links appear as each add-on answers instead of after the slowest one.
                val showPartial: (List<SportsCatalogMatch>) -> Unit = { partial ->
                    viewModelScope.launch { if (loading && partial.size >= matches.size) matches = partial }
                }
                val result = if (isFinal) {
                    coroutineScope {
                        val recordings = async { discovery.discover(event, replays = true, onPartial = showPartial) }
                        val live = async { discovery.discover(event) }
                        recordings.await().takeIf { it.matches.isNotEmpty() } ?: live.await()
                    }
                } else {
                    discovery.discover(event, onPartial = showPartial)
                }
                matches = result.matches
                browse = result.browse
                diagnostics = "Checked ${result.catalogsChecked} catalogs • ${result.catalogItems} entries • ${result.catalogErrors} errors"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = "Could not load sports sources. Please try again."
            } finally {
                loading = false
            }
        }
    }

    private var prefetchJob: Job? = null

    /** Looks up links for live games in the background (cached), a few at a time. */
    fun prefetchLive(events: List<SportsEvent>) {
        prefetchJob?.cancel()
        if (events.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3_000)
            events.take(4).forEach { event ->
                runCatching { discovery.discover(event) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    fun clear() {
        job?.cancel()
        loadedEventId = null
        matches = emptyList()
        browse = emptyList()
        diagnostics = ""
        loading = false
        error = null
    }
}

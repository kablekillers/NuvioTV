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
) : ViewModel() {
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
            events.take(8).forEach { event ->
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

package com.nuvio.tv.ui.screens.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsStreamsViewModel @Inject constructor(
    private val discovery: SportsAddonDiscovery
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

    fun lookup(event: SportsEvent) {
        job?.cancel()
        matches = emptyList()
        browse = emptyList()
        diagnostics = ""
        error = null
        loading = true
        job = viewModelScope.launch {
            try {
                val result = discovery.discover(event, replays = event.kind == SportsContentKind.REPLAY)
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

    fun clear() {
        job?.cancel()
        matches = emptyList()
        browse = emptyList()
        diagnostics = ""
        loading = false
        error = null
    }
}

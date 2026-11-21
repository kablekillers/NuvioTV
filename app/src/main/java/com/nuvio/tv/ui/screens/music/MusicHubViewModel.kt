package com.nuvio.tv.ui.screens.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MusicCategory(val label: String) {
    SONGS("Songs & Artists"),
    ALBUMS("Albums & Concerts"),
    RADIO("Live Radio")
}

data class MusicResult(
    val id: String,
    val title: String,
    val poster: String?,
    val category: MusicCategory,
    val addonBaseUrl: String
)

@HiltViewModel
class MusicHubViewModel @Inject constructor(
    private val addons: AddonRepository,
    private val catalogs: CatalogRepository
) : ViewModel() {
    var results by mutableStateOf<List<MusicResult>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    private var searchJob: Job? = null

    fun search(query: String, category: MusicCategory) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            loading = true
            message = null
            results = emptyList()
            try {
                val installed = addons.getInstalledAddons().first()
                    .firstOrNull { it.enabled && it.id == "org.kablekillers.music" }
                if (installed == null) {
                    message = "Install and enable Kable Killers Music in Add-ons to use Music Hub."
                    return@launch
                }
                val ids = when (category) {
                    MusicCategory.SONGS -> listOf("kablekillers_music_search", "kk_audius", "kk_commons")
                    MusicCategory.ALBUMS -> listOf("kk_archive")
                    MusicCategory.RADIO -> listOf("kk_radio")
                }
                val collected = mutableListOf<MusicResult>()
                var failed = 0
                for (id in ids) {
                    val descriptor = installed.catalogs.firstOrNull { it.id == id } ?: continue
                    if (query.isBlank() && descriptor.extra.any { it.name == "search" && it.isRequired }) continue
                    try {
                        val response = catalogs.getCatalog(
                            addonBaseUrl = installed.baseUrl,
                            addonId = installed.id,
                            addonName = installed.displayName,
                            catalogId = descriptor.id,
                            catalogName = descriptor.name,
                            type = descriptor.apiType,
                            extraArgs = if (query.isBlank()) emptyMap() else mapOf("search" to query)
                        ).first { it !is NetworkResult.Loading }
                        if (response is NetworkResult.Success) {
                            collected += response.data.items.map { item: MetaPreview ->
                                MusicResult(item.id, item.name, item.poster, category, installed.baseUrl)
                            }
                        } else failed++
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        failed++
                    }
                }
                results = collected.distinctBy { it.id }
                if (results.isEmpty()) {
                    message = if (failed > 0) "Music sources are temporarily unavailable. Try again."
                    else if (query.isBlank()) "Search for an artist, song or album."
                    else "No matching recordings. Try another search."
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "Unable to load music right now."
            } finally {
                loading = false
            }
        }
    }
}

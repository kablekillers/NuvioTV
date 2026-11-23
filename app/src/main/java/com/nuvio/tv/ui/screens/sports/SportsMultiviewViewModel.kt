package com.nuvio.tv.ui.screens.sports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TvMultiviewChoice(
    val id: String,
    val label: String,
    val addon: String,
    val url: String,
)

/** Resolve catalog-owned IDs through all enabled add-ons, preserving each provider's link. */
@HiltViewModel
class SportsMultiviewViewModel @Inject constructor(
    private val streams: StreamRepository,
) : ViewModel() {
    var choices by mutableStateOf<List<TvMultiviewChoice>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun search(match: SportsCatalogMatch) {
        choices = emptyList()
        error = null
        loading = true
        viewModelScope.launch {
            try {
                streams.getStreamsFromAllAddons(
                    type = match.contentType,
                    videoId = match.streamId,
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            choices = result.data.flatMap { group ->
                                group.streams.mapNotNull { stream ->
                                    val url = stream.getStreamUrl()?.trim()
                                        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                                        ?: return@mapNotNull null
                                    TvMultiviewChoice(
                                        id = match.streamId + ":" + stream.addonName + ":" + url,
                                        label = stream.getDisplayName(),
                                        addon = stream.addonName,
                                        url = url,
                                    )
                                }
                            }.distinctBy { it.id }
                            loading = false
                        }
                        is NetworkResult.Error -> {
                            error = "Could not load streams from the selected add-ons."
                            loading = false
                        }
                        else -> Unit
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = "Source search failed. Try another game or provider."
            } finally {
                loading = false
            }
        }
    }
}

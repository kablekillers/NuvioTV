package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class CalendarEpisode(
    val showId: String,
    val showType: String,
    val showName: String,
    val poster: String?,
    val still: String?,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val date: LocalDate,
    val dayOffset: Long,
) {
    val code: String
        get() = when {
            season != null && episode != null -> "S${season}E$episode"
            episode != null -> "E$episode"
            else -> ""
        }
    val dayLabel: String
        get() = when (dayOffset) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        }
}

data class CalendarUiState(
    val loaded: Boolean = false,
    val upcoming: List<CalendarEpisode> = emptyList(),
    val recent: List<CalendarEpisode> = emptyList(),
)

/** Upcoming and recently aired episodes of the series in the Library. */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val metaRepository: MetaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val today = LocalDate.now()
        // The library may still be loading; wait briefly for it before giving up.
        val shows = runCatching {
            withTimeoutOrNull(6_000L) { libraryRepository.libraryItems.first { it.isNotEmpty() } }
                ?: libraryRepository.libraryItems.first()
        }.getOrDefault(emptyList())
            .filter { it.type.equals("series", true) || it.type.equals("tv", true) }
            .distinctBy { it.type to it.id }
            .take(MAX_SHOWS)
        val gate = Semaphore(4)
        val episodes = kotlinx.coroutines.coroutineScope {
            shows.map { show ->
                async {
                    gate.withPermit {
                        val result = withTimeoutOrNull(15_000L) {
                            metaRepository.getMetaFromAllAddons(type = show.type, id = show.id)
                                .first { it !is NetworkResult.Loading }
                        }
                        val meta = (result as? NetworkResult.Success)?.data ?: return@withPermit emptyList()
                        meta.videos.mapNotNull { video ->
                            if (video.season == null && video.episode == null) return@mapNotNull null
                            if (video.season == 0) return@mapNotNull null
                            val date = parseEpisodeReleaseLocalDate(video.released) ?: return@mapNotNull null
                            val offset = ChronoUnit.DAYS.between(today, date)
                            if (offset < -PAST_DAYS || offset > FUTURE_DAYS) return@mapNotNull null
                            CalendarEpisode(
                                showId = show.id,
                                showType = show.type,
                                showName = meta.name.ifBlank { show.name },
                                poster = meta.poster ?: show.poster,
                                still = video.thumbnail ?: meta.background ?: show.background,
                                season = video.season,
                                episode = video.episode,
                                title = video.title.takeIf { it.isNotBlank() },
                                date = date,
                                dayOffset = offset,
                            )
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        _state.value = CalendarUiState(
            loaded = true,
            upcoming = episodes.filter { it.dayOffset >= 0 }
                .sortedWith(compareBy<CalendarEpisode> { it.date }.thenBy { it.showName }.thenBy { it.season ?: 0 }.thenBy { it.episode ?: 0 }),
            recent = episodes.filter { it.dayOffset < 0 }
                .sortedWith(compareByDescending<CalendarEpisode> { it.date }.thenBy { it.showName }),
        )
    }

    private companion object {
        const val MAX_SHOWS = 60
        const val PAST_DAYS = 14L
        const val FUTURE_DAYS = 60L
    }
}

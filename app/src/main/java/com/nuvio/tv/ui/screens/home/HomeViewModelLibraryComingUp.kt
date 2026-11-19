package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val COMING_UP_MAX_SHOWS = 40
private const val COMING_UP_DAYS_AHEAD = 30L
private const val COMING_UP_REFRESH_MS = 6 * 60 * 60 * 1000L

/**
 * The next unaired episode of each series in the Library, within the next month, for the home
 * screen's Upcoming row — so a show you saved but haven't started still shows when it's back.
 * Refreshes when the Library changes and every few hours.
 */
@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeLibraryComingUp() {
    viewModelScope.launch {
        libraryRepository.libraryItems
            .map { items ->
                items.filter { it.type.equals("series", true) || it.type.equals("tv", true) }
                    .map { Triple(it.id, it.type, it.name) }
                    .distinct()
                    .take(COMING_UP_MAX_SHOWS)
            }
            .distinctUntilChanged()
            .debounce(2_000L)
            .collectLatest { shows ->
                while (true) {
                    val items = loadLibraryComingUp(shows)
                    _uiState.update { if (it.libraryComingUpItems == items) it else it.copy(libraryComingUpItems = items) }
                    delay(COMING_UP_REFRESH_MS)
                }
            }
    }
}

private suspend fun HomeViewModel.loadLibraryComingUp(
    shows: List<Triple<String, String, String>>
): List<ContinueWatchingItem> {
    if (shows.isEmpty()) return emptyList()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val gate = Semaphore(3)
    return kotlinx.coroutines.coroutineScope {
        shows.map { (id, type, fallbackName) ->
            async {
                gate.withPermit {
                    val result = withTimeoutOrNull(15_000L) {
                        metaRepository.getMetaFromAllAddons(type = type, id = id).first { it !is NetworkResult.Loading }
                    }
                    val meta = (result as? NetworkResult.Success)?.data ?: return@withPermit null
                    val next = meta.videos
                        .asSequence()
                        .filter { (it.season ?: 0) > 0 && it.episode != null }
                        .mapNotNull { video -> parseEpisodeReleaseLocalDate(video.released)?.let { video to it } }
                        .filter { (_, date) -> date.isAfter(today) || date.isEqual(today) }
                        .filter { (_, date) -> ChronoUnit.DAYS.between(today, date) <= COMING_UP_DAYS_AHEAD }
                        .minWithOrNull(compareBy<Pair<com.nuvio.tv.domain.model.Video, LocalDate>> { it.second }
                            .thenBy { it.first.season ?: 0 }.thenBy { it.first.episode ?: 0 })
                        ?: return@withPermit null
                    val (video, date) = next
                    val releaseMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    ContinueWatchingItem.NextUp(
                        info = NextUpInfo(
                            contentId = id,
                            contentType = type,
                            name = meta.name.ifBlank { fallbackName },
                            poster = meta.poster,
                            backdrop = meta.background,
                            logo = meta.logo,
                            videoId = video.id,
                            season = video.season ?: 0,
                            episode = video.episode ?: 0,
                            episodeTitle = video.title.takeIf { it.isNotBlank() },
                            thumbnail = video.thumbnail,
                            released = video.released,
                            hasAired = false,
                            lastWatched = 0L,
                            sortTimestamp = releaseMs,
                            releaseTimestamp = releaseMs,
                            isReleaseAlert = true,
                        )
                    )
                }
            }
        }.awaitAll().filterNotNull()
            .sortedBy { it.info.releaseTimestamp ?: Long.MAX_VALUE }
    }
}

/**
 * The Upcoming row: unaired episodes from Continue Watching first, then Library shows coming
 * back soon, without repeating a show that already appears on the home screen's top rows.
 */
internal fun mergeComingUp(
    continueWatching: List<ContinueWatchingItem>,
    upcoming: List<ContinueWatchingItem>,
    library: List<ContinueWatchingItem>,
): List<ContinueWatchingItem> {
    if (library.isEmpty()) return upcoming
    fun ContinueWatchingItem.showId() = when (this) {
        is ContinueWatchingItem.InProgress -> progress.contentId
        is ContinueWatchingItem.NextUp -> info.contentId
    }
    val shown = (continueWatching + upcoming).map { it.showId() }.toSet()
    return upcoming + library.filter { it.showId() !in shown }
}

package com.nuvio.tv.ui.screens.sports

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.nuvio.tv.ui.screens.stream.LinkHealth
import com.nuvio.tv.ui.screens.stream.LiveLinkChecker
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.theme.CinemaGold
import com.nuvio.tv.ui.theme.CinemaLivePill
import com.nuvio.tv.ui.theme.CinemaPulseDot
import com.nuvio.tv.ui.theme.cinemaFocusShine
import com.nuvio.tv.ui.theme.cinemaShine
import com.nuvio.tv.ui.theme.cinemaSpinRing
import com.nuvio.tv.ui.theme.rememberCinemaBreath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

// Cinema Black palette for the hub.
private val Background = Color(0xFF08080A)
private val Card = Color.White.copy(alpha = 0.045f)
private val CardBorder = Color.White.copy(alpha = 0.07f)
private val CardFocused = CinemaGold.Base.copy(alpha = 0.12f)
private val LiveRed = CinemaGold.Live
private val LiveText = Color(0xFFFF7B86)
private val Muted = Color(0xFF9E998F)
private val Soft = Color(0xFFCFC9BE)

private enum class HubView(val label: String) {
    // "All" tab only
    ALL_SPORTS("All Sports"), LIVE_EVENTS("Live Events"), US_CHANNELS("US Channels"), MY_TEAMS("My Teams"), SEARCH("🔍 Search"),
    // Each sport's tabs
    CHANNELS("NFL Channels"), LIVE("Live Now"), UPCOMING("Upcoming"), FINAL("Final"), EVENTS("Events"),
    REPLAYS("Replays"), HIGHLIGHTS("Highlights"), STANDINGS("Standings"),
}

/** Tabs under "All" (league = null) or under one sport. NFL keeps NFL Channels first. */
private fun viewsFor(league: SportsLeague?): List<HubView> = if (league == null) {
    listOf(HubView.ALL_SPORTS, HubView.LIVE_EVENTS, HubView.US_CHANNELS, HubView.MY_TEAMS, HubView.SEARCH)
} else buildList {
    if (league == SportsLeague.NFL) add(HubView.CHANNELS)
    if (league.scoreboardPath != null) addAll(listOf(HubView.LIVE, HubView.UPCOMING, HubView.FINAL))
    // Add-on events: WWE has no ESPN schedule; boxing isn't on ESPN's UFC feed.
    if (league.scoreboardPath == null || league == SportsLeague.UFC) add(HubView.EVENTS)
    add(HubView.REPLAYS)
    add(HubView.HIGHLIGHTS)
    // F1 championship tables come from Jolpica.
    if (league.usesTeamStats && league.scoreboardPath != null || league == SportsLeague.FORMULA_1) add(HubView.STANDINGS)
}

/** Sport plus Men's/Women's (or ATP/WTA, UFC) when the sport has several feeds. */
private fun SportsEvent.divisionLabel(): String? = division?.let { "$it ${league.shortLabel}" }

@Composable
fun SportsHubScreen(repository: SportsEventRepository = SportsEventRepository.shared, onOpenStream: (SportsCatalogMatch) -> Unit = {}, streamsViewModel: SportsStreamsViewModel = hiltViewModel(), multiviewViewModel: SportsMultiviewViewModel = hiltViewModel()) {
    var selectedLeague by rememberSaveable { mutableStateOf(SportsLeague.NFL) }
    // "All" is the first tab: All Sports, Live Events, US Channels (and My Teams, Search).
    var allTab by rememberSaveable { mutableStateOf(true) }
    var board by remember { mutableStateOf(repository.peek(selectedLeague)) }
    var loading by remember { mutableStateOf(board == null) }
    var refreshFailed by remember { mutableStateOf(false) }
    // Saveable so pressing Back from the stream picker re-opens the same game
    // with the streams that were already found.
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var showIntro by rememberSaveable { mutableStateOf(true) }
    val tabFocus = remember { FocusRequester() }
    val multiviewTiles = remember { mutableStateListOf<TvMultiviewTile>() }
    var multiviewOpen by remember { mutableStateOf(false) }
    var multiviewFocus by remember { mutableStateOf<String?>(null) }
    var multiviewPending by remember { mutableStateOf<String?>(null) }
    var multiviewHelp by remember { mutableStateOf(false) }
    // Games opened from search results (any league), so their details can be shown.
    var searchGames by remember { mutableStateOf<List<SportsEvent>>(emptyList()) }

    LaunchedEffect(selectedLeague, allTab, lifecycleOwner) {
        if (allTab || selectedLeague.scoreboardPath == null) {
            loading = false
            return@LaunchedEffect
        }
        board = repository.cached(selectedLeague)
        loading = board == null
        refreshFailed = false
        // Only poll while the screen is visible; stops when the app is backgrounded.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var first = true
            while (true) {
                try {
                    board = repository.scoreboard(selectedLeague, forceRefresh = !first)
                    refreshFailed = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    refreshFailed = true
                }
                loading = false
                first = false
                delay(SportsEventRepository.refreshIntervalMillis(board))
            }
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    remember { SportsPreferences.init(context) }
    val prefs by SportsPreferences.state.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(HubView.ALL_SPORTS) }
    // Never leave a tab showing that this sport doesn't have.
    val tabViews = viewsFor(if (allTab) null else selectedLeague)
    if (view !in tabViews) view = tabViews.first()

    // All Sports: live and starting soon in every sport.
    var allBoards by remember { mutableStateOf<List<SportsScoreboard>?>(null) }
    LaunchedEffect(allTab, view, lifecycleOwner) {
        if (!allTab || view != HubView.ALL_SPORTS) return@LaunchedEffect
        val leagues = SportsLeague.entries.filter { it.scoreboardPath != null }
        allBoards = leagues.mapNotNull { repository.peek(it) }.takeIf { it.isNotEmpty() }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var first = true
            while (true) {
                val fresh = coroutineScope {
                    leagues.map { lg -> async { runCatching { repository.scoreboard(lg, forceRefresh = !first) }.getOrNull() } }
                        .awaitAll().filterNotNull()
                }
                allBoards = fresh
                first = false
                delay(60_000L)
            }
        }
    }

    // Score delay: keep recent scoreboards and show the one from [delay] ago.
    val history = remember(selectedLeague) { mutableStateListOf<SportsScoreboard>() }
    LaunchedEffect(board) {
        val b = board?.takeIf { it.league == selectedLeague } ?: return@LaunchedEffect
        if (history.lastOrNull()?.fetchedAtEpochMillis != b.fetchedAtEpochMillis) {
            history.add(b)
            while (history.size > 40) history.removeAt(0)
        }
    }
    var clockNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prefs.scoreDelaySeconds) {
        while (prefs.scoreDelaySeconds > 0) {
            clockNow = System.currentTimeMillis()
            delay(5_000L)
        }
    }
    val shownBoard = if (prefs.scoreDelaySeconds <= 0) board?.takeIf { it.league == selectedLeague } else {
        val cutoff = clockNow - prefs.scoreDelaySeconds * 1_000L
        history.lastOrNull { it.fetchedAtEpochMillis <= cutoff } ?: history.firstOrNull() ?: board?.takeIf { it.league == selectedLeague }
    }
    val events = shownBoard?.events.orEmpty()
    val liveGames = SportsEventSections.live(events)

    val replays = view == HubView.REPLAYS || view == HubView.HIGHLIGHTS
    val browseCatalog = !allTab && (replays || view == HubView.EVENTS)
    var catalogMatches by remember(selectedLeague, browseCatalog, replays) {
        mutableStateOf<List<SportsCatalogMatch>?>(null)
    }
    LaunchedEffect(selectedLeague, browseCatalog, replays, events) {
        if (!browseCatalog) return@LaunchedEffect
        try {
            val schedule = if (replays && SportsRecordingClassifier.appliesTo(selectedLeague)) {
                (events + repository.recentResults(selectedLeague)).distinctBy { it.id }
            } else events
            catalogMatches = streamsViewModel.browseLeague(selectedLeague, schedule, replays)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            catalogMatches = emptyList()
        }
    }

    // Background search for live games (like Mobile): links for games on now are looked up
    // ahead of time, so opening one shows its links right away.
    val liveNowIds = (if (allTab) allBoards?.flatMap { it.events }.orEmpty() else events)
        .filter { it.status == SportsEventStatus.LIVE }
    LaunchedEffect(liveNowIds.map { it.id }.sorted().joinToString(",")) {
        streamsViewModel.prefetchLive(liveNowIds)
    }

    var nflChannels by remember { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(view, selectedLeague) {
        if (view == HubView.CHANNELS && selectedLeague == SportsLeague.NFL) {
            nflChannels = streamsViewModel.cachedNflChannels()
            nflChannels = try { streamsViewModel.findNflChannels() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { emptyList() }
        }
    }

    // Every add-on's live events and US sports channels (same for any league tab), as on Mobile.
    var liveEvents by remember { mutableStateOf(streamsViewModel.cachedLiveEvents()) }
    var usChannels by remember { mutableStateOf(streamsViewModel.cachedUsChannels()) }
    LaunchedEffect(view) {
        try {
            when (view) {
                HubView.LIVE_EVENTS -> liveEvents = streamsViewModel.liveEvents()
                HubView.US_CHANNELS -> usChannels = streamsViewModel.usChannels()
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (view == HubView.LIVE_EVENTS && liveEvents == null) liveEvents = emptyList()
            if (view == HubView.US_CHANNELS && usChannels == null) usChannels = emptyList()
        }
    }

    // Search: teams ("packers", "brewers"), channels ("espn2"), events ("ufc").
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchText = searchQuery.trim()
    val searchActive = view == HubView.SEARCH && searchText.length >= 2
    var searchBoards by remember { mutableStateOf<List<SportsScoreboard>>(emptyList()) }
    var searchAddonResults by remember { mutableStateOf<List<SportsCatalogMatch>?>(null) }
    LaunchedEffect(searchActive) {
        if (!searchActive) return@LaunchedEffect
        // Today's games in every league (cached first, then fresh), plus channels and events.
        val leagues = SportsLeague.entries.filter { it.scoreboardPath != null }
        val boards = leagues.mapNotNull { repository.peek(it) }.toMutableList()
        searchBoards = boards.toList()
        coroutineScope {
            launch { if (usChannels == null) usChannels = runCatching { streamsViewModel.usChannels() }.getOrNull() }
            launch { if (liveEvents == null) liveEvents = runCatching { streamsViewModel.liveEvents() }.getOrNull() }
            leagues.filter { lg -> boards.none { it.league == lg } }.map { lg ->
                async { runCatching { repository.scoreboard(lg) }.getOrNull() }
            }.awaitAll().filterNotNull().let { fresh -> searchBoards = boards + fresh }
        }
    }
    LaunchedEffect(searchText, view) {
        searchAddonResults = null
        if (view != HubView.SEARCH || searchText.length < 2) return@LaunchedEffect
        delay(600) // wait for typing to pause
        searchAddonResults = try { streamsViewModel.searchAddons(searchText) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { emptyList() }
    }
    val addToMultiview: (SportsCatalogMatch) -> Unit = { match ->
        multiviewPending = match.title
        multiviewViewModel.search(match)
    }

    var myTeamGames by remember { mutableStateOf<List<SportsEvent>?>(null) }
    LaunchedEffect(view, prefs.favoriteTeams) {
        if (view != HubView.MY_TEAMS) return@LaunchedEffect
        myTeamGames = null
        val found = coroutineScope {
            SportsPreferences.favoriteLeagues(prefs).map { lg ->
                async { runCatching { repository.scoreboard(lg).events }.getOrDefault(emptyList()) }
            }.awaitAll().flatten()
        }.filter { SportsPreferences.involvesFavorite(it, prefs) }
        myTeamGames = SportsEventSections.live(found) + SportsEventSections.upcoming(found) + SportsEventSections.final(found)
    }
    var standings by remember(selectedLeague) { mutableStateOf<SportsStandings?>(null) }
    var standingsLoaded by remember(selectedLeague) { mutableStateOf(false) }
    LaunchedEffect(view, selectedLeague) {
        if (view != HubView.STANDINGS || standingsLoaded) return@LaunchedEffect
        standings = runCatching { repository.standings(selectedLeague) }.getOrNull()
        standingsLoaded = true
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        // Nothing behind the multi-view should animate: every redraw here takes
        // GPU time away from the video tiles on onn / Google TV sticks.
        if (!multiviewOpen) HubGlows()
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("KABLE KILLERS SPORTS", color = CinemaGold.Base, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.6.sp)
                    Text("Sports Hub", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    if (allTab) "All sports" else statusLine(board, refreshFailed, selectedLeague),
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(end = 14.dp, bottom = 8.dp)
                )
                if (liveGames.isNotEmpty()) CinemaLivePill("${liveGames.size} LIVE", Modifier.padding(bottom = 4.dp))
            }

            ScoreTicker(if (allTab) allBoards?.flatMap { it.events }.orEmpty() else events, animate = !multiviewOpen)

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AllSportsTab(active = allTab, modifier = if (allTab) Modifier.focusRequester(tabFocus) else Modifier) {
                    if (!allTab) {
                        allTab = true
                        view = viewsFor(null).first()
                    }
                }
                SportsLeague.entries.forEach { league ->
                    val active = !allTab && league == selectedLeague
                    LeagueTab(league, active = active,
                        modifier = if (active) Modifier.focusRequester(tabFocus) else Modifier) {
                        if (!active) {
                            allTab = false
                            selectedLeague = league
                            // NFL opens on NFL Channels; every other sport on its first tab.
                            view = viewsFor(league).first()
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabViews.forEach { v ->
                    PanelButton(v.label, active = view == v) { view = v }
                }
                Spacer(Modifier.width(18.dp))
                PanelButton("▦  Multi-View (" + multiviewTiles.size + "/4)", active = multiviewOpen) {
                    if (multiviewTiles.isNotEmpty()) {
                        multiviewOpen = true
                    } else {
                        multiviewHelp = true
                    }
                }
                Spacer(Modifier.width(18.dp))
                PanelButton(
                    "⏱  " + if (prefs.scoreDelaySeconds <= 0) "Live scores" else if (prefs.scoreDelaySeconds < 60) "${prefs.scoreDelaySeconds}s delay" else "${prefs.scoreDelaySeconds / 60} min delay",
                    active = prefs.scoreDelaySeconds > 0
                ) {
                    val options = SportsPreferences.delayOptions
                    SportsPreferences.setScoreDelay(options[(options.indexOf(prefs.scoreDelaySeconds).coerceAtLeast(0) + 1) % options.size])
                }
            }

            when {
                view == HubView.LIVE_EVENTS -> {
                    InfoPanel("Live events from all your add-ons · American sports only (soccer = MLS) · the same event from several add-ons is grouped")
                    val list = liveEvents
                    when {
                        list == null -> InfoPanel("Searching your add-ons for live events…")
                        list.isEmpty() -> InfoPanel("No live events found right now in your enabled add-ons.")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            list.groupBy { it.sport }.forEach { (sport, inSport) ->
                                SectionTitle("${sport.uppercase()} · ${inSport.size}", live = false)
                                inSport.forEach { event ->
                                    GroupedSources(event.title, event.sources, onOpenStream, addToMultiview)
                                }
                            }
                        }
                    }
                }
                view == HubView.US_CHANNELS -> {
                    InfoPanel("US sports channels (ESPN, ESPN2, FS1, NFL Network…) from all your add-ons · foreign feeds left out")
                    val list = usChannels
                    when {
                        list == null -> InfoPanel("Searching your add-ons for US sports channels…")
                        list.isEmpty() -> InfoPanel("No US sports channels found in your enabled add-ons.")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            list.forEach { channel ->
                                GroupedSources(channel.name, channel.sources, onOpenStream, addToMultiview)
                            }
                        }
                    }
                }
                view == HubView.SEARCH -> {
                    SportsSearchField(searchQuery, onChange = { searchQuery = it })
                    if (searchText.length < 2) {
                        InfoPanel("Type a team (Packers, Brewers), a channel (ESPN2) or an event (UFC).")
                    } else {
                        val games = SportsHubSearch.games(searchText, searchBoards.flatMap { it.events } + events)
                        val channels = usChannels?.let { SportsHubSearch.channels(searchText, it) }
                        val foundEvents = liveEvents?.let { SportsHubSearch.events(searchText, it) }
                        if (games.isNotEmpty()) {
                            SectionTitle("GAMES · ${games.size}", live = false)
                            games.take(30).forEach { event ->
                                val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                                GameCard(event, requester) { searchGames = games; selectedEventId = event.id }
                            }
                        }
                        if (!channels.isNullOrEmpty()) {
                            SectionTitle("CHANNELS · ${channels.size}", live = false)
                            channels.forEach { GroupedSources(it.name, it.sources, onOpenStream, addToMultiview) }
                        }
                        if (!foundEvents.isNullOrEmpty()) {
                            SectionTitle("LIVE EVENTS · ${foundEvents.size}", live = false)
                            foundEvents.forEach { GroupedSources(it.title, it.sources, onOpenStream, addToMultiview) }
                        }
                        SectionTitle("FROM YOUR ADD-ONS" + (searchAddonResults?.let { " · ${it.size}" } ?: ""), live = false)
                        val addonResults = searchAddonResults
                        when {
                            addonResults == null -> InfoPanel("Searching your add-ons for \"$searchText\"…")
                            addonResults.isEmpty() -> InfoPanel("No add-on results for \"$searchText\".")
                            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                addonResults.forEach { match ->
                                    SourceRow(match, verified = true) { onOpenStream(match) }
                                    PanelButton("＋ Add to Multi-View", active = false) { addToMultiview(match) }
                                }
                            }
                        }
                    }
                }
                view == HubView.CHANNELS -> {
                    when {
                        nflChannels == null -> InfoPanel("Searching installed add-ons for NFL Network and RedZone…")
                        nflChannels!!.isEmpty() -> InfoPanel("No NFL Network or NFL RedZone channels found in enabled add-ons.")
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            nflChannels!!.forEach { match ->
                                SourceRow(match, verified = true) { onOpenStream(match) }
                                PanelButton("＋ Add to Multi-View", active = false) { addToMultiview(match) }
                            }
                        }
                    }
                }
                browseCatalog -> {
                    val kind = when (view) {
                        HubView.REPLAYS -> SportsContentKind.REPLAY
                        HubView.HIGHLIGHTS -> SportsContentKind.HIGHLIGHT
                        else -> SportsContentKind.EVENT
                    }
                    val rows = catalogMatches?.filter { match ->
                        match.kind == kind && (view != HubView.CHANNELS ||
                            Regex("""(?i)nfl.?network|nfl.?red.?zone|red.?zone.?nfl|nfl.?channel""")
                                .containsMatchIn(match.title))
                    }
                    when {
                        rows == null -> InfoPanel("Checking installed add-ons…")
                        rows.isEmpty() -> {
                            val label = if (view == HubView.CHANNELS) "channels" else if (replays) view.label.lowercase() else "events"
                            InfoPanel("No ${selectedLeague.label} $label from installed add-ons.")
                        }
                        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            rows.forEach { match ->
                                SourceRow(match, verified = true) { onOpenStream(match) }
                            }
                        }
                    }
                }
                view == HubView.STANDINGS -> when {
                    !standingsLoaded -> InfoPanel("Loading ${selectedLeague.label} standings…")
                    standings == null -> InfoPanel("Standings aren't available for ${selectedLeague.label}.")
                    else -> standings!!.groups.forEach { group -> StandingsTable(selectedLeague, group, prefs) }
                }
                view == HubView.MY_TEAMS -> when {
                    prefs.favoriteTeams.isEmpty() -> InfoPanel("Open a game and choose Follow on a team, or press OK on a team in Standings. Its games from every league will show up here.")
                    myTeamGames == null -> InfoPanel("Finding your teams' games…")
                    myTeamGames.orEmpty().isEmpty() -> InfoPanel("No games for your teams on the current schedule.")
                    else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        myTeamGames.orEmpty().take(30).forEach { event ->
                            val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                            GameCard(event, requester) { selectedEventId = event.id }
                        }
                    }
                }
                view == HubView.ALL_SPORTS -> {
                    val all = allBoards?.flatMap { it.events }.orEmpty()
                    val now = System.currentTimeMillis()
                    val live = SportsEventSections.live(all)
                    val soon = SportsEventSections.upcoming(all).filter { e ->
                        e.status == SportsEventStatus.SCHEDULED &&
                            (e.startTimeEpochMillis ?: Long.MAX_VALUE) <= now + 3 * 60 * 60_000L
                    }
                    when {
                        allBoards == null -> InfoPanel("Loading today's games in every sport…")
                        else -> {
                            SectionTitle("LIVE NOW · ${live.size}", live = true)
                            if (live.isEmpty()) InfoPanel("Nothing live right now.")
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                live.take(40).forEach { event ->
                                    val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                                    GameCard(event, requester, showLeague = true) { selectedEventId = event.id }
                                }
                            }
                            SectionTitle("STARTING SOON · ${soon.size}", live = false)
                            if (soon.isEmpty()) InfoPanel("Nothing starting in the next 3 hours.")
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                soon.take(40).forEach { event ->
                                    val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                                    GameCard(event, requester, showLeague = true) { selectedEventId = event.id }
                                }
                            }
                        }
                    }
                }
                loading && events.isEmpty() ->
                    InfoPanel("Loading ${selectedLeague.label} scores…")
                refreshFailed && events.isEmpty() ->
                    InfoPanel("Scores unavailable. Check your connection — we'll keep trying automatically.")
                view == HubView.LIVE -> {
                    val featured = liveGames.firstOrNull()
                    if (featured == null) {
                        InfoPanel("No ${selectedLeague.label} games live right now.")
                    } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        val requester = cardFocus.getOrPut(featured.id) { FocusRequester() }
                        FeaturedGameCard(featured, requester, Modifier.weight(0.47f)) { selectedEventId = featured.id }
                        Column(Modifier.weight(0.53f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            liveGames.drop(1).take(30).forEach { event ->
                                val r = cardFocus.getOrPut(event.id) { FocusRequester() }
                                GameCard(event, r) { selectedEventId = event.id }
                            }
                        }
                    }
                }
                view == HubView.UPCOMING || view == HubView.FINAL -> {
                    val list = if (view == HubView.UPCOMING) SportsEventSections.upcoming(events) else SportsEventSections.final(events)
                    if (list.isEmpty()) {
                        InfoPanel(if (view == HubView.UPCOMING) "No upcoming ${selectedLeague.label} games on the schedule." else "No finished ${selectedLeague.label} games right now.")
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        list.take(40).forEach { event ->
                            val requester = cardFocus.getOrPut(event.id) { FocusRequester() }
                            GameCard(event, requester) { selectedEventId = event.id }
                        }
                    }
                }
            }
        }

        if (showIntro) SportsHubIntro(onFinished = { showIntro = false })
    }
    LaunchedEffect(showIntro) {
        if (!showIntro) {
            delay(50)
            runCatching { tabFocus.requestFocus() }
        }
    }

    val selected = selectedEventId?.let { id ->
        events.firstOrNull { it.id == id } ?: myTeamGames?.firstOrNull { it.id == id }
            ?: searchGames.firstOrNull { it.id == id }
            ?: allBoards?.firstNotNullOfOrNull { b -> b.events.firstOrNull { it.id == id } }
    }
    if (selectedEventId != null) {
        val closeDetails = {
            val id = selectedEventId
            selectedEventId = null
            streamsViewModel.clear()
            id?.let { cardFocus[it] }?.let { runCatching { it.requestFocus() } }
            Unit
        }
        val gameListsReady = when (view) {
            HubView.MY_TEAMS -> myTeamGames != null
            HubView.SEARCH -> searchBoards.isNotEmpty()
            HubView.ALL_SPORTS -> allBoards != null
            else -> shownBoard != null
        }
        if (selected == null) {
            // Game dropped off the provider's scoreboard (e.g. league switched); just close.
            // Wait for the lists to load first, e.g. right after returning from a stream.
            if (gameListsReady) LaunchedEffect(Unit) { closeDetails() }
        } else {
            LaunchedEffect(selected.id) { streamsViewModel.lookup(selected) }
            GameDetailsDialog(selected, repository, streamsViewModel.matches, streamsViewModel.browse, streamsViewModel.diagnostics, streamsViewModel.loading, streamsViewModel.error, onOpenStream = { match -> onOpenStream(match) }, onAddMultiview = { match -> multiviewPending = selected.title; multiviewViewModel.search(match); closeDetails() }, onDismiss = closeDetails)
        }
    }

    if (multiviewHelp) {
        Dialog(onDismissRequest = { multiviewHelp = false }) {
            Column(
                Modifier.background(Background, RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                DetailHeading("START MULTI-VIEW")
                InfoPanel("Choose a game in Sports Hub, select a source, then choose Add to Multi-View. Repeat for up to four games. Use the D-pad to select which game has sound.")
                PanelButton("OK · Browse games", active = true) {
                    multiviewHelp = false
                    allTab = true
                    view = HubView.ALL_SPORTS
                }
            }
        }
    }

    if (multiviewPending != null) {
        Dialog(onDismissRequest = { multiviewPending = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Background).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PanelButton("‹ Back to Sports Hub", active = false) { multiviewPending = null }
                DetailHeading("CHOOSE A STREAM · " + multiviewPending.orEmpty())
                if (multiviewViewModel.loading) InfoPanel("Searching enabled add-ons for playable streams…")
                multiviewViewModel.error?.let { InfoPanel(it) }
                if (!multiviewViewModel.loading && multiviewViewModel.choices.isEmpty()) {
                    InfoPanel("No direct playable links found. Try another source.")
                }
                // Same as Mobile: only links that answered the test are offered.
                val allChoices = multiviewViewModel.choices
                val health = allChoices.associate { it.id to (it.stream?.let(LiveLinkChecker::health) ?: LinkHealth.Ok) }
                val working = allChoices.filter { health[it.id] == LinkHealth.Ok }
                val testing = allChoices.count { health[it.id] == LinkHealth.Checking }
                val dead = allChoices.count { health[it.id] == LinkHealth.Dead }
                if (allChoices.isNotEmpty()) {
                    Text(
                        buildString {
                            append(if (working.isEmpty() && testing > 0) "Testing links…" else "Pick a link · ${working.size} working")
                            if (testing > 0 && working.isNotEmpty()) append(" · testing $testing more")
                            if (dead > 0) append(" · $dead hidden (not working)")
                        },
                        color = Muted, fontSize = 14.sp,
                    )
                    if (working.isEmpty() && testing == 0) InfoPanel("None of these links are working right now. Try another stream.")
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    working.forEach { choice ->
                        PanelButton(choice.addon + " · " + choice.label, active = false) {
                            val tile = TvMultiviewTile(choice.id, multiviewPending.orEmpty(), choice.url)
                            if (multiviewTiles.size == 4) multiviewTiles.removeAt(0)
                            multiviewTiles.add(tile)
                            multiviewFocus = tile.id
                            multiviewPending = null
                            multiviewOpen = true
                        }
                    }
                }
            }
        }
    }
    if (multiviewOpen && multiviewTiles.isNotEmpty()) {
        Dialog(onDismissRequest = { multiviewOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Background).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelButton("‹ Sports Hub", active = false) { multiviewOpen = false }
                    PanelButton("Remove focused", active = false) {
                        multiviewTiles.removeAll { it.id == multiviewFocus }
                        multiviewFocus = multiviewTiles.firstOrNull()?.id
                        if (multiviewTiles.isEmpty()) multiviewOpen = false
                    }
                    Text("MULTI-VIEW · " + multiviewTiles.size + "/4 · D-pad selects audio",
                        color = CinemaGold.Base, fontSize = 16.sp)
                }
                SportsMultiviewPlayers(
                    tiles = multiviewTiles.toList(),
                    focusedId = multiviewFocus,
                    onFocus = { multiviewFocus = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }

}

/** Gold and ember glows that slowly breathe behind the hub. */
@Composable
private fun HubGlows() {
    val left = rememberCinemaBreath(5_000)
    val right = rememberCinemaBreath(5_000, delayMillis = 2_500)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val r = size.width * 0.42f
            val a = Offset(size.width * 0.22f, size.height * 0.62f)
            val b = Offset(size.width * 0.55f, size.height * 0.58f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB612).copy(alpha = 0.22f * left.value), Color.Transparent), a, r), r, a)
            drawCircle(Brush.radialGradient(listOf(CinemaGold.Ember.copy(alpha = 0.22f * right.value), Color.Transparent), b, r), r, b)
        }
    )
}

/** Broadcast-style strip of scores that scrolls on its own. */
@Composable
private fun ScoreTicker(events: List<SportsEvent>, animate: Boolean = true) {
    val scored = events.filter { it.hasScores }
        .sortedBy { if (it.status == SportsEventStatus.LIVE) 0 else 1 }
        .take(16)
    if (scored.isEmpty()) return
    Box(
        Modifier.fillMaxWidth().height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            (if (animate) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 800, velocity = 42.dp) else Modifier).padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            scored.forEach { event ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.league.label, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(event.scoreLine().orEmpty(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        whenText(event),
                        color = if (event.status == SportsEventStatus.LIVE) LiveText else Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, live: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) CinemaPulseDot(size = 7.dp)
        Text(
            text,
            color = if (live) Color(0xFFFF8A94) else if (text == "FINAL") Muted else CinemaGold.Base,
            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.8.sp
        )
    }
}

/** Springy grow while focused. */
private fun Modifier.focusGrow(focused: Boolean, to: Float): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (focused) to else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "focus_scale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private fun leagueLogoUrl(league: SportsLeague): String? = when (league) {
    else -> league.logoPath?.let { "https://a.espncdn.com/$it" }
}

private fun teamLogoUrl(league: SportsLeague, abbreviation: String?): String? {
    val abbr = abbreviation?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
    val slug = when (league) {
        SportsLeague.NFL -> "nfl"
        SportsLeague.NBA -> "nba"
        SportsLeague.MLB -> "mlb"
        SportsLeague.NHL -> "nhl"
        else -> return null
    }
    return "https://a.espncdn.com/i/teamlogos/$slug/500/$abbr.png"
}

@Composable
private fun LeagueLogo(league: SportsLeague, logoSize: Dp) {
    var failed by remember(league) { mutableStateOf(false) }
    val url = leagueLogoUrl(league)
    Box(modifier = Modifier.size(logoSize), contentAlignment = Alignment.Center) {
        if (url == null || failed) {
            SportIcon(league.glyph(), size = logoSize, color = CinemaGold.Base)
        } else {
            AsyncImage(
                model = url,
                contentDescription = "${league.label} logo",
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = if (league == SportsLeague.TENNIS) {
                    Modifier.fillMaxSize().background(Color(0xFFF5EFE9), CircleShape).padding(2.dp)
                } else Modifier.fillMaxSize(),
            )
        }
    }
}

private fun statusLine(board: SportsScoreboard?, refreshFailed: Boolean, league: SportsLeague): String {
    if (league.scoreboardPath == null) return "Installed sports add-ons"
    val base = "Live scores and schedules • Data: ESPN"
    val fetched = board?.takeIf { it.league == league }?.fetchedAtEpochMillis ?: return base
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fetched))
    return if (refreshFailed) "$base • Couldn't refresh — showing scores from $time"
    else "$base • Updated $time"
}

/** First tab in the sports row: every sport, Live Events and US Channels. */
@Composable
private fun AllSportsTab(active: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val textColor by animateColorAsState(if (focused || active) CinemaGold.Soft else Soft, label = "all_text")
    Row(
        modifier = modifier
            .focusGrow(focused, 1.07f)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) CinemaGold.Soft else if (active) CinemaGold.Base.copy(alpha = 0.7f) else CardBorder,
                shape
            )
            .background(if (focused || active) CardFocused else Card, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(46.dp).cinemaSpinRing(active).padding(4.dp).clip(CircleShape).background(Color(0xFF141317)),
            contentAlignment = Alignment.Center
        ) { Text("★", color = CinemaGold.Base, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
        Text("All", color = textColor, fontSize = 15.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

@Composable
private fun LeagueTab(league: SportsLeague, active: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val textColor by animateColorAsState(if (focused || active) CinemaGold.Soft else Soft, label = "league_text")
    Row(
        modifier = modifier
            .focusGrow(focused, 1.07f)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) CinemaGold.Soft else if (active) CinemaGold.Base.copy(alpha = 0.7f) else CardBorder,
                shape
            )
            .background(if (focused || active) CardFocused else Card, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(46.dp).cinemaSpinRing(active).padding(4.dp).clip(CircleShape).background(Color(0xFF141317)),
            contentAlignment = Alignment.Center
        ) { LeagueLogo(league, 28.dp) }
        Text(league.shortLabel, color = textColor, fontSize = 15.sp, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

@Composable
private fun InfoPanel(message: String) {
    Text(
        message,
        color = Soft,
        modifier = Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(18.dp)
    )
}

@Composable
private fun StatusBadge(event: SportsEvent) {
    val (label, color) = when (event.status) {
        SportsEventStatus.LIVE -> "LIVE" to Color(0xFFD93A2F)
        SportsEventStatus.FINAL -> "FINAL" to Color(0xFF3A3833)
        SportsEventStatus.POSTPONED -> "POSTPONED" to Color(0xFF8A5A12)
        else -> return
    }
    Row(
        Modifier.clip(CircleShape).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (event.status == SportsEventStatus.LIVE) {
            val blink = rememberCinemaBreath(1_000)
            Box(Modifier.size(6.dp).graphicsLayer { alpha = blink.value }.clip(CircleShape).background(Color.White))
        }
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
    }
}

/** Status text: provider detail for live/final games, local start time for upcoming ones. */
private fun whenText(event: SportsEvent): String = when (event.status) {
    SportsEventStatus.SCHEDULED -> event.startTimeEpochMillis?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: event.statusDetail ?: "Time TBD"
    else -> event.statusDetail ?: event.status.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** A score that springs up and glows gold whenever it changes. */
@Composable
private fun PopScore(score: String, color: Color, size: Int) {
    AnimatedContent(
        targetState = score,
        transitionSpec = {
            (slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn() + scaleIn(initialScale = 1.5f)) togetherWith
                (slideOutVertically(tween(350)) { -it } + fadeOut())
        },
        label = "pop_score"
    ) { value ->
        Text(value, color = color, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun TeamBadge(team: SportsCompetitor, league: SportsLeague, glow: Color, size: Dp) {
    Box(
        Modifier.size(size)
            .shadow(18.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(Color(0xFF17161A))
            .border(3.dp, glow, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        var failed by remember(team.name) { mutableStateOf(false) }
        val logo = team.logoUrl ?: teamLogoUrl(league, team.abbreviation)
        if (logo == null || failed) {
            Text(team.shortLabel.take(4), color = Color.White, fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.ExtraBold)
        } else {
            AsyncImage(
                model = logo,
                contentDescription = team.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.size(size * 0.66f)
            )
        }
    }
}

/** The big live game on the left of the hub. */
@Composable
private fun FeaturedGameCard(event: SportsEvent, focusRequester: FocusRequester, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val away = event.away
    val home = event.home
    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .focusGrow(focused, 1.02f)
            .then(if (focused) Modifier.border(3.dp, CinemaGold.Soft, RoundedCornerShape(30.dp)).padding(5.dp) else Modifier.padding(5.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xCCFFB612), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f), Color(0xCCC83803))))
            .padding(1.dp)
            .clip(RoundedCornerShape(25.dp))
            .background(Color(0xF0100E0C))
            .cinemaFocusShine(focused, strength = 0.14f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 26.dp, vertical = 22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusBadge(event)
                Text(event.divisionLabel() ?: "Featured game", color = Soft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(whenText(event), color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (away != null && home != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FeaturedTeam(away, event.league, Color(0xFFFFB612), Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PopScore(away.score ?: "–", Color.White, 64)
                        Text("–", color = Color(0xFF5E5A53), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        PopScore(home.score ?: "–", Color(0xFFA39E94), 64)
                    }
                    FeaturedTeam(home, event.league, CinemaGold.Ember, Modifier.weight(1f))
                }
            } else {
                Text(event.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val where = listOfNotNull(
                    event.venue,
                    event.broadcasts.takeIf { it.isNotEmpty() }?.let { "TV: ${it.joinToString(", ")}" }
                ).joinToString(" · ")
                Text(where, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(CinemaGold.brush).cinemaShine()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶", color = CinemaGold.Ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Watch & stats", color = CinemaGold.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun FeaturedTeam(team: SportsCompetitor, league: SportsLeague, glow: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamBadge(team, league, glow, 72.dp)
        Text(team.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        team.record?.let { Text("Record $it", color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun GameCard(event: SportsEvent, focusRequester: FocusRequester, showLeague: Boolean = false, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val statusColor = when (event.status) {
        SportsEventStatus.LIVE -> LiveText
        SportsEventStatus.FINAL -> Muted
        else -> CinemaGold.Soft
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .focusRequester(focusRequester)
            .focusGrow(focused, 1.03f)
            .clip(shape)
            .background(if (focused) CardFocused else Card)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
            .cinemaFocusShine(focused, strength = 0.14f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (event.status == SportsEventStatus.POSTPONED) StatusBadge(event)
            Text(
                event.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            val away = event.away
            val home = event.home
            if (event.hasScores && away != null && home != null) {
                PopScore("${away.score} – ${home.score}", Color.White, 18)
            }
        }
        val details = buildList {
            // "Women's College Basketball" / "ATP Tennis" on every game, sport name on All Sports.
            (event.divisionLabel() ?: event.league.shortLabel.takeIf { showLeague })?.let(::add)
            if (event.status == SportsEventStatus.SCHEDULED) {
                val records = listOfNotNull(event.away, event.home)
                    .filter { !it.record.isNullOrBlank() }
                    .joinToString("  vs  ") { "${it.shortLabel} ${it.record}" }
                if (records.isNotBlank()) add(records)
            }
            if (event.broadcasts.isNotEmpty()) add("TV: ${event.broadcasts.joinToString(", ")}")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(details.joinToString(" • "), color = Muted, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (event.status == SportsEventStatus.LIVE) {
                CinemaPulseDot(size = 6.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(whenText(event), color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}

@Composable
private fun GameDetailsDialog(event: SportsEvent, repository: SportsEventRepository, matches: List<SportsCatalogMatch>, browse: List<SportsCatalogMatch>, diagnostics: String, loading: Boolean, error: String?, onOpenStream: (SportsCatalogMatch) -> Unit, onAddMultiview: (SportsCatalogMatch) -> Unit, onDismiss: () -> Unit) {
    var boxScore by remember(event.id) { mutableStateOf<SportsBoxScore?>(null) }
    var statsLoading by remember(event.id) { mutableStateOf(event.status != SportsEventStatus.SCHEDULED) }
    var statsFailed by remember(event.id) { mutableStateOf(false) }
    var selectedTeam by remember(event.id) { mutableStateOf(0) }
    // Golf leaderboard / race order / tennis sets / fight card: fuller F1 results from Jolpica when finished.
    var detailed by remember(event.id) { mutableStateOf<SportsResults?>(null) }
    LaunchedEffect(event.id, event.status) {
        if (event.league.usesTeamStats) return@LaunchedEffect
        detailed = runCatching { repository.detailedResults(event) }.getOrElse { if (it is CancellationException) throw it; null }
    }
    // Season leaders before the game; game leaders when ESPN has no full box score (some college sports).
    var leaders by remember(event.id) { mutableStateOf<SportsLeaders?>(null) }
    var leadersLoading by remember(event.id) { mutableStateOf(event.league.usesTeamStats) }
    LaunchedEffect(event.id, event.status, statsLoading, boxScore == null) {
        if (!event.league.usesTeamStats) { leadersLoading = false; return@LaunchedEffect }
        val needed = event.status == SportsEventStatus.SCHEDULED || (!statsLoading && boxScore == null)
        if (!needed) return@LaunchedEffect
        leaders = runCatching { repository.leaders(event) }.getOrElse { if (it is CancellationException) throw it; null }
        leadersLoading = false
    }

    // Load player stats; keep them updating while the game is live.
    LaunchedEffect(event.id, event.status) {
        if (event.status == SportsEventStatus.SCHEDULED || !event.league.usesTeamStats) {
            statsLoading = false
            return@LaunchedEffect
        }
        var first = true
        while (true) {
            try {
                repository.boxScore(event, forceRefresh = !first)?.let { boxScore = it }
                statsFailed = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                statsFailed = boxScore == null
            }
            statsLoading = false
            first = false
            if (event.status != SportsEventStatus.LIVE) break
            delay(SportsEventRepository.LIVE_BOX_SCORE_MAX_AGE_MILLIS)
        }
    }

    // Full-screen dialog keeps remote focus inside the panel and closes on Back.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Background)) {
            DetailGlows()
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Actions first so focus starts at the top of the panel.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelButton("‹  Sports Hub", active = false, modifier = Modifier.focusRequester(closeFocus), onClick = onDismiss)
                    val prefs by SportsPreferences.state.collectAsStateWithLifecycle()
                    listOfNotNull(event.away, event.home).forEach { team ->
                        val key = SportsPreferences.teamKey(event.league, team)
                        val following = key in prefs.favoriteTeams
                        PanelButton((if (following) "★ Following " else "☆ Follow ") + team.shortLabel, active = following) {
                            SportsPreferences.toggleFavoriteKey(key)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    StatusBadge(event)
                    Text((event.division?.let { "$it " }.orEmpty() + event.league.label).uppercase(), color = CinemaGold.Base, fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                }

                Scoreboard(event)

                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailHeading("WATCH GAME")
                        when {
                            loading && matches.isEmpty() -> InfoPanel("Searching installed sports add-ons…")
                            error != null -> InfoPanel(error)
                            matches.isEmpty() -> {
                                InfoPanel("No exact game match found in installed add-ons.")
                                if (diagnostics.isNotBlank()) Text(diagnostics, color = Muted, fontSize = 12.sp)
                                if (browse.isNotEmpty()) {
                                    Text("BROWSE LEAGUE SOURCES · MATCHUP NOT VERIFIED", color = Muted, fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 6.dp))
                                    browse.take(12).forEach { match ->
                                        SourceRow(match, verified = false) { onOpenStream(match) }
                                        PanelButton("＋ Add to Multi-View", active = false) { onAddMultiview(match) }
                                    }
                                }
                            }
                            event.status == SportsEventStatus.FINAL -> {
                                // Same as Mobile: full games first, then the short highlight clips.
                                val full = matches.filter { it.kind != SportsContentKind.HIGHLIGHT }
                                val clips = matches.filter { it.kind == SportsContentKind.HIGHLIGHT }
                                if (full.isNotEmpty()) {
                                    Text("FULL GAME REPLAYS", color = CinemaGold.Base, fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
                                    full.take(12).forEach { match ->
                                        SourceRow(match, verified = true) { onOpenStream(match) }
                                    }
                                }
                                if (clips.isNotEmpty()) {
                                    Text("HIGHLIGHTS", color = CinemaGold.Base, fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp,
                                        modifier = Modifier.padding(top = 6.dp))
                                    clips.take(12).forEach { match ->
                                        SourceRow(match, verified = true) { onOpenStream(match) }
                                    }
                                }
                            }
                            else -> matches.take(12).forEach { match ->
                                SourceRow(match, verified = true) { onOpenStream(match) }
                                PanelButton("＋ Add to Multi-View", active = false) { onAddMultiview(match) }
                            }
                        }
                        if (loading && matches.isNotEmpty()) {
                            Text("Still checking more add-ons…", color = Muted, fontSize = 12.sp)
                        }
                    }
                    Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val scheduled = event.status == SportsEventStatus.SCHEDULED
                            val showLeaders = scheduled || (boxScore == null && leaders != null)
                            Box(Modifier.weight(1f)) {
                                DetailHeading(when {
                                    !event.league.usesTeamStats -> "RESULTS"
                                    scheduled -> "SEASON STATS"
                                    showLeaders -> "GAME LEADERS"
                                    else -> "PLAYER STATS"
                                })
                            }
                            val teamLabels = if (showLeaders) leaders?.teams?.map { it.shortLabel } else boxScore?.teams?.map { it.shortLabel }
                            teamLabels?.forEachIndexed { index, label ->
                                PanelButton(label, active = index == selectedTeam) { selectedTeam = index }
                            }
                        }
                        val team = boxScore?.teams?.getOrNull(selectedTeam)
                        val teamLeaders = leaders?.teams?.getOrNull(selectedTeam)
                        when {
                            !event.league.usesTeamStats -> {
                                val results = detailed ?: event.results
                                when {
                                    results != null -> ResultsTable(results)
                                    scheduledOrUnknown(event) -> InfoPanel("Results appear once this event starts.")
                                    else -> InfoPanel("No results reported for this event yet.")
                                }
                            }
                            event.status == SportsEventStatus.SCHEDULED -> when {
                                teamLeaders != null -> {
                                    Text("This season's leaders · the game hasn't started yet", color = Muted, fontSize = 13.sp)
                                    LeadersTable(teamLeaders)
                                }
                                leadersLoading -> InfoPanel("Loading season stats…")
                                else -> InfoPanel("Season stats aren't available for this game. Player stats appear once it starts.")
                            }
                            team != null -> team.groups.forEach { group -> StatTable(group) }
                            statsLoading -> InfoPanel("Loading player stats…")
                            teamLeaders != null -> LeadersTable(teamLeaders)
                            statsFailed -> InfoPanel("Player stats unavailable right now.")
                            else -> InfoPanel("No player stats reported for this game yet.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailGlows() {
    val left = rememberCinemaBreath(5_000)
    val right = rememberCinemaBreath(5_000, delayMillis = 2_500)
    Box(
        Modifier.fillMaxSize().drawBehind {
            val r = size.width * 0.38f
            val a = Offset(size.width * 0.1f, size.height * 0.05f)
            val b = Offset(size.width * 0.9f, size.height * 0.05f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFB612).copy(alpha = 0.26f * left.value), Color.Transparent), a, r), r, a)
            drawCircle(Brush.radialGradient(listOf(CinemaGold.Ember.copy(alpha = 0.28f * right.value), Color.Transparent), b, r), r, b)
        }
    )
}

/** Team badges, live score, status, venue and channel across the top of the details screen. */
@Composable
private fun Scoreboard(event: SportsEvent) {
    val sides = if (event.home != null && event.away != null) listOfNotNull(event.away, event.home) else event.competitors
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xB3FFB612), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f), Color(0xB3C83803))))
            .padding(1.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color(0xE6100E0C))
            .padding(horizontal = 32.dp, vertical = 22.dp)
    ) {
        if (sides.size == 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreboardSide(sides[0], event.league, Color(0xFFFFB612), alignEnd = false, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(whenText(event), color = Soft, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PopScore(sides[0].score ?: "–", Color.White, 68)
                        Text("–", color = Color(0xFF5E5A53), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        PopScore(sides[1].score ?: "–", Color(0xFFA39E94), 68)
                    }
                    val where = listOfNotNull(
                        event.venue,
                        "Watch on " + (event.broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "— not listed yet")
                    ).joinToString(" · ")
                    Text(where, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                ScoreboardSide(sides[1], event.league, CinemaGold.Ember, alignEnd = true, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(whenText(event), color = Soft, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                event.venue?.let { Text(it, color = Muted, fontSize = 13.sp) }
                Text("Watch on " + (event.broadcasts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "— not listed yet"), color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ScoreboardSide(side: SportsCompetitor, league: SportsLeague, glow: Color, alignEnd: Boolean, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val text: @Composable () -> Unit = {
            Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(side.name, color = if (side.isWinner) CinemaGold.Soft else Color.White, fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(side.record?.let { "Record $it" }, if (side.isHome) "Home" else "Away").joinToString(" · ")
                Text(sub, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (alignEnd) { text(); TeamBadge(side, league, glow, 80.dp) } else { TeamBadge(side, league, glow, 80.dp); text() }
    }
}

@Composable
private fun DetailHeading(text: String) {
    Text(text, color = CinemaGold.Base, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.8.sp)
}

/** One stream source from an installed add-on. */
@Composable
private fun SourceRow(match: SportsCatalogMatch, verified: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth()
            .focusGrow(focused, 1.03f)
            .clip(shape)
            .background(if (focused) CardFocused else if (verified) Card else Color.White.copy(alpha = 0.03f))
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
            .cinemaFocusShine(focused, strength = 0.14f)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape)
                .background(if (verified) CinemaGold.brush else Brush.linearGradient(listOf(Color(0xFF2A2830), Color(0xFF1E1D22)))),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = if (verified) CinemaGold.Ink else Soft, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(match.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val worked = remember(match) {
                com.nuvio.tv.core.links.LinkMemory.workedBefore(match.streamId, match.title, match.addon.displayName)
            }
            Text(
                if (worked) "${match.addon.displayName}  ·  ✓ Worked last time" else match.addon.displayName,
                color = if (worked) CinemaGold.Soft else Muted,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

/** A channel or event with one or more add-on links; more than one folds open on OK. */
@Composable
private fun GroupedSources(
    title: String,
    sources: List<SportsCatalogMatch>,
    onOpenStream: (SportsCatalogMatch) -> Unit,
    onAddToMultiview: (SportsCatalogMatch) -> Unit,
) {
    if (sources.size == 1) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceRow(sources.first().copy(title = title.takeIf { it.isNotBlank() } ?: sources.first().title), verified = true) {
                onOpenStream(sources.first())
            }
            PanelButton("＋ Add to Multi-View", active = false) { onAddToMultiview(sources.first()) }
        }
        return
    }
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .focusGrow(focused, 1.03f)
                .clip(shape)
                .background(if (focused) CardFocused else Card)
                .border(if (focused) 3.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
                .cinemaFocusShine(focused, strength = 0.14f)
                .onFocusChanged { focused = it.isFocused }
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${sources.size} links · " + sources.map { it.addon.displayName }.distinct().joinToString(),
                    color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(if (expanded) "Hide ▲" else "Show ▼", color = CinemaGold.Base, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (expanded) {
            Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sources.forEach { source ->
                    SourceRow(source, verified = true) { onOpenStream(source) }
                    PanelButton("＋ Add to Multi-View", active = false) { onAddToMultiview(source) }
                }
            }
        }
    }
}

/** Search box: OK on the remote opens the TV keyboard; results update as you type. */
@Composable
private fun SportsSearchField(query: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (focused) CardFocused else Card)
            .border(if (focused) 3.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🔍", fontSize = 18.sp)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search teams, channels, events…", color = Muted, fontSize = 17.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(CinemaGold.Base),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (query.isNotEmpty()) PanelButton("Clear", active = false) { onChange("") }
    }
}

@Composable
private fun PanelButton(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .focusGrow(focused, 1.06f)
            .clip(shape)
            .then(
                when {
                    focused || active -> Modifier.background(CinemaGold.brush)
                    else -> Modifier.background(Color.White.copy(alpha = 0.07f))
                }
            )
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else CardBorder, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (focused || active) CinemaGold.Ink else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

private fun scheduledOrUnknown(event: SportsEvent) =
    event.status == SportsEventStatus.SCHEDULED || event.status == SportsEventStatus.UNKNOWN

/** Leaderboard / race results / sets / fight card. Each row can take focus so the remote scrolls it. */
@Composable
private fun ResultsTable(results: SportsResults) {
    // First column narrow (position), second flexible (name), the rest fixed.
    val nameColumn = if (results.columns.firstOrNull() in setOf("Pos")) 1 else 0
    Column(
        Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(results.title, color = CinemaGold.Base, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            Text(results.source, color = Muted, fontSize = 11.sp)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            results.columns.forEachIndexed { index, col ->
                Text(col, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = resultCell(index, nameColumn, col))
            }
        }
        results.rows.forEach { row ->
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (focused) CardFocused else if (row.highlight) CinemaGold.Base.copy(alpha = 0.08f) else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.cells.forEachIndexed { index, cell ->
                    Text(cell, color = if (row.highlight && index == nameColumn) CinemaGold.Soft else Color.White,
                        fontSize = 14.sp, fontWeight = if (index == nameColumn) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = resultCell(index, nameColumn, results.columns.getOrNull(index).orEmpty()))
                }
            }
        }
    }
}

private val narrowResultColumn = Regex("""^(R\d|S\d|No\.|Pts|PTS|W|To par)$""")

private fun RowScope.resultCell(index: Int, nameColumn: Int, column: String): Modifier = when {
    index == nameColumn -> Modifier.weight(1f).padding(end = 8.dp)
    index < nameColumn -> Modifier.width(44.dp)
    narrowResultColumn.matches(column) -> Modifier.width(50.dp).padding(start = 4.dp)
    column.startsWith("Q") -> Modifier.width(74.dp).padding(start = 4.dp)
    else -> Modifier.width(110.dp).padding(start = 6.dp)
}

/** One team's stat leaders: category, then each leader with their numbers. */
@Composable
private fun LeadersTable(team: SportsTeamLeaders) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        team.categories.forEach { category ->
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.fillMaxWidth()
                    .clip(shape)
                    .background(if (focused) CardFocused else Card)
                    .border(if (focused) 2.dp else 1.dp, if (focused) CinemaGold.Soft else CardBorder, shape)
                    // Focusable so the remote can scroll through long lists.
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(category.title.uppercase(), color = CinemaGold.Base, fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                category.leaders.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            line.name + (line.position?.let { "  $it" } ?: ""),
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f),
                        )
                        Text(line.value, color = Soft, fontSize = 14.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.55f))
                    }
                }
            }
        }
    }
}

private val StatNameWidth = 190.dp
private val StatColumnWidth = 52.dp

/** One stat table. Each player row can take focus so the remote scrolls through long tables. */
@Composable
private fun StatTable(group: SportsStatGroup) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.title?.uppercase() ?: "PLAYERS", color = CinemaGold.Base, fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp,
                modifier = Modifier.widthIn(min = StatNameWidth, max = StatNameWidth),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            group.labels.forEach { label ->
                Text(label, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = StatColumnWidth, max = StatColumnWidth), maxLines = 1)
            }
        }
        group.players.forEach { player -> PlayerStatRow(player, group.labels.size) }
        if (group.totals.isNotEmpty()) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                Text("Team", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = StatNameWidth, max = StatNameWidth))
                group.totals.forEach { value ->
                    Text(value, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = StatColumnWidth, max = StatColumnWidth), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PlayerStatRow(player: SportsPlayerLine, columns: Int) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (focused) CardFocused else Color.Transparent)
            .drawBehind { if (focused) drawRect(CinemaGold.Base, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val label = buildString {
            append(player.name)
            player.position?.let { append("  ").append(it) }
        }
        Text(
            label, color = Color.White, fontSize = 14.sp,
            fontWeight = if (player.starter) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(min = StatNameWidth, max = StatNameWidth),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        player.stats.take(columns).forEach { value ->
            Text(value, color = Color.White, fontSize = 14.sp,
                modifier = Modifier.widthIn(min = StatColumnWidth, max = StatColumnWidth), maxLines = 1)
        }
    }
}

/** One conference or division table. Press OK on a team to follow it. */
@Composable
private fun StandingsTable(league: SportsLeague, group: SportsStandingsGroup, prefs: SportsPrefs) {
    Column(
        Modifier.fillMaxWidth()
            .background(Card, RoundedCornerShape(18.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(group.name.uppercase(), color = CinemaGold.Base, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.6.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            group.columns.forEach { col ->
                Text(col, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
        group.entries.forEachIndexed { index, entry ->
            val key = "${league.name}:${(entry.abbreviation ?: entry.teamName).uppercase()}"
            val fav = key in prefs.favoriteTeams
            var focused by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth()
                    .background(if (focused) CardFocused else if (fav) CinemaGold.Base.copy(alpha = 0.07f) else Color.Transparent)
                    .drawBehind { if (focused) drawRect(CinemaGold.Base, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { SportsPreferences.toggleFavoriteKey(key) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${index + 1}", color = Muted, fontSize = 13.sp, modifier = Modifier.width(30.dp))
                Text(if (fav) "★" else "☆", color = if (fav) CinemaGold.Base else Muted, fontSize = 15.sp, modifier = Modifier.width(26.dp))
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    entry.logoUrl?.let { url ->
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(entry.teamName, color = if (fav) CinemaGold.Soft else Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                entry.values.forEach { value ->
                    Text(value, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

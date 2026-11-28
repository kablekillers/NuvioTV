package com.nuvio.tv.livetv

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.livetv.player.AddonStreamSource
import com.nuvio.tv.ui.screens.sports.SportsAddonCatalogs
import com.nuvio.tv.ui.screens.sports.SportsAddonPriority
import com.nuvio.tv.ui.screens.sports.SportsLiveTv
import com.nuvio.tv.ui.screens.stream.LiveLinkChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One add-on link for a channel, small enough to keep on disk. */
@Serializable
data class KkChannelSource(val addonBaseUrl: String, val addonName: String, val type: String, val id: String, val name: String)

/** The last channel list kept on disk: which add-ons it came from and when. */
@Serializable
private data class KkSavedChannels(val key: String, val at: Long, val channels: List<KkLiveChannel>)

/** One US channel with every add-on that carries it, best add-on first. */
@Serializable
data class KkLiveChannel(
    val slug: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val sources: List<KkChannelSource>,
)

/**
 * Kable Killers Live TV channels (same as Kable Killers Mobile): every US / USA channel in the
 * installed add-ons' live TV lists, merged across add-ons. Live TV reads them as a playlist
 * served on this device ([PLAYLIST_URL]); a channel's link is found through the add-ons when
 * it's played, so add-on headers are kept and dead links are skipped.
 */
@Singleton
class KkLiveChannels @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val streamRepository: StreamRepository,
) {
    /** Set by [KkLiveServer]: where the last channel list is kept, so channels play straight after a restart. */
    var save: ((String) -> Unit)? = null
    var load: (() -> String?)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cache: Triple<String, Long, List<KkLiveChannel>>? = null
    private val requests = Semaphore(8)

    // ---- Which lists and which channels ----

    private suspend fun activeAddons(): List<Addon> = addonRepository.getInstalledAddons().first().filter { it.enabled }

    fun addonsKey(addons: List<Addon>): String =
        LINEUP_VERSION + addons.filter { it.enabled }.map { it.baseUrl }.sorted().joinToString(",")

    private fun liveCatalogs(addon: Addon): List<CatalogDescriptor> =
        addon.catalogs.filter { catalog ->
            catalog.apiType.lowercase() in liveTypes &&
                !notChannels.containsMatchIn("${catalog.id} ${catalog.name}") &&
                addon.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.apiType in it.types) } &&
                catalog.extra.none { it.isRequired && it.name !in setOf("genre", "skip") }
        }.take(16)

    private suspend fun pages(addon: Addon, catalog: CatalogDescriptor, genre: String?, maxPages: Int): List<MetaPreview> {
        val canPage = catalog.extra.any { it.name == "skip" }
        val skipRequired = catalog.extra.any { it.name == "skip" && it.isRequired }
        val items = mutableListOf<MetaPreview>()
        var skip: Int? = if (skipRequired) 0 else null
        repeat(if (canPage) maxPages else 1) {
            val page = requests.withPermit {
                withTimeoutOrNull(15_000) {
                    try {
                        val response = catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            extraArgs = buildMap {
                                genre?.let { put("genre", it) }
                                skip?.let { put("skip", it.toString()) }
                            },
                        ).first { it !is NetworkResult.Loading }
                        (response as? NetworkResult.Success)?.data?.items
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            } ?: return items
            items += page
            if (page.size < 20) return items
            skip = (skip ?: 0) + page.size
        }
        return items
    }

    private class Found(val addon: Addon, val item: MetaPreview, val name: String, val group: String)

    private suspend fun sweep(addons: List<Addon>): List<Found> = coroutineScope {
        addons.flatMap { addon ->
            val addonIsUs = usAddon.containsMatchIn("${addon.id} ${addon.name} ${addon.displayName}") &&
                !otherCountry.containsMatchIn(addon.name)
            liveCatalogs(addon).map { catalog ->
                async {
                    val catalogText = "${catalog.id} ${catalog.name}"
                    if (otherCountry.containsMatchIn(catalog.name) && !usLabel.containsMatchIn(catalog.name)) return@async emptyList()
                    val catalogIsUs = usLabel.containsMatchIn(catalogText)
                    val genreExtra = catalog.extra.firstOrNull { it.name == "genre" }
                    val usGenres = genreExtra?.options.orEmpty().filter { usLabel.containsMatchIn(it) && !otherCountry.containsMatchIn(it) }
                    // Lists split by country: only the US ones. Split by kind (News, Sports…): each one.
                    val asks: List<Pair<String?, Boolean>> = when {
                        usGenres.isNotEmpty() -> usGenres.take(4).map { it to true }
                        genreExtra?.isRequired == true -> genreExtra.options.orEmpty()
                            .filterNot { otherCountry.containsMatchIn(it) }
                            .take(8).map { it to false }
                        else -> listOf(null to false)
                    }
                    asks.flatMap { (genre, genreIsUs) ->
                        pages(addon, catalog, genre, maxPages = 6)
                            .filter { SportsAddonCatalogs.supportsStream(addon, it.apiType, it.id) }
                            .mapNotNull { item ->
                                val (name, nameSaysUs) = clean(item.name) ?: return@mapNotNull null
                                if (otherCountry.containsMatchIn(name)) return@mapNotNull null
                                // Only the classic cable lineup is kept.
                                val core = KkCoreLineup.match(name) ?: return@mapNotNull null
                                val itemGenresUs = item.genres.any { usLabel.containsMatchIn(it) && !otherCountry.containsMatchIn(it) }
                                val itemGenresForeign = item.genres.any { otherCountry.containsMatchIn(it) } && !itemGenresUs
                                // A feed counts as US only with real evidence (its name, genres, list or
                                // add-on says US); an unlabelled list is no longer enough. Feeds whose
                                // name or description reads Spanish/foreign are left out either way.
                                val text = "${item.name} ${item.description.orEmpty()}"
                                if (foreignText.containsMatchIn(text)) return@mapNotNull null
                                val isUs = !itemGenresForeign &&
                                    (nameSaysUs || itemGenresUs || genreIsUs || catalogIsUs || addonIsUs)
                                if (!isUs) return@mapNotNull null
                                Found(addon, item, core.name, core.group)
                            }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private fun rank(addon: Addon) = SportsAddonPriority.rank(addon.displayName, addon.name, addon.id)

    private val refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    @Volatile private var refreshing = false

    /**
     * The channel list. A list from the same add-ons is used straight away (from memory, or from
     * disk after a restart) and, when it's older than [TTL_MILLIS], scanned again in the background,
     * so Live TV never waits for the full add-on scan except the very first time.
     */
    suspend fun channels(force: Boolean = false): List<KkLiveChannel> = withContext(Dispatchers.Default) {
        val addons = activeAddons()
        val key = addonsKey(addons)
        if (!force) {
            val known = lock.withLock { cache?.takeIf { it.first == key } } ?: savedFor(key)
            if (known != null) {
                if (System.currentTimeMillis() - known.second >= TTL_MILLIS) refreshInBackground()
                return@withContext known.third
            }
        }
        scan(addons, key)
    }

    private fun refreshInBackground() {
        if (refreshing) return
        refreshing = true
        refreshScope.launch {
            try {
                val addons = activeAddons()
                scan(addons, addonsKey(addons))
            } catch (_: Exception) {
            } finally {
                refreshing = false
            }
        }
    }

    /** The saved list if it came from these add-ons. */
    private suspend fun savedFor(key: String): Triple<String, Long, List<KkLiveChannel>>? {
        val saved = runCatching { load?.invoke()?.let { json.decodeFromString<KkSavedChannels>(it) } }.getOrNull()
            ?: return null
        if (saved.key != key || saved.channels.isEmpty()) return null
        val entry = Triple(saved.key, saved.at, saved.channels)
        lock.withLock { if (cache == null || cache!!.second < saved.at) cache = entry }
        return entry
    }

    private var running: kotlinx.coroutines.Deferred<List<KkLiveChannel>>? = null
    private var runningKey: String? = null
    /** Told when a scan finished with channels (the server refreshes Live TV's playlist). */
    var onScanned: (() -> Unit)? = null

    /** One scan at a time: callers asking while one runs for the same add-ons wait for it. */
    private suspend fun scan(addons: List<Addon>, key: String): List<KkLiveChannel> {
        val job = lock.withLock {
            running?.takeIf { it.isActive && runningKey == key }
                ?: refreshScope.async { scanNow(addons, key) }.also { running = it; runningKey = key }
        }
        return job.await()
    }

    /** True when a channel list for the current add-ons is in memory or on disk. */
    private suspend fun haveListFor(key: String) = lock.withLock { cache?.first == key } || savedFor(key) != null

    /** The lineup with no links yet, so Live TV shows channels at once while the first scan runs. */
    private fun lineupOnly(): List<KkLiveChannel> = KkCoreLineup.channels.map {
        KkLiveChannel(slug = slugOfName(it.name), name = it.name, group = it.group, sources = emptyList())
    }

    /** The scan of every add-on's live TV lists for the lineup's channels. */
    private suspend fun scanNow(addons: List<Addon>, key: String): List<KkLiveChannel> {
        val found = sweep(addons)
        val channels = found.groupBy { mergeKey(it.name) }
            .filterKeys { it.length >= 2 }
            .map { (_, list) ->
                val ordered = list.sortedBy { rank(it.addon) }
                val first = ordered.first()
                KkLiveChannel(
                    slug = "",
                    name = first.name,
                    group = list.groupingBy { it.group }.eachCount().maxByOrNull { it.value }?.key ?: first.group,
                    logo = ordered.firstNotNullOfOrNull { it.item.logo?.takeIf(String::isNotBlank) ?: it.item.poster?.takeIf(String::isNotBlank) },
                    sources = ordered.map { KkChannelSource(it.addon.baseUrl, it.addon.displayName, it.item.apiType, it.item.id, it.item.name) }
                        .distinctBy { Triple(it.addonBaseUrl, it.type, it.id) },
                )
            }
            .sortedBy { c -> KkCoreLineup.channels.indexOfFirst { it.name == c.name }.let { if (it < 0) Int.MAX_VALUE else it } }
        // Slugs that stay the same from one refresh to the next (Live TV keys favorites by link).
        val used = HashSet<String>()
        val withSlugs = channels.map { channel ->
            var slug = slugOfName(channel.name)
            var n = 2
            while (!used.add(slug)) slug = slugOfName(channel.name) + "-" + n++
            channel.copy(slug = slug)
        }
        if (withSlugs.isNotEmpty()) {
            val now = System.currentTimeMillis()
            lock.withLock { cache = Triple(key, now, withSlugs) }
            runCatching { save?.invoke(json.encodeToString(KkSavedChannels(key, now, withSlugs))) }
            runCatching { onScanned?.invoke() }
        }
        // A sweep that came back empty (network hiccup): the last good list.
        return withSlugs.ifEmpty { cachedOrSaved().orEmpty() }
    }

    private suspend fun cachedOrSaved(): List<KkLiveChannel>? {
        lock.withLock { cache?.third }?.let { return it }
        val saved = runCatching { load?.invoke()?.let { json.decodeFromString<KkSavedChannels>(it).channels } }.getOrNull()
        if (!saved.isNullOrEmpty()) lock.withLock { if (cache == null) cache = Triple("", 0L, saved) }
        return saved
    }

    /** The playlist Live TV reads. Fails when nothing was found, so Live TV tries again later. */
    suspend fun playlist(force: Boolean = false): String {
        val addons = activeAddons()
        val key = addonsKey(addons)
        // First time with these add-ons: the lineup right away, links found in the background.
        val list = if (!force && !haveListFor(key)) {
            refreshInBackground()
            lineupOnly()
        } else {
            channels(force)
        }
        if (list.isEmpty()) error("No US channels found in your add-ons yet")
        return buildString {
            // Free daily US TV guide (XMLTV); Live TV matches it to channels by name.
            append("#EXTM3U x-tvg-url=\"").append(GUIDE_URL).append("\"\n")
            list.forEach { c ->
                val name = c.name.replace(",", " ").replace("\"", "'")
                append("#EXTINF:-1 tvg-id=\"kk.").append(c.slug).append("\" tvg-name=\"").append(name).append('"')
                c.logo?.let { append(" tvg-logo=\"").append(it.replace("\"", "%22")).append('"') }
                append(" group-title=\"").append(c.group).append("\",").append(name).append('\n')
                append(channelUrl(c.slug)).append('\n')
            }
        }
    }

    /**
     * Playable links for the channel: asks the add-ons that carry it (best first), skips links
     * that are clearly dead, working ones first.
     */
    suspend fun resolve(slug: String): List<AddonStreamSource> = withContext(Dispatchers.Default) {
        val addonsNow = activeAddons()
        val keyNow = addonsKey(addonsNow)
        val known = if (haveListFor(keyNow)) channels() else scan(addonsNow, keyNow)
        val channel = known.firstOrNull { it.slug == slug && it.sources.isNotEmpty() }
            ?: return@withContext emptyList()
        val addons = activeAddons().associateBy { it.baseUrl.trimEnd('/') }
        val candidates = coroutineScope {
            channel.sources.distinctBy { it.type to it.id }.take(6).map { source ->
                async {
                    val addon = addons[source.addonBaseUrl.trimEnd('/')] ?: return@async emptyList()
                    val result = withTimeoutOrNull(20_000) {
                        runCatching { streamRepository.getStreamsFromAddon(addon, source.type, source.id) }
                            .getOrElse { if (it is CancellationException) throw it; null }
                    }
                    (result as? NetworkResult.Success)?.data.orEmpty().mapNotNull { stream ->
                        val url = stream.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@mapNotNull null
                        if (stream.infoHash != null || stream.externalUrl != null) return@mapNotNull null
                        rank(addon) to AddonStreamSource(
                            url = url,
                            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
                            label = "${addon.displayName} · ${stream.name ?: stream.title ?: "Source"}",
                            qualityValue = stream.qualityValue,
                        )
                    }
                }
            }.awaitAll().flatten()
        }.sortedBy { it.first }.map { it.second }.distinctBy { it.url }
        if (candidates.isEmpty()) return@withContext emptyList()
        // Check the first few quickly; ones that answer go first (unknown counts as fine).
        val checked = candidates.take(5).map { candidate ->
            candidate to (withTimeoutOrNull(6_000) { runCatching { LiveLinkChecker.probeOnce(candidate.url, candidate.headers) }.getOrNull() } != false)
        }
        val working = checked.filter { it.second }.map { it.first }
        (working + checked.filterNot { it.second }.map { it.first } + candidates.drop(5)).ifEmpty { candidates }
    }

    companion object {
        const val PORT = 38734
        const val BASE = "http://127.0.0.1:$PORT/kk"
        const val PLAYLIST_URL = "$BASE/playlist.m3u"
        const val PLAYLIST_NAME = "Kable Killers"
        private const val CHANNEL_PREFIX = "$BASE/ch/"
        /** How old the channel list can get before it's scanned again in the background. */
        private const val TTL_MILLIS = 6 * 60 * 60_000L
        /** Bumped when the lineup rules change, so lists saved under the old rules are rebuilt. */
        private const val LINEUP_VERSION = "core2|"
        /** epgshare01's US guide: rebuilt daily, about four days ahead. */
        const val GUIDE_URL = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz"

        fun channelUrl(slug: String) = CHANNEL_PREFIX + slug

        /** The channel a playlist link stands for, or null for any other link. */
        fun slugOf(url: String?): String? =
            if (url != null && url.startsWith(CHANNEL_PREFIX)) url.removePrefix(CHANNEL_PREFIX).substringBefore('?').substringBefore('#').ifBlank { null } else null

        private val liveTypes = setOf("tv", "channel", "channels", "livetv", "live-tv", "live_tv", "iptv")
        private val notChannels = Regex("""replays?|highlights?|recaps?|\bvod\b|movies?\b|series|shows?\b|events?|schedule|matches|fixtures|ppv|radio""", RegexOption.IGNORE_CASE)
        private val usLabel = Regex("""\b(us|usa|u\.s\.a?\.?|united states|america|american)\b""", RegexOption.IGNORE_CASE)
        private val usAddon = Regex("""us\s*-?\s*tv|usa\s*-?\s*tv|\busa?\b|united states|america""", RegexOption.IGNORE_CASE)
        private val otherCountry = Regex(
            """\b(uk|gb|united kingdom|canada|ca|mexico|méxico|mx|latam|latino|brasil|brazil|argentina|colombia|chile|peru|spain|españa|espana|france|italia|italy|germany|deutschland|de|portugal|india|pakistan|arab|arabic|mena|africa|dstv|australia|au|nz|ireland|nederland|netherlands|turkey|türkiye|poland|russia|philippines|caribbean|caribe|español|espanol|spanish|deportes)\b""",
            RegexOption.IGNORE_CASE,
        )
        /** Words that mark a Spanish-language or other-country feed of a US channel name. */
        private val foreignText = Regex(
            """\b(en vivo|en directo|noticias|canal|latin|latino|latinoam[eé]rica|hispano|universo|telemundo|univision|deportes|español|espanol|portugu[eê]s|brasil|méxico|mexico|argentina|colombia|chile|per[uú]|venezuela|ecuador|españa|espana|france|français|deutsch|italia|polska|türk|arab|العربية|india|hindi|uk|canada)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val usTags = setOf("us", "usa", "u.s.", "u.s.a.", "am", "us/ca")
        private val neutralTags = setOf("en", "eng", "english", "na", "4k", "uhd", "fhd", "hd", "sd", "hevc", "vip", "24 7", "247", "live", "tv")
        private val tagPrefix = Regex("""^\s*[\[(]?([A-Za-z0-9 ./]{1,8})[\])]?\s*[|:]\s*(.+)$""")
        private val dashPrefix = Regex("""^\s*([A-Za-z]{2,3})\s+-\s+(.+)$""")
        private val usSuffix = Regex("""[\s\-(\[]+(us|usa)[)\]]?\s*$""", RegexOption.IGNORE_CASE)
        private val quality = Regex("""\b(hd|fhd|uhd|sd|4k|8k|hevc|h265|1080p?|720p?|backup|alt|feed|live|vip|raw|\d+fps)\b""", RegexOption.IGNORE_CASE)

        /** The name without its region tag and quality words, and whether the name itself says US. */
        private fun clean(raw: String): Pair<String, Boolean>? {
            var name = raw.trim()
            var saysUs = false
            tagPrefix.find(name)?.let { m ->
                val tag = m.groupValues[1].trim().lowercase()
                when {
                    tag in usTags -> { saysUs = true; name = m.groupValues[2] }
                    tag in neutralTags -> name = m.groupValues[2]
                    else -> return null
                }
            }
            dashPrefix.find(name)?.let { m ->
                val tag = m.groupValues[1].lowercase()
                if (tag in usTags) { saysUs = true; name = m.groupValues[2] }
            }
            if (usSuffix.containsMatchIn(name)) { saysUs = true; name = name.replace(usSuffix, "") }
            name = name.replace(quality, " ").replace(Regex("""[\[\](){}|]+"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '.')
            if (name.length < 2) return null
            return name to saysUs
        }

        private fun mergeKey(name: String) = name.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]+"), "")

        private fun slugOfName(name: String) = name.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "channel" }

        private val groupOrder = listOf("Local", "Sports", "News", "Entertainment", "Movies", "Kids", "Music")

        private val groups: List<Pair<String, Regex>> = listOf(
            "News" to Regex("""\b(news|cnn|msnbc|cnbc|fox business|bloomberg|newsmax|newsnation|c-?span|weather|hln|abc news|cbs news|nbc news)\b""", RegexOption.IGNORE_CASE),
            "Sports" to Regex("""\b(sports?|espn\w*|fs1|fs2|nfl|nba|mlb|nhl|golf|tennis|redzone|acc ?network|sec ?network|big ten|btn|bein|marquee|yes network|sny|nesn|msg|tnt|tbs|trutv|olympic|ufc|wwe)\b""", RegexOption.IGNORE_CASE),
            "Kids" to Regex("""\b(disney|nick\w*|cartoon|boomerang|pbs kids|baby|kids|universal kids|discovery family|nat geo kids)\b""", RegexOption.IGNORE_CASE),
            "Movies" to Regex("""\b(hbo|cinemax|showtime|starz|encore|mgm|epix|tcm|amc|ifc|sundance|hallmark|lifetime movie|fxm|flix|movies?|cinema|paramount network)\b""", RegexOption.IGNORE_CASE),
            "Music" to Regex("""\b(mtv\w*|vh1|cmt|bet|music|revolt|axs)\b""", RegexOption.IGNORE_CASE),
            "Local" to Regex("""^\s*(abc|cbs|nbc|fox|cw|pbs|my ?network|telemundo|univision)\b(\s*\d+)?(\s+[A-Za-z .-]+)?$""", RegexOption.IGNORE_CASE),
            // Station call letters (WABC, KTLA).
            "Local" to Regex("""\b[KW][A-Z]{2,3}(-?(TV|DT|HD))?\b"""),
        )

        private fun groupOf(name: String, genres: List<String>, catalogGenre: String?): String {
            val local = groups.filter { it.first == "Local" }.any { it.second.containsMatchIn(name) }
            if (local && !Regex("""\b(sports?|news|business|kids)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "Local"
            if (SportsLiveTv.usChannelName(name) != null) return "Sports"
            groups.firstOrNull { (_, r) -> r.containsMatchIn(name) }?.let { return it.first }
            val labels = (genres + listOfNotNull(catalogGenre)).filterNot { usLabel.containsMatchIn(it) && it.length <= 14 }
            labels.forEach { label -> groups.firstOrNull { (_, r) -> r.containsMatchIn(label) }?.let { return it.first } }
            return "Entertainment"
        }
    }
}

/** One channel of the classic cable lineup and the names add-ons use for it. */
internal class KkCoreChannel(val name: String, val group: String, vararg aliases: String) {
    val keys: Set<String> = (aliases.toList() + name).map { kkKey(it) }.toSet()
}

internal fun kkKey(name: String) = name.lowercase().replace("&", "and").replace("+", "plus").replace(Regex("[^a-z0-9]+"), "")

/**
 * The Kable Killers lineup: the core channels of a normal cable package from about ten years
 * ago. Only these are kept from the add-ons, so the list stays short and loads fast.
 */
internal object KkCoreLineup {
    val channels: List<KkCoreChannel> = listOf(
        // Local networks
        KkCoreChannel("ABC", "Local", "ABC East", "ABC West"),
        KkCoreChannel("CBS", "Local", "CBS East", "CBS West"),
        KkCoreChannel("NBC", "Local", "NBC East", "NBC West"),
        KkCoreChannel("FOX", "Local", "FOX East", "FOX West", "FOX Network"),
        KkCoreChannel("PBS", "Local"),
        KkCoreChannel("The CW", "Local", "CW"),
        // Sports
        KkCoreChannel("ESPN", "Sports"),
        KkCoreChannel("ESPN2", "Sports", "ESPN 2"),
        KkCoreChannel("ESPNU", "Sports", "ESPN U"),
        KkCoreChannel("ESPNews", "Sports", "ESPN News"),
        KkCoreChannel("FS1", "Sports", "Fox Sports 1"),
        KkCoreChannel("FS2", "Sports", "Fox Sports 2"),
        KkCoreChannel("NFL Network", "Sports", "NFL Net"),
        KkCoreChannel("NFL RedZone", "Sports", "RedZone", "NFL Red Zone"),
        KkCoreChannel("NBA TV", "Sports"),
        KkCoreChannel("MLB Network", "Sports", "MLB Net"),
        KkCoreChannel("NHL Network", "Sports", "NHL Net"),
        KkCoreChannel("Golf Channel", "Sports", "Golf"),
        KkCoreChannel("Tennis Channel", "Sports", "Tennis"),
        KkCoreChannel("CBS Sports Network", "Sports", "CBS Sports", "CBSSN"),
        KkCoreChannel("SEC Network", "Sports", "SECN"),
        KkCoreChannel("Big Ten Network", "Sports", "BTN", "Big Ten"),
        KkCoreChannel("ACC Network", "Sports", "ACCN"),
        // News
        KkCoreChannel("CNN", "News", "CNN USA"),
        KkCoreChannel("HLN", "News", "Headline News"),
        KkCoreChannel("Fox News", "News", "Fox News Channel", "FNC"),
        KkCoreChannel("MSNBC", "News", "MS NOW"),
        KkCoreChannel("CNBC", "News"),
        KkCoreChannel("Fox Business", "News", "Fox Business Network", "FBN"),
        KkCoreChannel("Bloomberg", "News", "Bloomberg TV", "Bloomberg Television"),
        KkCoreChannel("The Weather Channel", "News", "Weather Channel", "TWC"),
        KkCoreChannel("C-SPAN", "News", "CSPAN", "C-SPAN 1"),
        // Entertainment
        KkCoreChannel("TBS", "Entertainment"),
        KkCoreChannel("TNT", "Entertainment"),
        KkCoreChannel("USA Network", "Entertainment", "USA"),
        KkCoreChannel("FX", "Entertainment"),
        KkCoreChannel("FXX", "Entertainment"),
        KkCoreChannel("AMC", "Entertainment"),
        KkCoreChannel("A&E", "Entertainment", "A and E", "AE", "A&E Network"),
        KkCoreChannel("Bravo", "Entertainment"),
        KkCoreChannel("E!", "Entertainment", "E Entertainment", "E! Entertainment"),
        KkCoreChannel("Syfy", "Entertainment", "Sci Fi"),
        KkCoreChannel("Comedy Central", "Entertainment"),
        KkCoreChannel("Paramount Network", "Entertainment", "Spike", "Spike TV"),
        KkCoreChannel("TLC", "Entertainment"),
        KkCoreChannel("Discovery Channel", "Entertainment", "Discovery"),
        KkCoreChannel("History", "Entertainment", "History Channel"),
        KkCoreChannel("Lifetime", "Entertainment"),
        KkCoreChannel("Hallmark Channel", "Entertainment", "Hallmark"),
        KkCoreChannel("Food Network", "Entertainment", "Food"),
        KkCoreChannel("HGTV", "Entertainment"),
        KkCoreChannel("Travel Channel", "Entertainment", "Travel"),
        KkCoreChannel("Animal Planet", "Entertainment"),
        KkCoreChannel("National Geographic", "Entertainment", "Nat Geo", "National Geographic Channel"),
        KkCoreChannel("truTV", "Entertainment", "Tru TV"),
        KkCoreChannel("Freeform", "Entertainment", "ABC Family"),
        KkCoreChannel("Investigation Discovery", "Entertainment", "ID", "Investigation Discovery ID"),
        KkCoreChannel("OWN", "Entertainment", "Oprah Winfrey Network"),
        KkCoreChannel("Oxygen", "Entertainment", "Oxygen True Crime"),
        KkCoreChannel("WE tv", "Entertainment", "WE"),
        KkCoreChannel("TV Land", "Entertainment"),
        KkCoreChannel("Game Show Network", "Entertainment", "GSN"),
        KkCoreChannel("Science Channel", "Entertainment", "Science"),
        KkCoreChannel("Sundance TV", "Entertainment", "SundanceTV", "Sundance"),
        KkCoreChannel("IFC", "Entertainment"),
        // Movies
        KkCoreChannel("HBO", "Movies", "HBO East"),
        KkCoreChannel("Cinemax", "Movies", "Cinemax East"),
        KkCoreChannel("Showtime", "Movies", "Showtime East", "SHO"),
        KkCoreChannel("Starz", "Movies", "Starz East"),
        KkCoreChannel("TCM", "Movies", "Turner Classic Movies"),
        KkCoreChannel("FXM", "Movies", "FX Movie Channel"),
        KkCoreChannel("Hallmark Movies & Mysteries", "Movies", "Hallmark Movies and Mysteries", "HMM"),
        KkCoreChannel("Lifetime Movies", "Movies", "LMN", "Lifetime Movie Network"),
        // Kids
        KkCoreChannel("Disney Channel", "Kids", "Disney", "Disney East"),
        KkCoreChannel("Disney Junior", "Kids", "Disney Jr"),
        KkCoreChannel("Disney XD", "Kids"),
        KkCoreChannel("Nickelodeon", "Kids", "Nick", "Nickelodeon East"),
        KkCoreChannel("Nick Jr.", "Kids", "Nick Jr", "Nick Junior"),
        KkCoreChannel("Nicktoons", "Kids"),
        KkCoreChannel("Cartoon Network", "Kids", "Cartoon Network East"),
        KkCoreChannel("Boomerang", "Kids"),
        KkCoreChannel("Discovery Family", "Kids"),
        // Music
        KkCoreChannel("MTV", "Music", "MTV East"),
        KkCoreChannel("MTV2", "Music", "MTV 2"),
        KkCoreChannel("VH1", "Music"),
        KkCoreChannel("CMT", "Music", "Country Music Television"),
        KkCoreChannel("BET", "Music", "Black Entertainment Television"),
    )

    private val byKey: Map<String, KkCoreChannel> = buildMap { channels.forEach { c -> c.keys.forEach { putIfAbsent(it, c) } } }
    private val suffixes = listOf("east", "west", "usa", "us", "hd", "channel", "network", "tv", "eastern", "pacific")
    /**
     * Local affiliates only: the network, then nothing, a channel number, a call sign (WABC, KTVU),
     * East/West, or a place name. "NBC Universo", "Fox Soccer Plus", "CBS Sports" and the like
     * are other channels, not the network.
     */
    private val localNetwork = Regex(
        """^\s*(abc|cbs|nbc|fox|pbs|cw)\b(?!\s*(news|sports?|business|kids|life|soul|weather|deportes|family|movies?|universo|soccer|nation|now|plus|español|espanol|latino|world|international|comedy|classics?|reality|music|crime|go\b|xtra|extra|drama|action|docs?|stream|sci|entertainment|cinema|channel|true|heroes|kids|nature|food|living|signature|bet|max|one\b))""",
        RegexOption.IGNORE_CASE,
    )

    /** The lineup channel a (cleaned) add-on name stands for, or null when it isn't in the lineup. */
    fun match(name: String): KkCoreChannel? {
        var key = kkKey(name)
        byKey[key]?.let { return it }
        // "ESPN HD", "TBS East", "History Channel US"…: drop trailing feed words one at a time.
        repeat(3) {
            val suffix = suffixes.firstOrNull { key.endsWith(it) && key.length > it.length + 1 } ?: return@repeat
            key = key.removeSuffix(suffix)
            byKey[key]?.let { return it }
        }
        // Local affiliates ("ABC 7 New York", "NBC (WNBC)") all count as that network.
        localNetwork.find(name)?.let { m -> return byKey[kkKey(m.groupValues[1])] }
        return null
    }

    fun groupRank(group: String) = listOf("Local", "Sports", "News", "Entertainment", "Movies", "Kids", "Music").indexOf(group)
}

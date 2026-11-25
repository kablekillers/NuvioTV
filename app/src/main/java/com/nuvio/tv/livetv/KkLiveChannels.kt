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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** One add-on link for a channel, small enough to keep on disk. */
@Serializable
data class KkChannelSource(val addonBaseUrl: String, val addonName: String, val type: String, val id: String, val name: String)

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

    fun addonsKey(addons: List<Addon>): String = addons.filter { it.enabled }.map { it.baseUrl }.sorted().joinToString(",")

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
                            .take(14).map { it to false }
                        else -> listOf(null to false)
                    }
                    asks.flatMap { (genre, genreIsUs) ->
                        pages(addon, catalog, genre, maxPages = 12)
                            .filter { SportsAddonCatalogs.supportsStream(addon, it.apiType, it.id) }
                            .mapNotNull { item ->
                                val (name, nameSaysUs) = clean(item.name) ?: return@mapNotNull null
                                if (otherCountry.containsMatchIn(name)) return@mapNotNull null
                                val known = SportsLiveTv.usChannelName(item.name)
                                val itemGenresUs = item.genres.any { usLabel.containsMatchIn(it) && !otherCountry.containsMatchIn(it) }
                                val itemGenresForeign = item.genres.any { otherCountry.containsMatchIn(it) } && !itemGenresUs
                                val isUs = !itemGenresForeign &&
                                    (nameSaysUs || known != null || itemGenresUs || genreIsUs || catalogIsUs || addonIsUs)
                                if (!isUs) return@mapNotNull null
                                val display = known ?: name
                                Found(addon, item, display, groupOf(display, item.genres, genre))
                            }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private fun rank(addon: Addon) = SportsAddonPriority.rank(addon.displayName, addon.name, addon.id)

    /** The channel list (cached for a while). */
    suspend fun channels(force: Boolean = false): List<KkLiveChannel> = withContext(Dispatchers.Default) {
        val addons = activeAddons()
        val key = addonsKey(addons)
        if (!force) lock.withLock {
            cache?.takeIf { it.first == key && System.currentTimeMillis() - it.second < TTL_MILLIS }?.third
        }?.let { return@withContext it }
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
            .sortedWith(compareBy({ groupOrder.indexOf(it.group).let { i -> if (i < 0) groupOrder.size else i } }, { it.name.lowercase() }))
        // Slugs that stay the same from one refresh to the next (Live TV keys favorites by link).
        val used = HashSet<String>()
        val withSlugs = channels.map { channel ->
            var slug = slugOfName(channel.name)
            var n = 2
            while (!used.add(slug)) slug = slugOfName(channel.name) + "-" + n++
            channel.copy(slug = slug)
        }
        if (withSlugs.isNotEmpty()) {
            lock.withLock { cache = Triple(key, System.currentTimeMillis(), withSlugs) }
            runCatching { save?.invoke(json.encodeToString(withSlugs)) }
        }
        // A sweep that came back empty (network hiccup): the last good list.
        withSlugs.ifEmpty { cachedOrSaved().orEmpty() }
    }

    private suspend fun cachedOrSaved(): List<KkLiveChannel>? {
        lock.withLock { cache?.third }?.let { return it }
        val saved = runCatching { load?.invoke()?.let { json.decodeFromString<List<KkLiveChannel>>(it) } }.getOrNull()
        if (!saved.isNullOrEmpty()) lock.withLock { if (cache == null) cache = Triple("", 0L, saved) }
        return saved
    }

    /** The playlist Live TV reads. Fails when nothing was found, so Live TV tries again later. */
    suspend fun playlist(force: Boolean = false): String {
        val list = channels(force)
        if (list.isEmpty()) error("No US channels found in your add-ons yet")
        return buildString {
            append("#EXTM3U\n")
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
        val channel = (cachedOrSaved() ?: channels()).firstOrNull { it.slug == slug }
            ?: channels(force = true).firstOrNull { it.slug == slug }
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
        private const val TTL_MILLIS = 20 * 60_000L

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

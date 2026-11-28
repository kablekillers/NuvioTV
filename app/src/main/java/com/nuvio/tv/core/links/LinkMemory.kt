package com.nuvio.tv.core.links

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How one link (an add-on's stream for a channel or game) has done. */
@Serializable
internal data class LinkRecord(
    /** "meta:<id>" (the channel/game opened) or "ch:<channel name>". */
    val key: String,
    val addon: String,
    /** The stream's name within the add-on ("" when only the add-on is known). */
    val stream: String,
    val lastOk: Long = 0L,
    val lastFail: Long = 0L,
    val okCount: Int = 0,
)

/**
 * Remember what works (same as Kable Killers Mobile): links that played 20+ seconds are listed
 * first next time with "Worked last time"; links that failed recently drop down.
 */
object LinkMemory {
    private const val MAX_RECORDS = 600
    private const val FAIL_WINDOW = 6 * 60 * 60_000L
    private const val OK_WINDOW = 14L * 24 * 60 * 60_000L
    private const val PREFS = "kk_link_memory"
    private val json = Json { ignoreUnknownKeys = true }
    private var records: MutableList<LinkRecord>? = null
    private var context: Context? = null

    fun init(appContext: Context) { context = appContext.applicationContext }

    @Synchronized
    private fun all(): MutableList<LinkRecord> = records ?: run {
        val raw = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("records", null)
        val loaded = runCatching { raw?.let { json.decodeFromString(ListSerializer(LinkRecord.serializer()), it) } }
            .getOrNull().orEmpty()
        loaded.toMutableList().also { records = it }
    }

    private fun persist(list: List<LinkRecord>) {
        runCatching {
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString("records", json.encodeToString(ListSerializer(LinkRecord.serializer()), list))?.apply()
        }
    }

    /** "US| NFL NETWORK [BK]" and "NFL Network HD" both become "nflnetwork". */
    fun channelKey(name: String): String? {
        val cleaned = name.lowercase()
            .replace(Regex("""^\s*[\[(]?[a-z0-9 ]{1,6}[\])]?\s*[|:]\s*"""), "")
            .replace(Regex("""\[[^\]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(hd|fhd|uhd|sd|4k|hevc|backup|alt|raw|live|us|usa)\b"""), " ")
            .replace("+", "plus")
            .replace(Regex("[^a-z0-9]"), "")
        return cleaned.takeIf { it.length >= 3 }?.let { "ch:$it" }
    }

    private fun streamName(label: String?) = label.orEmpty().lowercase().replace(Regex("\\s+"), " ").trim().take(80)

    private fun keysFor(metaId: String?, title: String?): List<String> =
        listOfNotNull(metaId?.takeIf { it.isNotBlank() }?.let { "meta:$it" }, title?.let(::channelKey)).distinct()

    fun recordSuccess(metaId: String?, title: String?, addon: String?, streamLabel: String?) =
        record(metaId, title, addon, streamLabel, ok = true)

    fun recordFailure(metaId: String?, title: String?, addon: String?, streamLabel: String?) =
        record(metaId, title, addon, streamLabel, ok = false)

    @Synchronized
    private fun record(metaId: String?, title: String?, addon: String?, streamLabel: String?, ok: Boolean) {
        val addonName = addon?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val stream = streamName(streamLabel)
        val time = System.currentTimeMillis()
        val list = all()
        keysFor(metaId, title).forEach { key ->
            val index = list.indexOfFirst { it.key == key && it.addon == addonName && it.stream == stream }
            val old = if (index >= 0) list.removeAt(index) else LinkRecord(key, addonName, stream)
            list += if (ok) old.copy(lastOk = time, okCount = old.okCount + 1) else old.copy(lastFail = time)
        }
        while (list.size > MAX_RECORDS) list.removeAt(0)
        persist(list.toList())
    }

    /** Higher = list earlier. +3 this exact link worked, +2 this add-on worked here, -3 failed recently. */
    @Synchronized
    fun score(metaId: String?, title: String?, addon: String?, streamLabel: String? = null): Int {
        val addonName = addon?.trim() ?: return 0
        val keys = keysFor(metaId, title)
        if (keys.isEmpty()) return 0
        val stream = streamName(streamLabel)
        val time = System.currentTimeMillis()
        var best = 0
        var failed = false
        all().forEach { r ->
            if (r.key !in keys || r.addon != addonName) return@forEach
            val worked = r.lastOk > 0 && time - r.lastOk < OK_WINDOW && r.lastOk >= r.lastFail
            if (worked) best = maxOf(best, if (streamLabel != null && r.stream == stream) 3 else 2)
            if (r.lastFail > r.lastOk && time - r.lastFail < FAIL_WINDOW &&
                (streamLabel == null || r.stream == stream)
            ) failed = true
        }
        return if (best > 0) best else if (failed) -3 else 0
    }

    fun workedBefore(metaId: String?, title: String?, addon: String?, streamLabel: String? = null): Boolean =
        score(metaId, title, addon, streamLabel) >= 2
}

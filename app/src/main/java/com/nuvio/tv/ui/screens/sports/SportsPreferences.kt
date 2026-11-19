package com.nuvio.tv.ui.screens.sports

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Device-local Sports Hub choices. Never synced, so profiles and cloud sync are untouched. */
data class SportsPrefs(
    /** "LEAGUE:ABBR" keys, e.g. "NFL:GB". */
    val favoriteTeams: Set<String> = emptySet(),
    /** Seconds the scores on screen lag behind, so they don't spoil a delayed stream. */
    val scoreDelaySeconds: Int = 0,
)

object SportsPreferences {
    val delayOptions = listOf(0, 30, 60, 120, 300)
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(SportsPrefs())
    val state: StateFlow<SportsPrefs> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("kk_sports_hub", Context.MODE_PRIVATE)
        prefs = p
        _state.value = runCatching {
            val json = JSONObject(p.getString("sports_prefs", null) ?: return@runCatching SportsPrefs())
            val teams = json.optJSONArray("favoriteTeams") ?: JSONArray()
            SportsPrefs(
                favoriteTeams = (0 until teams.length()).map { teams.getString(it) }.toSet(),
                scoreDelaySeconds = json.optInt("scoreDelaySeconds", 0),
            )
        }.getOrDefault(SportsPrefs())
    }

    private fun update(change: (SportsPrefs) -> SportsPrefs) {
        val next = change(_state.value)
        _state.value = next
        val json = JSONObject()
            .put("favoriteTeams", JSONArray(next.favoriteTeams.toList()))
            .put("scoreDelaySeconds", next.scoreDelaySeconds)
        prefs?.edit()?.putString("sports_prefs", json.toString())?.apply()
    }

    fun teamKey(league: SportsLeague, team: SportsCompetitor) = "${league.name}:${team.shortLabel.uppercase()}"

    fun toggleFavoriteKey(key: String) = update { p ->
        p.copy(favoriteTeams = if (key in p.favoriteTeams) p.favoriteTeams - key else p.favoriteTeams + key)
    }

    fun setScoreDelay(seconds: Int) = update { it.copy(scoreDelaySeconds = seconds.coerceIn(0, 600)) }

    fun favoriteLeagues(p: SportsPrefs = _state.value): Set<SportsLeague> =
        p.favoriteTeams.mapNotNull { key -> SportsLeague.entries.firstOrNull { it.name == key.substringBefore(':') } }.toSet()

    fun involvesFavorite(event: SportsEvent, p: SportsPrefs = _state.value): Boolean =
        event.competitors.any { "${event.league.name}:${it.shortLabel.uppercase()}" in p.favoriteTeams }
}

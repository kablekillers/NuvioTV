package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payloads mirror the shape of ESPN's public scoreboard JSON (trimmed to the fields we read). */
class EspnScoreboardParserTest {

    private val nflPayload = """
        {"events":[
          {"id":"401","name":"Buffalo Bills at Kansas City Chiefs","shortName":"BUF @ KC",
           "date":"2026-11-16T18:00Z",
           "links":[{"href":"https://www.espn.com/nfl/game/_/gameId/401"}],
           "competitions":[{
             "status":{"type":{"state":"in","name":"STATUS_IN_PROGRESS","shortDetail":"3rd 4:12"}},
             "venue":{"fullName":"GEHA Field at Arrowhead Stadium","address":{"city":"Kansas City","state":"MO"}},
             "broadcasts":[{"names":["CBS"]},{"names":["Paramount+"]}],
             "competitors":[
               {"homeAway":"home","score":"24","team":{"displayName":"Kansas City Chiefs","abbreviation":"KC"},"records":[{"summary":"8-2"}]},
               {"homeAway":"away","score":"17","team":{"displayName":"Buffalo Bills","abbreviation":"BUF"},"records":[{"summary":"7-3"}]}
             ]}]},
          {"id":"402","name":"Dallas Cowboys at Philadelphia Eagles","date":"2026-11-17T01:20Z",
           "competitions":[{
             "status":{"type":{"state":"pre","name":"STATUS_SCHEDULED","shortDetail":"11/16 - 8:20 PM EST"}},
             "geoBroadcasts":[{"media":{"shortName":"NBC"}}],
             "competitors":[
               {"homeAway":"home","score":"0","team":{"displayName":"Philadelphia Eagles","abbreviation":"PHI"}},
               {"homeAway":"away","score":"0","team":{"displayName":"Dallas Cowboys","abbreviation":"DAL"}}
             ]}]},
          {"id":"403","name":"Jets at Dolphins","date":"2026-11-16T14:00:00Z",
           "competitions":[{"status":{"type":{"state":"post","name":"STATUS_FINAL","shortDetail":"Final/OT"}},
             "competitors":[
               {"homeAway":"home","score":"20","winner":true,"team":{"displayName":"Miami Dolphins","abbreviation":"MIA"}},
               {"homeAway":"away","score":"17","winner":false,"team":{"displayName":"New York Jets","abbreviation":"NYJ"}}
             ]}]},
          {"id":"404","name":"Rained Out","competitions":[{"status":{"type":{"state":"pre","name":"STATUS_POSTPONED"}}}]},
          {"id":"","name":"Missing id is skipped"},
          {"id":"406"}
        ]}
    """.trimIndent()

    @Test fun parsesLiveGameWithScoresVenueAndBroadcasts() {
        val live = EspnSportsEventProvider.parseScoreboard(nflPayload, SportsLeague.NFL).first { it.id == "NFL:401" }
        assertEquals(SportsEventStatus.LIVE, live.status)
        assertEquals("3rd 4:12", live.statusDetail)
        assertEquals("BUF 17 – 24 KC", live.scoreLine())
        assertEquals("8-2", live.home?.record)
        assertEquals(listOf("CBS", "Paramount+"), live.broadcasts)
        assertEquals("GEHA Field at Arrowhead Stadium • Kansas City, MO", live.venue)
        assertEquals("https://www.espn.com/nfl/game/_/gameId/401", live.detailUrl)
        assertNull(live.authorizedPlaybackUrl)
    }

    @Test fun parsesDatesWithoutSecondsAndFallsBackToGeoBroadcasts() {
        val upcoming = EspnSportsEventProvider.parseScoreboard(nflPayload, SportsLeague.NFL).first { it.id == "NFL:402" }
        assertEquals(SportsEventStatus.SCHEDULED, upcoming.status)
        assertEquals(java.time.Instant.parse("2026-11-17T01:20:00Z").toEpochMilli(), upcoming.startTimeEpochMillis)
        assertEquals(listOf("NBC"), upcoming.broadcasts)
    }

    @Test fun finalGameMarksWinnerAndPostponedIsDetected() {
        val events = EspnSportsEventProvider.parseScoreboard(nflPayload, SportsLeague.NFL)
        val final = events.first { it.id == "NFL:403" }
        assertEquals(SportsEventStatus.FINAL, final.status)
        assertTrue(final.home!!.isWinner)
        assertEquals(SportsEventStatus.POSTPONED, events.first { it.id == "NFL:404" }.status)
    }

    @Test fun skipsEventsMissingRequiredFields() {
        val ids = EspnSportsEventProvider.parseScoreboard(nflPayload, SportsLeague.NFL).map { it.id }
        assertEquals(listOf("NFL:401", "NFL:402", "NFL:403", "NFL:404"), ids)
    }

    @Test fun malformedOrEmptyPayloadsNeverInventGames() {
        assertTrue(EspnSportsEventProvider.parseScoreboard("not json", SportsLeague.NBA).isEmpty())
        assertTrue(EspnSportsEventProvider.parseScoreboard("{}", SportsLeague.NBA).isEmpty())
        assertTrue(EspnSportsEventProvider.parseScoreboard("""{"events":"oops"}""", SportsLeague.NBA).isEmpty())
    }

    @Test fun ufcUsesMainEventFightersAndCardStatus() {
        val payload = """
            {"events":[{"id":"600","name":"UFC 330","date":"2026-11-15T23:00Z",
              "status":{"type":{"state":"in","name":"STATUS_IN_PROGRESS","shortDetail":"Prelims"}},
              "competitions":[
                {"status":{"type":{"state":"post"}},"competitors":[
                  {"athlete":{"displayName":"Prelim Fighter A"}},{"athlete":{"displayName":"Prelim Fighter B"}}]},
                {"status":{"type":{"state":"pre"}},"broadcasts":[{"names":["ESPN+ PPV"]}],"competitors":[
                  {"athlete":{"displayName":"Main Event Fighter A"}},{"athlete":{"displayName":"Main Event Fighter B"}}]}
              ]}]}
        """.trimIndent()
        val card = EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.UFC).single()
        assertEquals(SportsEventStatus.LIVE, card.status)
        assertEquals(listOf("Main Event Fighter A", "Main Event Fighter B"), card.competitors.map { it.name })
        assertEquals(listOf("ESPN+ PPV"), card.broadcasts)
        assertNull(card.scoreLine())
    }

    @Test fun scoreObjectsWithDisplayValueAreSupported() {
        val payload = """
            {"events":[{"id":"7","name":"A at B","competitions":[{"status":{"type":{"state":"post"}},"competitors":[
              {"homeAway":"home","score":{"displayValue":"3"},"team":{"displayName":"B","abbreviation":"B"}},
              {"homeAway":"away","score":{"displayValue":"2"},"team":{"displayName":"A","abbreviation":"A"}}]}]}]}
        """.trimIndent()
        assertEquals("A 2 – 3 B", EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.NHL).single().scoreLine())
    }
}

package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsScoreboardExpansionTest {
    @Test
    fun tennisUsesTheMatchStatusAndDoublesRosterNames() {
        val payload = """{"events":[{
            "id":"t1","name":"Tour Finals","status":{"type":{"state":"post"}},
            "groupings":[{"competitions":[{
                "id":"m1","date":"2026-11-20T18:00Z","status":{"type":{"state":"in"}},
                "competitors":[
                    {"homeAway":"away","roster":{"displayName":"Alex Reed / Robin Allen"},"linescores":[{"winner":true}]},
                    {"homeAway":"home","roster":{"displayName":"Gabriel Brown / Rafael Gomez"},"linescores":[{"winner":false}]}
                ]
            }]}]
        }]}"""
        val (tournament, match) = EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.ATP)
        assertEquals(SportsEventStatus.FINAL, tournament.status)
        assertEquals(SportsEventStatus.LIVE, match.status)
        assertEquals("ATP:t1:m1", match.id)
        assertEquals("Alex Reed / Robin Allen vs Gabriel Brown / Rafael Gomez", match.title)
        assertEquals("1", match.away?.score)
        assertEquals("0", match.home?.score)
    }

    @Test
    fun racingSessionsUseTheirOwnStatusAndMatchTheRightStream() {
        val payload = """{"events":[{
            "id":"r1","name":"Sponsored Las Vegas Grand Prix","shortName":"Las Vegas Grand Prix",
            "status":{"type":{"state":"post"}},
            "competitions":[
                {"id":"p1","date":"2026-11-20T00:30Z","type":{"abbreviation":"FP1"},"status":{"type":{"state":"post"}}},
                {"id":"q1","date":"2026-11-21T04:00Z","type":{"abbreviation":"Qual"},"status":{"type":{"state":"pre"}}},
                {"id":"g1","date":"2026-11-22T04:00Z","type":{"abbreviation":"Race"},"status":{"type":{"state":"pre"}}}
            ]
        }]}"""
        val (practice, qualifying, race) = EspnSportsEventProvider.parseScoreboard(payload, SportsLeague.FORMULA_1)
        assertEquals(SportsEventStatus.FINAL, practice.status)
        assertEquals(listOf(qualifying, race), SportsEventSections.upcoming(listOf(practice, qualifying, race)))
        assertTrue(SportsEventMatcher.score(practice, "Las Vegas Grand Prix Free Practice 1") > 0)
        assertEquals(0, SportsEventMatcher.score(race, "Las Vegas Grand Prix Free Practice 1"))
        assertEquals(0, SportsEventMatcher.score(qualifying, "Las Vegas Grand Prix"))
        assertTrue(SportsEventMatcher.score(race, "Las Vegas Grand Prix") > 0)
    }
}

package com.nuvio.tv.ui.screens.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes mirror ESPN's game summary `boxscore.players` for NBA (one table) and NFL (titled groups). */
class EspnBoxScoreParserTest {

    private val nba = """
        {"boxscore":{"players":[
          {"team":{"displayName":"Orlando Magic","abbreviation":"ORL","location":"Orlando"},"displayOrder":1,
           "statistics":[{"labels":["MIN","PTS","REB","AST"],
             "athletes":[
               {"athlete":{"displayName":"Franz Wagner","jersey":"22","position":{"abbreviation":"SF"}},"starter":true,"didNotPlay":false,"stats":["36","27","6","5"]},
               {"athlete":{"displayName":"Bench Guy"},"starter":false,"didNotPlay":true,"reason":"COACH'S DECISION","stats":[]},
               {"athlete":{"displayName":"Empty Line"},"starter":false,"didNotPlay":false,"stats":["","","",""]}
             ],
             "totals":["","104","44","22"]}]},
          {"team":{"displayName":"Boston Celtics","abbreviation":"BOS"},"displayOrder":2,
           "statistics":[{"labels":["MIN","PTS","REB","AST"],
             "athletes":[{"athlete":{"displayName":"Jayson Tatum"},"starter":true,"didNotPlay":false,"stats":["38","31","9","7"]}]}]}
        ]}}
    """.trimIndent()

    @Test fun parsesNbaTablesAndSkipsPlayersWhoDidNotPlay() {
        val box = EspnSportsEventProvider.parseBoxScore(nba, "NBA:1")!!
        assertEquals(listOf("ORL", "BOS"), box.teams.map { it.shortLabel })
        val magic = box.teams.first().groups.single()
        assertEquals(listOf("MIN", "PTS", "REB", "AST"), magic.labels)
        assertEquals(listOf("Franz Wagner"), magic.players.map { it.name })
        assertEquals("SF", magic.players.single().position)
        assertTrue(magic.players.single().starter)
        assertEquals(listOf("", "104", "44", "22"), magic.totals)
        assertTrue(box.teams[1].groups.single().totals.isEmpty())
    }

    @Test fun nflGroupTitlesDropTheTeamName() {
        val nfl = """
            {"boxscore":{"players":[{"team":{"displayName":"Washington Commanders","location":"Washington","abbreviation":"WSH"},
              "statistics":[
                {"name":"passing","text":"Washington Passing","labels":["C/ATT","YDS","TD"],
                 "athletes":[{"athlete":{"displayName":"Jayden Daniels","jersey":"5"},"stats":["23/31","254","2"]}]},
                {"name":"kickReturns","labels":["NO","YDS"],
                 "athletes":[{"athlete":{"displayName":"Returner"},"stats":["2","48"]}]},
                {"name":"punting","text":"Washington Punting","labels":["NO","YDS"],"athletes":[]}
              ]}]}}
        """.trimIndent()
        val groups = EspnSportsEventProvider.parseBoxScore(nfl, "NFL:2")!!.teams.single().groups
        assertEquals(listOf("Passing", "Kick Returns"), groups.map { it.title })
        assertEquals(listOf("23/31", "254", "2"), groups.first().players.single().stats)
    }

    @Test fun missingOrEmptyBoxScoresReturnNull() {
        assertNull(EspnSportsEventProvider.parseBoxScore("{}", "NBA:3"))
        assertNull(EspnSportsEventProvider.parseBoxScore("nope", "NBA:3"))
        assertNull(EspnSportsEventProvider.parseBoxScore("""{"boxscore":{"players":[]}}""", "NBA:3"))
    }
}

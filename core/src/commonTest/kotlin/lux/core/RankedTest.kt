package lux.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankedTest {
    @Test fun divisionsRunBronzeToChampion() {
        assertEquals("Bronze III", Ranked.Division.of(0.0).name)
        assertEquals("Bronze III", Ranked.Division.of(749.0).name)
        assertEquals("Bronze II", Ranked.Division.of(750.0).name)
        assertEquals("Silver II", Ranked.Division.of(Ranked.START).name)
        assertEquals("Gold III", Ranked.Division.of(1000.0).name)
        assertEquals("Diamond I", Ranked.Division.of(1449.0).name)
        assertEquals("Champion", Ranked.Division.of(1450.0).name)
        assertEquals("Champion", Ranked.Division.of(3000.0).name)
        for (i in 0..15) assertEquals(i, Ranked.Division.ofIndex(i).index)
        // Players see stars: more is better.
        assertEquals(1, Ranked.starsOf("Gold III")); assertEquals(2, Ranked.starsOf("Gold II")); assertEquals(3, Ranked.starsOf("Gold I"))
        assertEquals(0, Ranked.starsOf("Champion")); assertEquals("Gold", Ranked.tierWord("Gold II"))
    }

    @Test fun placingAboveSomeoneGainsAndChangesBalance() {
        val r = Ranked.rate(listOf(1000.0, 1000.0, 1000.0, 1000.0), listOf(2, 1, 4, 3), listOf(20, 20, 20, 20))
        assertTrue(r[1] > r[0] && r[0] > 1000.0, "1st gains most, 2nd still gains")
        assertTrue(r[2] < r[3] && r[3] < 1000.0, "3rd and 4th lose")
        assertEquals(4000.0, r.sum(), 1e-6)
    }

    @Test fun beatingAStrongerPlayerGainsMore() {
        val up = Ranked.rate(listOf(900.0, 1200.0), listOf(1, 2), listOf(20, 20))[0] - 900.0
        val down = Ranked.rate(listOf(1200.0, 900.0), listOf(1, 2), listOf(20, 20))[0] - 1200.0
        assertTrue(up > down * 2, "upset win $up vs expected win $down")
    }

    @Test fun placementHidesTheTierThenRevealsIt() {
        var r = Ranked.Record()
        repeat(Ranked.PLACEMENT_GAMES - 1) { r = Ranked.afterGame(r, r.rating + 20, won = true) }
        assertNull(r.division)
        r = Ranked.afterGame(r, r.rating + 20, won = true)
        assertTrue(r.placed)
        assertEquals(Ranked.Division.of(r.rating), r.division)
    }

    @Test fun aNewTierIsShieldedForThreeGames() {
        // Placed in Silver, then promoted to Gold.
        var r = Ranked.Record(rating = 990.0, games = 20, floorTier = 1)
        r = Ranked.afterGame(r, 1005.0, won = true)
        assertEquals("Gold III", r.division!!.name)
        assertEquals(Ranked.SHIELD_GAMES, r.shield)
        // Three losses below the line: still shown as Gold III.
        repeat(Ranked.SHIELD_GAMES) {
            r = Ranked.afterGame(r, 985.0, won = false)
            assertEquals("Gold III", r.division!!.name)
        }
        // The next one drops for real.
        r = Ranked.afterGame(r, 980.0, won = false)
        assertEquals("Silver I", r.division!!.name)
        assertEquals("Gold III", Ranked.Division.ofIndex(r.peak).name, "the season's best is remembered")
    }

    @Test fun seasonsEaseTowardsTheStart() {
        val r = Ranked.Record(rating = 1400.0, games = 60, floorTier = 4, season = "2026-09")
        val n = Ranked.newSeason(r, "2026-10")
        assertEquals(Ranked.START + (1400.0 - Ranked.START) * Ranked.SEASON_KEEP, n.rating, 1e-9)
        assertTrue(n.placed, "placed players stay placed")
        assertEquals(0, n.peak)
        assertEquals("2026-10", n.season)
        assertEquals(n, Ranked.newSeason(n, "2026-10"), "same season: unchanged")
        assertEquals("2026-10", Ranked.newSeason(Ranked.Record(), "2026-10").season, "a first-ever record just joins the season")
    }
}

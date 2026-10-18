package lux.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeriesTest {
    private fun four(games: Int = 3) = Series(games, listOf(
        SeriesSeat(3, false, 0, "You"), SeriesSeat(0, true, 1, "Red"), SeriesSeat(1, true, 1, "Green"), SeriesSeat(2, true, 1, "Yellow"),
    ))

    @Test fun seatsAreKeptInColourOrder() {
        assertEquals(listOf(0, 1, 2, 3), four().seats.map { it.color })
    }

    @Test fun everyPlaceButLastScoresAndPiecesHomeCount() {
        val s = four()
        val r = s.record(intArrayOf(1, 2, 3, 4), intArrayOf(4, 3, 1, 0))
        assertContentEquals(intArrayOf(9, 6, 2, 0), r.points)
    }

    @Test fun finalGameCountsDouble() {
        val s = four(3)
        s.record(intArrayOf(1, 2, 3, 4), intArrayOf(4, 0, 0, 0))
        s.record(intArrayOf(1, 2, 3, 4), intArrayOf(4, 0, 0, 0))
        assertTrue(s.isFinal(s.gameIndex))
        val last = s.record(intArrayOf(4, 1, 2, 3), intArrayOf(1, 4, 2, 1))
        assertContentEquals(intArrayOf(2, 18, 10, 4), last.points)
        assertTrue(s.isOver)
    }

    @Test fun aSingleGameIsNotDoubled() {
        val s = Series(1, listOf(SeriesSeat(0, false, 0, "A"), SeriesSeat(2, true, 1, "B")))
        assertContentEquals(intArrayOf(7, 2), s.record(intArrayOf(1, 2), intArrayOf(4, 2)).points)
    }

    @Test fun losingTheFirstGameStillLeavesAWayBack() {
        // Lose game 1 badly, then win the double final: the series is still winnable.
        val s = Series(3, listOf(SeriesSeat(0, false, 0, "Dad"), SeriesSeat(2, true, 1, "CPU")))
        s.record(intArrayOf(2, 1), intArrayOf(1, 4))   // Dad 1, CPU 7
        s.record(intArrayOf(1, 2), intArrayOf(4, 3))   // Dad 7, CPU 3
        s.record(intArrayOf(1, 2), intArrayOf(4, 2))   // doubled: Dad 14, CPU 4
        assertEquals(listOf(0, 1), s.standings())
    }

    @Test fun tiesBreakOnWinsThenPiecesHome() {
        val s = Series(2, listOf(SeriesSeat(0, false, 0, "A"), SeriesSeat(2, false, 0, "B")))
        s.record(intArrayOf(1, 2), intArrayOf(4, 3))   // A 7, B 3
        s.record(intArrayOf(2, 1), intArrayOf(0, 4))   // doubled: A 0, B 14 -> A 7, B 17
        assertEquals(listOf(1, 0), s.standings())
        val t = Series(1, listOf(SeriesSeat(0, false, 0, "A"), SeriesSeat(1, false, 0, "B"), SeriesSeat(2, false, 0, "C")))
        t.record(intArrayOf(3, 2, 1), intArrayOf(2, 0, 0))   // A 2, B 2, C 5; A and B tie on points and wins, A has more pieces
        assertEquals(listOf(2, 0, 1), t.standings())
        assertContentEquals(intArrayOf(2, 3, 1), t.places())
    }

    @Test fun startingSeatRotates() {
        val s = four(5)
        assertEquals(listOf(0, 1, 2, 3, 0), (0 until 5).map { s.startSeat(it) })
    }

    @Test fun saveAndRestore() {
        val s = four(5)
        s.record(intArrayOf(2, 1, 4, 3), intArrayOf(3, 4, 0, 2))
        val back = Series.decode(s.encode())!!
        assertEquals(s.encode(), back.encode())
        assertEquals(1, back.gameIndex)
        assertEquals("You", back.seats[3].name)
        assertNull(Series.decode("garbage"))
        assertNull(Series.decode("S1|3|0,0,0,A"))
    }
}

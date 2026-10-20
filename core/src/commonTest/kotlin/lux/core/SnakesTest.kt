package lux.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnakesTest {
    private fun types(ev: List<SnakesEvent>) = ev.map { it.type }

    @Test fun theBoardIsSound() {
        val starts = SnakesBoard.ladders.keys + SnakesBoard.snakes.keys
        val ends = SnakesBoard.ladders.values + SnakesBoard.snakes.values
        assertEquals(starts.size, SnakesBoard.ladders.size + SnakesBoard.snakes.size, "one thing per square")
        assertTrue(ends.none { it in starts }, "no ladder or snake ends on another one's start")
        assertTrue(SnakesBoard.ladders.all { (f, t) -> t > f } && SnakesBoard.snakes.all { (h, t) -> t < h })
        assertTrue((1 in starts).not() && (100 in starts).not())
        // The zig-zag: 1 bottom-left, 10 bottom-right, 11 right above it, 100 top-left.
        assertEquals(0 to 0, SnakesBoard.colRow(1)); assertEquals(9 to 0, SnakesBoard.colRow(10))
        assertEquals(9 to 1, SnakesBoard.colRow(11)); assertEquals(0 to 9, SnakesBoard.colRow(100))
    }

    @Test fun laddersClimbAndSnakesSlide() {
        val s = SnakesState(intArrayOf(0, 2))
        assertEquals(listOf(SnakesEventType.Rolled, SnakesEventType.Moved, SnakesEventType.Ladder, SnakesEventType.TurnPassed), types(SnakesEngine.roll(s, 3)))
        assertEquals(22, s.pos[0])
        s.pos[1] = 12
        val ev = SnakesEngine.roll(s, 4)
        assertEquals(SnakesEventType.Snake, ev[2].type)
        assertEquals(6, s.pos[1])
        assertEquals(0, s.current)
    }

    @Test fun aSixRollsAgainButThreeSixesPassTheTurn() {
        val s = SnakesState(intArrayOf(0, 2))
        assertTrue(SnakesEventType.ExtraTurn in types(SnakesEngine.roll(s, 6)))
        assertEquals(0, s.current)
        SnakesEngine.roll(s, 6)
        val third = types(SnakesEngine.roll(s, 6))
        assertTrue(SnakesEventType.ThreeSixes in third)
        assertEquals(1, s.current)
        assertEquals(18, s.pos[0], "the moves still count")
    }

    @Test fun squareHundredNeedsTheExactNumber() {
        val s = SnakesState(intArrayOf(0, 1, 2))
        s.pos[0] = 97
        assertTrue(SnakesEventType.Waiting in types(SnakesEngine.roll(s, 5)))
        assertEquals(97, s.pos[0])
        s.current = 0
        val ev = SnakesEngine.roll(s, 3)
        assertEquals(SnakesEventType.Finished, ev.first { it.type == SnakesEventType.Finished }.type)
        assertEquals(1, s.rank[0])
        assertEquals(1, s.current, "the winner is skipped from now on")
        // Bounce-back house rule.
        val b = SnakesState(intArrayOf(0, 2), SnakesRules(exactFinish = false))
        b.pos[0] = 97
        SnakesEngine.roll(b, 5)
        assertEquals(78, b.pos[0], "97+5 bounces back to 98, which is a snake's head")
    }

    @Test fun theGameEndsWhenOneIsLeftAndEveryPlaceIsGiven() {
        val s = SnakesState(intArrayOf(0, 2))
        s.pos[0] = 99
        val ev = SnakesEngine.roll(s, 1)
        assertEquals(listOf(1, 2), listOf(s.rank[0], s.rank[1]))
        assertTrue(s.isOver)
        assertEquals(SnakesEventType.MatchEnded, ev.last().type)
    }

    @Test fun savedGamesComeBack() {
        val s = SnakesState(intArrayOf(0, 1, 3), SnakesRules(needSixToStart = true), startSeat = 2)
        val dice = SnakesDice(7)
        repeat(30) { if (!s.isOver) SnakesEngine.roll(s, dice.next()) }
        val back = SnakesState.decode(s.encode())!!
        assertEquals(s.encode(), back.encode())
        assertEquals(null, SnakesState.decode("garbage"))
    }

    @Test fun gamesTakeAReasonableTime() {
        // Thousands of random 4-player games: long enough to be a game, short enough for a quick round with family.
        val rng = Random(42)
        var total = 0L
        val games = 2000
        repeat(games) {
            val s = SnakesState(intArrayOf(0, 1, 2, 3))
            val dice = SnakesDice(rng)
            var guard = 0
            while (!s.isOver && guard++ < 5000) SnakesEngine.roll(s, dice.next())
            assertTrue(s.isOver)
            total += s.rolls
        }
        val perPlayer = total / games / 4.0
        assertTrue(perPlayer in 20.0..60.0, "rolls per player to finish: $perPlayer")
    }
}

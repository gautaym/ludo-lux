package lux.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineTest {
    private fun twoPlayer(rules: RuleOptions = RuleOptions()) = Engine.create(MatchSetup(listOf(0, 2), rules))

    @Test fun trackIsAClosedLoopOfNeighbours() {
        val cells = (0 until Board.TRACK_LENGTH).map { Board.trackCell(it) }
        assertEquals(52, cells.toSet().size)
        for (i in cells.indices) {
            val a = cells[i]; val b = cells[(i + 1) % cells.size]
            val d = kotlin.math.abs(a.col - b.col) + kotlin.math.abs(a.row - b.row)
            // Steps are to an adjacent cell, except the diagonal corner hops round the centre arms.
            assertTrue(d == 1 || (d == 2 && a.col != b.col && a.row != b.row), "step $i: $a -> $b")
        }
    }

    @Test fun homeLanesLeadToTheCentre() {
        for (c in 0 until 4) {
            val last = Board.homeLaneCell(c, 56)
            assertTrue(last.col in 5..9 && last.row in 5..9 && (last.col == 7 || last.row == 7), "colour $c ends at $last")
        }
    }

    @Test fun onlyASixEntersAndSixGivesAnotherRoll() {
        val s = twoPlayer()
        assertEquals(EventType.NoLegalMoves, Engine.roll(s, 0, 5).events[1].type)
        assertEquals(1, s.current)
        Engine.roll(s, 1, 6)
        assertEquals(Phase.WaitingForMove, s.phase)
        val r = Engine.move(s, 1, 0)
        assertTrue(r.ok)
        assertEquals(1, s.progress[1][0])
        assertTrue(r.events.any { it.type == EventType.ExtraTurn && it.value == 1 })
        assertEquals(1, s.current)
    }

    @Test fun landingOnAnOpponentCapturesIt() {
        val s = twoPlayer()
        // Yellow (colour 2) progress p sits on global (26 + p - 1); red at 30 is global 29 = yellow's progress 4.
        s.progress[0][0] = 30
        s.progress[1][0] = 1
        s.current = 1
        Engine.roll(s, 1, 3)
        val r = Engine.move(s, 1, 0)
        assertTrue(r.events.any { it.type == EventType.PieceCaptured && it.otherSeat == 0 && it.from == 30 })
        assertEquals(0, s.progress[0][0])
        assertEquals(1, s.current, "capture grants another roll")
    }

    @Test fun safeSquaresProtect() {
        val s = twoPlayer()
        s.progress[0][0] = 9           // red's star square (global 8)
        assertTrue(Board.isSafeSquare(Board.globalIndex(0, 9)))
        // Yellow progress p is global 26+p-1, so global 8 is yellow progress 35.
        s.progress[1][0] = 32
        s.current = 1
        Engine.roll(s, 1, 3)
        val r = Engine.move(s, 1, 0)
        assertFalse(r.events.any { it.type == EventType.PieceCaptured })
        assertEquals(9, s.progress[0][0])
    }

    @Test fun landingOnAPairIsAllowedButCapturesNothing() {
        val s = twoPlayer()
        s.progress[0][0] = 30; s.progress[0][1] = 30
        s.progress[1][0] = 1
        s.current = 1
        Engine.roll(s, 1, 3)
        assertEquals(Phase.WaitingForMove, s.phase, "landing on the pair is a legal move")
        val r = Engine.move(s, 1, 0)
        assertTrue(r.ok)
        assertFalse(r.events.any { it.type == EventType.PieceCaptured }, "a pair is never captured")
        assertEquals(30, s.progress[0][0]); assertEquals(30, s.progress[0][1])
        assertEquals(4, s.progress[1][0])
        assertEquals(0, s.current, "no capture, so no extra roll")
    }

    @Test fun aSingleSharingTheSquareWithAPairIsStillCaptured() {
        val s = Engine.create(MatchSetup(listOf(0, 1, 2)))
        // Green progress p is global 13+p-1; red 30 = global 29 = green 17 = yellow 4.
        s.progress[0][0] = 30; s.progress[0][1] = 30
        s.progress[1][0] = 17
        s.progress[2][0] = 1
        s.current = 2
        Engine.roll(s, 2, 3)
        val r = Engine.move(s, 2, 0)
        val caps = r.events.filter { it.type == EventType.PieceCaptured }
        assertEquals(listOf(1), caps.map { it.otherSeat }, "only green's lone piece is captured")
        assertEquals(30, s.progress[0][0])
        assertEquals(0, s.progress[1][0])
    }

    @Test fun blockadeRuleStillBlocks() {
        val s = twoPlayer(RuleOptions(stacks = StackRule.Blockade))
        s.progress[0][0] = 30; s.progress[0][1] = 30
        s.progress[1][0] = 1
        s.current = 1
        Engine.roll(s, 1, 3)
        assertEquals(0, s.current, "under the blockade rule the move is refused and the turn passes")
    }

    @Test fun exactRollNeededToFinish() {
        val s = twoPlayer()
        s.progress[0][0] = 55
        s.progress[0][1] = Board.FINISH; s.progress[0][2] = Board.FINISH; s.progress[0][3] = Board.FINISH
        Engine.roll(s, 0, 3)
        assertEquals(1, s.current)
        s.current = 0
        Engine.roll(s, 0, 2)
        val r = Engine.move(s, 0, 0)
        assertTrue(r.events.any { it.type == EventType.SeatFinished && it.value == 1 })
        assertTrue(s.isOver)
        assertContentEquals(intArrayOf(1, 2), s.rank)
    }

    @Test fun thirdSixForfeitsTheTurn() {
        val s = twoPlayer()
        s.progress[0][0] = 10
        repeat(2) { Engine.roll(s, 0, 6); Engine.move(s, 0, 0) }
        val r = Engine.roll(s, 0, 6)
        assertTrue(r.events.any { it.type == EventType.SixPenalty })
        assertEquals(1, s.current)
    }

    @Test fun luckySixAfterFourDryRolls() {
        val session = MatchSession(MatchSetup(listOf(0, 2)), ScriptedDice(1, 1, 1, 1, 1, 1, 1, 1))
        repeat(8) { session.execute(Command.roll(session.state.current, session.state.sequence)) }
        val r = session.execute(Command.roll(0, session.state.sequence))
        assertEquals(EventType.LuckySix, r.events[0].type)
        assertEquals(6, session.state.lastRoll)
    }

    @Test fun sessionRejectsStaleAndDuplicateCommands() {
        val session = MatchSession(MatchSetup(listOf(0, 2)), ScriptedDice(6, 6))
        val c = Command.roll(0, 0)
        assertTrue(session.execute(c).ok)
        assertEquals(RejectReason.Duplicate, session.execute(c).reason)
        assertEquals(RejectReason.StaleSequence, session.execute(Command.move(0, 0, 0)).reason)
        assertEquals(RejectReason.OutOfTurn, session.execute(Command.roll(1, session.state.sequence)).reason)
    }

    @Test fun snapshotRoundTrips() {
        val s = Engine.create(MatchSetup(listOf(0, 1, 3), RuleOptions(stacks = StackRule.Blockade)))
        s.progress[1][2] = 44; s.drought[2] = 3; s.sequence = 99; s.lastRoll = 4
        val back = GameState.deserialize(s.serialize())
        assertContentEquals(s.serialize(), back.serialize())
        assertEquals(s.checksum(), back.checksum())
    }

    @Test fun cpuMatchesAlwaysFinish() {
        for (seed in 0 until 40) {
            val players = 2 + seed % 3
            val dice: DiceSource = if (seed % 2 == 0) BalancedDice(seed) else FairDice(seed)
            val session = MatchSession(MatchSetup.default(players), dice)
            val rng = Random(seed)
            var steps = 0
            while (!session.state.isOver) {
                val s = session.state
                val cmd = if (s.phase == Phase.WaitingForRoll) Command.roll(s.current, s.sequence)
                else Command.move(s.current, Cpu.chooseMove(s, s.current, CpuLevel.entries[seed % 3], rng), s.sequence)
                assertTrue(session.execute(cmd).ok)
                assertTrue(++steps < 20_000, "match $seed did not end")
            }
            assertEquals((1..players).toSet(), session.state.rank.toSet())
        }
    }

    @Test fun balancedSixesComeInTime() {
        assertEquals(0.10, BalancedDice.sixChance(0), 1e-9)
        assertEquals(0.70, BalancedDice.sixChance(30), 1e-9)
        // All in base: the longest wait for a six over many rolls stays short.
        val s = twoPlayer(RuleOptions(sixPityTurns = 0))
        val dice = BalancedDice(7)
        var wait = 0; var worst = 0
        repeat(5000) {
            if (dice.next(s, 0) == 6) wait = 0 else { wait++; worst = maxOf(worst, wait) }
        }
        assertTrue(worst <= 12, "worst wait $worst")
    }
}

package lux.core

/** One player in a series. [level] is the CPU level ordinal (ignored for humans). */
data class SeriesSeat(val color: Int, val cpu: Boolean, val level: Int, val name: String)

/** What one game of a series added: final [ranks] (1 = winner), pieces brought home, and the points awarded. */
class GameResult(val ranks: IntArray, val piecesHome: IntArray, val points: IntArray)

/**
 * A set of games whose points decide one overall winner. Series points exist only for that purpose: they start at
 * zero for every series and are unrelated to ranked ratings or wallet points.
 *
 * Scoring per game: points for the finishing place (every place scores except last), +1 per piece brought home
 * (so even a lost game earns something), and the final game of a multi-game series counts double.
 * Ties are broken by game wins, then total pieces home.
 */
class Series(val games: Int, seats: List<SeriesSeat>) {
    /** Seats in colour order, the same order the engine uses. */
    val seats: List<SeriesSeat> = seats.sortedBy { it.color }
    val results = ArrayList<GameResult>()

    init {
        require(games >= 1) { "A series needs at least one game." }
        require(this.seats.size in 2..4) { "A series needs 2-4 players." }
    }

    val players get() = seats.size
    /** Index (0-based) of the game being played next. */
    val gameIndex get() = results.size
    val isOver get() = results.size >= games
    fun isFinal(game: Int) = games > 1 && game == games - 1
    fun multiplier(game: Int) = if (isFinal(game)) 2 else 1
    /** Who rolls first rotates each game, so nobody always starts. */
    fun startSeat(game: Int) = game % players

    fun total(seat: Int) = results.sumOf { it.points[seat] }
    fun totalsBefore(game: Int) = IntArray(players) { s -> results.take(game).sumOf { it.points[s] } }
    fun wins(seat: Int) = results.count { it.ranks[seat] == 1 }
    fun piecesHome(seat: Int) = results.sumOf { it.piecesHome[seat] }

    /** Records the finished game and returns what it scored. */
    fun record(ranks: IntArray, piecesHome: IntArray): GameResult {
        check(!isOver) { "The series is already over." }
        require(ranks.size == players && piecesHome.size == players)
        val m = multiplier(gameIndex)
        val pts = IntArray(players) { s -> (placementPoints(players, ranks[s]) + piecesHome[s]) * m }
        return GameResult(ranks.copyOf(), piecesHome.copyOf(), pts).also { results += it }
    }

    /** Seats from first to last place. */
    fun standings(): List<Int> =
        (0 until players).sortedWith(compareByDescending<Int> { total(it) }.thenByDescending { wins(it) }.thenByDescending { piecesHome(it) }.thenBy { it })

    /** Series place per seat (1 = champion). Seats tied on every tiebreak share a place. */
    fun places(): IntArray {
        val order = standings()
        val place = IntArray(players)
        for ((i, s) in order.withIndex()) {
            val prev = order.getOrNull(i - 1)
            place[s] = if (prev != null && key(prev) == key(s)) place[prev] else i + 1
        }
        return place
    }

    private fun key(s: Int) = Triple(total(s), wins(s), piecesHome(s))

    // ---------------- saving (plain text, so it survives app updates and is easy to inspect)

    fun encode(): String = buildString {
        append("S1|").append(games)
        for (s in seats) append("|").append(s.color).append(',').append(if (s.cpu) 1 else 0).append(',').append(s.level).append(',').append(clean(s.name))
        for (r in results) {
            append("|R")
            for (i in 0 until players) append(if (i == 0) "" else ";").append(r.ranks[i]).append(',').append(r.piecesHome[i]).append(',').append(r.points[i])
        }
    }

    companion object {
        /** Points for finishing [rank] (1-based) in a game of [players]. Last place scores nothing. */
        fun placementPoints(players: Int, rank: Int): Int = when (players) {
            2 -> if (rank == 1) 3 else 0
            3 -> when (rank) { 1 -> 5; 2 -> 2; else -> 0 }
            else -> when (rank) { 1 -> 5; 2 -> 3; 3 -> 1; else -> 0 }
        }

        private fun clean(name: String) = name.replace(Regex("[|,;]"), " ").take(24)

        /** Null if [text] is not a saved series (old version, corrupted). */
        fun decode(text: String): Series? = runCatching {
            val parts = text.split('|')
            require(parts[0] == "S1")
            val games = parts[1].toInt()
            val seats = ArrayList<SeriesSeat>()
            val rows = ArrayList<String>()
            for (p in parts.drop(2)) if (p.startsWith("R")) rows += p.drop(1) else {
                val f = p.split(',', limit = 4)
                seats += SeriesSeat(f[0].toInt(), f[1] == "1", f[2].toInt(), f[3])
            }
            Series(games, seats).also { s ->
                for (row in rows) {
                    val cells = row.split(';').map { c -> c.split(',').map(String::toInt) }
                    require(cells.size == s.players)
                    s.results += GameResult(IntArray(s.players) { cells[it][0] }, IntArray(s.players) { cells[it][1] }, IntArray(s.players) { cells[it][2] })
                }
                require(s.results.size <= games)
            }
        }.getOrNull()
    }
}

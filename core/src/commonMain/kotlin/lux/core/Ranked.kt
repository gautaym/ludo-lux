package lux.core

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Ranked play: a hidden skill rating (for fair matchmaking) shown to players as tiers with three steps each.
 * Completely separate from home series points and family-group rankings.
 *
 * Rating: Elo extended to 2-4 players. Each pair of players counts as a head-to-head result (whoever placed higher
 * "won" it), so coming 2nd of 4 still gains against the two below. New players move faster (placement).
 * Protections keep it from feeling harsh: tiers stay hidden for the first [PLACEMENT_GAMES]; reaching a new tier
 * gives a [SHIELD_GAMES]-game shield against dropping straight back out of it; seasons (calendar quarters) reset gently.
 */
object Ranked {
    const val START = 925.0
    /** Games before the tier is shown ("finding your level"). Kept short so new players see a rank quickly. */
    const val PLACEMENT_GAMES = 5
    const val SHIELD_GAMES = 3
    /** Points per step within a tier, and steps per tier. */
    const val STEP = 50
    const val STEPS = 3
    /** Rating where Bronze III ends and the ladder proper begins. */
    const val LADDER_BASE = 700.0
    /** Fraction of the distance from the start that is kept into a new season. */
    const val SEASON_KEEP = 0.75

    val TIERS = listOf("Bronze", "Silver", "Gold", "Platinum", "Diamond", "Champion")
    val CHAMPION = TIERS.lastIndex

    /** A visible rank: [tier] index into [TIERS]; [step] 3, 2 or 1 (Champion has no steps, step = 0). */
    data class Division(val tier: Int, val step: Int) {
        val name get() = if (tier == CHAMPION) TIERS[tier] else "${TIERS[tier]} ${roman(step)}"
        /** 0 = Bronze III ... 14 = Diamond I, 15 = Champion. */
        val index get() = if (tier == CHAMPION) CHAMPION * STEPS else tier * STEPS + (STEPS - step)
        /** Lowest rating that shows this division. */
        val floor get() = LADDER_BASE + index * STEP

        companion object {
            fun ofIndex(i: Int): Division {
                val c = i.coerceIn(0, CHAMPION * STEPS)
                return if (c == CHAMPION * STEPS) Division(CHAMPION, 0) else Division(c / STEPS, STEPS - c % STEPS)
            }

            fun of(rating: Double): Division = ofIndex(((rating - LADDER_BASE) / STEP).toInt().coerceAtLeast(0))

            fun lowestOf(tier: Int) = if (tier >= CHAMPION) Division(CHAMPION, 0) else Division(tier, STEPS)
        }
    }

    private fun roman(n: Int) = when (n) { 1 -> "I"; 2 -> "II"; 3 -> "III"; else -> "" }

    /**
     * Stars shown for a division name like "Gold II": more stars = better (III = 1 star, II = 2, I = 3).
     * Champion shows none (it has a crown). Roman numerals are kept on the wire; players only ever see stars.
     */
    fun starsOf(division: String): Int = when (division.substringAfterLast(' ', "")) { "III" -> 1; "II" -> 2; "I" -> 3; else -> 0 }

    /** The tier word of a division name ("Gold II" -> "Gold"). */
    fun tierWord(division: String): String = division.substringBefore(' ')

    /** One player's ranked record for the current season. */
    data class Record(
        val rating: Double = START,
        val games: Int = 0,
        val wins: Int = 0,
        /** Highest tier reached since the last real drop (the shield protects it). */
        val floorTier: Int = 0,
        val shield: Int = 0,
        /** Best division index shown this season. */
        val peak: Int = 0,
        val season: String = "",
    ) {
        val placed get() = games >= PLACEMENT_GAMES
        /** What the player sees: null during placement; never below the shielded tier. */
        val division: Division?
            get() {
                if (!placed) return null
                val d = Division.of(rating)
                return if (d.tier < floorTier) Division.lowestOf(floorTier) else d
            }
        /** 0..1 progress through the current step (for the bar). */
        val progress: Float
            get() {
                val d = division ?: return games / PLACEMENT_GAMES.toFloat()
                if (d.tier == CHAMPION) return 1f
                return ((rating - d.floor) / STEP).toFloat().coerceIn(0f, 1f)
            }
    }

    /**
     * New ratings after one game. [places] are finishing positions (1 = first), [games] each player's ranked games
     * so far (new players move faster). The changes sum to zero when everyone has the same K.
     */
    fun rate(ratings: List<Double>, places: List<Int>, games: List<Int>): List<Double> {
        val n = ratings.size
        require(n >= 2 && places.size == n && games.size == n)
        return List(n) { i ->
            val k = (if (games[i] < PLACEMENT_GAMES) 40.0 else 24.0) / (n - 1)
            var d = 0.0
            for (j in 0 until n) if (j != i) {
                val expected = 1.0 / (1.0 + 10.0.pow((ratings[j] - ratings[i]) / 400.0))
                val score = when { places[i] < places[j] -> 1.0; places[i] > places[j] -> 0.0; else -> 0.5 }
                d += k * (score - expected)
            }
            ratings[i] + d
        }
    }

    /** Applies one finished game to a record (the new rating comes from [rate]). */
    fun afterGame(r: Record, newRating: Double, won: Boolean): Record {
        val games = r.games + 1
        val tier = Division.of(newRating).tier
        var floor = r.floorTier
        var shield = r.shield
        when {
            games == PLACEMENT_GAMES -> { floor = tier; shield = 0 }           // placement done: start where you landed
            games < PLACEMENT_GAMES -> { floor = tier; shield = 0 }
            tier > floor -> { floor = tier; shield = SHIELD_GAMES }            // promoted into a new tier: shielded
            tier < floor -> if (shield > 0) shield-- else floor = tier         // below it: the shield absorbs a few games
        }
        val next = r.copy(rating = newRating, games = games, wins = r.wins + if (won) 1 else 0, floorTier = floor, shield = shield)
        return next.copy(peak = max(r.peak, next.division?.index ?: 0))
    }

    /** A record carried into season [season]: the rating eases towards the start; everything else begins again. */
    fun newSeason(r: Record, season: String): Record {
        if (r.season == season) return r
        if (r.season.isEmpty()) return r.copy(season = season)
        val rating = START + (r.rating - START) * SEASON_KEEP
        // Players who were placed stay placed (no new placement games), at their eased rating.
        val games = if (r.placed) PLACEMENT_GAMES else r.games
        return Record(rating = rating, games = games, floorTier = Division.of(rating).tier, season = season)
    }

    fun change(before: Double, after: Double) = (after - before).roundToInt()
}

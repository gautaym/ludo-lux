package lux.server

import lux.core.net.MemberStanding
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

data class User(val id: String, val name: String)
data class Group(val id: String, val name: String, val code: String, val owner: String)

/** One human's result in a finished game, for the group standings. */
data class PlayerResult(val userId: String, val rank: Int, val pieces: Int, val points: Int)

/**
 * Accounts, groups, memberships and finished-game results, in a single SQLite file (one machine, no separate database
 * server). Every call is short and synchronised; SQLite in WAL mode handles this load comfortably.
 * Live games are kept in memory by [Rooms]; only finished results are stored.
 */
class Store(path: String) {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val rng = SecureRandom()

    init {
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.execute("CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT NOT NULL UNIQUE, owner TEXT NOT NULL REFERENCES users(id), created INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS members(group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id), joined INTEGER NOT NULL, PRIMARY KEY(group_id, user_id))")
            st.execute("CREATE TABLE IF NOT EXISTS games(id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL REFERENCES groups(id), room TEXT NOT NULL, finished INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS game_players(game_id INTEGER NOT NULL REFERENCES games(id), user_id TEXT NOT NULL REFERENCES users(id), rank INTEGER NOT NULL, pieces INTEGER NOT NULL, points INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS series_wins(group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id), at INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS ranked(user_id TEXT PRIMARY KEY REFERENCES users(id), rating REAL NOT NULL, games INTEGER NOT NULL, wins INTEGER NOT NULL, floor_tier INTEGER NOT NULL, shield INTEGER NOT NULL, peak INTEGER NOT NULL, season TEXT NOT NULL)")
            st.execute("CREATE INDEX IF NOT EXISTS ranked_board ON ranked(season, rating)")
            st.execute("CREATE INDEX IF NOT EXISTS gp_user ON game_players(user_id)")
            st.execute("CREATE INDEX IF NOT EXISTS games_group ON games(group_id, finished)")
        }
    }

    private fun id(): String = ByteArray(12).also(rng::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun now() = System.currentTimeMillis()

    // ---------------- accounts (guest for now; phone/Google sign-in will link to the same user id later)

    /** Creates a guest account. Returns the user and the token (only its hash is stored). */
    @Synchronized fun createGuest(name: String): Pair<User, String> {
        val token = ByteArray(32).also(rng::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val user = User(id(), name)
        db.prepareStatement("INSERT INTO users(id, name, token_hash, created) VALUES(?,?,?,?)").use {
            it.setString(1, user.id); it.setString(2, name); it.setString(3, hash(token)); it.setLong(4, now()); it.executeUpdate()
        }
        return user to token
    }

    @Synchronized fun userByToken(token: String): User? =
        db.prepareStatement("SELECT id, name FROM users WHERE token_hash = ?").use {
            it.setString(1, hash(token))
            it.executeQuery().use { r -> if (r.next()) User(r.getString(1), r.getString(2)) else null }
        }

    @Synchronized fun rename(userId: String, name: String) {
        db.prepareStatement("UPDATE users SET name = ? WHERE id = ?").use { it.setString(1, name); it.setString(2, userId); it.executeUpdate() }
    }

    // ---------------- groups

    @Synchronized fun createGroup(name: String, owner: String, code: String): Group {
        val g = Group(id(), name, code, owner)
        db.prepareStatement("INSERT INTO groups(id, name, code, owner, created) VALUES(?,?,?,?,?)").use {
            it.setString(1, g.id); it.setString(2, name); it.setString(3, code); it.setString(4, owner); it.setLong(5, now()); it.executeUpdate()
        }
        addMember(g.id, owner)
        return g
    }

    @Synchronized fun codeTaken(code: String): Boolean =
        db.prepareStatement("SELECT 1 FROM groups WHERE code = ?").use { it.setString(1, code); it.executeQuery().use { r -> r.next() } }

    @Synchronized fun groupByCode(code: String): Group? = oneGroup("SELECT id, name, code, owner FROM groups WHERE code = ?", code)
    @Synchronized fun group(id: String): Group? = oneGroup("SELECT id, name, code, owner FROM groups WHERE id = ?", id)

    private fun oneGroup(sql: String, arg: String): Group? = db.prepareStatement(sql).use {
        it.setString(1, arg)
        it.executeQuery().use { r -> if (r.next()) Group(r.getString(1), r.getString(2), r.getString(3), r.getString(4)) else null }
    }

    @Synchronized fun addMember(groupId: String, userId: String) {
        db.prepareStatement("INSERT OR IGNORE INTO members(group_id, user_id, joined) VALUES(?,?,?)").use {
            it.setString(1, groupId); it.setString(2, userId); it.setLong(3, now()); it.executeUpdate()
        }
    }

    @Synchronized fun isMember(groupId: String, userId: String): Boolean =
        db.prepareStatement("SELECT 1 FROM members WHERE group_id = ? AND user_id = ?").use {
            it.setString(1, groupId); it.setString(2, userId); it.executeQuery().use { r -> r.next() }
        }

    /** Groups the user belongs to (a person can be in any number of groups). */
    @Synchronized fun groupsOf(userId: String): List<Group> =
        db.prepareStatement("SELECT g.id, g.name, g.code, g.owner FROM groups g JOIN members m ON m.group_id = g.id WHERE m.user_id = ? ORDER BY m.joined").use {
            it.setString(1, userId)
            it.executeQuery().use { r -> buildList { while (r.next()) add(Group(r.getString(1), r.getString(2), r.getString(3), r.getString(4))) } }
        }

    @Synchronized fun memberCount(groupId: String): Int =
        db.prepareStatement("SELECT COUNT(*) FROM members WHERE group_id = ?").use { it.setString(1, groupId); it.executeQuery().use { r -> r.next(); r.getInt(1) } }

    // ---------------- results and standings (only within a group; ranked play is separate)

    @Synchronized fun recordGame(groupId: String, room: String, players: List<PlayerResult>) {
        db.autoCommit = false
        try {
            val gameId = db.prepareStatement("INSERT INTO games(group_id, room, finished) VALUES(?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS).use {
                it.setString(1, groupId); it.setString(2, room); it.setLong(3, now()); it.executeUpdate()
                it.generatedKeys.use { k -> k.next(); k.getLong(1) }
            }
            db.prepareStatement("INSERT INTO game_players(game_id, user_id, rank, pieces, points) VALUES(?,?,?,?,?)").use { st ->
                for (p in players) {
                    st.setLong(1, gameId); st.setString(2, p.userId); st.setInt(3, p.rank); st.setInt(4, p.pieces); st.setInt(5, p.points)
                    st.addBatch()
                }
                st.executeBatch()
            }
            db.commit()
        } catch (e: Exception) {
            db.rollback(); throw e
        } finally {
            db.autoCommit = true
        }
    }

    @Synchronized fun recordSeriesWin(groupId: String, userId: String) {
        db.prepareStatement("INSERT INTO series_wins(group_id, user_id, at) VALUES(?,?,?)").use {
            it.setString(1, groupId); it.setString(2, userId); it.setLong(3, now()); it.executeUpdate()
        }
    }

    /** Every member with all-time and this-month totals, best first (points, then wins, then fewer games). */
    @Synchronized fun standings(groupId: String): List<MemberStanding> {
        val monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val owner = group(groupId)?.owner
        val sql = """
            SELECT u.id, u.name,
                   COALESCE(SUM(x.points), 0), COUNT(x.game_id), COALESCE(SUM(CASE WHEN x.rank = 1 THEN 1 ELSE 0 END), 0),
                   (SELECT COUNT(*) FROM series_wins sw WHERE sw.group_id = m.group_id AND sw.user_id = u.id),
                   COALESCE(SUM(CASE WHEN x.finished >= ? THEN x.points ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN x.finished >= ? THEN 1 ELSE 0 END), 0)
            FROM members m
            JOIN users u ON u.id = m.user_id
            LEFT JOIN (SELECT gp.game_id, gp.user_id, gp.rank, gp.points, g.finished, g.group_id
                       FROM game_players gp JOIN games g ON g.id = gp.game_id) x
                   ON x.user_id = u.id AND x.group_id = m.group_id
            WHERE m.group_id = ?
            GROUP BY u.id, u.name
        """.trimIndent()
        return db.prepareStatement(sql).use {
            it.setLong(1, monthStart); it.setLong(2, monthStart); it.setString(3, groupId)
            it.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(MemberStanding(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getString(1) == owner))
                }
            }
        }.sortedWith(compareByDescending<MemberStanding> { it.points }.thenByDescending { it.wins }.thenBy { it.games }.thenBy { it.name })
    }

    // ---------------- ranked (separate from groups)

    /** This user's record for [season] (a new season eases the rating; a first-timer starts fresh). */
    @Synchronized fun ranked(userId: String, season: String): lux.core.Ranked.Record {
        val r = db.prepareStatement("SELECT rating, games, wins, floor_tier, shield, peak, season FROM ranked WHERE user_id = ?").use {
            it.setString(1, userId)
            it.executeQuery().use { q -> if (q.next()) lux.core.Ranked.Record(q.getDouble(1), q.getInt(2), q.getInt(3), q.getInt(4), q.getInt(5), q.getInt(6), q.getString(7)) else null }
        } ?: lux.core.Ranked.Record(season = season)
        return lux.core.Ranked.newSeason(r, season)
    }

    @Synchronized fun saveRanked(userId: String, r: lux.core.Ranked.Record) {
        db.prepareStatement("INSERT OR REPLACE INTO ranked(user_id, rating, games, wins, floor_tier, shield, peak, season) VALUES(?,?,?,?,?,?,?,?)").use {
            it.setString(1, userId); it.setDouble(2, r.rating); it.setInt(3, r.games); it.setInt(4, r.wins)
            it.setInt(5, r.floorTier); it.setInt(6, r.shield); it.setInt(7, r.peak); it.setString(8, r.season); it.executeUpdate()
        }
    }

    /** Placed players of [season], best first: (userId, name, record). */
    @Synchronized fun leaderboard(season: String, limit: Int): List<Triple<String, String, lux.core.Ranked.Record>> =
        db.prepareStatement("SELECT r.user_id, u.name, r.rating, r.games, r.wins, r.floor_tier, r.shield, r.peak FROM ranked r JOIN users u ON u.id = r.user_id WHERE r.season = ? AND r.games >= ? ORDER BY r.rating DESC LIMIT ?").use {
            it.setString(1, season); it.setInt(2, lux.core.Ranked.PLACEMENT_GAMES); it.setInt(3, limit)
            it.executeQuery().use { q ->
                buildList { while (q.next()) add(Triple(q.getString(1), q.getString(2), lux.core.Ranked.Record(q.getDouble(3), q.getInt(4), q.getInt(5), q.getInt(6), q.getInt(7), q.getInt(8), season))) }
            }
        }

    /** 1-based leaderboard place for [rating] in [season] (placed players only). */
    @Synchronized fun rankedPosition(season: String, rating: Double): Int =
        db.prepareStatement("SELECT COUNT(*) FROM ranked WHERE season = ? AND games >= ? AND rating > ?").use {
            it.setString(1, season); it.setInt(2, lux.core.Ranked.PLACEMENT_GAMES); it.setDouble(3, rating)
            it.executeQuery().use { q -> q.next(); q.getInt(1) + 1 }
        }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}

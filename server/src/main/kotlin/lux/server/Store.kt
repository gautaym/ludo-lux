package lux.server

import lux.core.net.MemberStanding
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

data class User(val id: String, val name: String, val tag: Int = 0, val google: Boolean = false)
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
            // Added later: Google account link and the name tag. (Older databases get the columns added here.)
            val cols = st.executeQuery("PRAGMA table_info(users)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
            if ("google_sub" !in cols) st.execute("ALTER TABLE users ADD COLUMN google_sub TEXT")
            if ("tag" !in cols) st.execute("ALTER TABLE users ADD COLUMN tag INTEGER NOT NULL DEFAULT 0")
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_google ON users(google_sub) WHERE google_sub IS NOT NULL")
            // One account can be signed in on several phones: one session token per phone.
            st.execute("CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), created INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT NOT NULL UNIQUE, owner TEXT NOT NULL REFERENCES users(id), created INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS members(group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id), joined INTEGER NOT NULL, PRIMARY KEY(group_id, user_id))")
            st.execute("CREATE TABLE IF NOT EXISTS games(id INTEGER PRIMARY KEY AUTOINCREMENT, group_id TEXT NOT NULL REFERENCES groups(id), room TEXT NOT NULL, finished INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS game_players(game_id INTEGER NOT NULL REFERENCES games(id), user_id TEXT NOT NULL REFERENCES users(id), rank INTEGER NOT NULL, pieces INTEGER NOT NULL, points INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS series_wins(group_id TEXT NOT NULL REFERENCES groups(id), user_id TEXT NOT NULL REFERENCES users(id), at INTEGER NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS ranked(user_id TEXT PRIMARY KEY REFERENCES users(id), rating REAL NOT NULL, games INTEGER NOT NULL, wins INTEGER NOT NULL, floor_tier INTEGER NOT NULL, shield INTEGER NOT NULL, peak INTEGER NOT NULL, season TEXT NOT NULL)")
            st.execute("CREATE INDEX IF NOT EXISTS ranked_board ON ranked(season, rating)")
            // Purchases: each Google Play token belongs to exactly one account.
            st.execute("CREATE TABLE IF NOT EXISTS purchases(token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), product TEXT NOT NULL, order_id TEXT NOT NULL, expires INTEGER NOT NULL, created INTEGER NOT NULL)")
            st.execute("CREATE INDEX IF NOT EXISTS purchases_user ON purchases(user_id)")
            // Which family group each Family Pass owner chose to cover.
            st.execute("CREATE TABLE IF NOT EXISTS family_pass(owner TEXT PRIMARY KEY REFERENCES users(id), group_id TEXT NOT NULL REFERENCES groups(id))")
            st.execute("CREATE INDEX IF NOT EXISTS gp_user ON game_players(user_id)")
            st.execute("CREATE INDEX IF NOT EXISTS games_group ON games(group_id, finished)")
        }
    }

    private fun id(): String = ByteArray(12).also(rng::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun now() = System.currentTimeMillis()

    // ---------------- accounts (guest for now; phone/Google sign-in will link to the same user id later)

    private fun newToken() = ByteArray(32).also(rng::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun newTag() = 1000 + rng.nextInt(9000)

    /** Creates an account (a guest unless [googleSub] is given). Returns the user and a session token (only its hash is stored). */
    @Synchronized fun createUser(name: String, googleSub: String? = null): Pair<User, String> {
        val token = newToken()
        val user = User(id(), name, newTag(), googleSub != null)
        db.prepareStatement("INSERT INTO users(id, name, token_hash, created, google_sub, tag) VALUES(?,?,?,?,?,?)").use {
            it.setString(1, user.id); it.setString(2, name); it.setString(3, hash(newToken())); it.setLong(4, now())
            it.setString(5, googleSub); it.setInt(6, user.tag); it.executeUpdate()
        }
        addSession(user.id, token)
        return user to token
    }

    @Synchronized fun createGuest(name: String): Pair<User, String> = createUser(name)

    private fun addSession(userId: String, token: String) {
        db.prepareStatement("INSERT INTO sessions(token_hash, user_id, created) VALUES(?,?,?)").use {
            it.setString(1, hash(token)); it.setString(2, userId); it.setLong(3, now()); it.executeUpdate()
        }
    }

    /** A new session token for an existing account (signing in on another phone). */
    @Synchronized fun newSession(userId: String): String = newToken().also { addSession(userId, it) }

    private fun readUser(sql: String, arg: String): User? = db.prepareStatement(sql).use {
        it.setString(1, arg)
        it.executeQuery().use { r -> if (r.next()) User(r.getString(1), r.getString(2), r.getInt(3), r.getString(4) != null) else null }
    }

    @Synchronized fun userByToken(token: String): User? =
        readUser("SELECT u.id, u.name, u.tag, u.google_sub FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?", hash(token))
            // Accounts made before sessions existed kept their token on the user row.
            ?: readUser("SELECT id, name, tag, google_sub FROM users WHERE token_hash = ?", hash(token))

    @Synchronized fun userByGoogle(sub: String): User? = readUser("SELECT id, name, tag, google_sub FROM users WHERE google_sub = ?", sub)

    /** Saves a guest account with Google, so the same account works on any phone from now on. */
    @Synchronized fun linkGoogle(userId: String, sub: String) {
        db.prepareStatement("UPDATE users SET google_sub = ? WHERE id = ?").use { it.setString(1, sub); it.setString(2, userId); it.executeUpdate() }
    }

    @Synchronized fun tagOf(userId: String): Int =
        db.prepareStatement("SELECT tag FROM users WHERE id = ?").use { it.setString(1, userId); it.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 } }

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
                   COALESCE(SUM(CASE WHEN x.finished >= ? THEN 1 ELSE 0 END), 0),
                   u.tag
            FROM members m
            JOIN users u ON u.id = m.user_id
            LEFT JOIN (SELECT gp.game_id, gp.user_id, gp.rank, gp.points, g.finished, g.group_id
                       FROM game_players gp JOIN games g ON g.id = gp.game_id) x
                   ON x.user_id = u.id AND x.group_id = m.group_id
            WHERE m.group_id = ?
            GROUP BY u.id, u.name, u.tag
        """.trimIndent()
        return db.prepareStatement(sql).use {
            it.setLong(1, monthStart); it.setLong(2, monthStart); it.setString(3, groupId)
            it.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(MemberStanding(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getString(1) == owner, r.getInt(9)))
                }
            }
        }.sortedWith(compareByDescending<MemberStanding> { it.points }.thenByDescending { it.wins }.thenBy { it.games }.thenBy { it.name })
    }

    // ---------------- shop

    /**
     * Records a verified purchase. Returns false if this purchase token is already claimed by another account
     * (one purchase can never unlock two accounts). Renewals of a subscription update its expiry.
     */
    @Synchronized fun recordPurchase(userId: String, product: String, token: String, orderId: String, expires: Long): Boolean {
        val h = hash(token)
        val owner = db.prepareStatement("SELECT user_id FROM purchases WHERE token_hash = ?").use { it.setString(1, h); it.executeQuery().use { r -> if (r.next()) r.getString(1) else null } }
        if (owner != null && owner != userId) return false
        db.prepareStatement("INSERT OR REPLACE INTO purchases(token_hash, user_id, product, order_id, expires, created) VALUES(?,?,?,?,?,?)").use {
            it.setString(1, h); it.setString(2, userId); it.setString(3, product); it.setString(4, orderId); it.setLong(5, expires); it.setLong(6, now()); it.executeUpdate()
        }
        return true
    }

    /** Products this user owns right now (subscriptions only while unexpired), with the latest expiry per product. */
    @Synchronized fun owned(userId: String, at: Long = now()): Map<String, Long> =
        db.prepareStatement("SELECT product, MAX(expires) FROM purchases WHERE user_id = ? AND (expires = 0 OR expires > ?) GROUP BY product").use {
            it.setString(1, userId); it.setLong(2, at)
            it.executeQuery().use { r -> buildMap { while (r.next()) put(r.getString(1), r.getLong(2)) } }
        }

    @Synchronized fun setFamilyPassGroup(owner: String, groupId: String) {
        db.prepareStatement("INSERT OR REPLACE INTO family_pass(owner, group_id) VALUES(?,?)").use { it.setString(1, owner); it.setString(2, groupId); it.executeUpdate() }
    }

    @Synchronized fun familyPassGroup(owner: String): String? =
        db.prepareStatement("SELECT group_id FROM family_pass WHERE owner = ?").use { it.setString(1, owner); it.executeQuery().use { r -> if (r.next()) r.getString(1) else null } }

    /**
     * The name of whoever's active Family Pass covers [userId], if any. A pass covers the first
     * [lux.core.Catalog.FAMILY_PASS_SEATS] members of its group: the owner, then members in the order they joined.
     */
    @Synchronized fun coveredBy(userId: String, at: Long = now()): String? {
        val sql = """
            SELECT fp.owner, u.name, fp.group_id FROM family_pass fp
            JOIN users u ON u.id = fp.owner
            JOIN members m ON m.group_id = fp.group_id AND m.user_id = ?
            WHERE EXISTS (SELECT 1 FROM purchases p WHERE p.user_id = fp.owner AND p.product = ? AND p.expires > ?)
        """.trimIndent()
        val passes = db.prepareStatement(sql).use {
            it.setString(1, userId); it.setString(2, lux.core.Catalog.FAMILY_PASS); it.setLong(3, at)
            it.executeQuery().use { r -> buildList { while (r.next()) add(Triple(r.getString(1), r.getString(2), r.getString(3))) } }
        }
        for ((owner, name, group) in passes) {
            if (owner == userId) continue   // the owner has the pass itself; "covered by" is for the others
            val seats = db.prepareStatement("SELECT user_id FROM members WHERE group_id = ? ORDER BY (user_id = ?) DESC, joined ASC LIMIT ?").use {
                it.setString(1, group); it.setString(2, owner); it.setInt(3, lux.core.Catalog.FAMILY_PASS_SEATS)
                it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
            }
            if (userId in seats) return name
        }
        return null
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

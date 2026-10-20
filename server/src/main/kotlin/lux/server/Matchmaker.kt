package lux.server

import lux.core.net.QueueState
import lux.core.net.QueueStatus
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Ranked seasons are calendar quarters (UTC), named like "2026 Q4". Three months is long enough that a reset never
 * feels like losing progress, short enough to keep things fresh.
 */
object Seasons {
    private fun quarter(d: LocalDate) = (d.monthValue - 1) / 3 + 1
    fun current(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): String = "%04d Q%d".format(today.year, quarter(today))
    fun daysLeft(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
        val start = LocalDate.of(today.year, (quarter(today) - 1) * 3 + 1, 1)
        return ChronoUnit.DAYS.between(today, start.plusMonths(3)).toInt()
    }
}

/**
 * Ranked matchmaking: one queue per table size (2 or 4 players). Players are matched with others close to their
 * rating; the acceptable gap widens the longer someone waits, so nobody waits forever on a quiet server.
 * Tickets that are not polled for a while are dropped (the player left the screen).
 */
class Matchmaker(private val store: Store, private val rooms: Rooms) {
    private class Ticket(val user: User, val players: Int, val rating: Double, val since: Long) {
        @Volatile var lastPoll = since
        @Volatile var room: String = ""
    }

    private val tickets = ConcurrentHashMap<String, Ticket>()

    @Synchronized fun join(user: User, players: Int, now: Long = System.currentTimeMillis()): QueueStatus {
        require(players == 2 || players == 4)
        val existing = tickets[user.id]
        if (existing != null && existing.room.isEmpty() && existing.players == players) return status(user, now)
        val rating = store.ranked(user.id, Seasons.current()).rating
        tickets[user.id] = Ticket(user, players, rating, now)
        match(now)
        return status(user, now)
    }

    @Synchronized fun leave(user: User) { tickets[user.id]?.takeIf { it.room.isEmpty() }?.let { tickets.remove(user.id) } }

    @Synchronized fun status(user: User, now: Long = System.currentTimeMillis()): QueueStatus {
        val t = tickets[user.id] ?: return QueueStatus(QueueState.None)
        t.lastPoll = now
        if (t.room.isNotEmpty()) {
            tickets.remove(user.id)            // matched: hand over the room once
            return QueueStatus(QueueState.Matched, t.players, ((now - t.since) / 1000).toInt(), 0, t.room)
        }
        val waiting = tickets.values.count { it.room.isEmpty() && it.players == t.players }
        return QueueStatus(QueueState.Waiting, t.players, ((now - t.since) / 1000).toInt(), waiting)
    }

    /** Rating gap accepted after waiting [ms]: 100 at once, growing ~40 a second, up to 800. */
    fun window(ms: Long) = (100.0 + 40.0 * ms / 1000.0).coerceAtMost(800.0)

    /** Forms every match it can. Called on each join and periodically. */
    @Synchronized fun match(now: Long = System.currentTimeMillis()) {
        tickets.values.removeIf { it.room.isEmpty() && now - it.lastPoll > 15_000 }
        for (size in listOf(2, 4)) {
            while (true) {
                val waiting = tickets.values.filter { it.room.isEmpty() && it.players == size }.sortedBy { it.since }
                if (waiting.size < size) break
                // The longest-waiting player anchors the table and takes the closest others within the window.
                var formed: List<Ticket>? = null
                for (anchor in waiting) {
                    val w = window(now - anchor.since)
                    val others = waiting.filter { it !== anchor && abs(it.rating - anchor.rating) <= maxOf(w, window(now - it.since)) }
                        .sortedBy { abs(it.rating - anchor.rating) }.take(size - 1)
                    if (others.size == size - 1) { formed = listOf(anchor) + others; break }
                }
                val table = formed ?: break
                val room = rooms.createRanked(table.map { it.user })
                for (t in table) t.room = room.code
            }
        }
    }
}

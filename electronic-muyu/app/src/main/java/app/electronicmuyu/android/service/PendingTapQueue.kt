package app.electronicmuyu.android.service

import app.electronicmuyu.android.model.ConnectionState
import java.util.ArrayDeque

internal data class PendingTap(
    val timestampMillis: Long,
    val enqueuedAtMillis: Long
)

internal data class PendingTapOfferResult(
    val expiredCount: Int,
    val overflowCount: Int
)

internal data class PendingTapPollResult(
    val tap: PendingTap?,
    val expiredCount: Int
)

internal fun shouldFlushPendingTaps(
    connectionState: ConnectionState,
    partnerOnline: Boolean
): Boolean = connectionState == ConnectionState.CONNECTED && partnerOnline

/**
 * A process-memory-only queue used to bridge short WebSocket reconnect windows.
 * It deliberately does not survive service/process death and is not offline history.
 */
internal class PendingTapQueue(
    private val maxSize: Int = 20,
    private val ttlMillis: Long = 10_000L
) {
    private val items = ArrayDeque<PendingTap>()

    init {
        require(maxSize > 0)
        require(ttlMillis > 0)
    }

    fun offer(timestampMillis: Long, nowMillis: Long): PendingTapOfferResult {
        val expired = discardExpired(nowMillis)
        var overflow = 0
        if (items.size >= maxSize) {
            items.removeFirst()
            overflow = 1
        }
        items.addLast(PendingTap(timestampMillis, nowMillis))
        return PendingTapOfferResult(expired, overflow)
    }

    fun poll(nowMillis: Long): PendingTapPollResult {
        val expired = discardExpired(nowMillis)
        return PendingTapPollResult(items.pollFirst(), expired)
    }

    fun addFirst(tap: PendingTap) {
        items.addFirst(tap)
    }

    fun discardExpired(nowMillis: Long): Int {
        var expired = 0
        while (true) {
            val first = items.peekFirst() ?: break
            if (nowMillis < first.enqueuedAtMillis || nowMillis - first.enqueuedAtMillis < ttlMillis) {
                break
            }
            items.removeFirst()
            expired++
        }
        return expired
    }

    fun nextExpiryAtMillis(): Long? = items.peekFirst()?.let { it.enqueuedAtMillis + ttlMillis }

    fun isEmpty(): Boolean = items.isEmpty()

    fun clear(): Int {
        val count = items.size
        items.clear()
        return count
    }
}

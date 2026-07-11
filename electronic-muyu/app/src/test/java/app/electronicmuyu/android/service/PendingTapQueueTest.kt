package app.electronicmuyu.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTapQueueTest {
    @Test
    fun preservesOrderDuringShortReconnect() {
        val queue = PendingTapQueue(maxSize = 20, ttlMillis = 10_000)
        queue.offer(timestampMillis = 101, nowMillis = 1_000)
        queue.offer(timestampMillis = 102, nowMillis = 1_100)

        assertEquals(101L, queue.poll(2_000).tap?.timestampMillis)
        assertEquals(102L, queue.poll(2_000).tap?.timestampMillis)
        assertNull(queue.poll(2_000).tap)
    }

    @Test
    fun dropsExpiredTapsAfterTenSeconds() {
        val queue = PendingTapQueue(maxSize = 20, ttlMillis = 10_000)
        queue.offer(timestampMillis = 101, nowMillis = 1_000)

        val result = queue.poll(11_000)

        assertEquals(1, result.expiredCount)
        assertNull(result.tap)
    }

    @Test
    fun keepsNewestTwentyTapsWhenQueueOverflows() {
        val queue = PendingTapQueue(maxSize = 20, ttlMillis = 10_000)
        var overflows = 0
        for (index in 1L..21L) {
            overflows += queue.offer(timestampMillis = index, nowMillis = 1_000).overflowCount
        }

        assertEquals(1, overflows)
        assertEquals(2L, queue.poll(1_001).tap?.timestampMillis)
        assertEquals(3L, queue.poll(1_001).tap?.timestampMillis)
    }

    @Test
    fun failedSendCanReturnTapToFrontWithoutChangingAge() {
        val queue = PendingTapQueue(maxSize = 20, ttlMillis = 10_000)
        queue.offer(timestampMillis = 101, nowMillis = 1_000)
        queue.offer(timestampMillis = 102, nowMillis = 1_100)
        val first = requireNotNull(queue.poll(2_000).tap)

        queue.addFirst(first)

        assertEquals(101L, queue.poll(2_001).tap?.timestampMillis)
        assertEquals(102L, queue.poll(2_001).tap?.timestampMillis)
    }

    @Test
    fun clockRollbackDoesNotExpireQueuedTap() {
        val queue = PendingTapQueue(maxSize = 20, ttlMillis = 10_000)
        queue.offer(timestampMillis = 101, nowMillis = 5_000)

        assertEquals(101L, queue.poll(4_000).tap?.timestampMillis)
    }
}

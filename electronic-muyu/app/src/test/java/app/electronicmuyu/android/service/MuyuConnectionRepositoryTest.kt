package app.electronicmuyu.android.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MuyuConnectionRepositoryTest {

    @Before
    fun setUp() {
        resetRepository()
    }

    @After
    fun tearDown() {
        resetRepository()
    }

    @Test
    fun pendingUiEventsAreBoundedAndOldestEventsAreDropped() {
        repeat(65) { index ->
            MuyuConnectionRepository.recordReceivedTap(
                receivedAtMillis = index.toLong(),
                enqueueForForegroundUi = true
            )
        }

        val events = MuyuConnectionRepository.pendingReceivedTapUiEvents.value
        assertEquals(64, events.size)
        assertEquals(1L, events.first().receivedAtMillis)
        assertEquals(64L, events.last().receivedAtMillis)
        assertEquals(64, events.map { it.id }.distinct().size)
    }

    @Test
    fun consumedEventsAreRemovedWithoutAffectingRemainingEvents() {
        repeat(3) { index ->
            MuyuConnectionRepository.recordReceivedTap(
                receivedAtMillis = index.toLong(),
                enqueueForForegroundUi = true
            )
        }
        val events = MuyuConnectionRepository.pendingReceivedTapUiEvents.value

        MuyuConnectionRepository.consumeReceivedTapUiEvents(listOf(events[0].id, events[2].id))

        assertEquals(listOf(events[1]), MuyuConnectionRepository.pendingReceivedTapUiEvents.value)
    }

    @Test
    fun backgroundTapUpdatesDiagnosticsWithoutEnteringUiQueue() {
        MuyuConnectionRepository.recordReceivedTap(
            receivedAtMillis = 1234L,
            enqueueForForegroundUi = false
        )

        assertEquals(1234L, MuyuConnectionRepository.lastTapReceivedAtMillis.value)
        assertTrue(MuyuConnectionRepository.pendingReceivedTapUiEvents.value.isEmpty())
    }

    @Test
    fun processAndUiForegroundStatesAreIndependent() {
        MuyuConnectionRepository.setAppForeground(true)
        MuyuConnectionRepository.setUiForeground(false)

        assertTrue(MuyuConnectionRepository.appForeground.value)
        assertFalse(MuyuConnectionRepository.uiForeground.value)

        MuyuConnectionRepository.setUiForeground(true)
        assertTrue(MuyuConnectionRepository.uiForeground.value)
    }

    private fun resetRepository() {
        MuyuConnectionRepository.clearPendingReceivedTapUiEvents()
        MuyuConnectionRepository.setAppForeground(false)
        MuyuConnectionRepository.setUiForeground(false)
        assertNull(MuyuConnectionRepository.lastTapReceivedAtMillis.value)
    }
}

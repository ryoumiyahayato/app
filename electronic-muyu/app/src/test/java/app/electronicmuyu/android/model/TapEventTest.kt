package app.electronicmuyu.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapEventTest {

    @Test
    fun parsesValidTap() {
        val event = TapEvent.fromJson(
            """{"type":"tap","pairId":"room","deviceId":"device","timestamp":123}"""
        )

        assertEquals(TapEvent("tap", "room", "device", 123L), event)
    }

    @Test
    fun rejectsInvalidIdentifiersAndMessageTypes() {
        assertNull(
            TapEvent.fromJson(
                """{"type":"other","pairId":"room","deviceId":"device","timestamp":1}"""
            )
        )
        assertNull(
            TapEvent.fromJson(
                """{"type":"tap","pairId":"","deviceId":"device","timestamp":1}"""
            )
        )
        assertNull(
            TapEvent.fromJson(
                """{"type":"tap","pairId":"room","deviceId":"bad\u0000id","timestamp":1}"""
            )
        )
    }

    @Test
    fun rejectsFractionalNonPositiveAndUnsafeTimestamps() {
        assertNull(
            TapEvent.fromJson(
                """{"type":"tap","pairId":"room","deviceId":"device","timestamp":1.5}"""
            )
        )
        assertNull(
            TapEvent.fromJson(
                """{"type":"tap","pairId":"room","deviceId":"device","timestamp":0}"""
            )
        )
        assertNull(
            TapEvent.fromJson(
                """{"type":"tap","pairId":"room","deviceId":"device","timestamp":9007199254740992}"""
            )
        )
    }
}

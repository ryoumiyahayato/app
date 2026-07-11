package app.electronicmuyu.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMigrationPolicyTest {
    @Test
    fun legacyRoomIsWarnedAndNeverConvertedIntoSecureCredentials() {
        assertEquals(
            LegacyMigrationAction.WARN_AND_REQUIRE_NEW_PAIRING,
            LegacyMigrationPolicy.action("wss://old.example", "old-room", false)
        )
        assertEquals(
            LegacyMigrationAction.DELETE_AFTER_SECURE_PAIR,
            LegacyMigrationPolicy.action("wss://old.example", "old-room", true)
        )
        assertEquals(LegacyMigrationAction.NONE, LegacyMigrationPolicy.action(null, null, false))
    }
}

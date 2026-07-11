package app.electronicmuyu.android.data

enum class LegacyMigrationAction { NONE, WARN_AND_REQUIRE_NEW_PAIRING, DELETE_AFTER_SECURE_PAIR }

object LegacyMigrationPolicy {
    fun action(wsUrl: String?, roomId: String?, securePairExists: Boolean): LegacyMigrationAction {
        val legacyExists = !wsUrl.isNullOrBlank() || !roomId.isNullOrBlank()
        return when {
            !legacyExists -> LegacyMigrationAction.NONE
            securePairExists -> LegacyMigrationAction.DELETE_AFTER_SECURE_PAIR
            else -> LegacyMigrationAction.WARN_AND_REQUIRE_NEW_PAIRING
        }
    }
}

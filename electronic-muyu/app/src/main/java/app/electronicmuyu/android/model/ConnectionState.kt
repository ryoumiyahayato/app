package app.electronicmuyu.android.model

enum class ConnectionState {
    UNPAIRED,
    PAIRING,
    PAIRED,
    PAIR_FAILED,
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    RECONNECTING,
    CONNECTED,
    AUTHENTICATION_FAILED,
    REVOKED,
    CONNECTION_FAILED,
    PARTNER_OFFLINE
}

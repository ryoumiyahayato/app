package app.electronicmuyu.android.model

enum class ConnectionState {
    UNPAIRED,
    PAIRING,
    PAIRED,
    PAIR_FAILED,
    DISCONNECTED,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    CONNECTION_FAILED,
    PARTNER_OFFLINE
}

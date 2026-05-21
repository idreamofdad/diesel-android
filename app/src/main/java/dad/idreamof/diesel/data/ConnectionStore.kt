package dad.idreamof.diesel.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Client-side connection settings. These are local to the device and are NOT part
 * of the server's [AppSettings] — they say *which* Diesel server to talk to.
 *
 * The default host is 10.0.2.2, which is how the Android emulator reaches the
 * development machine's loopback interface. On a physical device, set this to the
 * server's LAN IP (and enable "Expose on network" in the server's Settings).
 */
data class ConnectionConfig(
    val host: String = "10.0.2.2",
    val port: Int = 7777,
    val token: String = "",
) {
    /** REST base, e.g. http://10.0.2.2:7777/api/v1 */
    val apiBase: String get() = "http://$host:$port/api/v1"

    /** Origin for media/WebSocket URL assembly, e.g. http://10.0.2.2:7777 */
    val origin: String get() = "http://$host:$port"

    /** Resolve a possibly-relative server path (like "/api/v1/portrait/x") to an absolute URL. */
    fun resolve(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else origin + path
}

/** Persists [ConnectionConfig] and a stable per-install WebSocket client ID. */
class ConnectionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<ConnectionConfig> = _config.asStateFlow()

    /** Stable client ID reused across reconnects so reply-audio routing stays consistent. */
    val clientId: String = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString()
        .also { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }

    /**
     * Whether spoken replies are played aloud. A local UI preference, kept separate from
     * [config] so toggling it does not restart the session.
     */
    val ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)

    fun setTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply()
    }

    private fun read() = ConnectionConfig(
        host = prefs.getString(KEY_HOST, null) ?: "10.0.2.2",
        port = prefs.getInt(KEY_PORT, 7777),
        token = prefs.getString(KEY_TOKEN, null) ?: "",
    )

    fun save(config: ConnectionConfig) {
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putString(KEY_TOKEN, config.token)
            .apply()
        _config.value = config
    }

    private companion object {
        const val PREFS = "diesel_connection"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_TTS_ENABLED = "tts_enabled"
    }
}

package dad.idreamof.diesel.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.decodeFromString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** A frame surfaced by [DieselSocket.connect]. */
sealed interface WsEvent {
    /** The socket upgraded successfully. [socket] can be used to push commands. */
    data class Connected(val socket: WebSocket) : WsEvent

    /** A parsed server -> client [Event]. */
    data class Frame(val event: Event) : WsEvent

    /** The socket closed or failed; [reason] is best-effort. The flow completes after this. */
    data class Disconnected(val reason: String) : WsEvent
}

/**
 * Wraps the `/ws` endpoint as a cold [Flow] of [WsEvent]s.
 *
 * One collection == one socket. The flow completes (it does not throw) when the socket
 * drops, so callers reconnect by re-collecting — see ChatViewModel's reconnect loop.
 */
class DieselSocket(
    private val client: OkHttpClient,
    private val connection: ConnectionStore,
) {
    fun connect(): Flow<WsEvent> = callbackFlow {
        val config = connection.config.value
        val url = "${config.origin}/api/v1/ws".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", connection.clientId)
            .apply { if (config.token.isNotBlank()) addQueryParameter("token", config.token) }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(WsEvent.Connected(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { DieselJson.decodeFromString<Event>(text) }
                    .onSuccess { trySend(WsEvent.Frame(it)) }
                // Non-Event frames (or parse failures) are ignored, as the spec allows.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsEvent.Disconnected(reason.ifBlank { "closed" }))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsEvent.Disconnected(t.message ?: "connection failed"))
                close()
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        awaitClose { socket.cancel() }
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}

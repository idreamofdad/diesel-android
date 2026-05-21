package dad.idreamof.diesel.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the Diesel API (OpenAPI 3.1).
 *
 * The shared [DieselJson] instance applies a snake_case naming strategy, so Kotlin
 * properties stay camelCase here and serialize to the snake_case the server expects.
 */

/** A single conversation message. */
@Serializable
data class Message(
    val role: String,
    val content: String,
    val timestamp: String? = null,
    /** Present on some assistant messages. */
    val emotion: String? = null,
) {
    val isUser: Boolean get() = role == ROLE_USER
    val isAssistant: Boolean get() = role == ROLE_ASSISTANT
    val isSystem: Boolean get() = role == ROLE_SYSTEM

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }
}

/** Token accounting for a completed turn. */
@Serializable
data class Usage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

/** Conversation snapshot returned by GET /state. */
@Serializable
data class State(
    val history: List<Message> = emptyList(),
    val inFlight: Boolean = false,
    val status: String = "",
    /** Present only when a portrait has been rendered, e.g. "/api/v1/portrait/abc123". */
    val portraitUrl: String? = null,
)

/** Body for POST /send. */
@Serializable
data class SendRequest(
    val text: String,
    /** Client's stable ID so reply audio routes back to it. */
    val origin: String? = null,
)

/** Response to POST /send. */
@Serializable
data class SendResponse(val ok: Boolean = false)

/** Response to POST /transcribe. */
@Serializable
data class TranscribeResponse(
    val text: String = "",
    val sent: Boolean = false,
    val sendError: String? = null,
)

/** Body for the model-list and connection-test endpoints. */
@Serializable
data class ProbeRequest(
    /** One of: "llm", "stt", "tts", "image". */
    val kind: String,
    val endpoint: String? = null,
    /** Send "********" to reuse the saved key. */
    val apiKey: String? = null,
    val model: String? = null,
    val voice: String? = null,
    /** Sample text for /settings/test-tts. */
    val text: String? = null,
)

/** Response to POST /settings/models. */
@Serializable
data class ModelsResponse(
    val models: List<String> = emptyList(),
    val contextLength: Int? = null,
    val error: String? = null,
)

/** Response to POST /settings/test. */
@Serializable
data class TestResponse(val status: String = "")

/** Generic error envelope. */
@Serializable
data class ApiError(val error: String = "")

/**
 * A server -> client WebSocket frame. [type] discriminates which optional fields
 * are populated; see [EventType] for the catalogue.
 */
@Serializable
data class Event(
    val type: String,
    val timestamp: String = "",
    val clientId: String? = null,
    val origin: String? = null,
    val turnId: Long? = null,
    val user: Message? = null,
    val assistant: Message? = null,
    val emotion: String? = null,
    val naked: Boolean? = null,
    val portraitUrl: String? = null,
    val audioUrl: String? = null,
    val usage: Usage? = null,
    val step: Int? = null,
    val total: Int? = null,
    val status: String? = null,
    val error: String? = null,
    val inFlight: Boolean? = null,
)

/** WebSocket event type constants. */
object EventType {
    const val ACK = "ack"
    const val TURN_STARTED = "turn_started"
    const val TURN_COMPLETE = "turn_complete"
    const val AUDIO_READY = "audio_ready"
    const val PORTRAIT_READY = "portrait_ready"
    const val PORTRAIT_PROGRESS = "portrait_progress"
    const val TURN_ERROR = "turn_error"
    const val STATUS = "status"
    const val CLEARED = "cleared"
    const val BUSY = "busy"
}

/** Client -> server WebSocket command. */
@Serializable
data class WsCommand(
    val type: String,
    val text: String? = null,
)

/**
 * Diesel app configuration (GET/POST /settings).
 *
 * Secret fields come back masked as [MASKED]; send the mask back unchanged to keep
 * the stored value. Fields flagged read-only in the spec are preserved verbatim by
 * the server and are not editable from this client.
 */
@Serializable
data class AppSettings(
    val theme: String? = null,
    val apiEndpoint: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val systemPrompt: String? = null,
    val historyMessages: Int? = null,
    val sttEndpoint: String? = null,
    val sttApiKey: String? = null,
    val sttModel: String? = null,
    val continuousConversation: Boolean? = null,
    val enableTts: Boolean? = null,
    val ttsEndpoint: String? = null,
    val ttsApiKey: String? = null,
    val ttsModel: String? = null,
    val ttsVoice: String? = null,
    val inputDevice: String? = null,
    val outputDevice: String? = null,
    val saveToDisk: Boolean? = null,
    val enableImageGen: Boolean? = null,
    @SerialName("comfyui_endpoint")
    val comfyuiEndpoint: String? = null,
    val imagePrompt: String? = null,
    val imageClothing: String? = null,
    val imageNudity: String? = null,
    val imageNegativePrompt: String? = null,
    val imageSteps: Int? = null,
    // Read-only fields below: surfaced for display, preserved verbatim on save.
    val enableServer: Boolean? = null,
    val serverExposeNetwork: Boolean? = null,
    val serverPort: Int? = null,
    val serverAuthToken: String? = null,
    val enableSms: Boolean? = null,
    val twilioAccountSid: String? = null,
    val twilioAuthToken: String? = null,
    val twilioFromNumber: String? = null,
    val smsAllowedNumbers: List<String>? = null,
    val smsPollSeconds: Int? = null,
    val enableTelegram: Boolean? = null,
    val telegramBotToken: String? = null,
    val telegramAllowedUsername: String? = null,
) {
    companion object {
        /** Placeholder the server returns for, and accepts in place of, secret values. */
        const val MASKED = "********"
    }
}

package dad.idreamof.diesel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared JSON codec. Kotlin properties are camelCase; the wire format is snake_case. */
@OptIn(ExperimentalSerializationApi::class)
val DieselJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** Thrown for non-2xx responses. [error] is the server's message when one was parseable. */
class ApiException(val code: Int, val error: String) : Exception("HTTP $code: $error") {
    val isBusy: Boolean get() = code == 409
    val isUnauthorized: Boolean get() = code == 401
}

/**
 * Adds `Authorization: Bearer <token>` to every request when a token is configured.
 * Reads the live config so a token change in Settings takes effect without rebuilding
 * the client.
 */
class AuthInterceptor(private val config: StateFlow<ConnectionConfig>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = config.value.token
        val request =
            if (token.isBlank()) chain.request()
            else chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        return chain.proceed(request)
    }
}

/** Builds the [OkHttpClient] used for REST, WebSocket, and media loading. */
fun buildHttpClient(config: StateFlow<ConnectionConfig>): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(config))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // The WebSocket carries its own keep-alive; no client-side ping needed.
        .build()

/**
 * Typed wrapper over the Diesel REST API. WebSocket streaming lives in [DieselSocket];
 * media bytes are loaded by Coil / MediaPlayer using URLs from [mediaUrl].
 */
class DieselApi(
    private val client: OkHttpClient,
    private val connection: StateFlow<ConnectionConfig>,
) {
    private val jsonType = "application/json".toMediaType()

    private val base: String get() = connection.value.apiBase

    // --- chat ---------------------------------------------------------------

    /** GET /state — conversation snapshot for a freshly-connected client. */
    suspend fun getState(): State =
        get("/state").decode()

    /** POST /send — post a user message. Throws [ApiException] with [isBusy] on 409. */
    suspend fun send(text: String, origin: String) {
        post("/send", SendRequest(text = text, origin = origin)).discard()
    }

    /** POST /clear — wipe the conversation. */
    suspend fun clear() {
        post("/clear", body = null).discard()
    }

    /** POST /transcribe — upload audio for STT; the recognized text is posted as a turn. */
    suspend fun transcribe(audio: File, mediaType: String, origin: String): TranscribeResponse {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(mediaType.toMediaType()))
            .addFormDataPart("origin", origin)
            .build()
        val request = Request.Builder().url(base + "/transcribe").post(multipart).build()
        return execute(request).decode()
    }

    // --- settings -----------------------------------------------------------

    /** GET /settings — current configuration (secrets masked). */
    suspend fun getSettings(): AppSettings =
        get("/settings").decode()

    /** POST /settings — persist configuration; returns the saved settings (secrets masked). */
    suspend fun saveSettings(settings: AppSettings): AppSettings =
        post("/settings", settings).decode()

    /** POST /settings/models — list models for an LLM/STT/TTS service. */
    suspend fun listModels(request: ProbeRequest): ModelsResponse =
        post("/settings/models", request).decode()

    /** POST /settings/test — probe a service connection; returns a human-readable status. */
    suspend fun testConnection(request: ProbeRequest): String =
        post("/settings/test", request).decode<TestResponse>().status

    /**
     * POST /settings/test-tts — synthesize a sample. Returns WAV bytes on success, or
     * null when the server reported a JSON error (the message is returned via [onError]).
     */
    suspend fun testTts(request: ProbeRequest, onError: (String) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(base + "/settings/test-tts")
                .post(DieselJson.encodeToString(request).toRequestBody(jsonType))
                .build()
            rawExecute(req).use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) throw ApiException(resp.code, parseError(bytes))
                if (resp.header("Content-Type")?.contains("application/json") == true) {
                    onError(runCatching { DieselJson.decodeFromString<ApiError>(String(bytes)).error }
                        .getOrDefault("TTS synthesis failed"))
                    null
                } else {
                    bytes
                }
            }
        }

    // --- media --------------------------------------------------------------

    /** Absolute URL for a portrait/audio path supplied by /state or a WebSocket event. */
    fun mediaUrl(path: String): String = connection.value.resolve(path)

    /** Absolute URL for the freshest portrait. */
    fun latestPortraitUrl(): String = base + "/portrait/latest"

    // --- plumbing -----------------------------------------------------------

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        bodyOf(Request.Builder().url(base + path).get().build())
    }

    private suspend fun post(path: String, body: Any?): String = withContext(Dispatchers.IO) {
        val requestBody = when (body) {
            null -> ByteArray(0).toRequestBody(jsonType)
            is SendRequest -> DieselJson.encodeToString(body).toRequestBody(jsonType)
            is AppSettings -> DieselJson.encodeToString(body).toRequestBody(jsonType)
            is ProbeRequest -> DieselJson.encodeToString(body).toRequestBody(jsonType)
            else -> error("Unsupported request body: ${body::class}")
        }
        bodyOf(Request.Builder().url(base + path).post(requestBody).build())
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        bodyOf(request)
    }

    private fun bodyOf(request: Request): String =
        rawExecute(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, parseError(text.toByteArray()))
            }
            text
        }

    private fun rawExecute(request: Request): Response = client.newCall(request).execute()

    private fun parseError(bytes: ByteArray): String =
        runCatching { DieselJson.decodeFromString<ApiError>(String(bytes)).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "request failed"

    private inline fun <reified T> String.decode(): T =
        if (isBlank()) error("empty response body") else DieselJson.decodeFromString(this)

    /** Discard a body we don't need (e.g. the 202/204 of /send and /clear). */
    private fun String.discard() {
        // Success was already asserted by bodyOf(); nothing to parse.
    }
}

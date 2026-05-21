package dad.idreamof.diesel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dad.idreamof.diesel.data.ApiException
import dad.idreamof.diesel.data.AppContainer
import dad.idreamof.diesel.data.Event
import dad.idreamof.diesel.data.EventType
import dad.idreamof.diesel.data.Message
import dad.idreamof.diesel.data.Orientation
import dad.idreamof.diesel.data.RECORDING_MEDIA_TYPE
import dad.idreamof.diesel.data.WsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Connection lifecycle of the WebSocket event stream. */
enum class ConnState { Connecting, Connected, Disconnected }

/** Everything the chat screen renders. */
data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val status: String = "",
    val inFlight: Boolean = false,
    /** Absolute portrait URL, or null when no portrait has been rendered. */
    val portraitUrl: String? = null,
    val connection: ConnState = ConnState.Connecting,
    val isRecording: Boolean = false,
    /** Whether spoken replies (`audio_ready`) are played aloud. User-toggled in the top bar. */
    val ttsEnabled: Boolean = true,
    /**
     * Orientation requested for the next turn's portrait — see [Orientation].
     * Derived from device rotation (see [ChatViewModel.setImageOrientation]); not user-set.
     */
    val imageOrientation: String = Orientation.LANDSCAPE,
    /** Transient one-shot message for a snackbar; cleared via [ChatViewModel.dismissNotice]. */
    val notice: String? = null,
)

/**
 * Drives the shared Diesel conversation: seeds from GET /state, then keeps a `/ws`
 * socket open (reconnecting with backoff) and folds incoming [Event]s into [uiState].
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val api = container.api
    private val recorder = container.newAudioRecorder()
    private val clientId = container.connectionStore.clientId

    private val _uiState = MutableStateFlow(
        ChatUiState(ttsEnabled = container.connectionStore.ttsEnabled)
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Restart the whole session whenever the connection config changes.
        viewModelScope.launch {
            container.connectionStore.config.collectLatest { runSession() }
        }
    }

    // --- session loop -------------------------------------------------------

    private suspend fun runSession() {
        set { it.copy(connection = ConnState.Connecting, portraitUrl = null, portraitProgress = null) }
        refreshState()

        var backoffMs = 1_000L
        while (true) {
            try {
                container.socket.connect().collect { event ->
                    when (event) {
                        is WsEvent.Connected -> {
                            backoffMs = 1_000L
                            set { it.copy(connection = ConnState.Connected) }
                        }

                        is WsEvent.Frame -> applyEvent(event.event)

                        is WsEvent.Disconnected -> set {
                            it.copy(
                                connection = ConnState.Disconnected,
                                status = "Disconnected: ${event.reason}",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                set { it.copy(connection = ConnState.Disconnected) }
            }

            // Socket dropped: back off, then reconnect and re-sync per the API guidance.
            set { it.copy(connection = ConnState.Connecting) }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            refreshState()
        }
    }

    private suspend fun refreshState() {
        runCatching { api.getState() }
            .onSuccess { state ->
                set {
                    it.copy(
                        messages = state.history,
                        inFlight = state.inFlight,
                        status = state.status,
                        portraitUrl = state.portraitUrl?.let(api::mediaUrl) ?: it.portraitUrl,
                    )
                }
            }
            .onFailure { e -> set { it.copy(notice = friendly(e)) } }
    }

    private fun applyEvent(e: Event) {
        when (e.type) {
            EventType.ACK -> set {
                it.copy(
                    status = e.status ?: it.status,
                    inFlight = e.inFlight ?: it.inFlight,
                    portraitUrl = e.portraitUrl?.let(api::mediaUrl) ?: it.portraitUrl,
                )
            }

            EventType.TURN_STARTED -> set {
                it.copy(
                    inFlight = true,
                    messages = it.messages + listOfNotNull(e.user),
                )
            }

            EventType.TURN_COMPLETE -> set {
                it.copy(
                    inFlight = false,
                    messages = it.messages + listOfNotNull(e.assistant),
                )
            }

            EventType.PORTRAIT_READY -> set {
                it.copy(
                    portraitUrl = e.portraitUrl?.let(api::mediaUrl) ?: it.portraitUrl,
                    portraitProgress = null,
                )
            }

            EventType.PORTRAIT_PROGRESS -> set {
                it.copy(
                    portraitUrl = e.portraitUrl?.let(api::mediaUrl) ?: it.portraitUrl,
                    portraitProgress = if (e.step != null && e.total != null) e.step to e.total
                    else it.portraitProgress,
                )
            }

            EventType.AUDIO_READY -> {
                val url = e.audioUrl
                // Broadcast event; only play audio for turns this client originated,
                // and only when the user has TTS switched on.
                if (!url.isNullOrEmpty() && e.origin == clientId && _uiState.value.ttsEnabled) {
                    container.audioPlayer.play(
                        api.mediaUrl(url),
                        container.connectionStore.config.value.token,
                    )
                }
            }

            EventType.TURN_ERROR -> set {
                it.copy(inFlight = false, notice = e.error ?: "The turn failed")
            }

            EventType.STATUS -> set { it.copy(status = e.status ?: it.status) }

            EventType.CLEARED -> set {
                it.copy(
                    messages = emptyList(),
                    portraitUrl = null,
                    portraitProgress = null,
                    inFlight = false,
                )
            }

            EventType.BUSY -> set { it.copy(notice = "Server is busy with another turn") }
        }
    }

    // --- user actions -------------------------------------------------------

    fun updateDraft(text: String) = set { it.copy(draft = text) }

    /** Toggles spoken replies. Switching off also stops any audio playing now. */
    fun toggleTts() {
        val enabled = !_uiState.value.ttsEnabled
        if (!enabled) container.audioPlayer.stop()
        container.connectionStore.setTtsEnabled(enabled)
        set { it.copy(ttsEnabled = enabled) }
    }

    /**
     * Sets the portrait orientation for upcoming turns. Driven programmatically from the
     * device's screen rotation by the chat screen — there is no user-facing control.
     */
    fun setImageOrientation(orientation: String) = set {
        if (it.imageOrientation == orientation) it else it.copy(imageOrientation = orientation)
    }

    /** Posts the current draft. The user message reappears via the `turn_started` event. */
    fun send() {
        val text = _uiState.value.draft.trim()
        if (text.isEmpty() || _uiState.value.inFlight) return
        val orientation = _uiState.value.imageOrientation
        set { it.copy(draft = "") }
        viewModelScope.launch {
            runCatching { api.send(text, clientId, orientation) }
                .onFailure { e -> set { it.copy(draft = text, notice = friendly(e)) } }
        }
    }

    /** Begins microphone capture. Caller must hold the RECORD_AUDIO permission. */
    fun startRecording() {
        val started = recorder.start()
        set {
            it.copy(
                isRecording = started,
                notice = if (started) it.notice else "Could not start recording",
            )
        }
    }

    /** Stops capture and uploads the clip to /transcribe; the result is posted as a turn. */
    fun stopRecordingAndSend() {
        if (!recorder.isRecording) return
        val file = recorder.stop()
        set { it.copy(isRecording = false) }
        if (file == null) {
            set { it.copy(notice = "No audio captured") }
            return
        }
        val orientation = _uiState.value.imageOrientation
        viewModelScope.launch {
            set { it.copy(status = "Transcribing…") }
            runCatching { api.transcribe(file, RECORDING_MEDIA_TYPE, clientId, orientation) }
                .onSuccess { response ->
                    when {
                        response.text.isBlank() ->
                            set { it.copy(notice = "No speech detected") }

                        response.sendError != null ->
                            set { it.copy(notice = "Send failed: ${response.sendError}") }
                        // Otherwise the turn was posted; the WebSocket delivers it.
                    }
                }
                .onFailure { e -> set { it.copy(notice = friendly(e)) } }
        }
    }

    fun cancelRecording() {
        recorder.stop()
        set { it.copy(isRecording = false) }
    }

    fun dismissNotice() = set { it.copy(notice = null) }

    override fun onCleared() {
        recorder.stop()
        container.audioPlayer.stop()
    }

    // --- helpers ------------------------------------------------------------

    private inline fun set(block: (ChatUiState) -> ChatUiState) = _uiState.update(block)

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> when {
            e.isUnauthorized -> "Unauthorized — check the auth token in Settings"
            e.isBusy -> "Server is busy with another turn"
            else -> e.error
        }

        else -> e.message ?: "Network error — is the server reachable?"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as DieselApplication
                ChatViewModel(app.container)
            }
        }
    }
}

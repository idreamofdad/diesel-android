package dad.idreamof.diesel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dad.idreamof.diesel.data.ApiException
import dad.idreamof.diesel.data.AppContainer
import dad.idreamof.diesel.data.AppSettings
import dad.idreamof.diesel.data.ConnectionConfig
import dad.idreamof.diesel.data.ProbeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Service kinds that can be probed via /settings/models and /settings/test. */
object ProbeKind {
    const val LLM = "llm"
    const val STT = "stt"
    const val TTS = "tts"
    const val IMAGE = "image"
}

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    /** Client-side connection config (host/port/token) — local, not part of AppSettings. */
    val connection: ConnectionConfig = ConnectionConfig(),
    val saving: Boolean = false,
    /** Models fetched per kind for the LLM/STT/TTS dropdowns. */
    val models: Map<String, List<String>> = emptyMap(),
    /** Identifier of the probe currently running ("llm"/"stt"/"tts"/"image"/"tts-sample"), or null. */
    val busyProbe: String? = null,
    /** Transient snackbar message; cleared via [SettingsViewModel.dismissNotice]. */
    val notice: String? = null,
)

/** Backs the settings screen: server [AppSettings] plus the local connection config. */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val api = container.api

    private val _uiState = MutableStateFlow(
        SettingsUiState(connection = container.connectionStore.config.value)
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { api.getSettings() }
                .onSuccess { s -> set { it.copy(loading = false, settings = s) } }
                .onFailure { e -> set { it.copy(loading = false, notice = friendly(e)) } }
        }
    }

    // --- editing ------------------------------------------------------------

    fun editSettings(block: (AppSettings) -> AppSettings) =
        set { it.copy(settings = block(it.settings)) }

    fun editConnection(block: (ConnectionConfig) -> ConnectionConfig) =
        set { it.copy(connection = block(it.connection)) }

    // --- persistence --------------------------------------------------------

    /** Saves the client connection config; the chat session reconnects automatically. */
    fun saveConnection() {
        container.connectionStore.save(_uiState.value.connection)
        set { it.copy(notice = "Connection saved — reconnecting") }
    }

    /** POST /settings — persists server-side configuration. */
    fun saveSettings() {
        viewModelScope.launch {
            set { it.copy(saving = true) }
            runCatching { api.saveSettings(_uiState.value.settings) }
                .onSuccess { saved ->
                    set { it.copy(saving = false, settings = saved, notice = "Settings saved") }
                }
                .onFailure { e -> set { it.copy(saving = false, notice = friendly(e)) } }
        }
    }

    // --- probes -------------------------------------------------------------

    /** POST /settings/models — populates the model dropdown for [kind]. */
    fun fetchModels(kind: String) {
        val probe = probeFor(kind) ?: return
        viewModelScope.launch {
            set { it.copy(busyProbe = kind) }
            runCatching { api.listModels(probe) }
                .onSuccess { response ->
                    set {
                        it.copy(
                            busyProbe = null,
                            models = it.models + (kind to response.models),
                            notice = response.error
                                ?: "Loaded ${response.models.size} model(s)",
                        )
                    }
                }
                .onFailure { e -> set { it.copy(busyProbe = null, notice = friendly(e)) } }
        }
    }

    /** POST /settings/test — probes a service and shows the returned status string. */
    fun testConnection(kind: String) {
        val probe = probeFor(kind) ?: return
        viewModelScope.launch {
            set { it.copy(busyProbe = kind) }
            runCatching { api.testConnection(probe) }
                .onSuccess { status -> set { it.copy(busyProbe = null, notice = status) } }
                .onFailure { e -> set { it.copy(busyProbe = null, notice = friendly(e)) } }
        }
    }

    /** POST /settings/test-tts — synthesizes and plays a short voice sample. */
    fun testTts() {
        val probe = probeFor(ProbeKind.TTS) ?: return
        viewModelScope.launch {
            set { it.copy(busyProbe = "tts-sample") }
            runCatching {
                api.testTts(probe) { error -> set { it.copy(notice = "TTS: $error") } }
            }
                .onSuccess { bytes ->
                    set { it.copy(busyProbe = null) }
                    if (bytes != null) {
                        val file = File(container.cacheDir, "tts_sample.wav")
                        file.writeBytes(bytes)
                        container.audioPlayer.play(Uri.fromFile(file).toString(), token = "")
                        set { it.copy(notice = "Playing TTS sample") }
                    }
                }
                .onFailure { e -> set { it.copy(busyProbe = null, notice = friendly(e)) } }
        }
    }

    fun dismissNotice() = set { it.copy(notice = null) }

    override fun onCleared() {
        container.audioPlayer.stop()
    }

    // --- helpers ------------------------------------------------------------

    private fun probeFor(kind: String): ProbeRequest? {
        val s = _uiState.value.settings
        return when (kind) {
            ProbeKind.LLM -> ProbeRequest(kind, s.apiEndpoint, s.apiKey, s.model)
            ProbeKind.STT -> ProbeRequest(kind, s.sttEndpoint, s.sttApiKey, s.sttModel)
            ProbeKind.TTS -> ProbeRequest(kind, s.ttsEndpoint, s.ttsApiKey, s.ttsModel, s.ttsVoice)
            ProbeKind.IMAGE -> ProbeRequest(kind, endpoint = s.comfyuiEndpoint)
            else -> null
        }
    }

    private inline fun set(block: (SettingsUiState) -> SettingsUiState) = _uiState.update(block)

    private fun friendly(e: Throwable): String = when (e) {
        is ApiException -> when {
            e.isUnauthorized -> "Unauthorized — check the auth token"
            else -> e.error
        }

        else -> e.message ?: "Network error — is the server reachable?"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as DieselApplication
                SettingsViewModel(app.container)
            }
        }
    }
}

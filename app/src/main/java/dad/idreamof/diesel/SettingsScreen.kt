package dad.idreamof.diesel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dad.idreamof.diesel.data.AppSettings

/**
 * The sections of the settings page. The menu lists these; tapping one opens that
 * section's fields. "Connection" is local client config; the rest map to the
 * server's [AppSettings] via GET/POST /settings.
 */
private enum class SettingsSection(val title: String, val summary: String) {
    Connection("Connection", "Which Diesel server this app talks to"),
    LanguageModel("Language model", "API endpoint, key, model and system prompt"),
    SpeechToText("Speech to text", "Transcription endpoint and model"),
    TextToSpeech("Text to speech", "Voice synthesis endpoint and voice"),
    ImageGeneration("Image generation", "ComfyUI endpoint and image prompts"),
    Conversation("Conversation", "Theme and transcript behaviour"),
    Server("Server (read-only)", "Bridges and network exposure status");

    /** Read-only sections have nothing to persist, so the save action is hidden. */
    val isSavable: Boolean get() = this != Server
}

/**
 * Settings page. Shows a tappable menu of [SettingsSection]s; selecting one opens that
 * section, and the back arrow returns to the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var openSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissNotice()
        }
    }

    // Back returns to the menu when a section is open, otherwise leaves the screen.
    BackHandler(enabled = openSection != null) { openSection = null }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(openSection?.title ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = { openSection?.let { openSection = null } ?: onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (openSection?.isSavable == true) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = viewModel::saveSettings) {
                                Icon(Icons.Default.Done, contentDescription = "Save settings")
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(if (openSection == null) 12.dp else 8.dp),
        ) {
            when (val section = openSection) {
                null -> SettingsSection.entries.forEach { entry ->
                    SectionMenuItem(entry) { openSection = entry }
                }

                else -> SectionContent(section, state, viewModel)
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

// --- menu -------------------------------------------------------------------

/** A single tappable row in the settings menu. */
@Composable
private fun SectionMenuItem(section: SettingsSection, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    section.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- section bodies ---------------------------------------------------------

@Composable
private fun SectionContent(
    section: SettingsSection,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val settings = state.settings
    when (section) {
        SettingsSection.Connection -> {
            Text(
                "Which Diesel server this app talks to. Use 10.0.2.2 from the " +
                    "emulator, or the server's LAN IP from a device.",
                style = MaterialTheme.typography.bodySmall,
            )
            SettingField("Host", state.connection.host) { value ->
                viewModel.editConnection { it.copy(host = value) }
            }
            SettingIntField("Port", state.connection.port) { value ->
                viewModel.editConnection { it.copy(port = value ?: 7777) }
            }
            SettingField(
                label = "Auth token",
                value = state.connection.token,
                helper = "Leave blank if the server has auth disabled.",
            ) { value ->
                viewModel.editConnection { it.copy(token = value) }
            }
            Button(
                onClick = viewModel::saveConnection,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save & reconnect")
            }
        }

        SettingsSection.LanguageModel -> {
            SettingField("API endpoint", settings.apiEndpoint.orEmpty()) { value ->
                viewModel.editSettings { it.copy(apiEndpoint = value) }
            }
            SecretHint()
            SettingField("API key", settings.apiKey.orEmpty()) { value ->
                viewModel.editSettings { it.copy(apiKey = value) }
            }
            SettingField("Model", settings.model.orEmpty()) { value ->
                viewModel.editSettings { it.copy(model = value) }
            }
            ModelChips(state.models[ProbeKind.LLM]) { picked ->
                viewModel.editSettings { it.copy(model = picked) }
            }
            ProbeButtons(
                kind = ProbeKind.LLM,
                busyProbe = state.busyProbe,
                onFetchModels = { viewModel.fetchModels(ProbeKind.LLM) },
                onTest = { viewModel.testConnection(ProbeKind.LLM) },
            )
            SettingField(
                "System prompt",
                settings.systemPrompt.orEmpty(),
                singleLine = false,
            ) { value ->
                viewModel.editSettings { it.copy(systemPrompt = value) }
            }
            SettingIntField("History messages", settings.historyMessages) { value ->
                viewModel.editSettings { it.copy(historyMessages = value) }
            }
        }

        SettingsSection.SpeechToText -> {
            SettingField("STT endpoint", settings.sttEndpoint.orEmpty()) { value ->
                viewModel.editSettings { it.copy(sttEndpoint = value) }
            }
            SecretHint()
            SettingField("STT API key", settings.sttApiKey.orEmpty()) { value ->
                viewModel.editSettings { it.copy(sttApiKey = value) }
            }
            SettingField("STT model", settings.sttModel.orEmpty()) { value ->
                viewModel.editSettings { it.copy(sttModel = value) }
            }
            ModelChips(state.models[ProbeKind.STT]) { picked ->
                viewModel.editSettings { it.copy(sttModel = picked) }
            }
            ProbeButtons(
                kind = ProbeKind.STT,
                busyProbe = state.busyProbe,
                onFetchModels = { viewModel.fetchModels(ProbeKind.STT) },
                onTest = { viewModel.testConnection(ProbeKind.STT) },
            )
        }

        SettingsSection.TextToSpeech -> {
            SettingSwitch("Enable TTS", settings.enableTts ?: false) { value ->
                viewModel.editSettings { it.copy(enableTts = value) }
            }
            SettingField("TTS endpoint", settings.ttsEndpoint.orEmpty()) { value ->
                viewModel.editSettings { it.copy(ttsEndpoint = value) }
            }
            SecretHint()
            SettingField("TTS API key", settings.ttsApiKey.orEmpty()) { value ->
                viewModel.editSettings { it.copy(ttsApiKey = value) }
            }
            SettingField("TTS model", settings.ttsModel.orEmpty()) { value ->
                viewModel.editSettings { it.copy(ttsModel = value) }
            }
            ModelChips(state.models[ProbeKind.TTS]) { picked ->
                viewModel.editSettings { it.copy(ttsModel = picked) }
            }
            SettingField("Voice", settings.ttsVoice.orEmpty()) { value ->
                viewModel.editSettings { it.copy(ttsVoice = value) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.fetchModels(ProbeKind.TTS) },
                    enabled = state.busyProbe == null,
                ) { Text("Models") }
                Button(
                    onClick = viewModel::testTts,
                    enabled = state.busyProbe == null,
                ) {
                    ProbeButtonContent(
                        label = "Play sample",
                        busy = state.busyProbe == "tts-sample",
                    )
                }
            }
        }

        SettingsSection.ImageGeneration -> {
            SettingSwitch("Enable image gen", settings.enableImageGen ?: false) { value ->
                viewModel.editSettings { it.copy(enableImageGen = value) }
            }
            SettingField("ComfyUI endpoint", settings.comfyuiEndpoint.orEmpty()) { value ->
                viewModel.editSettings { it.copy(comfyuiEndpoint = value) }
            }
            SettingField(
                "Image prompt",
                settings.imagePrompt.orEmpty(),
                singleLine = false,
            ) { value -> viewModel.editSettings { it.copy(imagePrompt = value) } }
            SettingField("Clothing", settings.imageClothing.orEmpty()) { value ->
                viewModel.editSettings { it.copy(imageClothing = value) }
            }
            SettingField("Nudity", settings.imageNudity.orEmpty()) { value ->
                viewModel.editSettings { it.copy(imageNudity = value) }
            }
            SettingField(
                "Negative prompt",
                settings.imageNegativePrompt.orEmpty(),
                singleLine = false,
            ) { value -> viewModel.editSettings { it.copy(imageNegativePrompt = value) } }
            SettingIntField("Steps", settings.imageSteps) { value ->
                viewModel.editSettings { it.copy(imageSteps = value) }
            }
            OutlinedButton(
                onClick = { viewModel.testConnection(ProbeKind.IMAGE) },
                enabled = state.busyProbe == null,
            ) {
                ProbeButtonContent("Test connection", state.busyProbe == ProbeKind.IMAGE)
            }
        }

        SettingsSection.Conversation -> {
            SettingField("Theme", settings.theme.orEmpty()) { value ->
                viewModel.editSettings { it.copy(theme = value) }
            }
            SettingSwitch(
                "Continuous conversation",
                settings.continuousConversation ?: false,
            ) { value ->
                viewModel.editSettings { it.copy(continuousConversation = value) }
            }
            SettingSwitch("Save transcript to disk", settings.saveToDisk ?: false) { value ->
                viewModel.editSettings { it.copy(saveToDisk = value) }
            }
        }

        SettingsSection.Server -> {
            ReadOnlyRow("Server enabled", yesNo(settings.enableServer))
            ReadOnlyRow("Server port", settings.serverPort?.toString() ?: "—")
            ReadOnlyRow("Exposed on network", yesNo(settings.serverExposeNetwork))
            ReadOnlyRow("SMS bridge", yesNo(settings.enableSms))
            ReadOnlyRow("Telegram bridge", yesNo(settings.enableTelegram))
        }
    }
}

// --- reusable pieces --------------------------------------------------------

@Composable
private fun SettingField(
    label: String,
    value: String,
    helper: String? = null,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = helper?.let { { Text(it) } },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingIntField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { text -> onChange(text.trim().toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecretHint() {
    Text(
        text = "Secrets show as ${AppSettings.MASKED} — leave unchanged to keep the stored value.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModelChips(models: List<String>?, onPick: (String) -> Unit) {
    if (models.isNullOrEmpty()) return
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        models.forEach { model ->
            AssistChip(onClick = { onPick(model) }, label = { Text(model) })
        }
    }
}

@Composable
private fun ProbeButtons(
    kind: String,
    busyProbe: String?,
    onFetchModels: () -> Unit,
    onTest: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onFetchModels, enabled = busyProbe == null) {
            Text("Models")
        }
        OutlinedButton(onClick = onTest, enabled = busyProbe == null) {
            ProbeButtonContent("Test connection", busyProbe == kind)
        }
    }
}

@Composable
private fun ProbeButtonContent(label: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(label)
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "Yes"
    false -> "No"
    null -> "—"
}

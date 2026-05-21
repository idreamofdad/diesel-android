package dad.idreamof.diesel

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import dad.idreamof.diesel.data.Message
import dad.idreamof.diesel.data.Orientation

/**
 * The chat page: a portrait viewport, the scrolling conversation, and a message/voice
 * input. In portrait the viewport sits on top of the conversation; in landscape the two
 * sit side by side. All state comes from [ChatViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(factory = ChatViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Surface transient notices (errors, voice-input results) as a snackbar.
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissNotice()
        }
    }

    // Render orientation tracks device rotation: a portrait phone gets a wide (landscape)
    // image to suit the top viewport, and a landscape phone gets a tall (portrait) one
    // to suit the side-by-side viewport.
    val deviceOrientation = LocalConfiguration.current.orientation
    LaunchedEffect(deviceOrientation) {
        viewModel.setImageOrientation(
            if (deviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                Orientation.PORTRAIT
            } else {
                Orientation.LANDSCAPE
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startRecording() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Diesel")
                        val subtitle = state.status.ifBlank { state.connection.label() }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    ConnectionDot(state.connection)
                    IconButton(onClick = viewModel::toggleTts) {
                        Icon(
                            imageVector = if (state.ttsEnabled) {
                                Icons.Default.VolumeUp
                            } else {
                                Icons.Default.VolumeOff
                            },
                            contentDescription = if (state.ttsEnabled) {
                                "Turn off spoken replies"
                            } else {
                                "Turn on spoken replies"
                            },
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val isLandscape = deviceOrientation == Configuration.ORIENTATION_LANDSCAPE

        // Edge-to-edge disables the window's adjustResize; consume the IME
        // inset here so the input bar rises with the keyboard.
        //
        // innerPadding's bottom is the navigation-bar inset; mark it
        // consumed so the following imePadding() — whose IME inset is
        // measured from the screen bottom and already spans the nav bar —
        // doesn't add that height a second time and leave a gap above
        // the keyboard on devices with a tall nav bar.
        val contentModifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .imePadding()

        // The conversation list and input bar are identical in both layouts;
        // only the placement of the portrait around them differs.
        val conversation: @Composable (Modifier) -> Unit = { listModifier ->
            MessageList(
                messages = state.messages,
                showTyping = state.inFlight,
                modifier = listModifier,
            )
        }
        val messageInput: @Composable () -> Unit = {
            MessageInput(
                draft = state.draft,
                isRecording = state.isRecording,
                sendEnabled = state.draft.isNotBlank() && !state.inFlight,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send,
                onMicToggle = {
                    if (state.isRecording) {
                        viewModel.stopRecordingAndSend()
                    } else if (hasRecordPermission(context)) {
                        viewModel.startRecording()
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }

        if (isLandscape) {
            // Landscape: a tall portrait render beside the conversation.
            Row(modifier = contentModifier) {
                PortraitViewport(
                    portraitUrl = state.portraitUrl,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1.4f)
                ) {
                    conversation(Modifier.weight(1f).fillMaxWidth())
                    messageInput()
                }
            }
        } else {
            // Portrait: the portrait render stacked above the conversation.
            Column(modifier = contentModifier) {
                // Shrink the portrait while the soft keyboard is up so the
                // conversation and input bar keep enough room.
                val portraitHeight by animateDpAsState(
                    targetValue = if (WindowInsets.isImeVisible) 120.dp else 220.dp,
                    label = "portraitHeight",
                )
                PortraitViewport(
                    portraitUrl = state.portraitUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(portraitHeight)
                )
                conversation(Modifier.weight(1f).fillMaxWidth())
                messageInput()
            }
        }
    }
}

/** Top-of-screen image area: shows the latest portrait, or a placeholder when there is none. */
@Composable
private fun PortraitViewport(
    portraitUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (portraitUrl != null) {
            // Render steps arrive as a stream of new URLs. Each step is decoded
            // off-screen first and only swapped into view once Coil reports it
            // fully loaded, so the viewport always shows a complete image and
            // never an empty frame mid-load.
            var shownUrl by remember { mutableStateOf(portraitUrl) }

            // Visible image — only ever a URL that has finished decoding.
            // Fit (not Crop) so a wide landscape render is shown whole.
            AsyncImage(
                model = shownUrl,
                contentDescription = "Portrait",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            // Off-screen decoder for the latest step: fully transparent so it
            // is never seen, it promotes itself to the visible layer once the
            // image is decoded (a memory-cache hit there, so no reload flash).
            if (portraitUrl != shownUrl) {
                AsyncImage(
                    model = portraitUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onSuccess = { shownUrl = portraitUrl },
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0f),
                )
            }
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The scrolling conversation. Auto-scrolls to the newest message. */
@Composable
private fun MessageList(
    messages: List<Message>,
    showTyping: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val itemCount = messages.size + if (showTyping) 1 else 0

    // Key on both inputs, not their sum: when a reply arrives the typing indicator
    // is dropped (-1) as the message is appended (+1), leaving itemCount unchanged.
    LaunchedEffect(messages.size, showTyping) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message -> MessageBubble(message) }
        if (showTyping) {
            item { TypingIndicator() }
        }
    }
}

/** A single chat bubble, aligned by role. */
@Composable
private fun MessageBubble(message: Message) {
    val fromUser = message.isUser
    val bubbleColor = when {
        message.isSystem -> MaterialTheme.colorScheme.surfaceVariant
        fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        message.isSystem -> MaterialTheme.colorScheme.onSurfaceVariant
        fromUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = message.content, color = textColor)
                message.emotion?.takeIf { it.isNotBlank() }?.let { emotion ->
                    Text(
                        text = emotion,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** "Assistant is responding" placeholder shown while a turn is in flight. */
@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Thinking…",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/** Bottom input bar: text field, voice-input toggle, and send button. */
@Composable
private fun MessageInput(
    draft: String,
    isRecording: Boolean,
    sendEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicToggle: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onMicToggle,
                colors = if (isRecording) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop recording" else "Record voice message",
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    // Enter sends; Shift+Enter inserts a newline.
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (isEnter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text(if (isRecording) "Recording…" else "Message") },
                enabled = !isRecording,
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { onSend() }
                ),
            )

            FilledIconButton(onClick = onSend, enabled = sendEnabled) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Small coloured dot reflecting the WebSocket connection state. */
@Composable
private fun ConnectionDot(state: ConnState) {
    val color = when (state) {
        ConnState.Connected -> Color(0xFF4CAF50)
        ConnState.Connecting -> Color(0xFFFFB300)
        ConnState.Disconnected -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
    Spacer(Modifier.width(4.dp))
}

private fun ConnState.label(): String = when (this) {
    ConnState.Connected -> "Connected"
    ConnState.Connecting -> "Connecting…"
    ConnState.Disconnected -> "Offline"
}

private fun hasRecordPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

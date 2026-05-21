package dad.idreamof.diesel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dad.idreamof.diesel.ui.theme.DieselTheme

/** A single chat message. [fromMe] marks messages sent by the local user. */
data class ChatMessage(val text: String, val fromMe: Boolean)

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val messages = remember {
        mutableStateListOf(
            ChatMessage("Welcome to Diesel chat 👋", fromMe = false),
            ChatMessage("Type a message below to get started.", fromMe = false),
        )
    }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the newest message in view as the conversation grows.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ImageViewport(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
        }

        MessageInput(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                val trimmed = draft.trim()
                if (trimmed.isNotEmpty()) {
                    messages.add(ChatMessage(trimmed, fromMe = true))
                    draft = ""
                }
            }
        )
    }
}

/** Placeholder image area at the top of the screen. */
@Composable
private fun ImageViewport(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Image",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Chat bubble, aligned right for the local user and left for others. */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.fromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (message.fromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                color = textColor,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** Text field plus send button pinned to the bottom of the screen. */
@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Button(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier
                    .width(88.dp)
                    .padding(start = 8.dp)
            ) {
                Text("Send")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    DieselTheme {
        ChatScreen()
    }
}

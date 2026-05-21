package dad.idreamof.diesel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dad.idreamof.diesel.ui.theme.DieselTheme

/** The two top-level destinations. */
private enum class Screen { Chat, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DieselTheme {
                DieselApp()
            }
        }
    }
}

/** Hosts the chat and settings screens with a minimal back-stack of one. */
@Composable
private fun DieselApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }

    when (screen) {
        Screen.Chat -> ChatScreen(onOpenSettings = { screen = Screen.Settings })
        Screen.Settings -> {
            BackHandler { screen = Screen.Chat }
            SettingsScreen(onBack = { screen = Screen.Chat })
        }
    }
}

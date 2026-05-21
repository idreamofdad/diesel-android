package dad.idreamof.diesel.data

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

/**
 * Manual dependency graph for the app. Built once by [dad.idreamof.diesel.DieselApplication]
 * and reached by ViewModels via the application context.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val connectionStore: ConnectionStore = ConnectionStore(appContext)

    /** Single OkHttp client shared by REST, WebSocket, and Coil image loading. */
    val httpClient: OkHttpClient = buildHttpClient(connectionStore.config)

    val api: DieselApi = DieselApi(httpClient, connectionStore.config)

    val socket: DieselSocket = DieselSocket(httpClient, connectionStore)

    val audioPlayer: AudioPlayer = AudioPlayer(appContext)

    /** Cache directory for transient media (recorded clips, TTS samples). */
    val cacheDir: File get() = appContext.cacheDir

    fun newAudioRecorder(): AudioRecorder = AudioRecorder(appContext)
}

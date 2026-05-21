package dad.idreamof.diesel.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.File

/** Audio media type used for /transcribe uploads (MediaRecorder MPEG-4/AAC output). */
const val RECORDING_MEDIA_TYPE = "audio/mp4"

/**
 * Records microphone input to an MPEG-4/AAC file in the cache directory, suitable for
 * upload to POST /transcribe. Not thread-safe; drive it from one place (the ViewModel).
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Begins recording. Returns false if the recorder could not be started. */
    fun start(): Boolean {
        stop()
        val file = File(context.cacheDir, "voice_input.m4a")
        val mediaRecorder = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(SAMPLE_RATE)
            setOutputFile(file.absolutePath)
        }
        return runCatching {
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            outputFile = file
            true
        }.getOrElse {
            runCatching { mediaRecorder.release() }
            false
        }
    }

    /** Stops recording and returns the captured file, or null if nothing was recorded. */
    fun stop(): File? {
        val active = recorder ?: return null
        recorder = null
        val file = outputFile
        outputFile = null
        return runCatching {
            active.stop()
            active.release()
            file?.takeIf { it.length() > 0 }
        }.getOrElse {
            runCatching { active.release() }
            null
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Streams TTS audio from /audio/{id} URLs. The bearer token is passed as an HTTP
 * header so playback works when the server requires auth.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(url: String, token: String) {
        stop()
        val headers = if (token.isBlank()) {
            emptyMap()
        } else {
            mapOf("Authorization" to "Bearer $token")
        }
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            runCatching {
                setDataSource(context, Uri.parse(url), headers)
                prepareAsync()
            }.onFailure { stop() }
        }
    }

    fun stop() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}

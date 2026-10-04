package com.lagradost.cloudstream3.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.mvvm.logError
import java.io.File
import java.util.ArrayDeque

class WatchTogetherVoiceHelper(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: File? = null
    private var recordStartTime: Long = 0L
    private var activeMediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    // Continuous Live Mic (ON / OFF Toggle)
    var isLiveMicOn: Boolean = false
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    private var liveChunkRunnable: Runnable? = null
    private var onLiveChunkReady: ((base64Audio: String, durationMs: Long) -> Unit)? = null

    // Playback Queue to prevent overlapping voice messages
    private val playbackQueue = ArrayDeque<String>()
    private var isPlayingQueue: Boolean = false
    private var queueStartCallback: (() -> Unit)? = null
    private var queueAllCompleteCallback: (() -> Unit)? = null

    val isRecording: Boolean
        get() = mediaRecorder != null

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun startLiveMic(onChunkReady: (base64Audio: String, durationMs: Long) -> Unit): Boolean {
        if (!hasMicPermission()) return false
        stopLiveMic()

        isLiveMicOn = true
        onLiveChunkReady = onChunkReady

        val started = startRecording()
        if (!started) {
            isLiveMicOn = false
            return false
        }

        scheduleNextChunk()
        return true
    }

    private fun scheduleNextChunk() {
        val runnable = Runnable {
            if (!isLiveMicOn) return@Runnable

            val result = stopRecording(cancel = false)
            if (result != null) {
                val (base64, duration) = result
                onLiveChunkReady?.invoke(base64, duration)
            }

            if (isLiveMicOn) {
                startRecording()
                scheduleNextChunk()
            }
        }
        liveChunkRunnable = runnable
        mainHandler.postDelayed(runnable, 1200L) // Fast 1.2-second live audio packets for ultra-low latency
    }

    @Synchronized
    fun stopLiveMic() {
        isLiveMicOn = false
        liveChunkRunnable?.let { mainHandler.removeCallbacks(it) }
        liveChunkRunnable = null

        val result = stopRecording(cancel = false)
        if (result != null) {
            val (base64, duration) = result
            onLiveChunkReady?.invoke(base64, duration)
        }
        onLiveChunkReady = null
    }

    @Synchronized
    fun startRecording(): Boolean {
        if (!hasMicPermission()) return false
        stopRecording(cancel = true)

        return try {
            val file = File(context.cacheDir, "wt_rec_${System.currentTimeMillis()}.m4a")
            currentRecordFile = file
            recordStartTime = System.currentTimeMillis()

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                // VOICE_COMMUNICATION enables native Android hardware Noise Suppression (NS),
                // Acoustic Echo Cancellation (AEC), and Automatic Gain Control (AGC) for crystal clear voice
                try {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                } catch (_: Throwable) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24000)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            true
        } catch (e: Throwable) {
            logError(e)
            stopRecording(cancel = true)
            false
        }
    }

    @Synchronized
    fun stopRecording(cancel: Boolean = false): Pair<String, Long>? {
        val recorder = mediaRecorder ?: return null
        mediaRecorder = null
        val file = currentRecordFile
        currentRecordFile = null

        val duration = System.currentTimeMillis() - recordStartTime

        try {
            recorder.stop()
        } catch (_: Throwable) {
        } finally {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
        }

        if (cancel || duration < 250L || file == null || !file.exists() || file.length() < 100) {
            file?.delete()
            return null
        }

        return try {
            val bytes = file.readBytes()
            file.delete()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Pair(base64, duration)
        } catch (e: Throwable) {
            logError(e)
            file.delete()
            null
        }
    }

    @Synchronized
    fun enqueueVoice(
        base64Audio: String,
        onStart: () -> Unit,
        onAllComplete: () -> Unit
    ) {
        queueStartCallback = onStart
        queueAllCompleteCallback = onAllComplete
        playbackQueue.add(base64Audio)
        if (!isPlayingQueue) {
            playNextInQueue()
        }
    }

    private fun playNextInQueue() {
        val nextAudio = synchronized(this) {
            if (playbackQueue.isEmpty()) {
                isPlayingQueue = false
                currentPlayingFile?.delete()
                currentPlayingFile = null
                queueAllCompleteCallback?.invoke()
                return
            }
            isPlayingQueue = true
            playbackQueue.poll()
        } ?: return

        try {
            val bytes = Base64.decode(nextAudio, Base64.NO_WRAP)
            val tempFile = File(context.cacheDir, "wt_play_${System.currentTimeMillis()}.m4a")
            tempFile.writeBytes(bytes)
            currentPlayingFile = tempFile

            val mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnCompletionListener {
                stopSinglePlayback()
                playNextInQueue()
            }
            mp.setOnErrorListener { _, _, _ ->
                stopSinglePlayback()
                playNextInQueue()
                true
            }
            mp.prepare()
            mp.start()
            activeMediaPlayer = mp
            queueStartCallback?.invoke()
        } catch (e: Throwable) {
            logError(e)
            stopSinglePlayback()
            playNextInQueue()
        }
    }

    private fun stopSinglePlayback() {
        try {
            activeMediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeMediaPlayer?.release()
        } catch (_: Throwable) {
        }
        activeMediaPlayer = null
        currentPlayingFile?.delete()
        currentPlayingFile = null
    }

    @Synchronized
    fun stopPlayback() {
        playbackQueue.clear()
        isPlayingQueue = false
        stopSinglePlayback()
        queueAllCompleteCallback?.invoke()
    }

    fun release() {
        stopLiveMic()
        stopPlayback()
    }
}

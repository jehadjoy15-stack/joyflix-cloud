package com.lagradost.cloudstream3.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.mvvm.logError
import java.io.File

class WatchTogetherVoiceHelper(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: File? = null
    private var recordStartTime: Long = 0L
    private var activeMediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null

    val isRecording: Boolean
        get() = mediaRecorder != null

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
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
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32000)
                setAudioSamplingRate(24000)
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

        if (cancel || duration < 500L || file == null || !file.exists() || file.length() < 100) {
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
    fun playVoice(
        base64Audio: String,
        onStart: () -> Unit,
        onComplete: () -> Unit
    ) {
        stopPlayback()
        try {
            val bytes = Base64.decode(base64Audio, Base64.NO_WRAP)
            val tempFile = File(context.cacheDir, "wt_play_${System.currentTimeMillis()}.m4a")
            tempFile.writeBytes(bytes)
            currentPlayingFile = tempFile

            val mp = MediaPlayer()
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnCompletionListener {
                onComplete()
                stopPlayback()
            }
            mp.setOnErrorListener { _, _, _ ->
                onComplete()
                stopPlayback()
                true
            }
            mp.prepare()
            mp.start()
            activeMediaPlayer = mp
            onStart()
        } catch (e: Throwable) {
            logError(e)
            onComplete()
        }
    }

    @Synchronized
    fun stopPlayback() {
        try {
            activeMediaPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeMediaPlayer?.release()
        } catch (_: Throwable) {
        }
        activeMediaPlayer = null

        currentPlayingFile?.let {
            try {
                it.delete()
            } catch (_: Throwable) {
            }
        }
        currentPlayingFile = null
    }

    fun release() {
        stopRecording(cancel = true)
        stopPlayback()
    }
}

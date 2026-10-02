package com.lagradost.cloudstream3.utils

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import kotlin.random.Random

object WatchTogetherManager {
    private const val TAG = "WatchTogether"
    const val DEFAULT_FIREBASE_URL = "https://joyflix-1eb68-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    val myUserId: String by lazy {
        "user_" + UUID.randomUUID().toString().substring(0, 6)
    }

    @Serializable
    data class PlaybackState(
        @JsonProperty("isPlaying") val isPlaying: Boolean = false,
        @JsonProperty("position") val position: Long = 0L,
        @JsonProperty("updatedAt") val updatedAt: Long = 0L,
        @JsonProperty("updatedBy") val updatedBy: String = ""
    )

    @Serializable
    data class RoomData(
        @JsonProperty("roomId") val roomId: String = "",
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("streamUrl") val streamUrl: String? = null,
        @JsonProperty("hostId") val hostId: String = "",
        @JsonProperty("createdAt") val createdAt: Long = 0L,
        @JsonProperty("playback") val playback: PlaybackState = PlaybackState()
    )

    var currentRoomId: String? = null
        private set

    var currentRoom: RoomData? = null
        private set

    var isHost: Boolean = false
        private set

    val isInRoom: Boolean get() = !currentRoomId.isNullOrBlank()

    @Volatile
    var isApplyingRemoteSync = false

    private var syncJob: Job? = null
    private var onRemoteSyncCallback: ((PlaybackState) -> Unit)? = null
    private var onRoomClosedCallback: (() -> Unit)? = null

    private fun getBaseUrl(): String {
        return DEFAULT_FIREBASE_URL.removeSuffix("/")
    }

    private fun generateRoomCode(): String {
        val num = Random.nextInt(1000, 9999)
        return "JOY-$num"
    }

    suspend fun createRoom(
        title: String?,
        streamUrl: String?,
        currentPos: Long,
        isPlaying: Boolean
    ): Result<String> {
        return try {
            val roomId = generateRoomCode()
            val room = RoomData(
                roomId = roomId,
                title = title,
                streamUrl = streamUrl,
                hostId = myUserId,
                createdAt = System.currentTimeMillis(),
                playback = PlaybackState(
                    isPlaying = isPlaying,
                    position = currentPos,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = myUserId
                )
            )

            val url = "${getBaseUrl()}/rooms/$roomId.json"
            val jsonString = room.toJson()
            val body = jsonString.toRequestBody(JSON_MEDIA_TYPE)
            val res = app.put(url, requestBody = body)

            if (res.text.contains("Permission denied", ignoreCase = true)) {
                return Result.failure(Exception("Firebase Permission Denied. Please enable read/write rules in Firebase Console."))
            }

            currentRoomId = roomId
            currentRoom = room
            isHost = true
            startListening()

            Log.i(TAG, "Created room: $roomId")
            Result.success(roomId)
        } catch (e: Throwable) {
            logError(e)
            Result.failure(e)
        }
    }

    suspend fun joinRoom(
        inputCode: String,
        onRoomLoaded: (RoomData) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val trimmed = inputCode.trim().uppercase().replace(" ", "")
            val roomId = if (trimmed.startsWith("JOY-")) {
                trimmed
            } else if (trimmed.startsWith("JOY")) {
                "JOY-" + trimmed.removePrefix("JOY").removePrefix("-")
            } else {
                "JOY-$trimmed"
            }

            val url = "${getBaseUrl()}/rooms/$roomId.json"
            val res = app.get(url)
            val json = res.text

            if (json.contains("Permission denied", ignoreCase = true)) {
                onError("Firebase Permission Denied. Please check Firebase database rules.")
                return
            }

            if (json.isBlank() || json == "null") {
                onError("Room not found ($roomId)")
                return
            }

            val room = AppUtils.parseJson<RoomData>(json)
            currentRoomId = roomId
            currentRoom = room
            isHost = (room.hostId == myUserId)
            startListening()

            Log.i(TAG, "Joined room: $roomId")
            onRoomLoaded(room)
        } catch (e: Throwable) {
            logError(e)
            onError(e.message ?: "Failed to join room")
        }
    }

    fun leaveRoom() {
        val roomToClean = currentRoomId
        val host = isHost
        currentRoomId = null
        currentRoom = null
        isHost = false
        stopListening()

        if (host && roomToClean != null) {
            ioSafe {
                try {
                    app.delete("${getBaseUrl()}/rooms/$roomToClean.json")
                } catch (e: Throwable) {
                    logError(e)
                }
            }
        }
    }

    fun broadcastPlayback(isPlaying: Boolean, position: Long) {
        val roomId = currentRoomId ?: return
        if (isApplyingRemoteSync) return

        ioSafe {
            try {
                val state = PlaybackState(
                    isPlaying = isPlaying,
                    position = position,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = myUserId
                )
                val url = "${getBaseUrl()}/rooms/$roomId/playback.json"
                val body = state.toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.patch(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun setOnRemoteSyncListener(listener: ((PlaybackState) -> Unit)?) {
        onRemoteSyncCallback = listener
    }

    fun setOnRoomClosedListener(listener: (() -> Unit)?) {
        onRoomClosedCallback = listener
    }

    private fun startListening() {
        stopListening()
        val roomId = currentRoomId ?: return

        syncJob = ioSafe {
            val url = "${getBaseUrl()}/rooms/$roomId/playback.json"
            while (isActive && currentRoomId == roomId) {
                try {
                    delay(1200)
                    if (!isActive || currentRoomId != roomId) break

                    val res = app.get(url)
                    val text = res.text
                    if (text.isNotBlank() && text != "null") {
                        if (!text.contains("Permission denied", ignoreCase = true)) {
                            val state = AppUtils.parseJson<PlaybackState>(text)
                            if (state.updatedBy != myUserId) {
                                onRemoteSyncCallback?.invoke(state)
                            }
                        }
                    } else if (text == "null" && !isHost) {
                        // Room was closed / deleted by host
                        leaveRoom()
                        onRoomClosedCallback?.invoke()
                        break
                    }
                } catch (e: Throwable) {
                    // Retry silently on connection hiccups
                }
            }
        }
    }

    private fun stopListening() {
        syncJob?.cancel()
        syncJob = null
    }
}

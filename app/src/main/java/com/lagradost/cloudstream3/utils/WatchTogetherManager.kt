package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.util.Log
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.DialogWatchTogetherBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.txt
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
    const val JOYCALL_SERVER_URL = "https://joycall.onrender.com"
    const val DEFAULT_FIREBASE_URL = "https://joyflix-1eb68-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    private const val PREFS_KEY_NICKNAME = "watch_together_nickname"

    val myUserId: String by lazy {
        "user_" + UUID.randomUUID().toString().substring(0, 6)
    }

    @Serializable
    data class MemberData(
        @JsonProperty("userId") val userId: String = "",
        @JsonProperty("name") val name: String = "",
        @JsonProperty("isHost") val isHost: Boolean = false,
        @JsonProperty("joinedAt") val joinedAt: Long = 0L,
        @JsonProperty("socketId") val socketId: String? = null,
        @JsonProperty("voiceActive") val voiceActive: Boolean = false
    )

    @Serializable
    data class PlaybackState(
        @JsonProperty("isPlaying") val isPlaying: Boolean = false,
        @JsonProperty("position") val position: Long = 0L,
        @JsonProperty("updatedAt") val updatedAt: Long = 0L,
        @JsonProperty("updatedBy") val updatedBy: String = "",
        @JsonProperty("episodeIndex") val episodeIndex: Int? = null
    )

    @Serializable
    data class ChatMessage(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("senderId") val senderId: String = "",
        @JsonProperty("senderName") val senderName: String = "",
        @JsonProperty("text") val text: String = "",
        @JsonProperty("timestamp") val timestamp: Long = 0L
    )

    @Serializable
    data class VoiceMessage(
        @JsonProperty("id") val id: String = "",
        @JsonProperty("senderId") val senderId: String = "",
        @JsonProperty("senderName") val senderName: String = "",
        @JsonProperty("audioBase64") val audioBase64: String = "",
        @JsonProperty("durationMs") val durationMs: Long = 0L,
        @JsonProperty("timestamp") val timestamp: Long = 0L
    )

    @Serializable
    data class RoomData(
        @JsonProperty("roomId") val roomId: String = "",
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("streamUrl") val streamUrl: String? = null,
        @JsonProperty("mediaUrl") val mediaUrl: String? = null,
        @JsonProperty("apiName") val apiName: String? = null,
        @JsonProperty("episodeId") val episodeId: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("season") val season: Int? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("tvType") val tvType: String? = null,
        @JsonProperty("hostId") val hostId: String = "",
        @JsonProperty("hostName") val hostName: String = "",
        @JsonProperty("createdAt") val createdAt: Long = 0L,
        @JsonProperty("playback") val playback: PlaybackState = PlaybackState(),
        @JsonProperty("episodeIndex") val episodeIndex: Int? = null,
        @JsonProperty("members") val members: List<MemberData> = emptyList(),
        @JsonProperty("messages") val messages: List<ChatMessage> = emptyList(),
        @JsonProperty("lastMessage") val lastMessage: ChatMessage? = null,
        @JsonProperty("lastVoice") val lastVoice: VoiceMessage? = null
    )

    var currentRoomId: String? = null
        private set

    var currentRoom: RoomData? = null
        private set

    var isHost: Boolean = false
        private set

    var currentEpisodeIndex: Int? = null

    val isInRoom: Boolean get() = !currentRoomId.isNullOrBlank()

    @Volatile
    var isApplyingRemoteSync = false

    @Volatile
    var isCreatingWatchParty = false

    @Volatile
    var pendingHostNickname: String? = null

    @Volatile
    var pendingJoinRoomId: String? = null

    private var syncJob: Job? = null
    private var onRemoteSyncCallback: ((PlaybackState) -> Unit)? = null
    private var onRoomClosedCallback: (() -> Unit)? = null
    private var onMemberJoinedCallback: ((MemberData) -> Unit)? = null
    private var onMembersUpdatedCallback: ((List<MemberData>) -> Unit)? = null
    private var onNewMessageCallback: ((ChatMessage) -> Unit)? = null
    private var lastSeenMessageId: String? = null
    private var onNewVoiceCallback: ((VoiceMessage) -> Unit)? = null
    private var lastSeenVoiceId: String? = null
    private val knownMemberIds = mutableSetOf<String>()

    val chatHistory = arrayListOf<ChatMessage>()
    private var onChatHistoryUpdatedCallback: ((List<ChatMessage>) -> Unit)? = null
    var isVoiceCallActive: Boolean = false
        private set
    private var onVoiceCallToggledCallback: ((Boolean) -> Unit)? = null
    private var onEpisodeChangedCallback: ((Int) -> Unit)? = null

    fun setOnChatHistoryUpdatedListener(listener: ((List<ChatMessage>) -> Unit)?) {
        onChatHistoryUpdatedCallback = listener
    }

    fun setOnVoiceCallToggledListener(listener: ((Boolean) -> Unit)?) {
        onVoiceCallToggledCallback = listener
    }

    fun setOnEpisodeChangedListener(listener: ((Int) -> Unit)?) {
        onEpisodeChangedCallback = listener
    }

    fun toggleVoiceCall(active: Boolean) {
        isVoiceCallActive = active
        onVoiceCallToggledCallback?.invoke(active)
    }

    fun setOnNewMessageListener(listener: ((ChatMessage) -> Unit)?) {
        onNewMessageCallback = listener
    }

    fun setOnNewVoiceListener(listener: ((VoiceMessage) -> Unit)?) {
        onNewVoiceCallback = listener
    }

    fun sendVoiceMessage(base64Audio: String, durationMs: Long, senderNickname: String) {
        val roomId = currentRoomId ?: return
        if (base64Audio.isBlank()) return

        val voice = VoiceMessage(
            id = UUID.randomUUID().toString(),
            senderId = myUserId,
            senderName = senderNickname.ifBlank { "User" },
            audioBase64 = base64Audio,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )

        lastSeenVoiceId = voice.id

        ioSafe {
            try {
                val url = "${getBaseUrl()}/rooms/$roomId/lastVoice.json"
                val body = voice.toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.put(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun sendMessage(text: String, senderNickname: String) {
        val roomId = currentRoomId ?: return
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = myUserId,
            senderName = senderNickname.ifBlank { "User" },
            text = trimmed,
            timestamp = System.currentTimeMillis()
        )

        lastSeenMessageId = msg.id
        chatHistory.add(msg)
        onChatHistoryUpdatedCallback?.invoke(chatHistory.toList())
        onNewMessageCallback?.invoke(msg)

        ioSafe {
            try {
                val url = "${getBaseUrl()}/api/rooms/$roomId/message"
                val body = mapOf(
                    "senderId" to myUserId,
                    "senderName" to msg.senderName,
                    "text" to trimmed
                ).toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.post(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun changeEpisode(episodeIndex: Int, season: Int? = null, episode: Int? = null, title: String? = null) {
        val roomId = currentRoomId ?: return
        if (!isHost) return

        currentEpisodeIndex = episodeIndex
        ioSafe {
            try {
                val url = "${getBaseUrl()}/api/rooms/$roomId/change-episode"
                val body = mapOf(
                    "episodeIndex" to episodeIndex,
                    "season" to season,
                    "episode" to episode,
                    "title" to title
                ).toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.post(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun setVoiceStatus(isActive: Boolean) {
        val roomId = currentRoomId ?: return
        ioSafe {
            try {
                val url = "${getBaseUrl()}/api/rooms/$roomId/voice-status"
                val body = mapOf(
                    "userId" to myUserId,
                    "voiceActive" to isActive
                ).toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.post(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun broadcastSource(streamUrl: String?, mediaUrl: String?, apiName: String?, episodeId: Int? = null) {
        val roomId = currentRoomId ?: return
        if (!isHost) return

        ioSafe {
            try {
                val url = "${getBaseUrl()}/api/rooms/$roomId/source"
                val body = mapOf(
                    "streamUrl" to (streamUrl ?: ""),
                    "mediaUrl" to (mediaUrl ?: ""),
                    "apiName" to (apiName ?: ""),
                    "episodeId" to episodeId
                ).toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.post(url, requestBody = body)
            } catch (e: Throwable) {
                logError(e)
            }
        }
    }

    fun getSavedNickname(context: Context?): String {
        return try {
            val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            prefs?.getString(PREFS_KEY_NICKNAME, "") ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    fun saveNickname(context: Context?, nickname: String) {
        try {
            val trimmed = nickname.trim()
            if (trimmed.isBlank()) return
            val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            prefs?.edit()?.putString(PREFS_KEY_NICKNAME, trimmed)?.apply()
        } catch (e: Throwable) {
            logError(e)
        }
    }

    private fun getBaseUrl(): String {
        return JOYCALL_SERVER_URL.removeSuffix("/")
    }

    private fun generateRoomCode(): String {
        val num = Random.nextInt(1000, 9999)
        return "JOY-$num"
    }

    suspend fun createRoom(
        nickname: String,
        title: String?,
        streamUrl: String? = null,
        mediaUrl: String? = null,
        apiName: String? = null,
        episodeId: Int? = null,
        episode: Int? = null,
        season: Int? = null,
        poster: String? = null,
        tvType: String? = null,
        currentPos: Long = 0L,
        isPlaying: Boolean = false,
        episodeIndex: Int? = null
    ): Result<String> {
        return try {
            currentEpisodeIndex = episodeIndex
            val url = "${getBaseUrl()}/api/rooms/create"
            val map = mapOf(
                "nickname" to nickname.ifBlank { "Host" },
                "userId" to myUserId,
                "title" to title,
                "streamUrl" to streamUrl,
                "mediaUrl" to mediaUrl,
                "apiName" to apiName,
                "episodeId" to episodeId,
                "episode" to episode,
                "season" to season,
                "poster" to poster,
                "tvType" to tvType,
                "isPlaying" to isPlaying,
                "position" to currentPos,
                "episodeIndex" to episodeIndex
            )
            val body = map.toJson().toRequestBody(JSON_MEDIA_TYPE)
            val res = app.post(url, requestBody = body)

            if (res.code != 200) {
                return Result.failure(Exception("Server returned code ${res.code}"))
            }

            val parsed = AppUtils.parseJson<Map<String, Any?>>(res.text)
            val roomId = parsed["roomId"]?.toString() ?: generateRoomCode()
            val roomObj = parsed["room"]
            val room = if (roomObj != null) {
                AppUtils.parseJson<RoomData>(roomObj.toJson())
            } else {
                RoomData(roomId = roomId, hostId = myUserId, hostName = nickname.ifBlank { "Host" }, episodeIndex = episodeIndex)
            }

            knownMemberIds.clear()
            knownMemberIds.add(myUserId)

            currentRoomId = roomId
            currentRoom = room
            isHost = true
            isCreatingWatchParty = false
            pendingHostNickname = null
            chatHistory.clear()
            startListening()

            Log.i(TAG, "Created room: $roomId with episodeIndex: $episodeIndex")
            Result.success(roomId)
        } catch (e: Throwable) {
            logError(e)
            Result.failure(e)
        }
    }

    suspend fun joinRoom(
        inputCode: String,
        nickname: String,
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

            val url = "${getBaseUrl()}/api/rooms/join"
            val body = mapOf(
                "roomId" to roomId,
                "nickname" to nickname.ifBlank { "Guest" },
                "userId" to myUserId
            ).toJson().toRequestBody(JSON_MEDIA_TYPE)
            val res = app.post(url, requestBody = body)

            if (res.code != 200) {
                onError("Room not found ($roomId)")
                return
            }

            val parsed = AppUtils.parseJson<Map<String, Any?>>(res.text)
            val roomObj = parsed["room"] ?: throw Exception("Room data not found")
            val room = AppUtils.parseJson<RoomData>(roomObj.toJson())

            knownMemberIds.clear()
            room.members.forEach { knownMemberIds.add(it.userId) }
            knownMemberIds.add(myUserId)

            currentRoomId = roomId
            currentRoom = room
            isHost = (room.hostId == myUserId)
            currentEpisodeIndex = room.playback.episodeIndex ?: room.episodeIndex
            chatHistory.clear()
            if (room.messages.isNotEmpty()) {
                chatHistory.addAll(room.messages)
            }
            startListening()

            Log.i(TAG, "Joined room: $roomId with episodeIndex: $currentEpisodeIndex")
            onRoomLoaded(room)
        } catch (e: Throwable) {
            logError(e)
            onError(e.message ?: "Failed to join room")
        }
    }

    fun leaveRoom() {
        setVoiceStatus(false)
        currentRoomId = null
        currentRoom = null
        currentEpisodeIndex = null
        isHost = false
        isCreatingWatchParty = false
        pendingHostNickname = null
        stopListening()
        knownMemberIds.clear()
        chatHistory.clear()
        lastSeenMessageId = null
        lastSeenVoiceId = null
        isVoiceCallActive = false
        onVoiceCallToggledCallback?.invoke(false)
    }

    fun broadcastPlayback(isPlaying: Boolean, position: Long, episodeIndex: Int? = null) {
        val roomId = currentRoomId ?: return
        if (isApplyingRemoteSync) return

        if (episodeIndex != null) {
            currentEpisodeIndex = episodeIndex
        }

        ioSafe {
            try {
                val url = "${getBaseUrl()}/api/rooms/$roomId/sync"
                val body = mapOf(
                    "isPlaying" to isPlaying,
                    "position" to position,
                    "updatedBy" to myUserId
                ).toJson().toRequestBody(JSON_MEDIA_TYPE)
                app.post(url, requestBody = body)
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

    fun setOnMemberJoinedListener(listener: ((MemberData) -> Unit)?) {
        onMemberJoinedCallback = listener
    }

    fun setOnMembersUpdatedListener(listener: ((List<MemberData>) -> Unit)?) {
        onMembersUpdatedCallback = listener
    }

    private fun startListening() {
        stopListening()
        val roomId = currentRoomId ?: return

        syncJob = ioSafe {
            while (isActive && currentRoomId == roomId) {
                try {
                    delay(800)
                    if (!isActive || currentRoomId != roomId) break

                    val url = "${getBaseUrl()}/api/rooms/$roomId"
                    val res = app.get(url)
                    if (res.code == 200 && res.text.isNotBlank()) {
                        val parsed = AppUtils.parseJson<Map<String, Any?>>(res.text)
                        val roomObj = parsed["room"]
                        if (roomObj != null) {
                            val room = AppUtils.parseJson<RoomData>(roomObj.toJson())
                            currentRoom = room

                            // 1. Check remote playback sync & episode change
                            val state = room.playback
                            if (!isHost) {
                                val targetEp = state.episodeIndex ?: room.episodeIndex
                                if (targetEp != null && targetEp != currentEpisodeIndex) {
                                    currentEpisodeIndex = targetEp
                                    onEpisodeChangedCallback?.invoke(targetEp)
                                }
                                onRemoteSyncCallback?.invoke(state)
                            }

                            // 2. Check members list
                            val memberList = room.members
                            for (member in memberList) {
                                if (member.userId !in knownMemberIds) {
                                    knownMemberIds.add(member.userId)
                                    if (member.userId != myUserId) {
                                        onMemberJoinedCallback?.invoke(member)
                                    }
                                }
                            }
                            onMembersUpdatedCallback?.invoke(memberList)

                            // 3. Check for messages (full chat history sync)
                            val messages = room.messages
                            if (messages.size != chatHistory.size || messages.lastOrNull()?.id != chatHistory.lastOrNull()?.id) {
                                val latest = messages.lastOrNull()
                                chatHistory.clear()
                                chatHistory.addAll(messages)
                                onChatHistoryUpdatedCallback?.invoke(chatHistory.toList())
                                if (latest != null && latest.id != lastSeenMessageId && latest.senderId != myUserId) {
                                    lastSeenMessageId = latest.id
                                    onNewMessageCallback?.invoke(latest)
                                }
                            }
                        }
                    } else if (res.code == 404 && !isHost) {
                        leaveRoom()
                        onRoomClosedCallback?.invoke()
                        break
                    }
                } catch (e: Throwable) {
                    // Retry silently on temporary connection blips
                }
            }
        }
    }

    private fun stopListening() {
        syncJob?.cancel()
        syncJob = null
    }

    fun showWatchTogetherHomeDialog(activity: FragmentActivity) {
        val binding = DialogWatchTogetherBinding.inflate(LayoutInflater.from(activity))
        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setView(binding.root)
            .create()

        binding.btnDialogClose.setOnClickListener {
            dialog.dismissSafe(activity)
        }

        val savedNick = getSavedNickname(activity)
        if (savedNick.isNotBlank()) {
            binding.etNickname.setText(savedNick)
        }

        fun updateUI() {
            if (isInRoom) {
                binding.layoutNotInRoom.isVisible = false
                binding.layoutInRoom.isVisible = true
                val role = if (isHost) activity.getString(R.string.host) else activity.getString(R.string.member)
                binding.tvRoomStatus.text = "● ${activity.getString(R.string.room_connected)} • $role"
                binding.tvCurrentRoomCode.text = currentRoomId ?: ""

                // Members
                val members = currentRoom?.members ?: emptyList()
                binding.tvMembersCount.text = "${members.size} online"
                val membersFormatted = members.joinToString(", ") { m ->
                    if (m.isHost) "👑 ${m.name} (Host)" else m.name
                }
                binding.tvMembersList.text = if (membersFormatted.isNotBlank()) membersFormatted else "👑 Host"

                // Host controls
                binding.btnHostStartPlay.isVisible = isHost
            } else {
                binding.layoutNotInRoom.isVisible = true
                binding.layoutInRoom.isVisible = false
            }
            binding.watchTogetherLoading.isVisible = false
            binding.tvWatchTogetherError.isVisible = false
        }

        updateUI()

        setOnMembersUpdatedListener { members ->
            activity.runOnUiThread {
                if (isInRoom) {
                    binding.tvMembersCount.text = "${members.size} online"
                    val membersFormatted = members.joinToString(", ") { m ->
                        if (m.isHost) "👑 ${m.name} (Host)" else m.name
                    }
                    binding.tvMembersList.text = membersFormatted
                }
            }
        }

        setOnMemberJoinedListener { member ->
            activity.runOnUiThread {
                showToast(activity.getString(R.string.guest_joined_notify, member.name))
            }
        }

        // Host Click: Start Room
        binding.btnCreateRoom.setOnClickListener {
            val nick = binding.etNickname.text?.toString()?.trim()
            if (nick.isNullOrBlank()) {
                binding.tvWatchTogetherError.text = activity.getString(R.string.enter_nickname)
                binding.tvWatchTogetherError.isVisible = true
                binding.etNickname.requestFocus()
                return@setOnClickListener
            }
            saveNickname(activity, nick)

            // Setup party creation state so when host selects video, it auto-creates room with video info
            isCreatingWatchParty = true
            pendingHostNickname = nick

            dialog.dismissSafe(activity)
            showToast("Select any movie or series to start Watch Party!")
        }

        // Join Room Click
        binding.btnJoinRoom.setOnClickListener {
            val nick = binding.etNickname.text?.toString()?.trim()
            if (nick.isNullOrBlank()) {
                binding.tvWatchTogetherError.text = activity.getString(R.string.enter_nickname)
                binding.tvWatchTogetherError.isVisible = true
                binding.etNickname.requestFocus()
                return@setOnClickListener
            }
            saveNickname(activity, nick)

            val code = binding.etRoomCode.text?.toString()?.trim()
            if (code.isNullOrBlank()) {
                binding.tvWatchTogetherError.text = activity.getString(R.string.enter_room_code)
                binding.tvWatchTogetherError.isVisible = true
                return@setOnClickListener
            }

            binding.watchTogetherLoading.isVisible = true
            binding.tvWatchTogetherError.isVisible = false

            ioSafe {
                try {
                    joinRoom(
                        inputCode = code,
                        nickname = nick,
                        onRoomLoaded = { room ->
                            activity.runOnUiThread {
                                binding.watchTogetherLoading.isVisible = false
                                dialog.dismissSafe(activity)
                                showToast(R.string.room_joined_success)

                                val url = room.mediaUrl
                                val api = room.apiName
                                if (!url.isNullOrBlank() && !api.isNullOrBlank()) {
                                    pendingJoinRoomId = room.roomId
                                    activity.loadResult(
                                        url = url,
                                        apiName = api,
                                        name = room.title ?: "Watch Party",
                                        startAction = START_ACTION_LOAD_EP,
                                        startValue = room.episodeId ?: (room.episode ?: 0),
                                        episode = room.episode,
                                        season = room.season
                                    )
                                } else {
                                    showToast("Connected to room! Loading stream...")
                                }
                            }
                        },
                        onError = { err ->
                            activity.runOnUiThread {
                                binding.watchTogetherLoading.isVisible = false
                                binding.tvWatchTogetherError.text = err
                                binding.tvWatchTogetherError.isVisible = true
                            }
                        }
                    )
                } catch (t: Throwable) {
                    activity.runOnUiThread {
                        binding.watchTogetherLoading.isVisible = false
                        binding.tvWatchTogetherError.text = t.message ?: "Failed to connect"
                        binding.tvWatchTogetherError.isVisible = true
                    }
                }
            }
        }

        binding.btnCopyRoomCode.setOnClickListener {
            val code = currentRoomId ?: return@setOnClickListener
            clipboardHelper(txt(R.string.room_code), code)
            showToast(R.string.room_code_copied)
        }

        binding.btnLeaveRoom.setOnClickListener {
            leaveRoom()
            showToast(R.string.leave_room)
            updateUI()
        }

        dialog.show()
    }
}

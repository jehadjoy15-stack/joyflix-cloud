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
import com.lagradost.cloudstream3.utils.AppUtils.dismissSafe
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
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
    private const val PREFS_KEY_NICKNAME = "watch_together_nickname"

    val myUserId: String by lazy {
        "user_" + UUID.randomUUID().toString().substring(0, 6)
    }

    @Serializable
    data class MemberData(
        @JsonProperty("userId") val userId: String = "",
        @JsonProperty("name") val name: String = "",
        @JsonProperty("isHost") val isHost: Boolean = false,
        @JsonProperty("joinedAt") val joinedAt: Long = 0L
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
        @JsonProperty("members") val members: Map<String, MemberData> = emptyMap()
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
    private val knownMemberIds = mutableSetOf<String>()

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
        return DEFAULT_FIREBASE_URL.removeSuffix("/")
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
            val roomId = generateRoomCode()
            currentEpisodeIndex = episodeIndex
            val myMember = MemberData(
                userId = myUserId,
                name = nickname.ifBlank { "Host" },
                isHost = true,
                joinedAt = System.currentTimeMillis()
            )
            val room = RoomData(
                roomId = roomId,
                title = title,
                streamUrl = streamUrl,
                mediaUrl = mediaUrl,
                apiName = apiName,
                episodeId = episodeId,
                episode = episode,
                season = season,
                poster = poster,
                tvType = tvType,
                hostId = myUserId,
                hostName = nickname.ifBlank { "Host" },
                createdAt = System.currentTimeMillis(),
                episodeIndex = episodeIndex,
                playback = PlaybackState(
                    isPlaying = isPlaying,
                    position = currentPos,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = myUserId,
                    episodeIndex = episodeIndex
                ),
                members = mapOf(myUserId to myMember)
            )

            val url = "${getBaseUrl()}/rooms/$roomId.json"
            val jsonString = room.toJson()
            val body = jsonString.toRequestBody(JSON_MEDIA_TYPE)
            val res = app.put(url, requestBody = body)

            if (res.text.contains("Permission denied", ignoreCase = true)) {
                return Result.failure(Exception("Firebase Permission Denied. Please enable read/write rules."))
            }

            knownMemberIds.clear()
            knownMemberIds.add(myUserId)

            currentRoomId = roomId
            currentRoom = room
            isHost = true
            isCreatingWatchParty = false
            pendingHostNickname = null
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

            // Register current user into room's members list
            val myMember = MemberData(
                userId = myUserId,
                name = nickname.ifBlank { "Guest" },
                isHost = (room.hostId == myUserId),
                joinedAt = System.currentTimeMillis()
            )
            val memberUrl = "${getBaseUrl()}/rooms/$roomId/members/$myUserId.json"
            val body = myMember.toJson().toRequestBody(JSON_MEDIA_TYPE)
            app.put(memberUrl, requestBody = body)

            knownMemberIds.clear()
            room.members.keys.forEach { knownMemberIds.add(it) }
            knownMemberIds.add(myUserId)

            currentRoomId = roomId
            currentRoom = room
            isHost = (room.hostId == myUserId)
            currentEpisodeIndex = room.playback.episodeIndex ?: room.episodeIndex
            startListening()

            Log.i(TAG, "Joined room: $roomId with episodeIndex: $currentEpisodeIndex")
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
        currentEpisodeIndex = null
        isHost = false
        isCreatingWatchParty = false
        pendingHostNickname = null
        stopListening()
        knownMemberIds.clear()

        if (roomToClean != null) {
            ioSafe {
                try {
                    if (host) {
                        app.delete("${getBaseUrl()}/rooms/$roomToClean.json")
                    } else {
                        app.delete("${getBaseUrl()}/rooms/$roomToClean/members/$myUserId.json")
                    }
                } catch (e: Throwable) {
                    logError(e)
                }
            }
        }
    }

    fun broadcastPlayback(isPlaying: Boolean, position: Long, episodeIndex: Int? = null) {
        val roomId = currentRoomId ?: return
        if (isApplyingRemoteSync) return

        if (episodeIndex != null) {
            currentEpisodeIndex = episodeIndex
        }

        ioSafe {
            try {
                val state = PlaybackState(
                    isPlaying = isPlaying,
                    position = position,
                    updatedAt = System.currentTimeMillis(),
                    updatedBy = myUserId,
                    episodeIndex = currentEpisodeIndex
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
            val url = "${getBaseUrl()}/rooms/$roomId.json"
            while (isActive && currentRoomId == roomId) {
                try {
                    delay(1200)
                    if (!isActive || currentRoomId != roomId) break

                    val res = app.get(url)
                    val text = res.text
                    if (text.isNotBlank() && text != "null") {
                        if (!text.contains("Permission denied", ignoreCase = true)) {
                            val room = AppUtils.parseJson<RoomData>(text)
                            currentRoom = room

                            // 1. Check remote playback sync
                            val state = room.playback
                            if (state.updatedBy != myUserId) {
                                if (state.episodeIndex != null) {
                                    currentEpisodeIndex = state.episodeIndex
                                }
                                onRemoteSyncCallback?.invoke(state)
                            }

                            // 2. Check members list
                            val memberList = room.members.values.toList()
                            for (member in memberList) {
                                if (member.userId !in knownMemberIds) {
                                    knownMemberIds.add(member.userId)
                                    if (member.userId != myUserId) {
                                        onMemberJoinedCallback?.invoke(member)
                                    }
                                }
                            }
                            onMembersUpdatedCallback?.invoke(memberList)
                        }
                    } else if (text == "null" && !isHost) {
                        // Room was closed / deleted by host
                        leaveRoom()
                        onRoomClosedCallback?.invoke()
                        break
                    }
                } catch (e: Throwable) {
                    // Retry silently on network hiccups
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
                val members = currentRoom?.members?.values?.toList() ?: emptyList()
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
                                        startValue = room.episodeId ?: 0
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
            clipboardHelper(activity.getString(R.string.room_code), code)
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

package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.DiscordQuest
import com.example.data.TaskLog
import com.example.network.DiscordHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.random.Random

class DiscordQuestService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }

    // Foreground Service Configuration
    private val NOTIFICATION_ID = 8891
    private val CHANNEL_ID = "discord_automation_channel"

    companion object {
        private const val TAG = "DiscordQuestService"

        // Global Automation State
        private val _isExecuting = MutableStateFlow(false)
        val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

        private val _currentStatus = MutableStateFlow("Idle")
        val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

        val _activeQuests = MutableStateFlow<List<DiscordQuest>>(emptyList())
        val activeQuests: StateFlow<List<DiscordQuest>> = _activeQuests.asStateFlow()

        val _selectedQuestIds = MutableStateFlow<Set<String>>(emptySet())
        val selectedQuestIds: StateFlow<Set<String>> = _selectedQuestIds.asStateFlow()

        // Service instance trigger control
        private var activeServiceJob: Job? = null

        // Quest đã từng thấy: chỉ tự chọn quest MỚI, không chọn lại quest user đã bỏ chọn
        private val knownQuestIds = mutableSetOf<String>()

        fun toggleQuestSelection(questId: String) {
            val current = _selectedQuestIds.value
            _selectedQuestIds.value = if (current.contains(questId)) {
                current - questId
            } else {
                current + questId
            }
        }

        fun selectAllQuests(quests: List<DiscordQuest>) {
            _selectedQuestIds.value = quests.filter { !it.completed }.map { it.id }.toSet()
        }

        fun deselectAllQuests() {
            _selectedQuestIds.value = emptySet()
        }

        /**
         * Đẩy danh sách quest mới lên UI. Không cần service đang bind,
         * ViewModel có thể gọi trực tiếp.
         */
        @Synchronized
        fun publishQuests(quests: List<DiscordQuest>) {
            _activeQuests.value = quests
            val ids = quests.map { it.id }.toSet()
            // Bỏ quest đã biến mất (hết hạn) khỏi danh sách đã chọn
            val selected = _selectedQuestIds.value.filter { it in ids }.toMutableSet()
            quests.forEach {
                if (!it.completed && knownQuestIds.add(it.id)) {
                    selected.add(it.id)
                }
            }
            _selectedQuestIds.value = selected
        }

        @Synchronized
        fun resetState() {
            knownQuestIds.clear()
            _activeQuests.value = emptyList()
            _selectedQuestIds.value = emptySet()
        }

        /** Yêu cầu các vòng lặp đang chạy dừng lại (dùng khi logout). */
        fun requestStop() {
            _isExecuting.value = false
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): DiscordQuestService = this@DiscordQuestService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_AUTOMATION") {
            startForegroundServiceNotification("Initializing automation...")
            runAutomationFlow()
        } else if (action == "STOP_AUTOMATION") {
            stopAutomationFlow("Stopped by user")
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Discord Automation Services",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundServiceNotification(message: String) {
        val notification = createNotification(message)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Discord AutoQuest is Active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, createNotification(message))
    }

    // Dynamic User Console Logging
    private fun log(text: String, type: String = "info") {
        Log.d(TAG, "[$type] $text")
        serviceScope.launch {
            db.taskLogDao().insertLog(
                TaskLog(
                    timestamp = System.currentTimeMillis(),
                    text = text,
                    type = type
                )
            )
        }
    }

    // Toggle Quest Selection Checkbox
    fun toggleQuestSelectionInstance(questId: String) {
        Companion.toggleQuestSelection(questId)
    }

    fun selectAllQuestsInstance(quests: List<DiscordQuest>) {
        Companion.selectAllQuests(quests)
    }

    fun deselectAllQuestsInstance() {
        Companion.deselectAllQuests()
    }

    // Fetch quests list from API (chỉ quest đang cho cày)
    fun refreshQuests(token: String) {
        serviceScope.launch {
            _currentStatus.value = "Fetching quests..."
            log("Fetching available quests list...", "info")
            val quests = withContext(Dispatchers.IO) {
                DiscordHttpClient.getQuests(token)
            }
            publishQuests(quests)

            db.discordAccountDao().getAccount()?.let { account ->
                // Update username in background
                withContext(Dispatchers.IO) {
                    val meObj = DiscordHttpClient.getMe(token)
                    if (meObj != null) {
                        val name = meObj.optString("username")
                        // optString trả "null" khi avatar là null, phải check isNull
                        val avatarHash = if (meObj.isNull("avatar")) "" else meObj.optString("avatar")
                        val disc = meObj.optString("discriminator").ifEmpty { "0" }
                        val formattedName = if (disc == "0" || disc == "0000") name else "$name#$disc"
                        val avatarUrl = if (avatarHash.isNotEmpty()) {
                            "https://cdn.discordapp.com/avatars/${meObj.optString("id")}/$avatarHash.png"
                        } else ""

                        db.discordAccountDao().insertAccount(
                            account.copy(username = formattedName, avatarUrl = avatarUrl)
                        )
                    }
                }
            }

            _currentStatus.value = "Refreshed"
            log("Loaded ${quests.size} quests from Discord API.", "success")
        }
    }

    // Execute Main Flow Job
    private fun runAutomationFlow() {
        if (_isExecuting.value) return
        _isExecuting.value = true

        activeServiceJob = serviceScope.launch {
            try {
                _currentStatus.value = "Running Quest Automation..."
                log("Starting automation cycle...", "info")

                val account = db.discordAccountDao().getAccount()
                if (account == null || account.token.isEmpty()) {
                    log("Aborted: Discord Token not configured in repository.", "err")
                    // Phải reset _isExecuting, nếu không lần START sau sẽ bị bỏ qua
                    stopAutomationFlow("No token")
                    return@launch
                }

                val token = account.token
                val selectedIds = _selectedQuestIds.value

                // 1. Fetch Fresh Quest State
                log("Synchronizing active quest state...", "info")
                val questsList = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getQuests(token)
                }
                _activeQuests.value = questsList

                val activeTargetQuests = questsList.filter {
                    selectedIds.contains(it.id) && !it.completed
                }

                if (activeTargetQuests.isEmpty()) {
                    log("Completed! No active or selected quests need progress.", "success")
                    stopAutomationFlow("Done! All quests finished.")
                    return@launch
                }

                log("Discovered ${activeTargetQuests.size} pending tasks to process.", "info")

                val channels = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getChannels(token)
                }
                val streamChannel = channels.firstOrNull() ?: "1100000000000000000"

                supervisorScope {
                    for ((index, quest) in activeTargetQuests.withIndex()) {
                        launch {
                            if (!isActive || !_isExecuting.value) {
                                log("Process aborted by user request.", "warn")
                                return@launch
                            }

                            log("Starting task [${index + 1}/${activeTargetQuests.size}]: \"${quest.name}\" [Type: ${quest.type}]", "info")
                            updateNotification("Completing Quest: ${quest.name}")

                            // Ensure Enrolled
                            var isEnrolled = quest.enrolled
                            if (!isEnrolled) {
                                log("Enrolling quest \"${quest.name}\"...", "info")
                                val enrollmentRes = withContext(Dispatchers.IO) {
                                    DiscordHttpClient.enrollQuest(token, quest.id)
                                }
                                if (enrollmentRes.isSuccessful) {
                                    isEnrolled = true
                                    DiscordHttpClient.enrolledAtMillis[quest.id] = System.currentTimeMillis()
                                    log("Successfully enrolled in \"${quest.name}\". Wait a brief moment...", "success")
                                    delay(1200)
                                } else if (enrollmentRes.isRateLimit) {
                                    val waitS = enrollmentRes.retryAfterSeconds ?: 5L
                                    log("Rate Limit Hit! Backing off for $waitS seconds...", "warn")
                                    delay((waitS + 1) * 1000)
                                    val retryEnroll = withContext(Dispatchers.IO) {
                                        DiscordHttpClient.enrollQuest(token, quest.id)
                                    }
                                    if (retryEnroll.isSuccessful) {
                                        isEnrolled = true
                                        DiscordHttpClient.enrolledAtMillis[quest.id] = System.currentTimeMillis()
                                        log("Retry Successful: Enrolled in \"${quest.name}\"", "success")
                                    } else {
                                        log("Enrollment failed on retry: Code ${retryEnroll.code}.", "err")
                                    }
                                } else {
                                    log("Enrollment rejected by Discord (HTTP ${enrollmentRes.code}): ${enrollmentRes.body.take(200)}", "err")
                                }
                            }

                            if (!isEnrolled) {
                                log("Skipped: Quest must be enrolled first before progress can be simulated.", "warn")
                                updateQuestProgressInList(quest.id, enrolled = false, completed = false)
                                return@launch
                            }

                            val videoTasks = listOf("WATCH_VIDEO", "WATCH_VIDEO_ON_MOBILE")
                            when {
                                quest.type == "WATCH_VIDEO" || quest.taskKeyName in videoTasks ->
                                    processVideoQuest(token, quest)

                                quest.type == "ACHIEVEMENT" || quest.type == "Unknown" ->
                                    log("Skipped \"${quest.name}\": task type ${quest.type} (${quest.taskKeyName}) is not supported.", "warn")

                                else -> processHeartbeatQuest(token, quest, streamChannel)
                            }
                        }
                    }
                }

                // Bị dừng giữa chừng (logout / user stop) thì không sync tiếp
                if (!_isExecuting.value) {
                    stopAutomationFlow("Stopped")
                    return@launch
                }

                log("Final sync with Discord Quests dashboard...", "info")
                val finalQuests = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getQuests(token)
                }
                _activeQuests.value = finalQuests
                log("Automation cycle finished successfully!", "success")
                stopAutomationFlow("Done! Automation cycle done")

            } catch (e: CancellationException) {
                log("Automation process canceled.", "warn")
            } catch (e: Exception) {
                log("Automation error state: ${e.message}", "err")
                stopAutomationFlow("Crash: ${e.message}")
            }
        }
    }

    private fun isCompletedResponse(body: String): Boolean {
        return try {
            val o = JSONObject(body)
            !o.isNull("completed_at") && o.optString("completed_at").isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processVideoQuest(token: String, quest: DiscordQuest) {
        val targetSecs = quest.target.coerceAtLeast(0)
        var doneSecs = quest.currentProgress.coerceAtLeast(0)
        val speed = 7        // giây tua mỗi lần gửi
        val maxFuture = 10   // Discord chỉ chấp nhận timestamp <= thời gian đã trôi từ lúc enroll + ~10s

        log("Bắt đầu spoof video cho \"${quest.name}\" ($doneSecs/${targetSecs}s)...", "info")

        if (targetSecs == 0) {
            log("Video Quest \"${quest.name}\": Discord không trả về thời lượng mục tiêu.", "warn")
            return
        }

        // Nếu không biết thời điểm enroll thì coi như vừa enroll (an toàn, chậm hơn nhưng không bị từ chối)
        val enrolledAtMs = DiscordHttpClient.enrolledAtMillis[quest.id] ?: System.currentTimeMillis()
        var completed = false
        var failures = 0

        while (doneSecs < targetSecs && _isExecuting.value) {
            val elapsed = ((System.currentTimeMillis() - enrolledAtMs) / 1000).toInt()
            val maxAllowed = elapsed + maxFuture

            if (maxAllowed - doneSecs >= speed) {
                val next = minOf(targetSecs, doneSecs + speed)
                val ts = minOf(targetSecs.toDouble(), next + Random.nextDouble())
                val res = withContext(Dispatchers.IO) {
                    DiscordHttpClient.sendVideoProgress(token, quest.id, ts)
                }

                if (res.isSuccessful) {
                    failures = 0
                    doneSecs = next
                    if (isCompletedResponse(res.body)) completed = true
                    val percent = ((doneSecs.toDouble() / targetSecs) * 100.0).toInt().coerceIn(0, 100)
                    log("Video Quest \"${quest.name}\": $doneSecs/${targetSecs}s ($percent%)", "progress")
                    updateQuestProgressInList(quest.id, true, doneSecs, doneSecs >= targetSecs)
                } else if (res.isRateLimit) {
                    val s = res.retryAfterSeconds ?: 5L
                    log("Rate limit khi gửi video progress, chờ $s giây.", "warn")
                    delay((s + 1) * 1000)
                } else {
                    failures++
                    log("Gửi tiến trình video thất bại: HTTP ${res.code} ${res.body.take(150)} (lần $failures)", "warn")
                    if (failures > 8) {
                        log("Dừng quest \"${quest.name}\": Discord từ chối liên tục.", "err")
                        return
                    }
                    delay(5000)
                }
            }

            if (doneSecs >= targetSecs) break
            delay(1000)
        }

        if (doneSecs >= targetSecs && _isExecuting.value) {
            if (!completed) {
                // Gửi mốc cuối cùng để Discord đánh dấu hoàn thành
                val res = withContext(Dispatchers.IO) {
                    DiscordHttpClient.sendVideoProgress(token, quest.id, targetSecs.toDouble())
                }
                if (!res.isSuccessful) {
                    log("Gửi mốc cuối thất bại: HTTP ${res.code}", "warn")
                }
            }
            log("Video Quest \"${quest.name}\" đã hoàn thành! Đang nhận thưởng...", "success")
            triggerClaimIfNeeded(token, quest)
        }
    }

    private suspend fun processHeartbeatQuest(
        token: String,
        quest: DiscordQuest,
        streamChannel: String
    ) {
        val targetSecs = quest.target
        if (targetSecs <= 0) {
            log("Skipped \"${quest.name}\": Discord không trả về mục tiêu (target = 0).", "warn")
            return
        }

        val streamKey = "call:$streamChannel:${Random.nextInt(1000, 9999)}"
        log("Pre-starting game emulator for \"${quest.name}\" using key: $streamKey...", "info")

        var currentProgressSecs = quest.currentProgress
        var failuresCount = 0

        withContext(Dispatchers.IO) {
            DiscordHttpClient.sendHeartbeat(token, quest.id, streamKey, false)
        }

        while (currentProgressSecs < targetSecs && _isExecuting.value) {
            val delaySeconds = Random.nextInt(19, 22)
            log("Heartbeating... waiting ${delaySeconds}s (Progress: $currentProgressSecs/${targetSecs}s)", "debug")
            delay(delaySeconds * 1000L)

            val res = withContext(Dispatchers.IO) {
                DiscordHttpClient.sendHeartbeat(token, quest.id, streamKey, false)
            }

            if (res.isSuccessful) {
                failuresCount = 0
                val parsedObj = try {
                    JSONObject(res.body)
                } catch (e: Exception) {
                    null
                }

                val serverProgress = if (parsedObj != null) {
                    val progressObj = parsedObj.optJSONObject("progress")

                    val taskDef = progressObj?.optJSONObject(quest.taskKeyName)
                        ?: progressObj?.optJSONObject("PLAY_ON_DESKTOP")
                        ?: progressObj?.optJSONObject("STREAM_ON_DESKTOP")
                        ?: progressObj?.optJSONObject("PLAY_ACTIVITY")

                    taskDef?.optInt("value") ?: (currentProgressSecs + delaySeconds)
                } else {
                    currentProgressSecs + delaySeconds
                }

                currentProgressSecs =
                    if (serverProgress > currentProgressSecs) serverProgress
                    else currentProgressSecs + delaySeconds
                if (currentProgressSecs > targetSecs) currentProgressSecs = targetSecs

                updateQuestProgressInList(quest.id, true, currentProgressSecs, currentProgressSecs >= targetSecs)
            } else if (res.isRateLimit) {
                failuresCount++
                val s = res.retryAfterSeconds ?: 10L
                log("Rate Limit Hit during heartbeat! Blocked for $s seconds.", "warn")
                delay((s + 1) * 1000)
            } else {
                failuresCount++
                log("Heartbeat failed: HTTP ${res.code} ${res.body.take(150)}", "warn")
                if (failuresCount > 4) {
                    log("Aborted heartbeat task: API rejected session multiple times.", "err")
                    break
                }
            }
        }

        withContext(Dispatchers.IO) {
            DiscordHttpClient.sendHeartbeat(token, quest.id, streamKey, true)
        }

        if (currentProgressSecs >= targetSecs && _isExecuting.value) {
            log("Game/Stream Quest \"${quest.name}\" Completed!", "success")
            triggerClaimIfNeeded(token, quest)
        }
    }

    private suspend fun triggerClaimIfNeeded(token: String, quest: DiscordQuest) {
        log("Completed task. Ready to claim reward \"${quest.rewardName}\"!", "success")

        val account = db.discordAccountDao().getAccount()
        if (account != null) {
            log("Submitting claim reward API payload for \"${quest.rewardName}\"...", "info")
            val res = withContext(Dispatchers.IO) {
                DiscordHttpClient.claimReward(token, quest.id)
            }

            if (res.isSuccessful) {
                log("Reward \"${quest.rewardName}\" has been claimed successfully!", "success")
                updateQuestProgressInList(quest.id, true, quest.target, true, true)
            } else if (res.code == 400 || res.code == 403) {
                log("Auto-Claim cần tương tác (ví dụ Captcha). Hãy mở mục Quest trong app Discord để nhận thưởng.", "warn")
                updateQuestProgressInList(quest.id, true, quest.target, true, false)
            } else {
                log("Claim reward API returned warning: Code ${res.code}.", "warn")
                updateQuestProgressInList(quest.id, true, quest.target, true, false)
            }
        }
    }

    private fun updateQuestProgressInList(
        questId: String,
        enrolled: Boolean,
        progress: Int = 0,
        completed: Boolean = false,
        claimed: Boolean = false
    ) {
        _activeQuests.update { current ->
            current.map {
                if (it.id == questId) {
                    it.copy(
                        enrolled = enrolled,
                        currentProgress = progress,
                        completed = completed || claimed
                    )
                } else {
                    it
                }
            }
        }
    }

    private fun stopAutomationFlow(reason: String) {
        _isExecuting.value = false
        _currentStatus.value = "Idle"
        activeServiceJob?.cancel()
        activeServiceJob = null
        log("Automation loop stopped: $reason", "info")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isExecuting.value = false
        serviceJob.cancel()
    }
}

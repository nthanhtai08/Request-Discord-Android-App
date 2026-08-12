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
import com.example.data.DiscordAccount
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
    fun toggleQuestSelection(questId: String) {
        Companion.toggleQuestSelection(questId)
    }

    fun selectAllQuests(quests: List<DiscordQuest>) {
        Companion.selectAllQuests(quests)
    }

    fun deselectAllQuests() {
        Companion.deselectAllQuests()
    }

    // Fetch quests list from API
    fun refreshQuests(token: String) {
        serviceScope.launch {
            _currentStatus.value = "Fetching quests..."
            log("Fetching available quests list...", "info")
            val quests = withContext(Dispatchers.IO) {
                DiscordHttpClient.getQuests(token)
            }
            _activeQuests.value = quests
            
            // Auto-select is_completed = false quests initially
            val currentSelected = _selectedQuestIds.value.toMutableSet()
            quests.forEach {
                if (!it.completed) {
                    currentSelected.add(it.id)
                }
            }
            _selectedQuestIds.value = currentSelected

            db.discordAccountDao().getAccount()?.let { account ->
                // Update username in background
                withContext(Dispatchers.IO) {
                    val meObj = DiscordHttpClient.getMe(token)
                    if (meObj != null) {
                        val name = meObj.optString("username") ?: ""
                        val avatarHash = meObj.optString("avatar") ?: ""
                        val disc = meObj.optString("discriminator") ?: "0000"
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
                    stopSelf()
                    return@launch
                }

                val token = account.token
                val selectedIds = _selectedQuestIds.value

                // 1. Fetch Fresh Quest State
                log("Synchronizing active quest state...", "info")
                var questsList = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getQuests(token)
                }
                _activeQuests.value = questsList

                // Filter quests list
                val activeTargetQuests = questsList.filter {
                    selectedIds.contains(it.id) && !it.completed
                }

                if (activeTargetQuests.isEmpty()) {
                    log("Completed! No active or selected quests need progress.", "success")
                    stopAutomationFlow("Done! All quests finished.")
                    return@launch
                }

                log("Discovered ${activeTargetQuests.size} pending tasks to process.", "info")

                // Query private channels to construct solid DM stream key fallbacks
                val channels = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getChannels(token)
                }
                val streamChannel = channels.firstOrNull() ?: "1100000000000000000"

                // Process concurrently
                kotlinx.coroutines.supervisorScope {
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
                                    log("Successfully enrolled in \"${quest.name}\". Wait a brief moment...", "success")
                                    delay(1200)
                                } else if (enrollmentRes.isRateLimit) {
                                    val waitS = enrollmentRes.retryAfterSeconds ?: 5
                                    log("Rate Limit Hit! Delayed by Discord API. Backing off for $waitS seconds...", "warn")
                                    delay((waitS + 1) * 1000)
                                    // Retry once
                                    val retryEnroll = withContext(Dispatchers.IO) {
                                        DiscordHttpClient.enrollQuest(token, quest.id)
                                    }
                                    if (retryEnroll.isSuccessful) {
                                        isEnrolled = true
                                        log("Retry Successful: Enrolled in \"${quest.name}\"", "success")
                                    } else {
                                        log("Enrollment failed on retry: Code ${retryEnroll.code}.", "err")
                                    }
                                } else {
                                    log("Enrollment rejected by Discord (HTTP Code ${enrollmentRes.code}). skipping", "err")
                                }
                            }

                            if (!isEnrolled) {
                                log("Skipped: Quest must be accepted / enrolled first before progress can be simulated.", "warn")
                                // update quest status in state
                                updateQuestProgressInList(quest.id, enrolled = false, completed = false)
                                return@launch
                            }

                            // Process Quest Types
                            if (quest.type == "WATCH_VIDEO") {
                                processVideoQuest(token, quest)
                            } else {
                                // Game, Stream, Activity, Achievement
                                processHeartbeatQuest(token, quest, streamChannel)
                            }
                        }
                    }
                }

                // Final Rescan
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

    private suspend fun processVideoQuest(token: String, quest: DiscordQuest) {
        log("Playing video simulator for \"${quest.name}\". Target: ${quest.target} seconds...", "info")
        var currentProgressSecs = quest.currentProgress.toDouble()
        val targetSecs = quest.target.toDouble()
        
        var failuresCount = 0

        while (currentProgressSecs < targetSecs && _isExecuting.value) {
            // Simulator sleep (interval: 7.5 - 9.5 seconds)
            val randomStepTime = Random.nextDouble(7.5, 9.5)
            log("Streaming video buffer... wait ${String.format("%.1f", randomStepTime)}s (Progress: ${currentProgressSecs.toInt()}/${quest.target}s)...", "debug")
            delay((randomStepTime * 1000).toLong())

            currentProgressSecs += randomStepTime
            if (currentProgressSecs > targetSecs) {
                currentProgressSecs = targetSecs
            }

            // Ping
            val res = withContext(Dispatchers.IO) {
                DiscordHttpClient.sendVideoProgress(token, quest.id, currentProgressSecs)
            }

            if (res.isSuccessful) {
                failuresCount = 0
                // Verify server progress value
                val serverVal = try {
                    val obj = JSONObject(res.body)
                    val progressObj = obj.optJSONObject("progress")
                    val taskDef = progressObj?.optJSONObject(quest.taskKeyName) ?: progressObj?.optJSONObject("WATCH_VIDEO")
                    taskDef?.optDouble("value") ?: currentProgressSecs
                } catch (e: Exception) {
                    currentProgressSecs
                }
                if (serverVal > currentProgressSecs) {
                    currentProgressSecs = serverVal
                }
                updateQuestProgressInList(quest.id, true, currentProgressSecs.toInt(), currentProgressSecs >= targetSecs)
            } else if (res.isRateLimit) {
                failuresCount++
                val s = res.retryAfterSeconds ?: 4
                log("Rate Limit Hit! Backing off for $s seconds...", "warn")
                delay((s + 1) * 1000)
            } else {
                failuresCount++
                log("Progression response warning: HTTP Code ${res.code}.", "warn")
                if (failuresCount > 5) {
                    log("Aborted video task: Too many consecutive network API issues.", "err")
                    break
                }
            }
        }

        if (currentProgressSecs >= targetSecs && _isExecuting.value) {
            log("Video Quest \"${quest.name}\" Completed!", "success")
            triggerClaimIfNeeded(token, quest)
        }
    }

    private suspend fun processHeartbeatQuest(
        token: String,
        quest: DiscordQuest,
        streamChannel: String
    ) {
        val streamKey = "call:$streamChannel:${Random.nextInt(1000, 9999)}"
        log("Pre-starting game emulator for \"${quest.name}\" using key: $streamKey...", "info")

        var currentProgressSecs = quest.currentProgress
        val targetSecs = quest.target
        var failuresCount = 0

        // Send initial heartbeat
        withContext(Dispatchers.IO) {
            DiscordHttpClient.sendHeartbeat(token, quest.id, streamKey, false)
        }

        while (currentProgressSecs < targetSecs && _isExecuting.value) {
            // Heartbeats interval: 19 - 22 seconds
            val delaySeconds = Random.nextInt(19, 22)
            log("Heartbeating simulator... waiting ${delaySeconds}s (Progress: $currentProgressSecs/${quest.target}s)...", "debug")
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
                        ?: progressObj?.optJSONObject("WATCH_VIDEO")
                        ?: progressObj?.optJSONObject("WATCH_VIDEO_ON_MOBILE")
                        
                    taskDef?.optInt("value") ?: (currentProgressSecs + delaySeconds)
                } else {
                    currentProgressSecs + delaySeconds
                }

                currentProgressSecs = if (serverProgress > currentProgressSecs) serverProgress else currentProgressSecs + delaySeconds
                if (currentProgressSecs > targetSecs) {
                    currentProgressSecs = targetSecs
                }

                updateQuestProgressInList(quest.id, true, currentProgressSecs, currentProgressSecs >= targetSecs)
            } else if (res.isRateLimit) {
                failuresCount++
                val s = res.retryAfterSeconds ?: 10
                log("Rate Limit Hit during heartbeat! Blocked for $s seconds.", "warn")
                delay((s + 1) * 1000)
            } else {
                failuresCount++
                log("Heartbeat failed with warning: HTTP Code ${res.code}.", "warn")
                if (failuresCount > 4) {
                    log("Aborted heartbeat task: API rejected session multiple times.", "err")
                    break
                }
            }
        }

        // Send Terminal
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
                log("Auto-Claim requires visual user interaction (e.g., Catpcha required inside Discord). Please open the Quest section in your Discord app to complete the claim.", "warn")
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
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

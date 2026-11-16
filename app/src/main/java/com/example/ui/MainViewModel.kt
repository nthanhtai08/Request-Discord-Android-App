package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DiscordAccount
import com.example.data.DiscordQuest
import com.example.data.TaskLog
import com.example.network.DiscordHttpClient
import com.example.service.DiscordQuestService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(private val db: AppDatabase) : ViewModel() {

    val currentAccount: StateFlow<DiscordAccount?> = db.discordAccountDao().getAccountFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val logs: StateFlow<List<TaskLog>> = db.taskLogDao().getAllLogsDesc()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isExecuting: StateFlow<Boolean> = DiscordQuestService.isExecuting
    val currentStatus: StateFlow<String> = DiscordQuestService.currentStatus
    val activeQuests: StateFlow<List<DiscordQuest>> = DiscordQuestService.activeQuests
    val selectedQuestIds: StateFlow<Set<String>> = DiscordQuestService.selectedQuestIds

    init {
        // Tự làm mới danh sách mỗi 5 phút để quest mới ra (đến giờ cày) tự hiện
        // và quest hết hạn tự biến mất. Bỏ qua khi đang chạy automation.
        viewModelScope.launch {
            db.discordAccountDao().getAccountFlow()
                .map { it?.token }
                .distinctUntilChanged()
                .collectLatest { token ->
                    if (token.isNullOrEmpty()) return@collectLatest
                    while (true) {
                        if (!DiscordQuestService.isExecuting.value) {
                            val quests = withContext(Dispatchers.IO) {
                                DiscordHttpClient.getQuests(token)
                            }
                            DiscordQuestService.publishQuests(quests)
                        }
                        delay(5 * 60_000L)
                    }
                }
        }
    }

    fun saveTokenAndInit(token: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            db.taskLogDao().insertLog(
                TaskLog(
                    timestamp = System.currentTimeMillis(),
                    text = "Validating Access Token with Discord API gateways...",
                    type = "info"
                )
            )

            val meObj = withContext(Dispatchers.IO) {
                DiscordHttpClient.getMe(token)
            }

            if (meObj != null) {
                val username = meObj.optString("username").ifEmpty { "Unknown User" }
                val disc = meObj.optString("discriminator").ifEmpty { "0000" }
                val formattedName = if (disc == "0" || disc == "0000") username else "$username#$disc"
                // optString trả "null" khi avatar là null, phải check isNull
                val avatarHash = if (meObj.isNull("avatar")) "" else meObj.optString("avatar")
                val avatarUrl = if (avatarHash.isNotEmpty()) {
                    "https://cdn.discordapp.com/avatars/${meObj.optString("id")}/$avatarHash.png"
                } else ""

                val newAccount = DiscordAccount(
                    token = token,
                    username = formattedName,
                    avatarUrl = avatarUrl
                )
                db.discordAccountDao().insertAccount(newAccount)

                db.taskLogDao().insertLog(
                    TaskLog(
                        timestamp = System.currentTimeMillis(),
                        text = "Access Token verified! Account logged in: $formattedName",
                        type = "success"
                    )
                )

                refreshQuests(token)
                onComplete(true)
            } else {
                db.taskLogDao().insertLog(
                    TaskLog(
                        timestamp = System.currentTimeMillis(),
                        text = "Authentication failed: token sai hoặc không kết nối được tới Discord.",
                        type = "err"
                    )
                )
                onComplete(false)
            }
        }
    }

    fun refreshQuests(token: String) {
        viewModelScope.launch {
            val svc = _service
            if (svc != null) {
                svc.refreshQuests(token)
            } else {
                // Service chưa bind: gọi API trực tiếp thay vì không làm gì
                val quests = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getQuests(token)
                }
                DiscordQuestService.publishQuests(quests)
                db.taskLogDao().insertLog(
                    TaskLog(
                        timestamp = System.currentTimeMillis(),
                        text = "Loaded ${quests.size} quests from Discord API.",
                        type = "success"
                    )
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Dừng automation đang chạy (nếu có) trước khi xóa token
            DiscordQuestService.requestStop()
            db.discordAccountDao().deleteAccount()
            db.taskLogDao().clearLogs()
            DiscordQuestService.resetState()
        }
    }

    fun updateAccountOptions(autoEnroll: Boolean, autoClaim: Boolean, playSound: Boolean) {
        viewModelScope.launch {
            val account = db.discordAccountDao().getAccount()
            if (account != null) {
                db.discordAccountDao().insertAccount(
                    account.copy(
                        autoEnroll = autoEnroll,
                        autoClaim = autoClaim,
                        playSound = playSound
                    )
                )
                db.taskLogDao().insertLog(
                    TaskLog(
                        timestamp = System.currentTimeMillis(),
                        text = "Settings updated successfully",
                        type = "info"
                    )
                )
            }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            db.taskLogDao().clearLogs()
        }
    }

    // Quest selection state lives in the service's companion object,
    // so it works whether or not the service is currently bound.
    fun toggleQuestSelection(questId: String) {
        DiscordQuestService.toggleQuestSelection(questId)
    }

    fun selectAll(quests: List<DiscordQuest>) {
        DiscordQuestService.selectAllQuests(quests)
    }

    fun deselectAll() {
        DiscordQuestService.deselectAllQuests()
    }

    fun startAutomation(context: Context) {
        val intent = Intent(context, DiscordQuestService::class.java).apply {
            action = "START_AUTOMATION"
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopAutomation(context: Context) {
        val intent = Intent(context, DiscordQuestService::class.java).apply {
            action = "STOP_AUTOMATION"
        }
        context.startService(intent)
    }

    // Keep active temporary local service binding reference
    private var _service: DiscordQuestService? = null
    fun setServiceReference(service: DiscordQuestService?) {
        _service = service
    }
}

class MainViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

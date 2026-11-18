package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.TaskLog
import com.example.network.DiscordHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

// Đưa ra ngoài class để không cần reflection (an toàn khi bật R8/minify)
private data class GameItem(val id: String, val name: String, val exe: String)

class DiscordGameTimeService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }

    private val NOTIFICATION_ID = 8892
    private val CHANNEL_ID = "discord_gametime_channel"

    companion object {
        private const val TAG = "DiscordGameTimeService"

        private val _isExecuting = MutableStateFlow(false)
        val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

        private val _currentStatus = MutableStateFlow("Idle")
        val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

        private var activeServiceJob: Job? = null

        // Scope riêng cho việc ghi log, không bị huỷ khi service onDestroy
        // => log cuối ("stopped...") không bị mất.
        private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestStop() {
            _isExecuting.value = false
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): DiscordGameTimeService = this@DiscordGameTimeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SPOOF" -> {
                val totalHours = intent.getDoubleExtra("TOTAL_HOURS", 6000.0)
                val titles = intent.getIntExtra("TITLES", 250)
                val batchSize = intent.getIntExtra("BATCH_SIZE", 25)
                val delayMs = intent.getLongExtra("DELAY_MS", 450L)
                val wobble = intent.getBooleanExtra("WOBBLE", false)
                val dryRun = intent.getBooleanExtra("DRY_RUN", false)

                // Phải gọi startForeground ngay, nếu không Android sẽ kill app
                try {
                    startForegroundServiceNotification("Initializing game time spoofer...")
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed: ${e.message}", e)
                    log("Cannot start foreground service: ${e.message}", "err")
                    _isExecuting.value = false
                    stopSelf()
                    return START_NOT_STICKY
                }

                runSpooferFlow(totalHours, titles, batchSize, delayMs, wobble, dryRun)
            }
            "STOP_SPOOF" -> stopSpooferFlow("Stopped by user")
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Discord Game Time Spoofer Services",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startForegroundServiceNotification(message: String) {
        val notification = createNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Discord Game Time Spoofer")
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

    private fun log(text: String, type: String = "info") {
        Log.d(TAG, "[$type] $text")
        val appContext = applicationContext
        logScope.launch {
            try {
                AppDatabase.getDatabase(appContext).taskLogDao().insertLog(
                    TaskLog(timestamp = System.currentTimeMillis(), text = text, type = type)
                )
            } catch (e: Exception) {
                Log.e(TAG, "log insert failed: ${e.message}")
            }
        }
    }

    private fun runSpooferFlow(
        totalHours: Double,
        titles: Int,
        batchSize: Int,
        delayMs: Long,
        wobble: Boolean,
        dryRun: Boolean
    ) {
        if (_isExecuting.value) return
        _isExecuting.value = true

        activeServiceJob = serviceScope.launch {
            try {
                _currentStatus.value = "Starting Game Time Spoofer..."
                log("Starting game hours spoofing cycle...", "info")

                val account = db.discordAccountDao().getAccount()
                if (account == null || account.token.isEmpty()) {
                    log("Aborted: Discord Token not configured.", "err")
                    stopSpooferFlow("No token")
                    return@launch
                }

                val token = account.token

                // 1. Lấy thông tin user & analytics token
                val meObj = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getMeWithAnalytics(token)
                }

                if (meObj == null) {
                    log("Failed to fetch analytics token from API.", "err")
                    stopSpooferFlow("Token error")
                    return@launch
                }

                val analyticsToken = meObj.optString("analytics_token")
                val username = meObj.optString("username", "user")
                val userId = meObj.optString("id", "")

                if (analyticsToken.isEmpty()) {
                    log("Analytics token missing!", "err")
                    stopSpooferFlow("Missing analytics token")
                    return@launch
                }

                val fingerprint = generateFingerprint(userId)

                // 2. Lấy danh sách catalog game
                log("Fetching game catalogs...", "info")
                var rawCatalogArray = withContext(Dispatchers.IO) {
                    DiscordHttpClient.getDetectableApplications(token)
                }

                if (rawCatalogArray == null || rawCatalogArray.length() == 0) {
                    try {
                        val cdnRes = withContext(Dispatchers.IO) {
                            URL("https://cdn.discordapp.com/detectables/games.json").readText()
                        }
                        rawCatalogArray = JSONArray(cdnRes)
                    } catch (e: Exception) {
                        log("Failed to load catalog from CDN: ${e.message}", "err")
                        stopSpooferFlow("Catalog error")
                        return@launch
                    }
                }

                val catalog = mutableListOf<GameItem>()
                val seen = mutableSetOf<String>()

                for (i in 0 until rawCatalogArray.length()) {
                    val item = rawCatalogArray.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isEmpty() || seen.contains(id)) continue

                    val executables = item.optJSONArray("executables")
                    var winExe: String? = null
                    if (executables != null) {
                        for (j in 0 until executables.length()) {
                            val exeObj = executables.getJSONObject(j)
                            if (exeObj.optString("os") == "win32") {
                                winExe = exeObj.optString("name")
                                break
                            }
                        }
                    }
                    if (winExe == null && executables != null && executables.length() > 0) continue
                    val exe = winExe
                        ?: "${name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase().ifEmpty { "game" }}.exe"
                    seen.add(id)
                    catalog.add(GameItem(id, name, exe))
                }

                if (catalog.isEmpty()) {
                    log("No valid win32 games found.", "warn")
                    stopSpooferFlow("No games found")
                    return@launch
                }

                val safeBatch = maxOf(1, batchSize)
                val selected = catalog.shuffled().take(min(maxOf(1, titles), catalog.size))
                val hoursPerGame = totalHours / maxOf(1, selected.size)

                log(
                    "Connected as $username | Target: ${totalHours}h across ${selected.size} games (~${String.format("%.1f", hoursPerGame)}h/game)",
                    "success"
                )

                val heartbeatId = UUID.randomUUID().toString()
                val signature = UUID.randomUUID().toString()
                var seq = 0

                val totalBatches = ceil(selected.size.toDouble() / safeBatch).toInt()
                var loggedHours = 0.0
                var forgedCount = 0

                for (i in 0 until totalBatches) {
                    if (!_isExecuting.value) break

                    val chunk = selected.subList(i * safeBatch, min((i + 1) * safeBatch, selected.size))
                    val eventsArray = JSONArray()
                    var chunkHours = 0.0

                    for (game in chunk) {
                        val drift = if (wobble) 1.0 + (Random.nextDouble() * 0.06 - 0.03) else 1.0
                        val hours = maxOf(0.1, hoursPerGame * drift)
                        val durationMs = (hours * 3600 * 1000).toLong()
                        val now = System.currentTimeMillis()
                        val start = now - durationMs
                        val sid = UUID.randomUUID().toString()

                        eventsArray.put(buildHeartbeatJson(game, 0L, sid, true, false, start, heartbeatId, signature, ++seq, fingerprint))
                        eventsArray.put(buildLaunchJson(game, now, heartbeatId, signature, ++seq, fingerprint))
                        eventsArray.put(buildHeartbeatJson(game, durationMs, sid, false, true, now, heartbeatId, signature, ++seq, fingerprint))

                        chunkHours += hours
                    }

                    updateNotification("Processing batch ${i + 1}/$totalBatches")
                    _currentStatus.value = "Batch ${i + 1}/$totalBatches"

                    if (dryRun) {
                        forgedCount += chunk.size
                        loggedHours += chunkHours
                        log("[Dry-Run] Batch ${i + 1}/$totalBatches (+${String.format("%.1f", chunkHours)}h)", "info")
                    } else {
                        val res = withContext(Dispatchers.IO) {
                            DiscordHttpClient.sendScienceEvents(token, analyticsToken, eventsArray)
                        }

                        if (res.isSuccessful || res.code == 204) {
                            forgedCount += chunk.size
                            loggedHours += chunkHours
                            log("Batch ${i + 1}/$totalBatches sent successfully (+${String.format("%.1f", chunkHours)}h)", "success")
                        } else {
                            log("Batch ${i + 1}/$totalBatches response code: ${res.code}", "warn")
                            if (res.isRateLimit) {
                                val waitS = res.retryAfterSeconds ?: 5L
                                log("Rate limited! Cooling down ${waitS}s...", "warn")
                                delay(waitS * 1000)
                            }
                        }
                    }

                    val jitter = delayMs + Random.nextInt(-75, 75)
                    delay(maxOf(100L, jitter))
                }

                log(
                    "Done: $forgedCount/${selected.size} games forged | Total playtime: ${String.format("%.1f", loggedHours)}h",
                    "success"
                )
                stopSpooferFlow("Done! Playtime forged successfully.")

            } catch (e: CancellationException) {
                log("Spoofer process canceled.", "warn")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Spoofer error", e)
                log("Spoofer error state: ${e.message}", "err")
                stopSpooferFlow("Crash: ${e.message}")
            }
        }
    }

    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    private fun generateFingerprint(uid: String): String {
        val guid = UUID.randomUUID().toString()
        val machineUserHash = sha256((guid + uid).toByteArray(Charsets.UTF_8))
        val headerHash = sha256(("pe_header_$uid").toByteArray(Charsets.UTF_8))
        val sectionHash = sha256(("pe_section_$uid").toByteArray(Charsets.UTF_8))
        val certHash = sha256(("pe_cert_$uid").toByteArray(Charsets.UTF_8))

        val buf = ByteArray(146)
        buf[0] = 1
        buf[1] = 1

        val bigVal = 65432100L
        for (i in 0..7) buf[2 + i] = ((bigVal shr (i * 8)) and 0xFF).toByte()

        val timestamp = 1690000000
        for (i in 0..3) buf[10 + i] = ((timestamp shr (i * 8)) and 0xFF).toByte()

        val flags = 0x02500000
        for (i in 0..3) buf[14 + i] = ((flags shr (i * 8)) and 0xFF).toByte()

        System.arraycopy(headerHash, 0, buf, 18, 32)
        System.arraycopy(machineUserHash, 0, buf, 50, 32)
        System.arraycopy(sectionHash, 0, buf, 82, 32)
        System.arraycopy(certHash, 0, buf, 114, 32)

        val key = sha256((uid + "https://discord.com/careers").toByteArray(Charsets.UTF_8))
        for (i in 1 until buf.size) {
            buf[i] = (buf[i].toInt() xor key[(i - 1) and 31].toInt()).toByte()
        }

        return Base64.encodeToString(buf, Base64.NO_WRAP)
    }

    private fun buildLaunchJson(
        game: GameItem,
        timestamp: Long,
        heartbeatId: String,
        signature: String,
        seq: Int,
        fingerprint: String
    ): JSONObject {
        return JSONObject().apply {
            put("type", "launch_game")
            put("properties", JSONObject().apply {
                put("client_track_timestamp", timestamp)
                put("client_heartbeat_session_id", heartbeatId)
                put("event_sequence_number", seq)
                put("game", game.name)
                put("game_id", game.id)
                put("verified", true)
                put("elevated", false)
                put("is_launcher", false)
                put("game_platform", "desktop")
                put("detection_method", "verified_game")
                put("is_overlay_enabled", false)
                put("is_overlay_game_enabled", true)
                put("is_overlay_game_source", "OOP_DEFAULT_DATABASE")
                put("fullscreen_type", "UNKNOWN")
                put("hardware_display_count", 1)
                put("overlay_method", "Disabled")
                put("activity_status_enabled", true)
                put("activity_status_shared_guilds", JSONArray())
                put("current_user_status", "online")
                put("game_detection_enabled", true)
                put("executable_path", game.exe)
                put("voice_channel_id", JSONObject.NULL)
                put("voice_channel_type", JSONObject.NULL)
                put("voice_channel_bitrate", JSONObject.NULL)
                put("voice_channel_guild_id", JSONObject.NULL)
                put("hidden_by_distributor", false)
                put("game_metadata", JSONObject.NULL)
                put("executable_fingerprint", fingerprint)
                put("client_performance_cpu", JSONObject.NULL)
                put("client_performance_memory", JSONObject.NULL)
                put("cpu_core_count", JSONObject.NULL)
                put("accessibility_features", 0)
                put("rendered_locale", "en-US")
                put("launch_signature", signature)
                put("client_rtc_state", JSONObject.NULL)
                put("client_app_state", "focused")
                put("client_send_timestamp", timestamp)
            })
        }
    }

    private fun buildHeartbeatJson(
        game: GameItem,
        durationMs: Long,
        sid: String,
        initial: Boolean,
        final: Boolean,
        timestamp: Long,
        heartbeatId: String,
        signature: String,
        seq: Int,
        fingerprint: String
    ): JSONObject {
        return JSONObject().apply {
            put("type", "running_game_heartbeat")
            put("properties", JSONObject().apply {
                put("client_track_timestamp", timestamp)
                put("client_heartbeat_session_id", heartbeatId)
                put("event_sequence_number", seq)
                put("game_id", game.id)
                put("game_name", game.name)
                put("game_metadata", JSONObject.NULL)
                put("game_executable", game.exe)
                put("game_detection_enabled", true)
                put("initial_heartbeat", initial)
                put("final_heartbeat", final)
                put("game_session_id", sid)
                put("duration_tracked_ms", durationMs)
                put("rtc_connection_id", JSONObject.NULL)
                put("media_session_id", JSONObject.NULL)
                put("launch_signature", signature)
                put("client_app_state", "focused")
                put("client_send_timestamp", timestamp)
            })
        }
    }

    private fun stopSpooferFlow(reason: String) {
        _isExecuting.value = false
        _currentStatus.value = "Idle"
        activeServiceJob?.cancel()
        activeServiceJob = null
        log("Game time spoofer stopped: $reason", "info")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        _isExecuting.value = false
        serviceJob.cancel()
    }
}

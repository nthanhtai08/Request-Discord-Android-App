package com.example.service

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.random.Random

object DiscordGameTimeService {
    private const val TAG = "DiscordGameTimeService"
    private const val BASE_URL = "https://discord.com/api/v9"
    private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "discord/1.0.9237 Chrome/138.0.7204.251 Electron/37.6.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class SpooferConfig(
        val totalHours: Double = 6000.0,
        val titles: Int = 250,
        val batchSize: Int = 25,
        val delayMs: Long = 450L,
        val wobble: Boolean = false,
        val dryRun: Boolean = false
    )

    data class GameItem(
        val id: String,
        val name: String,
        val exe: String
    )

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun generateFingerprint(uid: String): String {
        val guid = UUID.randomUUID().toString()
        val machineUserHash = sha256((guid + uid).toByteArray(Charsets.UTF_8))
        val headerHash = sha256(("pe_header_$uid").toByteArray(Charsets.UTF_8))
        val sectionHash = sha256(("pe_section_$uid").toByteArray(Charsets.UTF_8))
        val certHash = sha256(("pe_cert_$uid").toByteArray(Charsets.UTF_8))

        val buf = ByteArray(146)
        buf[0] = 1
        buf[1] = 1
        
        // setBigUint64 ở index 2
        val bigVal = 65432100L
        for (i in 0..7) {
            buf[2 + i] = ((bigVal shr (i * 8)) and 0xFF).toByte()
        }
        
        // setUint32 timestamp ở index 10
        val timestamp = 1690000000
        for (i in 0..3) {
            buf[10 + i] = ((timestamp shr (i * 8)) and 0xFF).toByte()
        }
        
        // setUint32 flags ở index 14
        val flags = 0x02500000
        for (i in 0..3) {
            buf[14 + i] = ((flags shr (i * 8)) and 0xFF).toByte()
        }

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

    /**
     * Chạy tiến trình giả lập thời gian chơi game tương tự script JS.
     * @param token Discord User Token
     * @param config Cấu hình số giờ, số game, batch size,...
     * @param onLog Callback để cập nhật log ra UI hoặc Logcat.
     */
    suspend fun startSpoofing(
        token: String,
        config: SpooferConfig = SpooferConfig(),
        onLog: (String) -> Unit = {}
    ) {
        // 1. Lấy thông tin user & analytics token
        val meReq = Request.Builder()
            .url("$BASE_URL/users/@me?with_analytics_token=true")
            .header("Authorization", token)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        var analyticsToken: String? = null
        var username = "user"
        var userId = ""

        try {
            client.newCall(meReq).execute().use { res ->
                if (!res.isSuccessful) {
                    onLog("[spoofer] Failed to fetch analytics token: ${res.code}")
                    return
                }
                val body = JSONObject(res.body?.string() ?: "{}")
                analyticsToken = body.optString("analytics_token")
                username = body.optString("username", "user")
                userId = body.optString("id", "")
            }
        } catch (e: Exception) {
            onLog("[spoofer] Error fetching user info: ${e.message}")
            return
        }

        if (analyticsToken.isNullOrEmpty()) {
            onLog("[spoofer] Analytics token missing!")
            return
        }

        val fingerprint = generateFingerprint(userId)

        // 2. Lấy danh sách game detectable
        val catalogReq = Request.Builder()
            .url("$BASE_URL/applications/detectable")
            .header("Authorization", token)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val rawCatalog = mutableListOf<JSONObject>()
        try {
            client.newCall(catalogReq).execute().use { res ->
                if (res.isSuccessful) {
                    val arr = JSONArray(res.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        rawCatalog.add(arr.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            onLog("[spoofer] Failed to load local game catalog, trying CDN...")
        }

        if (rawCatalog.isEmpty()) {
            try {
                val cdnReq = Request.Builder().url("https://cdn.discordapp.com/detectables/games.json").get().build()
                client.newCall(cdnReq).execute().use { res ->
                    if (res.isSuccessful) {
                        val arr = JSONArray(res.body?.string() ?: "[]")
                        for (i in 0 until arr.length()) {
                            rawCatalog.add(arr.getJSONObject(i))
                        }
                    }
                }
            } catch (e: Exception) {
                onLog("[spoofer] Failed to load game catalog from CDN: ${e.message}")
                return
            }
        }

        val catalog = mutableListOf<GameItem>()
        val seen = mutableSetOf<String>()

        for (item in rawCatalog) {
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
            val exe = winExe ?: "${name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase().ifEmpty { "game" }}.exe"
            seen.add(id)
            catalog.add(GameItem(id, name, exe))
        }

        if (catalog.isEmpty()) {
            onLog("[spoofer] No valid win32 games found")
            return
        }

        val selected = catalog.shuffled().take(min(config.titles, catalog.size))
        val hoursPerGame = config.totalHours / maxOf(1, selected.size)

        onLog("[spoofer] Connected as $username | Target: ${config.totalHours}h across ${selected.size} games (~${String.format("%.1f", hoursPerGame)}h/game)")

        val heartbeatId = UUID.randomUUID().toString()
        val signature = UUID.randomUUID().toString()
        var seq = 0

        val totalBatches = ceil(selected.size.toDouble() / config.batchSize).toInt()
        var loggedHours = 0.0
        var forgedCount = 0

        for (i in 0 until totalBatches) {
            val chunk = selected.subList(i * config.batchSize, min((i + 1) * config.batchSize, selected.size))
            val eventsArray = JSONArray()
            var chunkHours = 0.0

            for (game in chunk) {
                val drift = if (config.wobble) 1.0 + (Random.nextDouble() * 0.06 - 0.03) else 1.0
                val hours = maxOf(0.1, hoursPerGame * drift)
                val durationMs = (hours * 3600 * 1000).toLong()
                val now = System.currentTimeMillis()
                val start = now - durationMs
                val sid = UUID.randomUUID().toString()

                // 1. Initial heartbeat
                eventsArray.put(buildHeartbeatJson(game, 0L, sid, true, false, start, heartbeatId, signature, ++seq, fingerprint))
                // 2. Launch game
                eventsArray.put(buildLaunchJson(game, now, heartbeatId, signature, ++seq, fingerprint))
                // 3. Final heartbeat
                eventsArray.put(buildHeartbeatJson(game, durationMs, sid, false, true, now, heartbeatId, signature, ++seq, fingerprint))

                chunkHours += hours
            }

            if (config.dryRun) {
                forgedCount += chunk.size
                loggedHours += chunkHours
                onLog("[spoofer] [dry-run] Batch ${i + 1}/$totalBatches (+${String.format("%.1f", chunkHours)}h)")
            } else {
                try {
                    val bodyJson = JSONObject().apply {
                        put("token", analyticsToken)
                        put("events", eventsArray)
                    }

                    val reqBody = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)
                    val scienceReq = Request.Builder()
                        .url("$BASE_URL/science")
                        .header("Authorization", token)
                        .header("User-Agent", USER_AGENT)
                        .header("Content-Type", "application/json")
                        .post(reqBody)
                        .build()

                    client.newCall(scienceReq).execute().use { res ->
                        if (res.code in 200..299 || res.code == 204) {
                            forgedCount += chunk.size
                            loggedHours += chunkHours
                            onLog("[spoofer] Batch ${i + 1}/$totalBatches sent (+${String.format("%.1f", chunkHours)}h)")
                        } else {
                            onLog("[spoofer] Batch ${i + 1}/$totalBatches response code: ${res.code}")
                            if (res.code == 429) {
                                onLog("[spoofer] Rate limited! Cooling down 5s...")
                                delay(5000)
                            }
                        }
                    }
                } catch (e: Exception) {
                    onLog("[spoofer] Batch ${i + 1}/$totalBatches failed: ${e.message}")
                }
            }

            val jitter = config.delayMs + Random.nextInt(-75, 75)
            delay(maxOf(100L, jitter))
        }

        onLog("[spoofer] Done: $forgedCount/${selected.size} games forged | Total playtime: ${String.format("%.1f", loggedHours)}h")
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
}

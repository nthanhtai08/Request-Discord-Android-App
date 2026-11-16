package com.example.network

import android.util.Base64
import android.util.Log
import com.example.data.DiscordQuest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object DiscordHttpClient {
    private const val TAG = "DiscordHttpClient"
    private const val BASE_URL = "https://discord.com/api/v9"
    private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

    // Không chặn quest nào nữa, load full. Muốn chặn quest lỗi thì thêm ID vào đây.
    private val BLACKLISTED_QUEST_IDS = emptySet<String>()
    val enrolledAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "discord/1.0.9237 Chrome/138.0.7204.251 Electron/37.6.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // X-Super-Properties: Discord hay yêu cầu header này, thiếu có thể bị 400/403
    private val superProperties: String by lazy {
        val json = JSONObject().apply {
            put("os", "Windows")
            put("browser", "Discord Client")
            put("release_channel", "stable")
            put("client_version", "1.0.9237")
            put("os_version", "10.0.19045")
            put("os_arch", "x64")
            put("app_arch", "x64")
            put("system_locale", "vi")
            put("browser_user_agent", USER_AGENT)
            put("browser_version", "37.6.0")
            put("client_build_number", 460000)
            put("native_build_number", JSONObject.NULL)
            put("client_event_source", JSONObject.NULL)
        }.toString()
        Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun getHeaders(token: String): Map<String, String> {
        return mapOf(
            "Authorization" to token,
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json",
            "Accept" to "*/*",
            "X-Super-Properties" to superProperties,
            "X-Discord-Locale" to "vi",
            "X-Discord-Timezone" to "Asia/Bangkok",
            "Accept-Language" to "vi-VN,vi;q=0.9"
        )
    }

    private fun makeRequest(
        url: String,
        method: String,
        bodyJson: String?,
        token: String
    ): HttpResponse {
        val requestBuilder = Request.Builder().url(url)

        getHeaders(token).forEach { (k, v) ->
            requestBuilder.header(k, v)
        }

        if (method == "POST") {
            val reqBody = (bodyJson ?: "{}").toRequestBody(JSON_MEDIA_TYPE)
            requestBuilder.post(reqBody)
        } else if (method == "GET") {
            requestBuilder.get()
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val bodyStr = response.body?.string() ?: ""
                HttpResponse(code, bodyStr, response.header("Retry-After")?.toDoubleOrNull()?.toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error: $url", e)
            HttpResponse(-1, "", null, e.message)
        }
    }

    data class HttpResponse(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
        val errorMessage: String? = null
    ) {
        val isSuccessful: Boolean get() = code in 200..299
        val isRateLimit: Boolean get() = code == 429
    }

    // 1. Get Me (Validate Token)
    fun getMe(token: String): JSONObject? {
        val res = makeRequest("$BASE_URL/users/@me", "GET", null, token)
        if (res.isSuccessful) {
            try {
                return JSONObject(res.body)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing user info", e)
            }
        }
        return null
    }

    // 2. Fetch User Channel List
    fun getChannels(token: String): List<String> {
        val channels = mutableListOf<String>()
        val res = makeRequest("$BASE_URL/users/@me/channels", "GET", null, token)
        if (res.isSuccessful) {
            try {
                val arr = JSONArray(res.body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id")
                    if (id.isNotEmpty()) {
                        channels.add(id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing channels", e)
            }
        }
        return channels
    }

    // 3. Get Quests List
    // includeInactive = false (mặc định): chỉ hiện quest đang cho cày
    // (đã đến giờ bắt đầu và chưa hết hạn). true: lấy cả quest chưa ra / đã hết hạn.
    fun getQuests(token: String, includeInactive: Boolean = false): List<DiscordQuest> {
        val res = makeRequest("$BASE_URL/quests/@me", "GET", null, token)
        if (!res.isSuccessful) {
            Log.e(TAG, "Failed to get quests: Code ${res.code} body: ${res.body} err: ${res.errorMessage}")
            return emptyList()
        }
        return parseQuests(res.body, includeInactive)
    }

    // 4. Enroll Quest
    fun enrollQuest(token: String, questId: String): HttpResponse {
        val body = JSONObject().apply {
            put("location", 11)
            put("is_targeted", false)
        }.toString()
        return makeRequest("$BASE_URL/quests/$questId/enroll", "POST", body, token)
    }

    // 5. Send Video Progress
    fun sendVideoProgress(token: String, questId: String, seconds: Double): HttpResponse {
        val roundedSeconds = String.format(java.util.Locale.US, "%.6f", seconds)
        val bodyStr = "{\"timestamp\":$roundedSeconds}"
        return makeRequest("$BASE_URL/quests/$questId/video-progress", "POST", bodyStr, token)
    }

    // 6. Send Game/Stream/Activity Heartbeat
    fun sendHeartbeat(token: String, questId: String, streamKey: String, terminal: Boolean): HttpResponse {
        val bodyStr = JSONObject().apply {
            put("stream_key", streamKey)
            put("terminal", terminal)
        }.toString()
        return makeRequest("$BASE_URL/quests/$questId/heartbeat", "POST", bodyStr, token)
    }

    // 7. Claim Quest Reward
    fun claimReward(token: String, questId: String): HttpResponse {
        val body = JSONObject().apply {
            put("platform", 0)
            put("location", 11)
            put("is_targeted", false)
            put("metadata_raw", JSONObject.NULL)
            put("metadata_sealed", JSONObject.NULL)
            put("traffic_metadata_raw", JSONObject.NULL)
            put("traffic_metadata_sealed", JSONObject.NULL)
        }.toString()
        return makeRequest("$BASE_URL/quests/$questId/claim-reward", "POST", body, token)
    }

    /** Lấy JSONObject theo nhiều tên key khác nhau (snake_case / camelCase). */
    private fun JSONObject.optObj(vararg keys: String): JSONObject? {
        for (k in keys) {
            val o = optJSONObject(k)
            if (o != null) return o
        }
        return null
    }

    /** Lấy chuỗi theo nhiều tên key, bỏ qua rỗng / "null". */
    private fun JSONObject.optStr(vararg keys: String): String? {
        for (k in keys) {
            if (isNull(k)) continue
            val s = optString(k)
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    private fun detectTaskType(taskName: String): String? {
        val upper = taskName.uppercase()
        return when {
            upper.contains("VIDEO") -> "WATCH_VIDEO"
            upper.contains("ACHIEVEMENT_IN_ACTIVITY") -> "ACHIEVEMENT"
            upper.contains("ACTIVITY") -> "ACTIVITY"
            upper.contains("STREAM") -> "STREAM"
            upper.contains("PLAY") -> "GAME"
            else -> null
        }
    }

    private fun parseQuests(jsonStr: String, includeInactive: Boolean): List<DiscordQuest> {
        val list = mutableListOf<DiscordQuest>()
        try {
            Log.d(TAG, "Quests raw: ${jsonStr.take(2000)}")

            val array = if (jsonStr.trim().startsWith("[")) {
                JSONArray(jsonStr)
            } else {
                // /quests/@me hiện trả về object có key "quests"
                val obj = JSONObject(jsonStr)
                obj.optJSONArray("quests") ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val qObj = array.getJSONObject(i)
                val id = qObj.optString("id").takeIf { it.isNotEmpty() } ?: continue
                if (id in BLACKLISTED_QUEST_IDS) continue

                // Không có config thì dùng object rỗng thay vì bỏ quest
                val config = qObj.optJSONObject("config") ?: JSONObject()

                val messages = config.optObj("messages")
                val questName = messages?.optStr("quest_name", "questName") ?: "Unknown Quest"
                val startsAt = config.optStr("starts_at", "startsAt") ?: ""
                val expiresAt = config.optStr("expires_at", "expiresAt") ?: ""

                val active = isQuestActive(startsAt, expiresAt)
                if (!active && !includeInactive) {
                    Log.d(TAG, "Quest $id ($questName) skipped: not active (start=$startsAt, end=$expiresAt)")
                    continue
                }

                val application = config.optObj("application")
                val appId = application?.optStr("id") ?: "0"

                // Discord có thể trả về task_config, task_config_v2, taskConfig hoặc taskConfigV2
                val taskConfig = config.optObj("task_config_v2", "task_config", "taskConfigV2", "taskConfig")
                val tasksObj = taskConfig?.optJSONObject("tasks")

                var detectedType = "Unknown"
                var taskKey = ""
                var target = 0

                if (tasksObj != null) {
                    // Ưu tiên task video nếu quest có nhiều task
                    val keys = tasksObj.keys().asSequence().toList()
                    val ordered = keys.sortedBy { if (it.uppercase().contains("VIDEO")) 0 else 1 }
                    for (k in ordered) {
                        val type = detectTaskType(k) ?: continue
                        detectedType = type
                        taskKey = k
                        target = tasksObj.optJSONObject(k)?.optInt("target") ?: 0
                        break
                    }

                    // Fallback: có task nhưng không nhận ra loại
                    if (detectedType == "Unknown" && tasksObj.length() > 0) {
                        taskKey = keys.first()
                        target = tasksObj.optJSONObject(taskKey)?.optInt("target") ?: 0
                        if (appId != "0") detectedType = "GAME"
                    }
                }

                Log.d(
                    TAG,
                    "Quest $id ($questName) configKeys=${config.keys().asSequence().toList()} " +
                        "taskKey=$taskKey type=$detectedType target=$target"
                )

                // Reward
                val rewardsConfig = config.optObj("rewards_config", "rewardsConfig")
                val rewardObj = rewardsConfig?.optJSONArray("rewards")?.optJSONObject(0)
                val rewardType = rewardObj?.optInt("type") ?: 0
                val rewardName = rewardObj?.optObj("messages")?.optStr("name", "name_with_article")
                    ?: "Mystery Reward"

                // User status
                val userStatus = qObj.optObj("user_status", "userStatus")
                val enrolledAt = userStatus?.optStr("enrolled_at", "enrolledAt")
                val completedAt = userStatus?.optStr("completed_at", "completedAt")
                if (!enrolledAt.isNullOrEmpty()) {
                    val ms = parseIsoDateToMillis(enrolledAt)
                    if (ms > 0L) enrolledAtMillis.putIfAbsent(id, ms)
                }
                // Progress: chỉ đọc đúng task đang theo dõi
                var progress = 0
                val progressObj = userStatus?.optJSONObject("progress")
                if (progressObj != null && taskKey.isNotEmpty()) {
                    progress = progressObj.optJSONObject(taskKey)
                        ?.optDouble("value", 0.0)?.toInt() ?: 0
                }
                if (progress == 0 && userStatus != null) {
                    progress = when {
                        userStatus.has("stream_progress_seconds") -> userStatus.optDouble("stream_progress_seconds", 0.0).toInt()
                        userStatus.has("streamProgressSeconds") -> userStatus.optDouble("streamProgressSeconds", 0.0).toInt()
                        else -> 0
                    }
                }

                list.add(
                    DiscordQuest(
                        id = id,
                        name = questName,
                        appId = appId,
                        expiresAt = expiresAt,
                        type = detectedType,
                        taskKeyName = taskKey,
                        target = target,
                        currentProgress = progress,
                        rewardType = rewardType,
                        rewardName = rewardName,
                        enrolled = !enrolledAt.isNullOrEmpty(),
                        completed = !completedAt.isNullOrEmpty()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quest parsing error: ", e)
        }
        Log.d(TAG, "Parsed ${list.size} quests")
        return list
    }

    private fun isQuestActive(startsAtStr: String?, expiresAtStr: String?): Boolean {
        val now = System.currentTimeMillis()
        val startsAtMs = parseIsoDateToMillis(startsAtStr)
        val expiresAtMs = parseIsoDateToMillis(expiresAtStr)

        if (startsAtMs > 0L && now < startsAtMs) return false
        if (expiresAtMs > 0L && now > expiresAtMs) return false
        return true
    }

    private fun parseIsoDateToMillis(iso: String?): Long {
        if (iso.isNullOrEmpty()) return 0L
        try {
            val cleaned = iso.trim()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // OffsetDateTime xử lý được cả "Z" lẫn "+00:00"
                return java.time.OffsetDateTime.parse(cleaned).toInstant().toEpochMilli()
            } else {
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss"
                )
                for (fmt in formats) {
                    try {
                        val parser = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                        parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        val date = parser.parse(cleaned)
                        if (date != null) return date.time
                    } catch (e: Exception) {
                        // thử format tiếp theo
                    }
                }
                if (cleaned.length >= 19) {
                    val part = cleaned.substring(0, 19)
                    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val date = parser.parse(part)
                    if (date != null) return date.time
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ISO date time: $iso", e)
        }
        return 0L
    }
}

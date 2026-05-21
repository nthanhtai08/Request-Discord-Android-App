package com.example.network

import android.util.Log
import com.example.data.DiscordQuest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object DiscordHttpClient {
    private const val TAG = "DiscordHttpClient"
    private const val BASE_URL = "https://discord.com/api/v9"
    private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getHeaders(token: String): Map<String, String> {
        return mapOf(
            "Authorization" to token,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9237 Chrome/138.0.7204.251 Electron/37.6.0 Safari/537.36",
            "Content-Type" to "application/json",
            "Accept" to "*/*",
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
        
        // Add headers
        getHeaders(token).forEach { (k, v) ->
            requestBuilder.addHeader(k, v)
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
                HttpResponse(code, bodyStr, response.header("Retry-After")?.toLongOrNull())
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
    fun getQuests(token: String): List<DiscordQuest> {
        val res = makeRequest("$BASE_URL/quests/@me", "GET", null, token)
        if (!res.isSuccessful) {
            Log.e(TAG, "Failed to get quests: Code ${res.code} body: ${res.body}")
            return emptyList()
        }
        return parseQuests(res.body)
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
        val roundedSeconds = String.format("%.6f", seconds).replace(",", ".")
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
            put("metadata_raw", null)
            put("metadata_sealed", null)
            put("traffic_metadata_raw", null)
            put("traffic_metadata_sealed", null)
        }.toString()
        return makeRequest("$BASE_URL/quests/$questId/claim-reward", "POST", body, token)
    }

    private fun parseQuests(jsonStr: String): List<DiscordQuest> {
        val list = mutableListOf<DiscordQuest>()
        try {
            val array = if (jsonStr.trim().startsWith("[")) {
                JSONArray(jsonStr)
            } else {
                // Sometimes it might come inside some wrapper key, but list is standard
                val obj = JSONObject(jsonStr)
                obj.optJSONArray("quests") ?: JSONArray()
            }
            
            for (i in 0 until array.length()) {
                val qObj = array.getJSONObject(i)
                val id = qObj.optString("id") ?: continue
                if (id == "1412491570820812933") continue // Known blacklisted quest that breaks enrollment

                val config = qObj.optJSONObject("config") ?: continue
                val messages = config.optJSONObject("messages")
                val questName = messages?.optString("quest_name")?.takeIf { it.isNotEmpty() }
                    ?: messages?.optString("questName")
                    ?: "Unknown Quest"
                val startsAt = config.optString("starts_at").takeIf { it.isNotEmpty() }
                    ?: config.optString("startsAt")
                    ?: ""
                val expiresAt = config.optString("expires_at").takeIf { it.isNotEmpty() }
                    ?: config.optString("expiresAt")
                    ?: ""

                if (!isQuestActive(startsAt, expiresAt)) {
                    continue
                }
                
                // App ID
                val application = config.optJSONObject("application")
                val appId = application?.optString("id") ?: "0"

                val originalTaskConfig = config.optJSONObject("task_config_v2")
                    ?: config.optJSONObject("taskConfig")
                    ?: config.optJSONObject("taskConfigV2")
                val tasksObj = originalTaskConfig?.optJSONObject("tasks")

                // Detect first task
                var detectedType = "Unknown"
                var taskKey = ""
                var target = 0
                
                if (tasksObj != null) {
                    val keys = tasksObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val taskDef = tasksObj.optJSONObject(k)
                        val tVal = taskDef?.optInt("target") ?: 0
                        
                        val upperK = k.uppercase()
                        val matchType = when {
                            upperK.contains("PLAY") -> "GAME"
                            upperK.contains("STREAM") -> "STREAM"
                            upperK.contains("VIDEO") -> "WATCH_VIDEO"
                            upperK.contains("ACHIEVEMENT_IN_ACTIVITY") -> "ACHIEVEMENT"
                            upperK.contains("ACTIVITY") -> "ACTIVITY"
                            else -> null
                        }
                        if (matchType != null) {
                            detectedType = matchType
                            taskKey = k
                            target = tVal
                            break
                        }
                    }
                }

                // Fallback for app ID but no typed tasks
                if (detectedType == "Unknown" && appId != "0" && tasksObj != null && tasksObj.length() > 0) {
                    detectedType = "GAME"
                    taskKey = tasksObj.keys().next()
                    target = tasksObj.optJSONObject(taskKey)?.optInt("target") ?: 0
                }

                // Parse Reward
                val rewardsConfig = config.optJSONObject("rewards_config")
                    ?: config.optJSONObject("rewardsConfig")
                val rewardsArr = rewardsConfig?.optJSONArray("rewards")
                val rewardMsgObj = rewardsArr?.optJSONObject(0)
                val rewardType = rewardMsgObj?.optInt("type") ?: 0
                val rewardMsgDetails = rewardMsgObj?.optJSONObject("messages")
                val rewardName = rewardMsgDetails?.optString("name") ?: "Mystery Reward"

                // Parse userStatus
                val userStatus = qObj.optJSONObject("user_status")
                    ?: qObj.optJSONObject("userStatus")
                val enrolledAt = userStatus?.optString("enrolled_at")
                    ?: userStatus?.optString("enrolledAt")
                val completedAt = userStatus?.optString("completed_at")
                    ?: userStatus?.optString("completedAt")
                
                // progress search
                var progress = 0
                val progressObj = userStatus?.optJSONObject("progress")
                if (progressObj != null && taskKey.isNotEmpty()) {
                    val taskProgress = progressObj.optJSONObject(taskKey)
                    progress = taskProgress?.optInt("value") ?: 0
                }
                if (progress == 0 && userStatus != null) {
                    progress = if (userStatus.has("stream_progress_seconds")) {
                        userStatus.optInt("stream_progress_seconds")
                    } else {
                        userStatus.optInt("streamProgressSeconds")
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
                        enrolled = !enrolledAt.isNullOrEmpty() && enrolledAt != "null",
                        completed = !completedAt.isNullOrEmpty() && completedAt != "null"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quest parsing error: ", e)
        }
        return list
    }

    private fun isQuestActive(startsAtStr: String?, expiresAtStr: String?): Boolean {
        val now = System.currentTimeMillis()
        val startsAtMs = parseIsoDateToMillis(startsAtStr)
        val expiresAtMs = parseIsoDateToMillis(expiresAtStr)
        
        val hasStart = startsAtMs > 0L
        val hasEnd = expiresAtMs > 0L
        
        if (hasStart && now < startsAtMs) {
            return false
        }
        if (hasEnd && now > expiresAtMs) {
            return false
        }
        return true
    }

    private fun parseIsoDateToMillis(iso: String?): Long {
        if (iso.isNullOrEmpty()) return 0L
        try {
            val cleaned = iso.trim()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return java.time.ZonedDateTime.parse(cleaned).toInstant().toEpochMilli()
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
                        // try next
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

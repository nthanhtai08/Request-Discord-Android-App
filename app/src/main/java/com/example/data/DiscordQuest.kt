package com.example.data

data class DiscordQuest(
    val id: String,
    val name: String,
    val appId: String,
    val expiresAt: String,
    val type: String, // "GAME", "STREAM", "WATCH_VIDEO", "ACHIEVEMENT", "ACTIVITY", "Unknown"
    val taskKeyName: String,
    val target: Int,
    val currentProgress: Int,
    val rewardType: Int,
    val rewardName: String,
    val enrolled: Boolean,
    val completed: Boolean
)

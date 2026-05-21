package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discord_accounts")
data class DiscordAccount(
    @PrimaryKey val id: String = "primary_account",
    val token: String,
    val username: String = "",
    val avatarUrl: String = "",
    val autoEnroll: Boolean = true,
    val autoClaim: Boolean = false,
    val playSound: Boolean = false
)

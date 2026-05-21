package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_logs")
data class TaskLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val text: String,
    val type: String // "info", "success", "warn", "err", "debug"
)

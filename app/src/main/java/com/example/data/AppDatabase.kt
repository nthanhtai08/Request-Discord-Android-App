package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskLogDao {
    @Query("SELECT * FROM task_logs ORDER BY id DESC LIMIT 100")
    fun getAllLogsDesc(): Flow<List<TaskLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: TaskLog)

    @Query("DELETE FROM task_logs")
    suspend fun clearLogs()
}

@Dao
interface DiscordAccountDao {
    @Query("SELECT * FROM discord_accounts WHERE id = 'primary_account' LIMIT 1")
    fun getAccountFlow(): Flow<DiscordAccount?>

    @Query("SELECT * FROM discord_accounts WHERE id = 'primary_account' LIMIT 1")
    suspend fun getAccount(): DiscordAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: DiscordAccount)

    @Query("DELETE FROM discord_accounts")
    suspend fun deleteAccount()
}

@Database(entities = [TaskLog::class, DiscordAccount::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskLogDao(): TaskLogDao
    abstract fun discordAccountDao(): DiscordAccountDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "orion_quest_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.alphaai.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ArtifactEntity::class,
        UsageLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun usageLogDao(): UsageLogDao

    companion object {
        const val NAME = "alphaai.db"
    }
}

package com.seankew.localai.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地长时记忆库（"档案员"）。数据只存在应用私有目录，且 Manifest 已关闭 allowBackup，不会被云备份带走。
 *
 * 注意：schema 变更时必须提供 Migration，不要用 fallbackToDestructiveMigration ——
 * 那会在升级时悄悄清空用户积累的全部记忆。
 */
@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
abstract class ArchivistDB : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DB_NAME = "archivist.db"

        @Volatile
        private var instance: ArchivistDB? = null

        fun get(context: Context): ArchivistDB =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArchivistDB::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}

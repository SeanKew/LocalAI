package com.seankew.localai.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地长时记忆库（"档案员"）。数据只存在应用私有目录，且 Manifest 已关闭 allowBackup，不会被云备份带走。
 *
 * 迁移约定（详见 docs/room-schema.md）：
 * - 改动任何实体 / 索引都必须同时升 version，并提交新生成的 app/schemas/.../<version>.json；
 * - 只加列 / 加表 / 加索引时用 @AutoMigration，其余写手动 Migration，并配迁移测试；
 * - 绝不使用 fallbackToDestructiveMigration —— 它会在升级时悄悄清空用户积累的全部记忆。
 *
 * exportSchema = true：表结构 JSON 由 KSP 参数 room.schemaLocation 导出到 app/schemas（见 app/build.gradle.kts），
 * CI 会检查导出结果是否已提交。
 */
@Database(entities = [MemoryEntity::class], version = 1, exportSchema = true)
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

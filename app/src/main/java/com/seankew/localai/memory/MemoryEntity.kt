package com.seankew.localai.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 持久化形态。kind / source 以字符串存储，避免引入 TypeConverter，也让以后加新类型不需要迁移。
 * fingerprint 带唯一索引：同一条内容在数据库层面也不会重复。
 */
@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val content: String,
    val importance: Int,
    val source: String,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val useCount: Int,
    val fingerprint: String,
)

fun MemoryEntity.toRecord(): MemoryRecord = MemoryRecord(
    id = id,
    kind = MemoryKind.fromCode(kind) ?: MemoryKind.FACT,
    content = content,
    importance = importance,
    source = runCatching { MemorySource.valueOf(source) }.getOrDefault(MemorySource.AUTO_REFLECTION),
    pinned = pinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    fingerprint = fingerprint,
)

fun MemoryRecord.toEntity(): MemoryEntity = MemoryEntity(
    id = id,
    kind = kind.code,
    content = content,
    importance = importance,
    source = source.name,
    pinned = pinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    fingerprint = fingerprint,
)

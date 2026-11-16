package com.seankew.localai.memory

import kotlinx.coroutines.flow.Flow

/**
 * 记忆存储的抽象。生产环境用 Room 实现（[RoomMemoryStore]），单元测试用内存实现。
 * 所有方法都应可以从任意协程调用。
 */
interface MemoryStore {
    /** 插入新记录；指纹冲突时不写入并返回 -1。 */
    suspend fun insert(record: MemoryRecord): Long

    suspend fun update(record: MemoryRecord)

    /** 置顶优先、再按更新时间倒序。 */
    suspend fun recent(limit: Int): List<MemoryRecord>

    suspend fun pinned(): List<MemoryRecord>

    /** 内容包含 [keyword]（不区分大小写的子串匹配）。 */
    suspend fun search(keyword: String, limit: Int): List<MemoryRecord>

    suspend fun byFingerprint(fingerprint: String): MemoryRecord?

    suspend fun markUsed(ids: List<Long>, now: Long)

    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    suspend fun count(): Int

    suspend fun delete(id: Long)

    suspend fun clear()

    /** 删除"价值最低"的非置顶记录，使总数不超过 [keep]；返回删除条数。 */
    suspend fun pruneLowestValue(keep: Int): Int

    fun observeAll(): Flow<List<MemoryRecord>>
}

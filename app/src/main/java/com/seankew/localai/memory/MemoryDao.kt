package com.seankew.localai.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /** 指纹唯一索引冲突时忽略并返回 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MemoryEntity): Long

    @Update
    suspend fun update(entity: MemoryEntity)

    /** 最近记忆：置顶优先，其次按更新时间倒序。 */
    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE pinned = 1 ORDER BY updatedAt DESC")
    suspend fun pinned(): List<MemoryEntity>

    /** 关键字检索：[pattern] 形如 "%词%"，调用方负责转义 % _ \（转义符为反斜杠）。 */
    @Query(
        "SELECT * FROM memories WHERE content LIKE :pattern ESCAPE '\\' " +
            "ORDER BY pinned DESC, importance DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun search(pattern: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): MemoryEntity?

    @Query("UPDATE memories SET lastUsedAt = :now, useCount = useCount + 1 WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<Long>, now: Long)

    @Query("UPDATE memories SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()

    /** 删除 [n] 条"价值最低"的非置顶记录：先比重要度，再比最近一次被使用/更新的时间。 */
    @Query(
        "DELETE FROM memories WHERE id IN (" +
            "SELECT id FROM memories WHERE pinned = 0 " +
            "ORDER BY importance ASC, MAX(lastUsedAt, updatedAt) ASC LIMIT :n)"
    )
    suspend fun deleteLowestValue(n: Int): Int

    @Query("SELECT * FROM memories ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<MemoryEntity>>
}

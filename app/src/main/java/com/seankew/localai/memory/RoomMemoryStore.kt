package com.seankew.localai.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [MemoryStore] 的 Room 实现：只做实体 <-> 领域模型的转换与 LIKE 转义。 */
class RoomMemoryStore(private val dao: MemoryDao) : MemoryStore {

    override suspend fun insert(record: MemoryRecord): Long = dao.insert(record.toEntity())

    override suspend fun update(record: MemoryRecord) = dao.update(record.toEntity())

    override suspend fun recent(limit: Int): List<MemoryRecord> = dao.recent(limit).map { it.toRecord() }

    override suspend fun pinned(): List<MemoryRecord> = dao.pinned().map { it.toRecord() }

    override suspend fun search(keyword: String, limit: Int): List<MemoryRecord> {
        if (keyword.isBlank()) return emptyList()
        return dao.search("%" + escapeLike(keyword) + "%", limit).map { it.toRecord() }
    }

    override suspend fun byFingerprint(fingerprint: String): MemoryRecord? = dao.byFingerprint(fingerprint)?.toRecord()

    override suspend fun markUsed(ids: List<Long>, now: Long) {
        if (ids.isNotEmpty()) dao.markUsed(ids, now)
    }

    override suspend fun setPinned(id: Long, pinned: Boolean, now: Long) = dao.setPinned(id, pinned, now)

    override suspend fun count(): Int = dao.count()

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun clear() = dao.clear()

    override suspend fun pruneLowestValue(keep: Int): Int {
        val excess = dao.count() - keep
        return if (excess > 0) dao.deleteLowestValue(excess) else 0
    }

    override fun observeAll(): Flow<List<MemoryRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

    internal companion object {
        /** 与 DAO 里的 ESCAPE '\' 配套：转义反斜杠、百分号、下划线。 */
        fun escapeLike(raw: String): String =
            raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

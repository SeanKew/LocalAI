package com.seankew.localai.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** 测试用的内存版存储，语义尽量贴近 Room 实现（LIKE 不区分大小写、指纹唯一）。 */
open class InMemoryMemoryStore : MemoryStore {
    protected val rows = ArrayList<MemoryRecord>()
    private var nextId = 1L
    private val changes = MutableStateFlow(0)

    /** 模拟"另一个写入者抢先插入了同指纹记录"：insert 写入后仍返回 -1。 */
    var simulateInsertRace = false
    var failAll = false
    var markUsedCalls = 0

    /** 收集 observeAll 时立刻抛出（模拟：数据库打不开 / 缺少迁移）。 */
    var observeFailure: Throwable? = null
    /** 首次发射之后再抛出（模拟：运行中途出错）。 */
    var observeFailureAfterFirst: Throwable? = null
    /** 调用 observeAll() 本身就同步抛出（模拟：创建流时失败）。 */
    var observeThrowsOnCall: Throwable? = null

    private fun check() { if (failAll) throw IllegalStateException("db down") }
    private fun touch() { changes.value = changes.value + 1 }
    val all: List<MemoryRecord> get() = rows.toList()

    override suspend fun insert(record: MemoryRecord): Long {
        check()
        if (simulateInsertRace) {
            simulateInsertRace = false
            rows += record.copy(id = nextId++)
            touch()
            return -1
        }
        if (rows.any { it.fingerprint == record.fingerprint }) return -1
        val id = nextId++
        rows += record.copy(id = id)
        touch()
        return id
    }

    override suspend fun update(record: MemoryRecord) {
        check()
        val i = rows.indexOfFirst { it.id == record.id }
        if (i >= 0) { rows[i] = record; touch() }
    }

    private fun ordered() = rows.sortedWith(compareByDescending<MemoryRecord> { it.pinned }.thenByDescending { it.updatedAt })

    override suspend fun recent(limit: Int): List<MemoryRecord> { check(); return ordered().take(limit) }
    override suspend fun pinned(): List<MemoryRecord> { check(); return ordered().filter { it.pinned } }
    override suspend fun search(keyword: String, limit: Int): List<MemoryRecord> {
        check()
        return ordered().filter { it.content.contains(keyword, ignoreCase = true) }.take(limit)
    }
    override suspend fun byFingerprint(fingerprint: String): MemoryRecord? { check(); return rows.firstOrNull { it.fingerprint == fingerprint } }

    override suspend fun markUsed(ids: List<Long>, now: Long) {
        check()
        markUsedCalls++
        for (i in rows.indices) if (rows[i].id in ids) rows[i] = rows[i].copy(lastUsedAt = now, useCount = rows[i].useCount + 1)
        touch()
    }

    override suspend fun setPinned(id: Long, pinned: Boolean, now: Long) {
        check()
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) { rows[i] = rows[i].copy(pinned = pinned, updatedAt = now); touch() }
    }

    override suspend fun count(): Int { check(); return rows.size }
    override suspend fun delete(id: Long) { check(); rows.removeAll { it.id == id }; touch() }
    override suspend fun clear() { check(); rows.clear(); touch() }

    override suspend fun pruneLowestValue(keep: Int): Int {
        check()
        val excess = rows.size - keep
        if (excess <= 0) return 0
        val victims = rows.filter { !it.pinned }
            .sortedWith(compareBy<MemoryRecord> { it.importance }.thenBy { maxOf(it.lastUsedAt, it.updatedAt) })
            .take(excess)
        rows.removeAll(victims.toSet())
        touch()
        return victims.size
    }

    override fun observeAll(): Flow<List<MemoryRecord>> {
        observeThrowsOnCall?.let { throw it }
        return flow {
            observeFailure?.let { throw it }
            var first = true
            changes.map { ordered() }.collect {
                emit(it)
                if (first) {
                    first = false
                    observeFailureAfterFirst?.let { e -> throw e }
                }
            }
        }
    }
}

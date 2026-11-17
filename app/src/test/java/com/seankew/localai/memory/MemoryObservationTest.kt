package com.seankew.localai.memory

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** observeAll 的兜底：数据库损坏 / 缺少迁移 / 中途出错都不能变成应用崩溃。 */
class MemoryObservationTest {
    private val logs = ArrayList<String>()

    private fun manager(store: MemoryStore) = CognitiveMemoryManager(store, log = { logs += it })

    private fun seed(store: InMemoryMemoryStore) = runBlocking {
        store.insert(
            MemoryRecord(
                kind = MemoryKind.FACT, content = "用户住在新加坡", source = MemorySource.USER_EXPLICIT,
                createdAt = 1, updatedAt = 1,
            ),
        )
    }

    @Test fun failingStoreYieldsEmptyListInsteadOfThrowing() = runBlocking {
        val store = InMemoryMemoryStore().apply {
            observeFailure = IllegalStateException("Room cannot verify the data integrity")
        }
        val m = manager(store)
        val emissions = m.observeAll().toList()          // 流必须正常结束，而不是抛异常
        assertEquals(listOf(emptyList<MemoryRecord>()), emissions)
        assertEquals("Room cannot verify the data integrity", m.storeError.value)
        assertTrue(logs.any { it.contains("记忆库不可用") })
    }

    @Test fun errorWithoutMessageFallsBackToClassName() = runBlocking {
        val store = InMemoryMemoryStore().apply { observeFailure = IllegalStateException() }
        val m = manager(store)
        m.observeAll().toList()
        assertEquals("IllegalStateException", m.storeError.value)
    }

    @Test fun synchronousFailureWhenCreatingTheFlowIsAlsoContained() = runBlocking {
        val store = InMemoryMemoryStore().apply { observeThrowsOnCall = IllegalStateException("boom") }
        val m = manager(store)
        assertEquals(listOf(emptyList<MemoryRecord>()), m.observeAll().toList())
        assertEquals("boom", m.storeError.value)
    }

    @Test fun midStreamFailureKeepsEarlierDataThenReportsError() = runBlocking {
        val store = InMemoryMemoryStore().apply { observeFailureAfterFirst = IOException("disk I/O error") }
        seed(store)
        val m = manager(store)
        val emissions = m.observeAll().toList()
        assertEquals(2, emissions.size)
        assertEquals(1, emissions[0].size)               // 出错前的数据照常送达
        assertTrue(emissions[1].isEmpty())               // 出错后给出空列表
        assertEquals("disk I/O error", m.storeError.value)
    }

    @Test fun healthyStreamLeavesNoError() = runBlocking {
        val store = InMemoryMemoryStore()
        seed(store)
        val m = manager(store)
        assertEquals(1, m.observeAll().first().size)
        assertNull(m.storeError.value)
        assertTrue(logs.isEmpty())
    }

    @Test fun errorClearsOnceTheStoreRecovers() = runBlocking {
        val store = InMemoryMemoryStore().apply { observeFailure = IllegalStateException("migration missing") }
        val m = manager(store)
        m.observeAll().toList()
        assertNotNull(m.storeError.value)

        store.observeFailure = null                      // 模拟：重新订阅时数据库已恢复
        m.observeAll().first()
        assertNull(m.storeError.value)
    }

    @Test fun collectorCancellationIsNotAnError() = runBlocking {
        val store = InMemoryMemoryStore()
        val m = manager(store)
        val job = launch { m.observeAll().collect { } }
        yield()                                          // 让收集协程先跑起来
        job.cancelAndJoin()                              // 界面离开 = 取消，不能被记成"记忆库不可用"
        assertNull(m.storeError.value)
        assertTrue(logs.isEmpty())
    }
}

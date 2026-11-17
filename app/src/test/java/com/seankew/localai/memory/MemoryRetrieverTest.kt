package com.seankew.localai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun rec(
        id: Long, kind: MemoryKind, content: String, importance: Int = 3,
        pinned: Boolean = false, ageDays: Long = 1,
    ) = MemoryRecord(
        id = id, kind = kind, content = content, importance = importance, source = MemorySource.AUTO_REFLECTION,
        pinned = pinned, createdAt = now - ageDays * day, updatedAt = now - ageDays * day,
    )

    private val project = rec(1, MemoryKind.PROJECT, "用户正在开发名为LocalAI的安卓应用")
    private val home = rec(2, MemoryKind.FACT, "用户住在新加坡")
    private val coffee = rec(3, MemoryKind.PREFERENCE, "用户喜欢喝美式咖啡")
    private val db = rec(4, MemoryKind.PROJECT, "用户用Room做本地存储")

    private fun ids(r: List<ScoredMemory>) = r.map { it.record.id }

    @Test fun selectsTopicallyRelevantMemory() {
        val r = MemoryRetriever().select("LocalAI 最近的开发进度怎么样", listOf(project, home, coffee), now)
        assertEquals(listOf(1L), ids(r))
    }

    @Test fun returnsNothingWhenNothingIsRelevant() {
        val r = MemoryRetriever().select("今天天气怎么样", listOf(project, home, coffee, db), now)
        assertTrue(r.isEmpty())
    }

    @Test fun singleStrongTermIsEnough() {
        val r = MemoryRetriever().select("Room 数据库怎么迁移", listOf(project, home, db), now)
        assertTrue(ids(r).contains(4L))
    }

    @Test fun singleWeakCjkOverlapIsNotEnough() {
        // 只共享一个 bigram "开发" —— 不应注入
        val r = MemoryRetriever().select("开发板怎么选", listOf(project), now)
        assertTrue(r.isEmpty())
    }

    @Test fun highImportancePreferenceIsAlwaysStanding() {
        val pref = rec(10, MemoryKind.PREFERENCE, "用户希望回答简洁", importance = 5)
        val r = MemoryRetriever().select("今天天气怎么样", listOf(pref, home), now)
        assertEquals(listOf(10L), ids(r))
        assertTrue(r.single().standing)
    }

    @Test fun lowImportancePreferenceIsNotStanding() {
        val pref = rec(10, MemoryKind.PREFERENCE, "用户希望回答简洁", importance = 3)
        assertTrue(MemoryRetriever().select("今天天气怎么样", listOf(pref), now).isEmpty())
    }

    @Test fun pinnedMemoryIsAlwaysIncluded() {
        val pinned = rec(11, MemoryKind.FACT, "用户是一名安卓开发者", pinned = true, importance = 1)
        val r = MemoryRetriever().select("讲个笑话", listOf(pinned), now)
        assertEquals(listOf(11L), ids(r))
    }

    @Test fun standingSlotsAreCapped() {
        val many = (1..6).map { rec(100L + it, MemoryKind.PREFERENCE, "用户偏好第${it}项设置内容", importance = 5) }
        val r = MemoryRetriever(RetrievalConfig(maxStanding = 3)).select("讲个笑话", many, now)
        assertEquals(3, r.size)
    }

    @Test fun respectsMaxItems() {
        val similar = (1..8).map { rec(200L + it, MemoryKind.PROJECT, "用户的LocalAI项目第${it}阶段开发进度") }
        val r = MemoryRetriever(RetrievalConfig(maxItems = 3, charBudget = 10_000)).select("LocalAI 开发进度", similar, now)
        assertEquals(3, r.size)
    }

    @Test fun respectsCharBudgetAndSkipsToShorterEntries() {
        val long = rec(300, MemoryKind.PROJECT, "用户的LocalAI项目" + "非常详细的背景说明".repeat(8), importance = 5)
        val short = rec(301, MemoryKind.PROJECT, "用户的LocalAI开发中", importance = 2)
        val budget = MemoryPrompt.line(short).length + 5
        val r = MemoryRetriever(RetrievalConfig(charBudget = budget)).select("LocalAI 项目开发", listOf(long, short), now)
        assertEquals(listOf(301L), ids(r))
    }

    @Test fun morePreciseAndImportantMatchRanksFirst() {
        val weak = rec(400, MemoryKind.PROJECT, "用户计划学习 Kotlin 协程", importance = 2)
        val strong = rec(401, MemoryKind.PROJECT, "用户的 LocalAI 使用 Kotlin 协程管理生成任务", importance = 5)
        val r = MemoryRetriever().select("Kotlin 协程怎么取消", listOf(weak, strong), now)
        assertEquals(401L, r.first().record.id)
    }

    @Test fun recentMemoryBeatsStaleOneWhenOtherwiseEqual() {
        val old = rec(500, MemoryKind.PROJECT, "用户的LocalAI项目使用Gradle构建", ageDays = 400)
        val fresh = rec(501, MemoryKind.PROJECT, "用户的LocalAI项目使用Gradle构建系统", ageDays = 1)
        val r = MemoryRetriever().select("LocalAI Gradle 构建", listOf(old, fresh), now)
        assertEquals(501L, r.first().record.id)
    }

    @Test fun duplicateCandidatesAreCollapsed() {
        val r = MemoryRetriever().select("LocalAI 开发进度", listOf(project, project.copy()), now)
        assertEquals(1, r.size)
    }

    @Test fun emptyInputsAreSafe() {
        assertTrue(MemoryRetriever().select("anything", emptyList(), now).isEmpty())
        assertTrue(MemoryRetriever().select("", listOf(project), now).isEmpty())
        assertFalse(MemoryRetriever().select("   ", listOf(home), now).any())
    }
}

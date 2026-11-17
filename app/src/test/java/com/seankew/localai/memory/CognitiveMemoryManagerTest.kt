package com.seankew.localai.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CognitiveMemoryManagerTest {
    private var clockMs = 1_800_000_000_000L
    private val logs = ArrayList<String>()

    private fun manager(store: MemoryStore, config: CognitiveMemoryManager.Config = CognitiveMemoryManager.Config()) =
        CognitiveMemoryManager(store, config = config, clock = { clockMs }, log = { logs += it })

    private fun llm(output: String?) = ReflectionLlm { _, _, _ -> output }

    private fun seed(store: InMemoryMemoryStore, kind: MemoryKind, content: String, importance: Int = 3, pinned: Boolean = false) =
        runBlocking {
            store.insert(
                MemoryRecord(
                    kind = kind, content = content, importance = importance, source = MemorySource.USER_EXPLICIT,
                    pinned = pinned, createdAt = clockMs, updatedAt = clockMs,
                ),
            )
        }

    // ------------------------------------------------------------ 检索 + 注入

    @Test fun composeWithEmptyStoreReturnsBaseUntouched() = runBlocking {
        val r = manager(InMemoryMemoryStore()).composeSystemPrompt("BASE", "你好")
        assertEquals("BASE", r.systemPrompt)
        assertTrue(r.used.isEmpty())
    }

    @Test fun composeInjectsRelevantMemoryAndMarksItUsed() = runBlocking {
        val store = InMemoryMemoryStore()
        seed(store, MemoryKind.PROJECT, "用户正在开发名为LocalAI的安卓应用")
        seed(store, MemoryKind.FACT, "用户住在新加坡")
        val r = manager(store).composeSystemPrompt("BASE", "LocalAI 最近的开发进度怎么样")
        assertEquals(1, r.used.size)
        assertTrue(r.systemPrompt!!.contains("用户正在开发名为LocalAI的安卓应用"))
        assertFalse(r.systemPrompt!!.contains("新加坡"))
        assertEquals(1, store.all.first { it.content.contains("LocalAI") }.useCount)
    }

    @Test fun composeKeepsBaseWhenNothingMatches() = runBlocking {
        val store = InMemoryMemoryStore()
        seed(store, MemoryKind.FACT, "用户住在新加坡")
        val r = manager(store).composeSystemPrompt("BASE", "讲个笑话")
        assertEquals("BASE", r.systemPrompt)
        assertEquals(0, store.markUsedCalls)
    }

    @Test fun composeAlwaysIncludesPinnedAndImportantPreferences() = runBlocking {
        val store = InMemoryMemoryStore()
        seed(store, MemoryKind.PREFERENCE, "用户希望回答简洁", importance = 5)
        seed(store, MemoryKind.FACT, "用户是一名安卓开发者", pinned = true)
        val r = manager(store).composeSystemPrompt("BASE", "讲个笑话")
        assertEquals(2, r.used.size)
    }

    @Test fun composeSurvivesStorageFailure() = runBlocking {
        val store = InMemoryMemoryStore().apply { failAll = true }
        val r = manager(store).composeSystemPrompt("BASE", "LocalAI 开发进度")
        assertEquals("BASE", r.systemPrompt)
        assertTrue(logs.any { it.contains("记忆检索失败") })
    }

    // ------------------------------------------------------------ 反思

    @Test fun reflectStoresValidatedMemory() = runBlocking {
        val store = InMemoryMemoryStore()
        val out = manager(store).reflect(llm("项目|4|用户正在开发LocalAI安卓应用"), "我正在做LocalAI", "好的")
        assertEquals(1, out.added.size)
        val saved = store.all.single()
        assertEquals(MemoryKind.PROJECT, saved.kind)
        assertEquals(MemorySource.AUTO_REFLECTION, saved.source)
        assertEquals(4, saved.importance)
        assertTrue(saved.id > 0)
    }

    @Test fun reflectPassesRestrictedPromptAndTokenBudgetToTheModel() = runBlocking {
        var seenSystem = ""; var seenUser = ""; var seenTokens = 0
        val spy = ReflectionLlm { s, u, n -> seenSystem = s; seenUser = u; seenTokens = n; "无" }
        manager(InMemoryMemoryStore()).reflect(spy, "我喜欢简洁的回答", "好的，我会简洁。")
        assertEquals(ReflectionPrompt.SYSTEM, seenSystem)
        assertTrue(seenUser.contains("我喜欢简洁的回答"))
        assertEquals(160, seenTokens)
    }

    @Test fun reflectingTheSameFactTwiceReinforcesInsteadOfDuplicating() = runBlocking {
        val store = InMemoryMemoryStore()
        val m = manager(store)
        m.reflect(llm("偏好|3|用户喜欢简洁的回答"), "u", "a")
        clockMs += 1000
        val second = m.reflect(llm("偏好|5|用户 喜欢简洁的回答。"), "u", "a")
        assertEquals(UpsertKind.REINFORCED, second.results.single().kind)
        assertEquals(1, store.all.size)
        assertEquals(5, store.all.single().importance)
        assertEquals(clockMs, store.all.single().updatedAt)
    }

    @Test fun nearDuplicateIsMergedKeepingTheMoreInformativeSentence() = runBlocking {
        val store = InMemoryMemoryStore()
        val m = manager(store)
        m.reflect(llm("偏好|3|用户喜欢简洁的回答"), "u", "a")
        val second = m.reflect(llm("偏好|4|用户喜欢简洁的回答风格"), "u", "a")
        assertEquals(UpsertKind.MERGED, second.results.single().kind)
        assertEquals(1, store.all.size)
        assertEquals("用户喜欢简洁的回答风格", store.all.single().content)
        assertEquals(4, store.all.single().importance)
    }

    @Test fun similarContentOfDifferentKindIsNotMerged() = runBlocking {
        val store = InMemoryMemoryStore()
        val m = manager(store)
        m.reflect(llm("偏好|3|用户喜欢简洁的回答"), "u", "a")
        m.reflect(llm("事实|3|用户喜欢简洁的回答风格"), "u", "a")
        assertEquals(2, store.all.size)
    }

    @Test fun reflectRejectsSensitiveOutput() = runBlocking {
        val store = InMemoryMemoryStore()
        val out = manager(store).reflect(llm("事实|5|用户的密码是abc123"), "u", "a")
        assertTrue(store.all.isEmpty())
        assertEquals(1, out.rejected.size)
    }

    @Test fun reflectIgnoresGarbage() = runBlocking {
        val store = InMemoryMemoryStore()
        val out = manager(store).reflect(llm("好的，下面是我的总结：用户很友善。"), "u", "a")
        assertTrue(store.all.isEmpty())
        assertTrue(out.rejected.isNotEmpty())
    }

    @Test fun reflectHandlesNullAndFailingModel() = runBlocking {
        val store = InMemoryMemoryStore()
        assertTrue(manager(store).reflect(llm(null), "u", "a").results.isEmpty())
        val boom = ReflectionLlm { _, _, _ -> throw IllegalStateException("native crash") }
        assertTrue(manager(store).reflect(boom, "u", "a").results.isEmpty())
        assertTrue(logs.any { it.contains("反思失败") })
        assertTrue(store.all.isEmpty())
    }

    @Test fun reflectPropagatesCancellation() {
        val cancelling = ReflectionLlm { _, _, _ -> throw CancellationException("preempted by user message") }
        try {
            runBlocking { manager(InMemoryMemoryStore()).reflect(cancelling, "u", "a") }
            fail("CancellationException must propagate so the caller's job really stops")
        } catch (expected: CancellationException) {
            // ok
        }
    }

    @Test fun reflectSurvivesStorageFailure() = runBlocking {
        val store = InMemoryMemoryStore().apply { failAll = true }
        val out = manager(store).reflect(llm("事实|3|用户住在新加坡"), "u", "a")
        assertTrue(out.results.isEmpty())
        assertTrue(logs.any { it.contains("写入记忆失败") })
    }

    @Test fun capacityIsEnforcedByDroppingLowestValueUnpinnedEntries() = runBlocking {
        val store = InMemoryMemoryStore()
        val pinned = seed(store, MemoryKind.FACT, "用户的置顶背景信息一", importance = 1, pinned = true)
        seed(store, MemoryKind.FACT, "用户的低重要度信息二", importance = 1)
        val m = manager(store, CognitiveMemoryManager.Config(maxMemories = 2))
        m.reflect(llm("事实|5|用户的高重要度信息三"), "u", "a")
        assertEquals(2, store.all.size)
        assertTrue(store.all.any { it.id == pinned })                       // 置顶不会被清理
        assertTrue(store.all.any { it.content == "用户的高重要度信息三" })      // 新的高价值条目保留
    }

    @Test fun concurrentInsertConflictFallsBackToReinforce() = runBlocking {
        val store = InMemoryMemoryStore().apply { simulateInsertRace = true }
        val r = manager(store).upsert(MemoryCandidate(MemoryKind.FACT, 4, "用户住在新加坡"), MemorySource.AUTO_REFLECTION)
        assertEquals(UpsertKind.REINFORCED, r.kind)
        assertEquals(1, store.all.size)
    }

    // ------------------------------------------------------------ 显式记忆

    @Test fun explicitRememberSavesWithUserSource() = runBlocking {
        val store = InMemoryMemoryStore()
        val r = manager(store).rememberExplicit("记住：我喜欢喝美式咖啡") as ExplicitResult.Saved
        assertEquals(UpsertKind.ADDED, r.result.kind)
        val saved = store.all.single()
        assertEquals("用户喜欢喝美式咖啡", saved.content)
        assertEquals(MemorySource.USER_EXPLICIT, saved.source)
        assertEquals(MemoryKind.PREFERENCE, saved.kind)
    }

    @Test fun explicitRememberRefusesSensitiveContent() = runBlocking {
        val store = InMemoryMemoryStore()
        val r = manager(store).rememberExplicit("记住我的密码是 hunter2")
        assertTrue(r is ExplicitResult.Rejected)
        assertTrue(store.all.isEmpty())
    }

    @Test fun explicitRememberIgnoresOrdinaryMessages() = runBlocking {
        assertNull(manager(InMemoryMemoryStore()).rememberExplicit("今天天气怎么样"))
    }

    // ------------------------------------------------------------ 管理

    @Test fun deleteClearAndPinWork() = runBlocking {
        val store = InMemoryMemoryStore()
        val a = seed(store, MemoryKind.FACT, "用户住在新加坡")
        val b = seed(store, MemoryKind.FACT, "用户养了一只猫")
        val m = manager(store)
        m.setPinned(a, true)
        assertTrue(store.all.first { it.id == a }.pinned)
        m.delete(b)
        assertEquals(1, m.count())
        m.clear()
        assertEquals(0, m.count())
    }
}

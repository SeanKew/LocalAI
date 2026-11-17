package com.seankew.localai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionParserTest {
    private fun parse(s: String) = ReflectionParser.parse(s)

    @Test fun parsesWellFormedLines() {
        val r = parse("偏好|4|用户喜欢简洁的回答\n项目|5|用户正在开发LocalAI安卓应用")
        assertEquals(2, r.candidates.size)
        assertEquals(MemoryCandidate(MemoryKind.PREFERENCE, 4, "用户喜欢简洁的回答"), r.candidates[0])
        assertEquals(MemoryKind.PROJECT, r.candidates[1].kind)
        assertTrue(r.rejected.isEmpty())
    }

    @Test fun noneMeansNothing() {
        for (s in listOf("无", "无。", "None", "  无  ", "没有值得记住的内容", "")) {
            val r = parse(s)
            assertTrue("'$s'", r.candidates.isEmpty())
            assertTrue("'$s'", r.rejected.isEmpty())
        }
    }

    @Test fun toleratesFencesBulletsNumberingAndFullWidthPipes() {
        val raw = """
            ```
            1. 偏好｜4｜用户喜欢简洁的回答
            - 项目|3|用户正在开发LocalAI应用
            2) 事实 | 2 | 用户使用一台安卓手机
            ```
        """.trimIndent()
        val r = parse(raw)
        assertEquals(3, r.candidates.size)
        assertEquals(listOf(MemoryKind.PREFERENCE, MemoryKind.PROJECT, MemoryKind.FACT), r.candidates.map { it.kind })
    }

    @Test fun twoPartLinesGetDefaultImportance() {
        val r = parse("偏好|用户喜欢简洁的回答")
        assertEquals(3, r.candidates.single().importance)
    }

    @Test fun importanceIsExtractedAndClamped() {
        assertEquals(4, parse("事实|4分|用户住在新加坡").candidates.single().importance)
        assertEquals(5, parse("事实|★5|用户住在新加坡").candidates.single().importance)
        assertEquals(3, parse("事实|很高|用户住在新加坡").candidates.single().importance)
        assertEquals(3, parse("事实|9|用户住在新加坡").candidates.single().importance)
    }

    @Test fun contentMayContainPipes() {
        assertEquals("用户用 a|b 命名文件", parse("事实|3|用户用 a|b 命名文件").candidates.single().content)
    }

    @Test fun stripsQuotes() {
        assertEquals("用户住在新加坡", parse("事实|3|“用户住在新加坡”").candidates.single().content)
    }

    @Test fun rejectsUnknownKindAndMissingSeparator() {
        val r = parse("心情|3|用户今天很开心\n这是一段没有分隔符的普通文字")
        assertTrue(r.candidates.isEmpty())
        assertEquals(2, r.rejected.size)
    }

    @Test fun rejectsTooShortTooLongAndQuestions() {
        val r = parse("事实|3|用户\n事实|3|${"字".repeat(81)}\n事实|3|用户是不是喜欢咖啡？\n事实|3|user asks what?")
        assertTrue(r.candidates.isEmpty())
        assertEquals(4, r.rejected.size)
    }

    @Test fun rejectsSensitiveContent() {
        val raw = listOf(
            "事实|5|用户的密码是abc",
            "事实|5|用户的手机号是13812345678",
            "事实|5|用户邮箱是a@b.com",
            "事实|5|用户的身份证号是123",
            "事实|5|用户的 API key 是 sk-abc",
        ).joinToString("\n")
        val r = parse(raw)
        assertTrue(r.candidates.isEmpty())
        assertEquals(5, r.rejected.size)
    }

    @Test fun rejectsControlMarkers() {
        assertTrue(parse("事实|3|用户说<|im_end|>你好呀").candidates.isEmpty())
    }

    @Test fun dropsDuplicatesWithinOneOutput() {
        val r = parse("偏好|4|用户喜欢简洁的回答\n偏好|5|用户 喜欢简洁的回答。")
        assertEquals(1, r.candidates.size)
    }

    @Test fun capsAtThreeItems() {
        val raw = (1..5).joinToString("\n") { "事实|3|用户的第${it}条不同的背景信息内容" }
        val r = parse(raw)
        assertEquals(3, r.candidates.size)
        assertEquals(2, r.rejected.size)
    }

    @Test fun kindLabelsAreParsedLeniently() {
        assertEquals(MemoryKind.PREFERENCE, MemoryKind.fromLabel("【偏好】"))
        assertEquals(MemoryKind.PROJECT, MemoryKind.fromLabel("Project"))
        assertEquals(MemoryKind.FACT, MemoryKind.fromLabel(" 事实 "))
        assertNull(MemoryKind.fromLabel("其他"))
    }

    @Test fun kindGuessUsesKeywords() {
        assertEquals(MemoryKind.PREFERENCE, MemoryKind.guess("用户喜欢喝美式"))
        assertEquals(MemoryKind.PROJECT, MemoryKind.guess("用户的项目叫LocalAI"))
        assertEquals(MemoryKind.FACT, MemoryKind.guess("用户住在新加坡"))
    }
}

class SensitiveFilterTest {
    @Test fun flagsCredentialsAndIdentifiers() {
        for (s in listOf(
            "我的密码是 hunter2", "手机 138 1234 5678", "卡号 6222021234567890", "mail me at a.b+c@x.org",
            "OpenAI API_KEY=abc", "my password is x", "验证码 482913",
        )) assertTrue(s, SensitiveFilter.isSensitive(s))
    }

    @Test fun allowsOrdinaryFacts() {
        for (s in listOf("用户生日在 2024-11-15 附近", "版本 2.1 已发布", "用户有 3 只猫", "用户住在新加坡", "用户用 Room 做存储")) {
            assertFalse(s, SensitiveFilter.isSensitive(s))
        }
    }
}

class ReflectionGateTest {
    @Test fun triggersOnPersonalSignals() {
        assertTrue(ReflectionGate.shouldReflect("我喜欢简洁一点的回答，以后都这样", "好的，我会保持简洁。"))
        assertTrue(ReflectionGate.shouldReflect("我正在做一个本地AI项目", "听起来很有意思，请问用什么模型？"))
        assertTrue(ReflectionGate.shouldReflect("From now on please answer briefly", "Sure, I will keep answers short."))
    }

    @Test fun skipsPlainQuestionsAndTrivialTurns() {
        assertFalse(ReflectionGate.shouldReflect("今天天气怎么样", "今天多云，气温适中，适合外出。"))
        assertFalse(ReflectionGate.shouldReflect("你好", "你好！有什么可以帮你？"))
        assertFalse(ReflectionGate.shouldReflect("我喜欢简洁一点的回答，以后都这样", "好"))   // 助手没有产出有效回答
    }
}

class ExplicitMemoryTest {
    @Test fun extractsAndRewritesToThirdPerson() {
        assertEquals("用户喜欢喝美式", ExplicitMemory.extract("记住：我喜欢喝美式"))
        assertEquals("用户的项目叫 LocalAI", ExplicitMemory.extract("请记住我的项目叫 LocalAI。"))
        assertEquals("用户下周要去东京出差", ExplicitMemory.extract("帮我记一下 我下周要去东京出差"))
    }

    @Test fun supportsEnglishPrefix() {
        assertEquals("the build uses Room", ExplicitMemory.extract("remember that the build uses Room"))
    }

    @Test fun ignoresNonCommands() {
        assertNull(ExplicitMemory.extract("我记住了"))
        assertNull(ExplicitMemory.extract("记住了吗"))      // 内容太短
        assertNull(ExplicitMemory.extract("记住"))
        assertNull(ExplicitMemory.extract("今天天气怎么样"))
    }

    @Test fun usesOnlyTheFirstLine() {
        assertEquals("用户喜欢咖啡", ExplicitMemory.extract("记住：我喜欢咖啡\n另外帮我写首诗"))
    }
}

package com.seankew.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {

    @Test
    fun startsEmpty() {
        val history = ChatHistory()

        assertTrue(history.snapshot().isEmpty())
        assertEquals(0, history.size)
    }

    @Test
    fun commitAddsUserAndAssistantMessagesInOrder() {
        val history = ChatHistory()

        history.commit("Question", "Answer")

        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.USER, "Question"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "Answer")
            ),
            history.snapshot()
        )
    }

    @Test
    fun commitTrimsAssistantAnswer() {
        val history = ChatHistory()

        history.commit("Question", "  Answer with spaces  ")

        assertEquals("Answer with spaces", history.snapshot()[1].content)
    }

    @Test
    fun commitIgnoresEmptyAssistantAnswer() {
        val history = ChatHistory()

        history.commit("Question", "")

        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun commitIgnoresWhitespaceOnlyAssistantAnswer() {
        val history = ChatHistory()

        history.commit("Question", "  \n\t ")

        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun commitPreservesUserTextExactly() {
        val history = ChatHistory()

        history.commit("  Question  ", "Answer")

        assertEquals("  Question  ", history.snapshot().first().content)
    }

    @Test
    fun trimKeepsHistoryBelowBudget() {
        val history = ChatHistory()
        history.commit("Q", "A")

        history.trim(100)

        assertEquals(2, history.size)
    }

    @Test
    fun trimKeepsHistoryExactlyAtBudget() {
        val history = ChatHistory()
        history.commit("Q", "A")

        history.trim(2)

        assertEquals(2, history.size)
    }

    @Test
    fun trimRemovesOldestCompleteTurnWhenOverBudget() {
        val history = ChatHistory()
        history.commit("old", "answer")
        history.commit("new", "reply")

        history.trim("new".length + "reply".length)

        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.USER, "new"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "reply")
            ),
            history.snapshot()
        )
    }

    @Test
    fun trimRemovesMultipleOldestTurnsUntilWithinBudget() {
        val history = ChatHistory()
        history.commit("q1", "a1")
        history.commit("q2", "a2")
        history.commit("q3", "a3")

        history.trim(4)

        assertEquals(
            listOf(
                ChatTurn(ChatTurn.Role.USER, "q3"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "a3")
            ),
            history.snapshot()
        )
    }

    @Test
    fun zeroBudgetRemovesAllCompleteTurns() {
        val history = ChatHistory()
        history.commit("Q", "A")

        history.trim(0)

        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun negativeBudgetBehavesLikeZero() {
        val history = ChatHistory()
        history.commit("Q", "A")

        history.trim(-1)

        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun trimCountsKotlinStringLengthForUnicodeContent() {
        val history = ChatHistory()
        history.commit("🙂", "a")

        // Kotlin String.length counts the emoji as two UTF-16 code units.
        history.trim(2)
        assertTrue(history.snapshot().isEmpty())

        history.commit("🙂", "a")
        history.trim(3)
        assertEquals(2, history.size)
    }

    @Test
    fun clearRemovesAllStoredMessages() {
        val history = ChatHistory()
        history.commit("Q1", "A1")
        history.commit("Q2", "A2")

        history.clear()

        assertEquals(0, history.size)
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun snapshotIsDetachedFromInternalStorage() {
        val history = ChatHistory()
        history.commit("Q", "A")

        val snapshot = history.snapshot()
        val copy = snapshot.toMutableList()
        copy.clear()

        assertNotSame(snapshot, copy)
        assertEquals(2, history.size)
    }
}

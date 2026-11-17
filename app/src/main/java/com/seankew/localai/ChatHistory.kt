package com.seankew.localai

/**
 * In-memory conversation history shared by the prompt builder and the LLM runtime.
 *
 * This class intentionally has no Android dependencies so its behavior can be
 * exercised by local JVM unit tests.
 *
 * All access from MnnLlm is serialized on its single-thread dispatcher.
 */
class ChatHistory {
    private val turns = ArrayList<ChatTurn>()

    /** Number of stored messages (a user/assistant pair contains two messages). */
    val size: Int
        get() = turns.size

    /** Returns a snapshot so callers cannot mutate the internal history. */
    fun snapshot(): List<ChatTurn> = turns.toList()

    /**
     * Commits one complete user/assistant turn.
     *
     * Matching the previous MnnLlm behavior, the assistant answer is trimmed
     * and an empty answer is ignored. The user text is preserved as supplied.
     */
    fun commit(user: String, assistant: String) {
        val answer = assistant.trim()
        if (answer.isEmpty()) return

        turns += ChatTurn(ChatTurn.Role.USER, user)
        turns += ChatTurn(ChatTurn.Role.ASSISTANT, answer)
    }

    /**
     * Removes the oldest complete turns until the stored content fits [maxChars].
     *
     * The budget uses Kotlin String.length (UTF-16 code units), matching the
     * previous implementation. Negative budgets are treated as zero. Messages
     * are removed in pairs, so history never retains an orphaned role message.
     */
    fun trim(maxChars: Int) {
        var totalChars = turns.sumOf { it.content.length }
        val budget = maxChars.coerceAtLeast(0)
        var removeCount = 0

        while (turns.size - removeCount >= 2 && totalChars > budget) {
            totalChars -= turns[removeCount].content.length
            totalChars -= turns[removeCount + 1].content.length
            removeCount += 2
        }

        if (removeCount > 0) {
            turns.subList(0, removeCount).clear()
        }
    }

    fun clear() {
        turns.clear()
    }
}

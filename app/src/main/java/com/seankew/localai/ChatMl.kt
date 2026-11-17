package com.seankew.localai

data class ChatTurn(val role: Role, val content: String) {
    enum class Role(val wire: String) {
        USER("user"),
        ASSISTANT("assistant")
    }
}

/**
 * Qwen2 / Qwen2.5 ChatML builder.
 *
 * Every message is closed explicitly; the final assistant prefix is left open
 * so the model continues by generating the assistant response.
 */
object ChatMl {
    private const val IM_START = "<|im_start|>"
    private const val IM_END = "<|im_end|>"

    fun build(system: String?, history: List<ChatTurn>, user: String): String {
        val sb = StringBuilder()
        if (!system.isNullOrBlank()) {
            sb.append(IM_START).append("system\n")
                .append(escape(system)).append(IM_END).append('\n')
        }
        for (turn in history) {
            sb.append(IM_START).append(turn.role.wire).append('\n')
                .append(escape(turn.content)).append(IM_END).append('\n')
        }
        sb.append(IM_START).append("user\n")
            .append(escape(user)).append(IM_END).append('\n')
        sb.append(IM_START).append("assistant\n")
        return sb.toString()
    }

    /**
     * Prevent message content from injecting control markers into the ChatML
     * structure. Structural markers above are authored only by this builder.
     */
    private fun escape(text: String): String =
        text.replace("<|", "<\u200B|")
}

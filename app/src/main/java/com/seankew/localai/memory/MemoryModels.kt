package com.seankew.localai.memory

/** 记忆类型：核心事实、用户偏好、项目进展。 */
enum class MemoryKind(val label: String, val code: String) {
    FACT("事实", "fact"),
    PREFERENCE("偏好", "preference"),
    PROJECT("项目", "project");

    companion object {
        fun fromCode(code: String?): MemoryKind? = values().firstOrNull { it.code == code }

        /** 宽松解析模型输出里的类型词（中文 / 英文 / 常见变体）。 */
        fun fromLabel(raw: String): MemoryKind? {
            val s = raw.trim().trim('[', ']', '【', '】', '(', ')', '（', '）', '"', '\'', '*', '`').lowercase()
            return when (s) {
                "事实", "fact", "facts", "核心事实", "背景" -> FACT
                "偏好", "preference", "preferences", "pref", "习惯", "喜好" -> PREFERENCE
                "项目", "project", "projects", "进展", "项目进展" -> PROJECT
                else -> null
            }
        }

        /** 没有明确类型时（例如用户说“记住：……”），按关键词粗略判断。 */
        fun guess(content: String): MemoryKind {
            val s = content.lowercase()
            return when {
                PREFERENCE_HINTS.any { s.contains(it) } -> PREFERENCE
                PROJECT_HINTS.any { s.contains(it) } -> PROJECT
                else -> FACT
            }
        }

        private val PREFERENCE_HINTS = listOf(
            "喜欢", "不喜欢", "讨厌", "偏好", "习惯", "希望", "不要", "请用", "回答要", "回答用", "prefer", "always", "never",
        )
        private val PROJECT_HINTS = listOf(
            "项目", "正在做", "正在开发", "正在写", "进度", "计划", "版本", "上线", "开发", "repo", "app",
        )
    }
}

enum class MemorySource { USER_EXPLICIT, AUTO_REFLECTION }

/**
 * 领域模型（纯 Kotlin）。Room 的 [MemoryEntity] 只是它的持久化形态，
 * 这样检索、注入、反思等逻辑都能在 JVM 上直接单元测试。
 */
data class MemoryRecord(
    val id: Long = 0,
    val kind: MemoryKind,
    val content: String,
    /** 1..5，越大越重要。 */
    val importance: Int = 3,
    val source: MemorySource,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
    val fingerprint: String = MemoryText.fingerprint(content),
)

/** 一条待写入的候选记忆（来自模型反思或用户显式指令，尚未去重）。 */
data class MemoryCandidate(
    val kind: MemoryKind,
    val importance: Int,
    val content: String,
)

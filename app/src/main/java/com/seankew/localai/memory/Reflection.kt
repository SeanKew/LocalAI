package com.seankew.localai.memory

/** 疑似隐私 / 凭据内容过滤：记忆库是明文存储，这类信息一律不写入。 */
object SensitiveFilter {
    private val LONG_DIGITS = Regex("\\d{6,}")
    private val GROUPED_DIGITS = Regex("\\d{3,4}[\\s-]\\d{3,4}[\\s-]\\d{3,4}")
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.-]+")
    private val KEYWORDS = listOf(
        "密码", "口令", "密钥", "私钥", "验证码", "身份证", "护照", "银行卡", "信用卡", "卡号", "社保",
        "password", "passwd", "token", "secret", "api key", "apikey", "api_key", "api-key", "cvv", "private key",
    )

    fun isSensitive(text: String): Boolean {
        val s = text.lowercase()
        return LONG_DIGITS.containsMatchIn(s) ||
            GROUPED_DIGITS.containsMatchIn(s) ||
            EMAIL.containsMatchIn(s) ||
            KEYWORDS.any { s.contains(it) }
    }
}

/** 反思提示词：让小模型只输出一行一条、固定分隔的简单格式（比 JSON 更不容易出错）。 */
object ReflectionPrompt {
    const val MAX_USER_CHARS = 400
    const val MAX_ANSWER_CHARS = 240

    val SYSTEM = """
        你是“记忆整理员”。请从下面这一轮对话中，提取值得长期记住的、关于用户本人的稳定信息。
        类型只有三种：事实（身份、背景、使用环境）、偏好（口味、习惯、对回答风格的要求）、项目（用户正在做的事与进展、已做出的决定）。
        规则：
        1. 每行输出一条，格式：类型|重要度|内容。重要度是 1 到 5 的整数。
        2. 内容用第三人称，以“用户”开头，一句话，不超过 40 个字。
        3. 只记录用户明确说出的信息，不要推测，不要记录助手的回答，不要记录一次性的提问。
        4. 不要记录密码、证件号、银行卡、手机号、邮箱等隐私信息。
        5. 最多 3 条。没有值得记住的内容时，只输出：无
    """.trimIndent()

    fun userMessage(userText: String, answer: String): String =
        "用户说：${userText.trim().take(MAX_USER_CHARS)}\n助手回答（仅作上下文）：${answer.trim().take(MAX_ANSWER_CHARS)}"
}

data class ParseResult(val candidates: List<MemoryCandidate>, val rejected: List<String>)

/** 解析并严格校验模型的反思输出；任何不合格的行都丢弃，宁缺毋滥。 */
object ReflectionParser {
    const val MAX_ITEMS = 3
    const val MIN_CONTENT = 4
    const val MAX_CONTENT = 80

    private val BULLET = Regex("^(?:[-*•·]+|\\d{1,2}[.)、．])\\s*")
    private val SEPARATOR = Regex("[|｜│]")

    fun parse(raw: String): ParseResult {
        val accepted = ArrayList<MemoryCandidate>()
        val rejected = ArrayList<String>()
        val seen = HashSet<String>()

        for (rawLine in raw.lineSequence()) {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            line = line.replace(BULLET, "").trim().trim('`')
            if (line.isEmpty()) continue
            if (isNone(line)) continue

            val parts = line.split(SEPARATOR, limit = 3).map { it.trim() }
            if (parts.size < 2) { rejected += "$line → 缺少分隔符"; continue }
            val kind = MemoryKind.fromLabel(parts[0])
            if (kind == null) { rejected += "$line → 未知类型"; continue }

            val importance: Int
            val content: String
            if (parts.size >= 3) {
                importance = Regex("[1-5]").find(parts[1])?.value?.toInt() ?: 3
                content = parts[2]
            } else {
                importance = 3
                content = parts[1]
            }
            val cleaned = content.trim().trim('"', '“', '”', '「', '」').trim()

            val reason = validateContent(cleaned)
            if (reason != null) { rejected += "$cleaned → $reason"; continue }
            if (!seen.add(MemoryText.fingerprint(cleaned))) continue
            if (accepted.size >= MAX_ITEMS) { rejected += "$cleaned → 超过上限"; continue }
            accepted += MemoryCandidate(kind, importance.coerceIn(1, 5), cleaned)
        }
        return ParseResult(accepted, rejected)
    }

    /** 统一的内容校验（自动反思与用户显式指令共用）；合格返回 null，否则返回原因。 */
    fun validateContent(content: String): String? = when {
        content.length < MIN_CONTENT -> "太短"
        content.length > MAX_CONTENT -> "太长"
        content.none { it.isLetter() } -> "没有有效文字"
        content.contains('?') || content.contains('？') -> "像是提问而不是事实"
        content.contains("<|") -> "含控制标记"
        SensitiveFilter.isSensitive(content) -> "疑似隐私信息"
        else -> null
    }

    private fun isNone(line: String): Boolean {
        val s = line.trim().trimEnd('。', '.', '!', '！').lowercase()
        return s == "无" || s == "没有" || s == "none" || s == "n/a" || s == "无。" || s.startsWith("没有值得")
    }
}

/**
 * 反思闸门：反思要多跑一次模型（耗电、占 CPU），所以只在这一轮"像是包含值得记住的信息"时才触发。
 */
object ReflectionGate {
    private val SIGNALS = listOf(
        "我是", "我叫", "我在", "我正在", "我喜欢", "我不喜欢", "我讨厌", "我习惯", "我平时", "我一般", "我常",
        "我的项目", "我们的", "我们在", "以后", "今后", "总是", "从不", "不要再", "别再", "希望你", "请你以后", "记住",
        "决定", "计划", "打算", "目标", "进度", "已经完成", "完成了", "做完", "偏好",
        "prefer", "always", "never", "i am", "i'm", "i like", "i love", "i hate", "my project", "we are building",
        "remember", "from now on", "going to", "plan to",
    )

    fun shouldReflect(userText: String, answer: String): Boolean {
        val u = userText.trim()
        if (u.length < 6) return false
        if (answer.trim().length < 10) return false
        val s = u.lowercase()
        return SIGNALS.any { s.contains(it) }
    }
}

/** 用户显式指令："记住：……" / "请记住……" / "remember that ……"。这是最可靠的写入方式。 */
object ExplicitMemory {
    private val PATTERN = Regex(
        "^\\s*(?:请|麻烦|帮我|你要|你得|务必)?\\s*(?:记住|记一下|记下|牢记|remember(?: that)?)\\s*[:：,，、]?\\s*(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 命中指令时返回改写成第三人称的内容；否则返回 null。 */
    fun extract(text: String): String? {
        val body = PATTERN.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val content = toThirdPerson(body.lineSequence().first().trim().trimEnd('。', '.', '!', '！'))
        return content.takeIf { it.length >= ReflectionParser.MIN_CONTENT }
    }

    /** 很朴素的人称转换："我的/我" → "用户的/用户"。 */
    fun toThirdPerson(text: String): String = text.replace("我的", "用户的").replace("我", "用户")
}

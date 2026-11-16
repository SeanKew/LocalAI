package com.seankew.localai.memory

import java.text.Normalizer
import kotlin.math.sqrt

/**
 * 文本处理工具（纯 Kotlin）。
 *
 * 检索没有用向量：端侧只有一个 1.5B 的对话模型，没有嵌入模型。因此采用对中文友好的"词法"方案：
 * - 中文 / 日文 / 韩文：取相邻两字的 bigram（不依赖分词器），并丢弃含常见虚词的 bigram；
 * - 英文 / 数字：取小写单词，丢弃常见停用词。
 */
object MemoryText {

    private const val STOP_CHARS = "的了是我你他她它们在和与及也就都有不这那么吗呢吧啊呀请把被让给对从向到为以之其而且或但并"

    private val ENGLISH_STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "you", "are", "was", "were", "what", "how", "can", "have",
        "has", "not", "but", "from", "they", "will", "would", "there", "their", "about", "which", "when", "your",
        "our", "any", "all", "use", "using", "please", "hello", "just", "like", "into", "than", "then", "its",
    )

    const val MAX_QUERY_TERMS = 48

    /** NFKC + 小写 + 压缩空白。 */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 去重指纹：归一化后只保留字母、数字、汉字。
     * “用户喜欢简洁的回答。” 与 “用户 喜欢简洁的回答” 得到同一个指纹。
     */
    fun fingerprint(content: String): String =
        buildString {
            for (ch in normalize(content)) if (ch.isLetterOrDigit()) append(ch)
        }

    /**
     * 写进系统提示词前的清洗：去掉换行和控制字符、拆掉 ChatML 控制标记、限制长度。
     * 记忆内容来自对话（间接来自用户和模型），不能让它改变提示词结构。
     */
    fun sanitizeForPrompt(content: String, maxChars: Int): String {
        val cleaned = content
            .replace("<|", "<")
            .replace("|>", ">")
            .map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars - 1).trimEnd() + "…"
    }

    private fun isCjk(cp: Int): Boolean {
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    private fun isAsciiWordChar(cp: Int): Boolean =
        cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'.code || cp == '.'.code || cp == '+'.code || cp == '#'.code || cp == '-'.code)

    /** 提取检索词，保持出现顺序、去重，最多 [limit] 个。 */
    fun terms(text: String, limit: Int = Int.MAX_VALUE): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        val s = normalize(text)
        var i = 0
        while (i < s.length && out.size < limit) {
            val cp = s.codePointAt(i)
            when {
                isCjk(cp) -> {
                    // 收集一段连续的 CJK 字符，再滑窗取 bigram
                    val run = ArrayList<Int>()
                    var j = i
                    while (j < s.length) {
                        val c = s.codePointAt(j)
                        if (!isCjk(c)) break
                        run += c
                        j += Character.charCount(c)
                    }
                    for (k in 0 until run.size - 1) {
                        if (out.size >= limit) break
                        val a = run[k]
                        val b = run[k + 1]
                        if (isStopChar(a) || isStopChar(b)) continue
                        out += String(Character.toChars(a)) + String(Character.toChars(b))
                    }
                    i = j
                }
                isAsciiWordChar(cp) -> {
                    var j = i
                    while (j < s.length && isAsciiWordChar(s[j].code)) j++
                    val word = s.substring(i, j).trim('.', '-', '+', '#', '_')
                    if (word.length >= 2 && word !in ENGLISH_STOP_WORDS) out += word
                    i = j
                }
                else -> i += Character.charCount(cp)
            }
        }
        return out
    }

    private fun isStopChar(cp: Int): Boolean = cp < 0x10000 && STOP_CHARS.indexOf(cp.toChar()) >= 0

    /** 较长的英文词 / 含数字的词 / 带点的词（如 "room"、"v2.1"）：单个命中就足以说明相关。 */
    fun isStrongTerm(term: String): Boolean =
        term.all { it.code < 128 } && (term.length >= 4 || term.any { it.isDigit() })

    /** 二值向量的余弦相似度：|A∩B| / sqrt(|A|·|B|)。 */
    fun cosine(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = if (a.size <= b.size) a.count { it in b } else b.count { it in a }
        return shared / sqrt(a.size.toDouble() * b.size.toDouble())
    }

    /** Jaccard 相似度，用于近重复判断。 */
    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return shared.toDouble() / (a.size + b.size - shared)
    }
}

package dev.shuohuaa.ime

/**
 * 段与段怎么接：补空格 + 扣着的末尾标点 + 开头大小写。
 * 和电脑端 core/postprocess.py 的 needs_space / split_trailing / join_segment 是同一套规则，
 * 改一边同步另一边；测试用例两边也一样（SegmentJoinTest.kt）。
 *
 * 一次录音切成好几段一段段上屏。每段末尾的句号逗号（HELD）先扣着：后面还有下一段就放回去，
 * 当句子之间的标点；最后一段的就不打（整段话结尾不带句号）。下一段开头的英文大写：模型把每段都当句首，
 * 所以前面不是句末标点、又是英文语境时改小写；I、全大写（AI）、大小写混排（iPhone）不动；前面是中文时不动。
 */
object SegmentJoin {
    /** 末尾要扣着的标点，和电脑端 Config.STRIP_TRAILING_PUNCTUATION 的默认值一致 */
    const val HELD = "。．.，,"
    private const val TERMINAL = ".!?。！？…"
    private const val WEST_PUNCT = ".,!?;:)]}\"'%"
    private val KEEP_CAP = setOf("I", "I'm", "I'll", "I've", "I'd")
    /** 一段开头的这些标点没意义（模型偶尔在段首补一个），和放回去的标点撞上就成了 "嘞。。"（用户实测） */
    private const val LEAD_PUNCT = "。．.，,、；;：:！!？?"
    private val FIRST_WORD = Regex("^([A-Za-z][A-Za-z']*)")

    /** 汉字、假名、全角标点和全角符号：这些之间不加空格。韩文不算（韩文按词加空格） */
    fun isCjk(c: Char): Boolean {
        val o = c.code
        return o in 0x3040..0x30FF || o in 0x3400..0x4DBF || o in 0x4E00..0x9FFF ||
            o in 0xF900..0xFAFF || o in 0x3000..0x303F || o in 0xFF00..0xFFEF
    }

    /** prev（前面最后一个字）和 next（这段第一个字）之间要不要补空格 */
    fun needsSpace(prev: Char?, next: Char): Boolean {
        if (prev == null || prev.isWhitespace() || next.isWhitespace()) return false
        if (!next.isLetterOrDigit()) return false
        val cjkPrev = isCjk(prev); val cjkNext = isCjk(next)
        if (cjkPrev && !prev.isLetterOrDigit()) return false
        if (cjkPrev && cjkNext) return false
        // 中文和数字之间不补："2008年 4月"、"三点 40分" 都难看；只在中英文之间补
        if ((cjkPrev && next.isDigit()) || (prev.isDigit() && cjkNext)) return false
        return prev.isLetterOrDigit() || prev in WEST_PUNCT
    }

    /** 把末尾的 HELD 标点切下来：(正文, 末尾标点) */
    fun splitTrailing(text: String): Pair<String, String> {
        val body = text.trimEnd()
        var i = body.length
        while (i > 0 && body[i - 1] in HELD) i--
        return body.substring(0, i).trimEnd() to body.substring(i)
    }

    /** 编辑器已经把临时文字收成正文（用户挪了光标）后，这段的识别结果只要"收下部分之后"的内容。
     *  返回 (接着打的标点, 剩下的正文)。按正文字数切（同一段音频重认，开头通常一样）；和电脑端
     *  core/postprocess.after_accepted 同一个意思（那边用 difflib 对齐，改了个别字也能对准）。 */
    fun afterAccepted(text: String, accepted: String): Pair<String, String> {
        val n = accepted.count { it.isLetterOrDigit() }
        if (n == 0) return "" to text
        var seen = 0; var i = 0
        while (i < text.length && seen < n) { if (text[i].isLetterOrDigit()) seen++; i++ }
        val rest = text.substring(i).trimStart()
        val lead = rest.takeWhile { it in LEAD_PUNCT }
        return lead to rest.substring(lead.length).trimStart()
    }

    /** 接上一段：prev 是前面最后一个字（不含扣着的标点），held 是扣着的标点，text 是切掉末尾标点的正文 */
    fun joinSegment(prev: Char?, held: String, text: String, protected: Set<String> = emptySet()): String {
        var t = text.trimStart { it in LEAD_PUNCT || it.isWhitespace() }
        if (t.isEmpty()) return ""
        val last = held.lastOrNull() ?: prev
        val m = FIRST_WORD.find(t)
        if (m != null && last != null && last !in TERMINAL && !last.isWhitespace()) {
            val w = m.groupValues[1]
            val englishCtx = last.code < 128 && (last.isLetterOrDigit() || last in WEST_PUNCT)
            val rest = w.substring(1)
            val title = w[0].isUpperCase() && (rest.isEmpty() || rest == rest.lowercase())
            if (englishCtx && title && w !in KEEP_CAP && w !in protected && w.lowercase() !in protected) {
                t = w[0].lowercaseChar() + t.substring(1)
            }
        }
        val sep = if (needsSpace(last, t[0])) " " else ""
        return held + sep + t
    }
}

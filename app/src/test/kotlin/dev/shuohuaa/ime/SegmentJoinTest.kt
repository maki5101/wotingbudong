package dev.shuohuaa.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用例和电脑端 core/postprocess.py 的 join_segment / needs_space 测试一样，两边规则不能走样 */
class SegmentJoinTest {
    private fun j(prev: Char?, held: String, text: String, prot: Set<String> = emptySet()) =
        SegmentJoin.joinSegment(prev, held, text, prot)

    @Test fun lowercaseAfterPauseInEnglish() = assertEquals(" this is worse", j('t', "", "This is worse"))
    @Test fun heldPeriodKeepsCapital() = assertEquals(". This is worse", j('t', ".", "This is worse"))
    @Test fun chinesePeriodNoSpace() = assertEquals("。电脑连不上", j('做', "。", "电脑连不上"))
    @Test fun chineseNoHeld() = assertEquals("电脑连不上", j('做', "", "电脑连不上"))
    @Test fun heldComma() = assertEquals(", so there's", j('e', ",", "So there's"))
    @Test fun keepI() = assertEquals(" I mean", j('e', "", "I mean"))
    @Test fun keepAllCaps() = assertEquals(" AI is", j('r', "", "AI is"))
    @Test fun chineseContextKeepsCase() = assertEquals(" Docker", j('用', "", "Docker"))
    @Test fun properNounKnownCost() = assertEquals(" paris is", j('e', "", "Paris is"))
    @Test fun protectedWord() = assertEquals(" Paris is", j('e', "", "Paris is", setOf("Paris")))
    @Test fun firstSegmentUntouched() = assertEquals("Hello", j(null, "", "Hello"))
    @Test fun leadingPunctDropped() = assertEquals("。当然", j('嘞', "。", "。当然"))
    @Test fun onlyPunct() = assertEquals("", j('嘞', "。", "。"))

    @Test fun afterAccepted() {
        assertEquals("" to "字这一行，我可以对着查", SegmentJoin.afterAccepted("日志里会有应用已收下临时文字这一行，我可以对着查", "日志里会有应用已收下临时文"))
        assertEquals("，" to "告诉我大概几分？", SegmentJoin.afterAccepted("如果还有重复，告诉我大概几分？", "如果还有重复"))
        assertEquals("" to "", SegmentJoin.afterAccepted("如果还有重复，告诉我大概几分", "如果还有重复，告诉我大概几分"))
        assertEquals("" to "我大概几分", SegmentJoin.afterAccepted("如果还有从复，告诉我大概几分", "如果还有重复，告诉"))
        assertEquals("" to "works, right", SegmentJoin.afterAccepted("I think this works, right", "I think this"))
    }

    @Test fun split() {
        assertEquals("下一步再做" to "。", SegmentJoin.splitTrailing("下一步再做。"))
        assertEquals("Is it?" to "", SegmentJoin.splitTrailing("Is it?"))
    }

    @Test fun spaces() {
        val cases = listOf(
            Triple('d', 'H', true), Triple('文', 'E', true), Triple('h', '中', true), Triple('文', '中', false),
            Triple('，', 'x', false), Triple('.', 'H', true), Triple('o', ',', false), Triple('요', '하', true),
            Triple('す', 'か', false), Triple('3', '个', false), Triple('年', '4', false), Triple(' ', 'x', false), Triple('。', 'H', false),
            Triple('!', '我', true), Triple('、', 'a', false), Triple('A', '1', true),
        )
        for ((p, n, e) in cases) assertEquals("$p|$n", e, SegmentJoin.needsSpace(p, n))
    }
}

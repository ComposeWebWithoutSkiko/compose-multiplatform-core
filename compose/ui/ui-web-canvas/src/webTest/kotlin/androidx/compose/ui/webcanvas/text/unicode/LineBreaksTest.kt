/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.webcanvas.text.unicode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineBreaksTest {
    private fun cp(vararg codePoints: Int): String = codePointsToString(codePoints)

    @Test
    fun encoding() {
        val breaks = lineBreaks("Hello world")
        assertContentEquals(intArrayOf(6 shl 1, (11 shl 1) or 1), breaks)
        assertEquals(6, lineBreakPosition(breaks[0]))
        assertFalse(isMandatoryLineBreak(breaks[0]))
        assertEquals(11, lineBreakPosition(breaks[1]))
        assertTrue(isMandatoryLineBreak(breaks[1]))
    }

    @Test
    fun mandatoryBreaks() {
        assertBreaks("a\nb", "2!", "3!")
        assertBreaks("a\r\nb", "3!", "4!")
        assertBreaks("a\rb", "2!", "3!")
        assertBreaks("a\u2028b", "2!", "3!") // LINE SEPARATOR
        assertBreaks("a\u0085b", "2!", "3!") // NEXT LINE
        assertBreaks("a\n", "2!")
        assertBreaks("\n\n", "1!", "2!")
    }

    @Test
    fun emptyText() {
        assertContentEquals(intArrayOf(1), lineBreaks(""))
        assertContentEquals(intArrayOf((3 shl 1) or 1), lineBreaks("abcdef", 3, 3))
    }

    @Test
    fun subRange() {
        val text = "xx Hello world yy"
        assertContentEquals(intArrayOf(9 shl 1, (14 shl 1) or 1), lineBreaks(text, 3, 14))
        assertFailsWith<IllegalArgumentException> { lineBreaks(text, 5, 4) }
        assertFailsWith<IllegalArgumentException> { lineBreaks(text, 0, 18) }
    }

    @Test
    fun spacesHangAtTheEndOfTheLine() {
        assertBreaks("a   b", "4", "5!")
        assertBreaks("a ", "2!")
    }

    @Test
    fun ideographsAndEmoji() {
        assertBreaks("\u4E2D\u6587\u5B57", "1", "2", "3!")
        // No break inside a surrogate pair, a break opportunity between two emoji.
        assertBreaks("\uD83D\uDE00\uD83D\uDE00", "2", "4!")
        // An emoji modifier sequence and a ZWJ sequence stay together.
        assertBreaks("\uD83D\uDC4D\uD83C\uDFFD", "4!")
        assertBreaks("\uD83D\uDC68\u200D\uD83D\uDC69", "5!")
        // A flag (pair of regional indicators) is not split, two flags may be.
        assertBreaks("\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7", "4", "8!")
    }

    @Test
    fun combiningMarksStayWithTheirBase() {
        assertBreaks("e\u0301 x", "3", "4!")
        // A combining mark after a space is treated as a letter (LB10).
        assertBreaks("a \u0301b", "2", "4!")
    }

    @Test
    fun punctuationAndNumbers() {
        assertBreaks("\$(12.50)", "8!")
        assertBreaks("100% done", "5", "9!")
        assertBreaks("a-b", "2", "3!")
        // No break after a word-initial hyphen (LB20a).
        assertBreaks("-b", "2!")
        assertBreaks("\"a\"", "3!")
        assertBreaks("e.g. this", "5", "9!")
        assertBreaks("x\u00A0y z", "4", "5!") // NO-BREAK SPACE
        assertBreaks("x\u200By", "2", "3!") // ZERO WIDTH SPACE
    }

    @Test
    fun longText() {
        val text = "ab ".repeat(7000)
        val breaks = lineBreaks(text)
        assertEquals(7000, breaks.size)
        assertEquals(3, lineBreakPosition(breaks[0]))
        assertEquals((text.length shl 1) or 1, breaks.last())
        // The buffers are released after a long text; short texts still work.
        assertBreaks("a b", "2", "3!")
    }

    @Test
    fun lineBreakClasses() {
        assertEquals(LineBreakClass.AL, lineBreakClass('a'.code))
        assertEquals(LineBreakClass.SP, lineBreakClass(' '.code))
        assertEquals(LineBreakClass.LF, lineBreakClass('\n'.code))
        assertEquals(LineBreakClass.ID, lineBreakClass(0x4E2D))
        assertEquals(LineBreakClass.QU, lineBreakClass(0x201C))
        assertEquals(LineBreakClass.QU, lineBreakClass(0x201D))
        assertEquals(LineBreakClass.SA, lineBreakClass(0x0E01)) // THAI CHARACTER KO KAI
        assertEquals(LineBreakClass.SA, lineBreakClass(0x0E31)) // THAI CHARACTER MAI HAN-AKAT
        assertEquals(LineBreakClass.AI, lineBreakClass(0x00A7)) // SECTION SIGN
        assertEquals(LineBreakClass.CJ, lineBreakClass(0x3041)) // HIRAGANA LETTER SMALL A
        assertEquals(LineBreakClass.XX, lineBreakClass(0xE000)) // private use
        assertEquals(LineBreakClass.SG, lineBreakClass(0xD800))
    }

    @Test
    fun classesAreResolvedPerLB1() {
        // AI, SA, XX and SG break like AL: not after a letter (LB28).
        assertBreaks(cp('a'.code, 0x00A7), "2!") // SECTION SIGN, AI
        assertBreaks(cp('a'.code, 0x0E01), "2!") // THAI CHARACTER KO KAI, SA
        assertBreaks(cp('a'.code, 0xE000), "2!") // private use, XX
        assertBreaks("a\uD800", "2!") // lone surrogate, SG
        // SA with General_Category Mn stays with what it follows, like CM (LB9), not after an
        // ideograph like AL would.
        assertBreaks(cp(0x4E00, 0x0E31), "2!") // THAI CHARACTER MAI HAN-AKAT
        // CJ doesn't start a line, like NS (LB21), not after an ideograph like ID would.
        assertBreaks(cp(0x4E00, 0x3041), "2!") // HIRAGANA LETTER SMALL A
        // Initial and final quotation marks are QU (LB19).
        assertBreaks(cp('a'.code, 0x201C, 'b'.code, 0x201D), "4!")
    }

    @Test
    fun whitespace() {
        assertTrue(isLineBreakWhitespace(' '.code))
        assertTrue(isLineBreakWhitespace('\t'.code))
        assertTrue(isLineBreakWhitespace('\n'.code))
        assertTrue(isLineBreakWhitespace(0x3000)) // IDEOGRAPHIC SPACE
        assertTrue(isLineBreakWhitespace(0x2003)) // EM SPACE
        // Mandatory breaks whose character hangs, as in SkParagraph.
        assertTrue(isLineBreakWhitespace(0x000B)) // LINE TABULATION
        assertTrue(isLineBreakWhitespace(0x000C)) // FORM FEED
        assertTrue(isLineBreakWhitespace(0x2028)) // LINE SEPARATOR
        assertTrue(isLineBreakWhitespace(0x2029)) // PARAGRAPH SEPARATOR
        // ICU's whitespace, but not SkParagraph's.
        assertFalse(isLineBreakWhitespace(0x001C)) // FILE SEPARATOR
        assertFalse(isLineBreakWhitespace(0x2007)) // FIGURE SPACE, a no-break space
        assertFalse(isLineBreakWhitespace(0x00A0)) // NO-BREAK SPACE
        assertFalse(isLineBreakWhitespace(0x202F)) // NARROW NO-BREAK SPACE
        assertFalse(isLineBreakWhitespace(0x200B)) // ZERO WIDTH SPACE
        assertFalse(isLineBreakWhitespace(0x0085)) // NEXT LINE (Cc), as in ICU and the JDK
        assertFalse(isLineBreakWhitespace('a'.code))
    }

    @Test
    fun eastAsianWidth() {
        assertTrue(isEastAsianWidth(0x4E2D))
        assertTrue(isEastAsianWidth(0xFF21)) // FULLWIDTH LATIN CAPITAL LETTER A
        assertTrue(isEastAsianWidth(0xFF61)) // HALFWIDTH IDEOGRAPHIC FULL STOP
        assertTrue(isEastAsianWidth(0x1F600))
        assertFalse(isEastAsianWidth('a'.code))
        assertFalse(isEastAsianWidth(0x00A7)) // ambiguous
    }

    /** Checks the breaks of [text], given as "position" or "position!" for mandatory breaks. */
    private fun assertBreaks(text: String, vararg expected: String) {
        val actual =
            lineBreaks(text).map {
                "${lineBreakPosition(it)}${if (isMandatoryLineBreak(it)) "!" else ""}"
            }
        assertEquals(expected.toList(), actual, "Line breaks of \"$text\"")
    }
}

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
import kotlin.test.assertNull

class BidiParagraphTest {
    private val hebrew = "\u05D0\u05D1\u05D2" // Hebrew alef, bet, gimel

    @Test
    fun pureLeftToRight() {
        val text = "Hello, world!"
        val paragraph = BidiText(text, LTR)
        assertContentEquals(ByteArray(text.length), paragraph.levels)
        assertContentEquals(intArrayOf(0, text.length, 0), paragraph.visualRuns(0, text.length))
    }

    @Test
    fun mixedTextInLtrParagraph() {
        val text = "abc $hebrew"
        val paragraph = BidiText(text, paragraphLevel = LTR)
        assertContentEquals(levels(0, 0, 0, 0, 1, 1, 1), paragraph.levels)
        assertContentEquals(intArrayOf(0, 4, 0, 4, 7, 1), paragraph.visualRuns(0, 7))
        assertContentEquals(intArrayOf(0, 1, 2, 3, 6, 5, 4), paragraph.visualOrder(0, 7))
    }

    @Test
    fun mixedTextInRtlParagraph() {
        val text = "abc $hebrew"
        val paragraph = BidiText(text, paragraphLevel = RTL)
        assertContentEquals(levels(2, 2, 2, 1, 1, 1, 1), paragraph.levels)
        assertContentEquals(intArrayOf(3, 7, 1, 0, 3, 2), paragraph.visualRuns(0, 7))
        assertContentEquals(intArrayOf(6, 5, 4, 3, 0, 1, 2), paragraph.visualOrder(0, 7))
    }

    @Test
    fun numbersInRtlText() {
        val paragraph = BidiText("$hebrew 123", paragraphLevel = LTR)
        assertContentEquals(levels(1, 1, 1, 1, 2, 2, 2), paragraph.levels)
        assertContentEquals(intArrayOf(4, 7, 2, 0, 4, 1), paragraph.visualRuns(0, 7))
    }

    @Test
    fun paragraphsAreResolvedOneByOne() {
        val paragraph = BidiText("abc\ndef", paragraphLevel = RTL)
        assertContentEquals(levels(2, 2, 2, 1, 2, 2, 2), paragraph.levels)
        // An embedding (RIGHT-TO-LEFT EMBEDDING) ends with its paragraph (rule X8).
        val embedding = BidiText(Char(0x202B) + "ab\ncd", paragraphLevel = LTR)
        assertContentEquals(levels(0, 2, 2, 0, 0, 0), embedding.levels)
    }

    @Test
    fun separatorsAndTheWhitespaceBeforeThemTakeTheParagraphLevel() {
        val paragraph = BidiText("a\tb", paragraphLevel = RTL)
        assertContentEquals(levels(2, 1, 2), paragraph.levels)
        assertContentEquals(intArrayOf(2, 3, 2, 1, 2, 1, 0, 1, 2), paragraph.visualRuns(0, 3))
        // Hebrew alef, a space and a tab, Hebrew bet.
        val text = charArrayOf(Char(0x05D0), ' ', '\t', Char(0x05D1)).concatToString()
        assertContentEquals(levels(1, 0, 0, 1), BidiText(text, paragraphLevel = LTR).levels)
    }

    @Test
    fun lineLevelsResetTrailingWhitespace() {
        val paragraph = BidiText("$hebrew \u05D3\u05D4", paragraphLevel = LTR)
        assertContentEquals(levels(1, 1, 1, 1, 1, 1), paragraph.levels)
        // The space ends the first line: L1 resets it to the paragraph level.
        assertEquals(3, paragraph.trailingWhitespaceStart(0, 4))
        assertEquals(6, paragraph.trailingWhitespaceStart(4, 6))
        assertContentEquals(levels(1, 1, 1, 0), paragraph.lineLevels(0, 4))
        assertContentEquals(levels(1, 1), paragraph.lineLevels(4, 6))
        assertContentEquals(intArrayOf(0, 3, 1, 3, 4, 0), paragraph.visualRuns(0, 4))
    }

    @Test
    fun surrogatePairsShareLevels() {
        // U+10900 PHOENICIAN LETTER ALF and U+10901 PHOENICIAN LETTER BET (R).
        val text = "a\uD802\uDD00\uD802\uDD01b"
        val paragraph = BidiText(text, paragraphLevel = LTR)
        assertContentEquals(levels(0, 1, 1, 1, 1, 0), paragraph.levels)
        // Code points are reordered, code units of a pair stay in logical order.
        assertContentEquals(intArrayOf(0, 3, 4, 1, 2, 5), paragraph.visualOrder(0, 6))
    }

    @Test
    fun charactersRemovedByX9TakeThePrecedingLevel() {
        // U+200D ZERO WIDTH JOINER (BN) between two Hebrew letters, and LRE/PDF.
        val paragraph = BidiText("\u05D0\u200D\u05D1\u202A\u202C", paragraphLevel = LTR)
        assertContentEquals(levels(1, 1, 1, 1, 1), paragraph.levels)
        // L1 resets the controls at the end of the line to the paragraph level.
        assertContentEquals(levels(1, 1, 1, 0, 0), paragraph.lineLevels(0, 5))
        assertContentEquals(intArrayOf(0, 3, 1, 3, 5, 0), paragraph.visualRuns(0, 5))
    }

    @Test
    fun explicitEmbeddingsAndIsolates() {
        // RLO forces a right-to-left run of Latin letters.
        val override = BidiText("a\u202Ebc\u202Cd", paragraphLevel = LTR)
        assertContentEquals(levels(0, 0, 1, 1, 1, 0), override.levels)
        // An RLI isolate keeps its content at level 1, the isolate controls at level 0.
        val isolate = BidiText("a\u2067bc\u2069d", paragraphLevel = LTR)
        assertContentEquals(levels(0, 0, 2, 2, 0, 0), isolate.levels)
    }

    @Test
    fun longText() {
        val text = "abc $hebrew ".repeat(3000)
        val paragraph = BidiText(text, paragraphLevel = LTR)
        assertEquals(1, paragraph.levels[text.length - 2].toInt())
        assertEquals(0, paragraph.levels[text.length - 6].toInt())
        // The buffers are released after a long text; short texts still work.
        assertContentEquals(levels(0, 0, 1), BidiText("a \u05D0", paragraphLevel = LTR).levels)
    }

    @Test
    fun emptyText() {
        val paragraph = BidiText("", paragraphLevel = RTL)
        assertContentEquals(ByteArray(0), paragraph.levels)
        assertEquals(0, paragraph.trailingWhitespaceStart(0, 0))
    }

    @Test
    fun invalidArguments() {
        assertFailsWith<IllegalArgumentException> { BidiParagraph("abc", paragraphLevel = 2) }
    }

    @Test
    fun reorderVisually() {
        val levels = levels(0, 0, 1, 1, 2, 2, 1)
        assertContentEquals(intArrayOf(0, 1, 6, 4, 5, 3, 2), BidiParagraph.reorderVisually(levels))
        assertContentEquals(
            intArrayOf(0, 2, 0, 6, 7, 1, 4, 6, 2, 2, 4, 1),
            BidiParagraph.visualRunsOf(levels),
        )
        // Without an odd level, the visual order is the logical one.
        assertNull(BidiParagraph.reorderVisually(levels(0, 2, 2, 0)))
    }

    @Test
    fun bidiClasses() {
        assertEquals(BidiClass.L, bidiClass('a'.code))
        assertEquals(BidiClass.R, bidiClass(0x05D0))
        assertEquals(BidiClass.AL, bidiClass(0x0627))
        assertEquals(BidiClass.EN, bidiClass('1'.code))
        assertEquals(BidiClass.AN, bidiClass(0x0661))
        assertEquals(BidiClass.WS, bidiClass(' '.code))
        assertEquals(BidiClass.B, bidiClass('\n'.code))
        assertEquals(BidiClass.S, bidiClass('\t'.code))
        assertEquals(BidiClass.BN, bidiClass(0x200D))
        assertEquals(BidiClass.FSI, bidiClass(0x2068))
        assertEquals(BidiClass.R, bidiClass(0x10900))
        // Unassigned code points in the Hebrew block default to R.
        assertEquals(BidiClass.R, bidiClass(0x05FF))
    }

    @Test
    fun firstStrongDirections() {
        assertEquals(STRONG_DIRECTION_LTR, firstStrongDirection("123 abc $hebrew"))
        assertEquals(STRONG_DIRECTION_RTL, firstStrongDirection("123 $hebrew abc"))
        assertEquals(STRONG_DIRECTION_RTL, firstStrongDirection("\u0627"))
        assertEquals(STRONG_DIRECTION_NONE, firstStrongDirection("123 !"))
        assertEquals(STRONG_DIRECTION_NONE, firstStrongDirection("\u0661\u0662"))
        assertEquals(STRONG_DIRECTION_NONE, firstStrongDirection(""))
        // Characters inside isolates are ignored, also when the isolate is not closed.
        assertEquals(STRONG_DIRECTION_LTR, firstStrongDirection("\u2067$hebrew\u2069abc"))
        assertEquals(STRONG_DIRECTION_NONE, firstStrongDirection("\u2068$hebrew"))
        // An unmatched PDI is ignored.
        assertEquals(STRONG_DIRECTION_RTL, firstStrongDirection("\u2069\u2069$hebrew"))
        assertEquals(STRONG_DIRECTION_RTL, firstStrongDirection("abc $hebrew", 4, 7))
    }

    @Test
    fun pairedBrackets() {
        // Packed as (paired bracket shl 2) or type.
        assertEquals((']'.code shl 2) or BIDI_BRACKET_OPEN, bidiBracketInfo('['.code))
        assertEquals(('['.code shl 2) or BIDI_BRACKET_CLOSE, bidiBracketInfo(']'.code))
        assertEquals((0x232A shl 2) or BIDI_BRACKET_OPEN, bidiBracketInfo(0x2329))
        assertEquals(0, bidiBracketInfo('<'.code))
        assertEquals(0, bidiBracketInfo('a'.code))
    }

    private fun levels(vararg levels: Int): ByteArray =
        ByteArray(levels.size) { levels[it].toByte() }
}

private const val LTR = 0
private const val RTL = 1

/**
 * [text] in a paragraph at [paragraphLevel], with the bidi levels and the visual order of its lines
 * as the layout gets them: [BidiParagraph.levels], [trailingWhitespaceStart] for the rest of rule
 * L1, and rule L2 ([BidiParagraph.visualRunsOf]).
 */
internal class BidiText(private val text: String, private val paragraphLevel: Int) {
    val levels: ByteArray = BidiParagraph(text, paragraphLevel).levels

    fun trailingWhitespaceStart(lineStart: Int, lineEnd: Int): Int =
        trailingWhitespaceStart(text, lineStart, lineEnd)

    /** The levels of the line `text[lineStart, lineEnd)` after rule L1. */
    fun lineLevels(lineStart: Int, lineEnd: Int): ByteArray {
        val result = levels.copyOfRange(lineStart, lineEnd)
        for (i in trailingWhitespaceStart(lineStart, lineEnd) until lineEnd) {
            result[i - lineStart] = paragraphLevel.toByte()
        }
        return result
    }

    /** The runs of the line `text[lineStart, lineEnd)` in visual order, as text indexes. */
    fun visualRuns(lineStart: Int, lineEnd: Int): IntArray {
        val runs = BidiParagraph.visualRunsOf(lineLevels(lineStart, lineEnd))
        for (i in runs.indices) if (i % 3 != 2) runs[i] += lineStart
        return runs
    }

    /**
     * The text indexes of the line `text[lineStart, lineEnd)` in visual order (left to right).
     * Code units of a surrogate pair stay in logical order within right-to-left runs: code points
     * are reordered, not code units.
     */
    fun visualOrder(lineStart: Int, lineEnd: Int): IntArray {
        val runs = visualRuns(lineStart, lineEnd)
        val result = IntArray(lineEnd - lineStart)
        var out = 0
        for (r in runs.indices step 3) {
            val runStart = runs[r]
            val runEnd = runs[r + 1]
            if (runs[r + 2] and 1 == 0) {
                for (i in runStart until runEnd) result[out++] = i
            } else {
                var i = runEnd
                while (i > runStart) {
                    i--
                    if (i > runStart && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()) {
                        result[out++] = i - 1
                        result[out++] = i
                        i--
                    } else {
                        result[out++] = i
                    }
                }
            }
        }
        return result
    }
}

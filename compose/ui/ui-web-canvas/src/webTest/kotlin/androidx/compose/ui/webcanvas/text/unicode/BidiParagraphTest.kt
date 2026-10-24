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

import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph.Companion.LEVEL_DEFAULT_LTR
import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph.Companion.LEVEL_DEFAULT_RTL
import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph.Companion.LEVEL_LTR
import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph.Companion.LEVEL_RTL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BidiParagraphTest {
    private val hebrew = "\u05D0\u05D1\u05D2" // אבג

    @Test
    fun pureLeftToRight() {
        val text = "Hello, world!"
        val paragraph = BidiParagraph(text)
        assertEquals(0, paragraph.paragraphLevel)
        assertTrue(paragraph.isLeftToRight)
        assertContentEquals(ByteArray(text.length), paragraph.levels)
        assertContentEquals(intArrayOf(0, text.length, 0), paragraph.visualRuns(0, text.length))
    }

    @Test
    fun mixedTextInLtrParagraph() {
        val text = "abc $hebrew"
        val paragraph = BidiParagraph(text, requestedLevel = LEVEL_LTR)
        assertFalse(paragraph.isLeftToRight)
        assertContentEquals(levels(0, 0, 0, 0, 1, 1, 1), paragraph.levels)
        assertContentEquals(intArrayOf(0, 4, 0, 4, 7, 1), paragraph.visualRuns(0, 7))
        assertContentEquals(intArrayOf(0, 1, 2, 3, 6, 5, 4), paragraph.visualOrder(0, 7))
    }

    @Test
    fun mixedTextInRtlParagraph() {
        val text = "abc $hebrew"
        val paragraph = BidiParagraph(text, requestedLevel = LEVEL_RTL)
        assertEquals(1, paragraph.paragraphLevel)
        assertContentEquals(levels(2, 2, 2, 1, 1, 1, 1), paragraph.levels)
        assertContentEquals(intArrayOf(3, 7, 1, 0, 3, 2), paragraph.visualRuns(0, 7))
        assertContentEquals(intArrayOf(6, 5, 4, 3, 0, 1, 2), paragraph.visualOrder(0, 7))
    }

    @Test
    fun numbersInRtlText() {
        val paragraph = BidiParagraph("$hebrew 123", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(1, 1, 1, 1, 2, 2, 2), paragraph.levels)
        assertContentEquals(intArrayOf(4, 7, 2, 0, 4, 1), paragraph.visualRuns(0, 7))
    }

    @Test
    fun levelRuns() {
        val paragraph = BidiParagraph("abc $hebrew 123", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2), paragraph.levels)
        assertEquals(4, paragraph.levelRunEnd(0))
        assertEquals(8, paragraph.levelRunEnd(4))
        assertEquals(11, paragraph.levelRunEnd(8))
        assertEquals(6, paragraph.levelRunEnd(4, 6))
        assertFailsWith<IllegalArgumentException> { paragraph.levelRunEnd(11) }
    }

    @Test
    fun automaticParagraphLevel() {
        assertEquals(1, BidiParagraph("$hebrew abc").paragraphLevel)
        assertEquals(0, BidiParagraph("123 abc $hebrew").paragraphLevel)
        assertEquals(0, BidiParagraph("123", requestedLevel = LEVEL_DEFAULT_LTR).paragraphLevel)
        assertEquals(1, BidiParagraph("123", requestedLevel = LEVEL_DEFAULT_RTL).paragraphLevel)
        assertEquals(0, BidiParagraph("abc", requestedLevel = LEVEL_DEFAULT_RTL).paragraphLevel)
        // Text inside an isolate is skipped by P2.
        assertEquals(0, BidiParagraph("\u2067$hebrew\u2069 abc").paragraphLevel)
        // Explicit levels win.
        assertEquals(1, BidiParagraph("abc", requestedLevel = LEVEL_RTL).paragraphLevel)
        assertEquals(0, BidiParagraph(hebrew, requestedLevel = LEVEL_LTR).paragraphLevel)
    }

    @Test
    fun multipleParagraphsWithAutomaticLevels() {
        val text = "$hebrew\nabc"
        val paragraph = BidiParagraph(text)
        assertEquals(2, paragraph.paragraphCount)
        assertEquals(4, paragraph.paragraphEnd(0))
        assertEquals(4, paragraph.paragraphStart(1))
        assertEquals(1, paragraph.paragraphLevel)
        assertEquals(1, paragraph.paragraphLevelAt(3))
        assertEquals(0, paragraph.paragraphLevelAt(4))
        assertContentEquals(levels(1, 1, 1, 1, 0, 0, 0), paragraph.levels)
        assertContentEquals(levels(1, 1, 1, 1), paragraph.lineLevels(0, 4))
    }

    @Test
    fun multipleParagraphsWithExplicitLevel() {
        val paragraph = BidiParagraph("abc\ndef", requestedLevel = LEVEL_RTL)
        assertEquals(2, paragraph.paragraphCount)
        assertEquals(1, paragraph.paragraphLevelAt(0))
        assertEquals(1, paragraph.paragraphLevelAt(5))
        assertContentEquals(levels(2, 2, 2, 1, 2, 2, 2), paragraph.levels)
    }

    @Test
    fun crLfIsOneParagraphSeparator() {
        val paragraph = BidiParagraph("a\r\nb")
        assertEquals(2, paragraph.paragraphCount)
        assertEquals(3, paragraph.paragraphEnd(0))
        assertEquals(0, paragraph.paragraphIndexOf(2))
        assertEquals(1, paragraph.paragraphIndexOf(3))
    }

    @Test
    fun lineLevelsResetSegmentSeparators() {
        val paragraph = BidiParagraph("a\tb", requestedLevel = LEVEL_RTL)
        assertContentEquals(levels(2, 2, 2), paragraph.levels)
        assertContentEquals(levels(2, 1, 2), paragraph.lineLevels(0, 3))
        assertContentEquals(intArrayOf(2, 3, 2, 1, 2, 1, 0, 1, 2), paragraph.visualRuns(0, 3))
    }

    @Test
    fun lineLevelsResetTrailingWhitespace() {
        val paragraph = BidiParagraph("$hebrew \u05D3\u05D4", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(1, 1, 1, 1, 1, 1), paragraph.levels)
        // The space ends the first line: L1 resets it to the paragraph level.
        assertContentEquals(levels(1, 1, 1, 0), paragraph.lineLevels(0, 4))
        assertContentEquals(levels(1, 1), paragraph.lineLevels(4, 6))
        assertContentEquals(intArrayOf(0, 3, 1, 3, 4, 0), paragraph.visualRuns(0, 4))
    }

    @Test
    fun surrogatePairsShareLevels() {
        // U+10900 PHOENICIAN LETTER ALF and U+10901 PHOENICIAN LETTER BET (R).
        val text = "a\uD802\uDD00\uD802\uDD01b"
        val paragraph = BidiParagraph(text, requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(0, 1, 1, 1, 1, 0), paragraph.levels)
        // Code points are reordered, code units of a pair stay in logical order.
        assertContentEquals(intArrayOf(0, 3, 4, 1, 2, 5), paragraph.visualOrder(0, 6))
    }

    @Test
    fun charactersRemovedByX9TakeThePrecedingLevel() {
        // U+200D ZERO WIDTH JOINER (BN) between two Hebrew letters, and LRE/PDF.
        val paragraph = BidiParagraph("\u05D0\u200D\u05D1\u202A\u202C", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(1, 1, 1, 1, 1), paragraph.levels)
        // L1 resets the controls at the end of the line to the paragraph level.
        assertContentEquals(levels(1, 1, 1, 0, 0), paragraph.lineLevels(0, 5))
        assertContentEquals(intArrayOf(0, 3, 1, 3, 5, 0), paragraph.visualRuns(0, 5))
    }

    @Test
    fun explicitEmbeddingsAndIsolates() {
        // RLO forces a right-to-left run of Latin letters.
        val override = BidiParagraph("a\u202Ebc\u202Cd", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(0, 0, 1, 1, 1, 0), override.levels)
        // An RLI isolate keeps its content at level 1, the isolate controls at level 0.
        val isolate = BidiParagraph("a\u2067bc\u2069d", requestedLevel = LEVEL_LTR)
        assertContentEquals(levels(0, 0, 2, 2, 0, 0), isolate.levels)
    }

    @Test
    fun subRange() {
        val text = "xx $hebrew yy"
        val paragraph = BidiParagraph(text, 3, 6)
        assertEquals(1, paragraph.paragraphLevel)
        assertContentEquals(levels(1, 1, 1), paragraph.levels)
        assertEquals(1, paragraph.levelAt(4))
        assertContentEquals(intArrayOf(3, 6, 1), paragraph.visualRuns(3, 6))
    }

    @Test
    fun longText() {
        val text = "abc $hebrew ".repeat(3000)
        val paragraph = BidiParagraph(text, requestedLevel = LEVEL_LTR)
        assertEquals(1, paragraph.levelAt(text.length - 2))
        assertEquals(0, paragraph.levelAt(text.length - 6))
        // The buffers are released after a long text; short texts still work.
        assertContentEquals(levels(0, 0, 1), BidiParagraph("a \u05D0").levels)
    }

    @Test
    fun emptyText() {
        val paragraph = BidiParagraph("")
        assertEquals(1, paragraph.paragraphCount)
        assertEquals(0, paragraph.paragraphLevel)
        assertTrue(paragraph.isLeftToRight)
        assertContentEquals(IntArray(0), paragraph.visualRuns(0, 0))
        assertEquals(1, BidiParagraph("", requestedLevel = LEVEL_RTL).paragraphLevel)
    }

    @Test
    fun invalidArguments() {
        assertFailsWith<IllegalArgumentException> { BidiParagraph("abc", 2, 1) }
        assertFailsWith<IllegalArgumentException> { BidiParagraph("abc", 0, 4) }
        assertFailsWith<IllegalArgumentException> { BidiParagraph("abc", requestedLevel = 2) }
        assertFailsWith<IllegalArgumentException> { BidiParagraph("abc").lineLevels(0, 4) }
    }

    @Test
    fun reorderVisually() {
        val levels = levels(0, 0, 1, 1, 2, 2, 1)
        assertContentEquals(intArrayOf(0, 1, 6, 4, 5, 3, 2), BidiParagraph.reorderVisually(levels))
        assertContentEquals(
            intArrayOf(0, 2, 0, 6, 7, 1, 4, 6, 2, 2, 4, 1),
            BidiParagraph.visualRunsOf(levels),
        )
        assertContentEquals(intArrayOf(10, 12, 0), BidiParagraph.visualRunsOf(levels(0, 0), 10))
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
    fun strongDirections() {
        assertEquals(STRONG_DIRECTION_LTR, strongDirection('a'.code))
        assertEquals(STRONG_DIRECTION_RTL, strongDirection(0x05D0))
        assertEquals(STRONG_DIRECTION_RTL, strongDirection(0x0627))
        assertEquals(STRONG_DIRECTION_NONE, strongDirection('1'.code))
        assertEquals(STRONG_DIRECTION_NONE, strongDirection(' '.code))
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
    fun neutralDirections() {
        assertTrue(isNeutralDirection(' '.code))
        assertTrue(isNeutralDirection('!'.code))
        assertTrue(isNeutralDirection(0x200B)) // ZERO WIDTH SPACE, BN
        assertFalse(isNeutralDirection('\n'.code)) // B
        assertFalse(isNeutralDirection('a'.code))
        assertFalse(isNeutralDirection('1'.code))
    }

    @Test
    fun pairedBrackets() {
        assertEquals(BIDI_BRACKET_OPEN, bidiPairedBracketType('['.code))
        assertEquals(BIDI_BRACKET_CLOSE, bidiPairedBracketType(']'.code))
        assertEquals(BIDI_BRACKET_NONE, bidiPairedBracketType('<'.code))
        assertEquals(']'.code, bidiPairedBracket('['.code))
        assertEquals(0x232A, bidiPairedBracket(0x2329))
        assertEquals('a'.code, bidiPairedBracket('a'.code))
    }

    private fun levels(vararg levels: Int): ByteArray =
        ByteArray(levels.size) { levels[it].toByte() }
}

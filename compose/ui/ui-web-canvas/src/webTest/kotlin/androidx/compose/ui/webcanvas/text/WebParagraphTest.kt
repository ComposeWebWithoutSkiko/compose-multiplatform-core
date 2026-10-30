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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebParagraphTest {
    private val density = Density(1f)
    private val style = TextStyle(fontSize = 16.sp)

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun paragraph(
        text: String,
        width: Int = 10_000,
        maxLines: Int = Int.MAX_VALUE,
        overflow: TextOverflow = TextOverflow.Clip,
        style: TextStyle = this.style,
        placeholders: List<AnnotatedString.Range<Placeholder>> = emptyList(),
    ): Paragraph = Paragraph(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = width),
        density = density,
        fontFamilyResolver = createFontFamilyResolver(),
        defaultLocaleList = LocaleList("en-US"),
        placeholders = placeholders,
        maxLines = maxLines,
        overflow = overflow,
    )

    @Test
    fun singleLine() {
        val p = paragraph("Hello world")
        assertEquals(1, p.lineCount)
        assertTrue(p.height > 0f)
        assertTrue(p.maxIntrinsicWidth > 0f)
        assertTrue(p.minIntrinsicWidth <= p.maxIntrinsicWidth)
        assertEquals(0, p.getLineStart(0))
        assertEquals(11, p.getLineEnd(0))
        assertTrue(p.firstBaseline > 0f && p.firstBaseline < p.height)
    }

    @Test
    fun aTabSplitsRightToLeftTextAtTheParagraphLevel() {
        // UAX #9 rule L1: a tab takes the paragraph level, so in a left-to-right paragraph the
        // right-to-left text before it stays on its left, and the text after it on its right.
        // Hebrew alef, bet, a tab, gimel, dalet.
        val text = charArrayOf(Char(0x05D0), Char(0x05D1), '\t', Char(0x05D2), Char(0x05D3)).concatToString()
        val p = paragraph(text, style = style.copy(textDirection = TextDirection.Ltr))
        val alef = p.getBoundingBox(0)
        val tab = p.getBoundingBox(2)
        val gimel = p.getBoundingBox(3)
        assertTrue(alef.left < tab.left && tab.left < gimel.left, "alef $alef, tab $tab, gimel $gimel")
        assertEquals(ResolvedTextDirection.Ltr, p.getBidiRunDirection(2))
    }

    @Test
    fun theSpaceEndingAWrappedLineReportsTheDirectionItIsDrawnIn() {
        // Two Hebrew words (alef, bet, gimel) in a left-to-right paragraph, the first one ending
        // the first line: rule L1 draws the space after it left-to-right, at the end of the line.
        val word = charArrayOf(Char(0x05D0), Char(0x05D1), Char(0x05D2)).concatToString()
        val ltr = style.copy(textDirection = TextDirection.Ltr)
        val width = ceil(paragraph(word, style = ltr).maxIntrinsicWidth).toInt() + 1
        val p = paragraph("$word $word", width = width, style = ltr)
        assertEquals(2, p.lineCount)
        assertEquals(ResolvedTextDirection.Rtl, p.getBidiRunDirection(2))
        assertEquals(ResolvedTextDirection.Ltr, p.getBidiRunDirection(3))
        // And Latin words in a right-to-left paragraph.
        val rtl = style.copy(textDirection = TextDirection.Rtl)
        val latinWidth = ceil(paragraph("abc", style = rtl).maxIntrinsicWidth).toInt() + 1
        val latin = paragraph("abc abc", width = latinWidth, style = rtl)
        assertEquals(2, latin.lineCount)
        assertEquals(ResolvedTextDirection.Ltr, latin.getBidiRunDirection(2))
        assertEquals(ResolvedTextDirection.Rtl, latin.getBidiRunDirection(3))
    }

    @Test
    fun aTabEndingARightAlignedLineHangsPastItsEdge() {
        val p = paragraph("ab\t", width = 100, style = style.copy(textAlign = TextAlign.Right))
        assertEquals(100f, p.getHorizontalPosition(2, usePrimaryDirection = true), 0.5f)
    }

    @Test
    fun onlyTheSpacesEndingARightAlignedLineHangPastItsEdge() {
        // A FIGURE SPACE is whitespace for the bidi algorithm, but a no-break space doesn't hang.
        val p = paragraph("ab${Char(0x2007)} ", width = 100, style = style.copy(textAlign = TextAlign.Right))
        assertEquals(100f, p.getHorizontalPosition(3, usePrimaryDirection = true), 0.5f)
    }

    @Test
    fun everyMandatoryBreakEndsALineLikeALineFeed() {
        // LF, VT, FF, CR, NEXT LINE, LINE SEPARATOR, PARAGRAPH SEPARATOR: like SkParagraph, a line
        // ends before its terminator, and a terminator ending the text makes an empty last line.
        // The spaces before a terminator hang like the ones ending a wrapped line.
        val fitting = paragraph("xxx ab").maxIntrinsicWidth
        val width = ceil(fitting).toInt()
        assertTrue(width < paragraph("xxx ab ").maxIntrinsicWidth, "the space doesn't fit")
        for (terminator in intArrayOf(0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029)) {
            val name = "U+${terminator.toString(16)}"
            val t = Char(terminator)
            val p = paragraph("ab${t}cd")
            assertEquals(2, p.lineCount, name)
            assertEquals(2, p.getLineEnd(0), name)
            assertEquals(3, p.getLineStart(1), name)
            assertEquals(2, paragraph("ab$t").lineCount, name)
            val wrapped = paragraph("xxx ab ${t}cd", width = width)
            assertEquals(2, wrapped.lineCount, name)
            assertEquals(7, wrapped.getLineEnd(0), name)
            assertEquals(paragraph("ab ").minIntrinsicWidth, paragraph("ab $t").minIntrinsicWidth, name)
        }
        // A CR ending a line before a terminator isn't part of a CR LF.
        val crThenVt = paragraph("a\r${Char(0x000B)}b")
        assertEquals(3, crThenVt.lineCount)
        assertEquals(2, crThenVt.getLineEnd(1))
    }

    @Test
    fun theCaretPositionsOfAPieceAreMeasuredOnce() {
        val text = "caret positions measured once"
        val p = paragraph(text)
        val boxes = FloatArray(4 * text.length)
        p.fillBoundingBoxes(TextRange(0, text.length), boxes, 0)
        // Even with the widths of the text dropped (as when a font loads), the boxes of the
        // paragraph come from its own measurements.
        TextMeasurement.clearCaches()
        val again = FloatArray(boxes.size)
        val calls = measureTextCallsDuring { p.fillBoundingBoxes(TextRange(0, text.length), again, 0) }
        assertEquals(0, calls)
        assertTrue(boxes.contentEquals(again))
    }

    @Test
    fun emptyText() {
        val p = paragraph("")
        assertEquals(1, p.lineCount)
        assertTrue(p.height > 0f)
        assertEquals(0f, p.getHorizontalPosition(0, true))
    }

    @Test
    fun wrapsAtWordBoundaries() {
        val single = paragraph("aaaa bbbb cccc")
        val wordWidth = paragraph("aaaa").maxIntrinsicWidth
        val p = paragraph("aaaa bbbb cccc", width = (wordWidth * 1.5f).toInt())
        assertEquals(3, p.lineCount)
        assertEquals(0, p.getLineStart(0))
        assertEquals(5, p.getLineStart(1))
        assertEquals(10, p.getLineStart(2))
        assertEquals(4, p.getLineEnd(0, visibleEnd = true))
        assertTrue(p.height > single.height * 2.5f)
    }

    @Test
    fun breaksLongWords() {
        val wordWidth = paragraph("aaaaaaaaaaaaaaaaaaaa").maxIntrinsicWidth
        val p = paragraph("aaaaaaaaaaaaaaaaaaaa", width = (wordWidth / 3).toInt())
        assertTrue(p.lineCount >= 3, "lineCount = ${p.lineCount}")
        for (line in 0 until p.lineCount - 1) {
            assertEquals(p.getLineEnd(line), p.getLineStart(line + 1))
        }
    }

    @Test
    fun hardLineBreaks() {
        val p = paragraph("a\nb\n")
        assertEquals(3, p.lineCount)
        // Like Skia, the logical end of a line excludes its line feed.
        assertEquals(1, p.getLineEnd(0))
        assertEquals(1, p.getLineEnd(0, visibleEnd = true))
        assertEquals(2, p.getLineStart(1))
        assertEquals(1, p.getLineForOffset(2))
        assertEquals(2, p.getLineForOffset(4))
    }

    @Test
    fun thaiTextWrapsBetweenWords() {
        // Thai has no spaces between words: the breaks come from the browser's dictionaries.
        val hello = "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35" // "hello"
        val polite = "\u0E04\u0E23\u0E31\u0E1A" // a polite particle
        val text = hello + polite
        val helloWidth = paragraph(hello).maxIntrinsicWidth
        val p = paragraph(text, width = (helloWidth + 4f).toInt())
        assertEquals(2, p.lineCount)
        assertEquals(hello.length, p.getLineEnd(0))
        assertTrue(p.minIntrinsicWidth < paragraph(text).maxIntrinsicWidth)
    }

    @Test
    fun maxLinesAndEllipsis() {
        val text = "one two three four five six seven eight nine ten"
        val wordWidth = paragraph("three").maxIntrinsicWidth
        val p = paragraph(
            text,
            width = (wordWidth * 2).toInt(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        assertEquals(2, p.lineCount)
        assertTrue(p.didExceedMaxLines)
        assertTrue(p.isLineEllipsized(1))
        assertFalse(p.isLineEllipsized(0))
    }

    @Test
    fun horizontalPositionsIncreaseInLtrText() {
        val p = paragraph("abcdef")
        var previous = -1f
        for (offset in 0..6) {
            val x = p.getHorizontalPosition(offset, true)
            assertTrue(x > previous, "offset $offset at $x after $previous")
            previous = x
        }
    }

    @Test
    fun offsetForPositionRoundTrips() {
        val p = paragraph("Hello world")
        for (offset in 0..11) {
            val x = p.getHorizontalPosition(offset, true)
            assertEquals(offset, p.getOffsetForPosition(Offset(x + 0.1f, p.height / 2)))
        }
        assertEquals(0, p.getOffsetForPosition(Offset(-100f, 1f)))
        assertEquals(11, p.getOffsetForPosition(Offset(10_000f, 1f)))
    }

    @Test
    fun rtlText() {
        val p = paragraph("\u05E9\u05DC\u05D5\u05DD")
        assertEquals(ResolvedTextDirection.Rtl, p.getParagraphDirection(0))
        assertEquals(ResolvedTextDirection.Rtl, p.getBidiRunDirection(0))
        // In a right-aligned RTL line, the text starts on the right.
        assertTrue(p.getHorizontalPosition(0, true) > p.getHorizontalPosition(4, true))
    }

    @Test
    fun placeholders() {
        val p = paragraph(
            "a b c",
            placeholders = listOf(
                AnnotatedString.Range(
                    Placeholder(20.sp, 10.sp, PlaceholderVerticalAlign.AboveBaseline),
                    2,
                    3,
                )
            ),
        )
        val rect = assertNotNull(p.placeholderRects.single())
        assertEquals(20f, rect.width, 0.01f)
        assertEquals(10f, rect.height, 0.01f)
        assertEquals(p.firstBaseline, rect.bottom, 0.5f)
    }

    /** A placeholder of 20 x 10 pixels replacing `text[start, end)`. */
    private fun placeholderAt(start: Int, end: Int) =
        AnnotatedString.Range(Placeholder(20.sp, 10.sp, PlaceholderVerticalAlign.AboveBaseline), start, end)

    // Like on Android and in SkParagraph, the layout sees an OBJECT REPLACEMENT CHARACTER where a
    // placeholder is, not the text it replaces.

    @Test
    fun aPlaceholderIsANeutralForTheBidiAlgorithm() {
        // A placeholder replacing a Hebrew letter between Latin text and a Hebrew word, in a
        // left-to-right paragraph: as a neutral, it stays before the word, where the letter would
        // have joined the right-to-left run of the word, after it.
        val text = charArrayOf('a', 'b', ' ', Char(0x05D3), ' ', Char(0x05D0), Char(0x05D1)).concatToString()
        val ltr = style.copy(textDirection = TextDirection.Ltr)
        val p = paragraph(text, style = ltr, placeholders = listOf(placeholderAt(3, 4)))
        val placeholder = assertNotNull(p.placeholderRects.single())
        val word = minOf(p.getBoundingBox(5).left, p.getBoundingBox(6).left)
        assertTrue(placeholder.right <= word + 0.5f, "placeholder $placeholder, word from $word")
    }

    @Test
    fun aPlaceholderEndingALineStaysInTheRunAroundIt() {
        // Rule L1 gives the whitespace ending a line the paragraph level, but a placeholder isn't
        // whitespace, whatever it replaces: replacing the space between two Hebrew words, it ends
        // the first line within the right-to-left run of the word, on its left.
        val word = charArrayOf(Char(0x05D0), Char(0x05D1), Char(0x05D2)).concatToString()
        val ltr = style.copy(textDirection = TextDirection.Ltr)
        val width = ceil(paragraph(word, style = ltr).maxIntrinsicWidth).toInt() + 25
        val p = paragraph("$word $word", width = width, style = ltr, placeholders = listOf(placeholderAt(3, 4)))
        assertEquals(2, p.lineCount)
        assertEquals(4, p.getLineEnd(0))
        val placeholder = assertNotNull(p.placeholderRects.single())
        val left = (0 until 3).minOf { p.getBoundingBox(it).left }
        assertTrue(placeholder.right <= left + 0.5f, "placeholder $placeholder, word from $left")
    }

    @Test
    fun aPlaceholderReplacingASpaceDoesntHang() {
        // Ending a line, the placeholder is part of what the line shows, so it has to fit in it.
        val placeholders = listOf(placeholderAt(2, 3))
        assertEquals(3, paragraph("ab ", placeholders = placeholders).getLineEnd(0, visibleEnd = true))
        val width = ceil(paragraph("ab").maxIntrinsicWidth).toInt() + 5
        assertEquals(2, paragraph("ab ", width = width, placeholders = placeholders).lineCount)
    }

    @Test
    fun theSpaceAPlaceholderReplacesTakesNoShareOfJustification() {
        // The middle one of the three spaces is a placeholder: the extra width of the justified
        // first line goes to the other two, so that the line still ends at its edge.
        val text = "mmm   mmm mmm"
        val placeholders = listOf(placeholderAt(4, 5))
        val lineWidth = paragraph("mmm   mmm", placeholders = placeholders).maxIntrinsicWidth
        val width = ceil(lineWidth * 1.15f).toInt()
        val justified = style.copy(textAlign = TextAlign.Justify)
        val p = paragraph(text, width = width, style = justified, placeholders = placeholders)
        assertEquals(2, p.lineCount)
        assertEquals(width.toFloat(), p.getBoundingBox(8).right, 0.5f)
    }

    @Test
    fun aLineTerminatorAPlaceholderReplacesDoesntEndALine() {
        assertEquals(1, paragraph("a\nb", placeholders = listOf(placeholderAt(1, 2))).lineCount)
        assertEquals(1, paragraph("a\n", placeholders = listOf(placeholderAt(1, 2))).lineCount)
        // An ellipsized last line hides the rest of the text, up to the next line terminator.
        val text = "ab x\ny cd ef gh ij"
        val ellipsized = paragraph(
            text,
            width = 60,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            placeholders = listOf(placeholderAt(3, 6)),
        )
        assertEquals(text.length, ellipsized.getLineEnd(0))
    }

    @Test
    fun aPlaceholderAfterACrIsntTakenForTheLfOfACrLf() {
        // The first placeholder replaces an LF after a CR; the second one has to be found after it.
        val p = paragraph("a\r\nbc", placeholders = listOf(placeholderAt(2, 3), placeholderAt(4, 5)))
        assertEquals(2, p.placeholderRects.count { it != null })
    }

    @Test
    fun theLineBreakRulesSeeAnObjectWhereAPlaceholderIs() {
        // An opening parenthesis forbids a break after the spaces following it (UAX #14 rule LB14),
        // an object doesn't: the line breaks after the space following the placeholder.
        val p = paragraph("( ab", width = 30, placeholders = listOf(placeholderAt(0, 1)))
        assertEquals(2, p.lineCount)
        assertEquals(2, p.getLineStart(1))
    }

    @Test
    fun aPlaceholderDoesntDecideTheParagraphDirection() {
        // Like on Android, the first strong character is looked for in the text as laid out: the
        // Latin letter a placeholder replaces at the start of a Hebrew paragraph doesn't count.
        val text = charArrayOf('x', ' ', Char(0x05D0), Char(0x05D1)).concatToString()
        val p = paragraph(text, placeholders = listOf(placeholderAt(0, 1)))
        assertEquals(ResolvedTextDirection.Rtl, p.getParagraphDirection(0))
    }

    @Test
    fun aPlaceholderWiderThanTheLineIsntSplit() {
        // Like a single character too wide for the line, it's laid out whole, on a line of its own.
        val p = paragraph("ab", width = 10, placeholders = listOf(placeholderAt(0, 2)))
        assertEquals(1, p.lineCount)
        assertEquals(2, p.getLineEnd(0))
    }

    @Test
    fun aPlaceholderIsASingleCharacterForItsBoxesAndHitTesting() {
        // The placeholder replaces "ab": both its units are inside it.
        val placeholders = listOf(placeholderAt(1, 3))
        val p = paragraph("xaby", placeholders = placeholders)
        val rect = assertNotNull(p.placeholderRects.single())
        for (offset in 1..2) {
            assertEquals(rect.left, p.getBoundingBox(offset).left, 0.5f, "box of $offset")
            assertEquals(rect.right, p.getBoundingBox(offset).right, 0.5f, "box of $offset")
        }
        val rightHalf = Rect(rect.center.x - 1f, rect.top, rect.right, rect.bottom)
        assertEquals(
            TextRange(1, 3),
            p.getRangeForRect(rightHalf, TextGranularity.Character, TextInclusionStrategy.ContainsCenter),
        )
        // Clicking past a line ending with the placeholder puts the caret before it, as the offset
        // after it is on the next line.
        val width = ceil(paragraph("x").maxIntrinsicWidth).toInt() + 21
        val wrapped = paragraph("xaby", width = width, placeholders = placeholders)
        assertEquals(3, wrapped.getLineEnd(0))
        assertEquals(1, wrapped.getOffsetForPosition(Offset(10_000f, wrapped.getLineBottom(0) / 2)))
    }

    @Test
    fun theEllipsisDoesntCutAPlaceholder() {
        // Room for "a" and the ellipsis, not for the placeholder after "a": it's hidden whole.
        val ellipsis = Char(0x2026).toString()
        val width = ceil(paragraph("a").maxIntrinsicWidth + paragraph(ellipsis).maxIntrinsicWidth).toInt() + 10
        val p = paragraph(
            "abcd efgh ijkl",
            width = width,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            placeholders = listOf(placeholderAt(1, 4)),
        )
        assertEquals(1, p.getLineEnd(0, visibleEnd = true))
    }

    // The offset past a truncated last line is in the text the line hides: the caret goes to the end
    // of what the line shows, not to its start.

    private fun Paragraph.caretAtFirstLine(x: Float) =
        getCursorRect(getOffsetForPosition(Offset(x, getLineBottom(0) / 2))).left

    private fun Paragraph.caretRightOfFirstLine() = caretAtFirstLine(10_000f)

    @Test
    fun clickingPastALastLineCutBeforeALineFeedPutsTheCaretAtItsEnd() {
        for (text in listOf("ab\ncd", "ab\r\ncd")) {
            val clipped = paragraph(text, maxLines = 1)
            assertEquals(clipped.getLineRight(0), clipped.caretRightOfFirstLine(), 0.5f, text)
            assertEquals(clipped.getLineRight(0), clipped.getHorizontalPosition(text.length, true), 0.5f, text)
        }
    }

    @Test
    fun clickingPastTheEllipsisPutsTheCaretAfterIt() {
        val wordWidth = paragraph("three").maxIntrinsicWidth
        val ellipsized = paragraph(
            "one two three four five",
            width = (wordWidth * 2).toInt(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        assertEquals(ellipsized.getLineRight(0), ellipsized.caretRightOfFirstLine(), 0.5f)
        // In a right-to-left paragraph, the ellipsis is on the left.
        val word = charArrayOf(Char(0x05E9), Char(0x05DC), Char(0x05D5), Char(0x05DD)).concatToString()
        val rtl = style.copy(textDirection = TextDirection.Rtl)
        val rtlEllipsized = paragraph(
            List(5) { word }.joinToString(" "),
            width = (paragraph(word, style = rtl).maxIntrinsicWidth * 2).toInt(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = rtl,
        )
        assertEquals(rtlEllipsized.getLineLeft(0), rtlEllipsized.caretAtFirstLine(-100f), 0.5f)
    }

    @Test
    fun aLineShowingOnlyTheEllipsisHasACaretPositionOnEachSide() {
        val ellipsisWidth = paragraph(Char(0x2026).toString()).maxIntrinsicWidth
        val p = paragraph(
            "abcdef ghij",
            width = ceil(ellipsisWidth).toInt() + 2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        assertEquals(0, p.getLineEnd(0, visibleEnd = true))
        assertEquals(p.getLineLeft(0), p.getHorizontalPosition(0, true), 0.5f)
        assertEquals(p.getLineRight(0), p.caretRightOfFirstLine(), 0.5f)
    }

    @Test
    fun aLetterBeforeAPlaceholderDoesntJoinTheTextItReplaces() {
        // An Arabic word before a placeholder ends there, whether the placeholder replaces a Latin
        // letter or an Arabic one, which its last letter would otherwise take a joining form for.
        val word = charArrayOf(Char(0x0628), Char(0x064A)).concatToString()
        fun widthBefore(replaced: Char) =
            paragraph("$word$replaced", placeholders = listOf(placeholderAt(2, 3))).maxIntrinsicWidth
        assertEquals(widthBefore('x'), widthBefore(Char(0x0628)), 0.01f)
    }

    @Test
    fun selectionPathCoversTheRange() {
        val p = paragraph("Hello world")
        val bounds = p.getPathForRange(0, 5).getBounds()
        assertEquals(p.getHorizontalPosition(0, true), bounds.left, 0.5f)
        assertEquals(p.getHorizontalPosition(5, true), bounds.right, 0.5f)
    }

    @Test
    fun caretPositionsInsideArabicWordsFollowTheShapedText() {
        // Arabic letters change shape (and width) with their neighbors: measuring each prefix of a
        // word alone would put the caret inside words at inconsistent, non-monotonic positions.
        val arabic = "\u0645\u062B\u0627\u0644 \u0646\u0635 \u0628\u064A\u062F\u064A"
        val text = "Bidi text example $arabic"
        val p = paragraph(text)
        val runStart = text.length - arabic.length
        var previous = Float.POSITIVE_INFINITY
        for (offset in runStart..text.length) {
            // In the right-to-left run, the caret moves left as the offset grows.
            val x = p.getHorizontalPosition(offset, usePrimaryDirection = true)
            assertTrue(x < previous, "offset $offset at $x, after $previous")
            previous = x
        }
        assertEquals(p.getLineRight(0), p.getHorizontalPosition(runStart, true), 0.5f)
        assertEquals(
            p.getHorizontalPosition(runStart - 1, true) + p.getBoundingBox(runStart - 1).width,
            p.getHorizontalPosition(text.length, true),
            0.5f,
        )
    }

    @Test
    fun clickingPastTheLineEdgesGoesToTheLineEndsInTheParagraphDirection() {
        val arabic = "\u0645\u062B\u0627\u0644 \u0646\u0635"
        // A left-to-right paragraph ending with right-to-left text.
        val text = "abc $arabic"
        val endsRtl = paragraph(text)
        assertEquals(text.length, endsRtl.getOffsetForPosition(Offset(10_000f, 1f)))
        assertEquals(0, endsRtl.getOffsetForPosition(Offset(-100f, 1f)))
        // A left-to-right paragraph starting with right-to-left text.
        val startsRtl = paragraph("$arabic abc", style = style.copy(textDirection = TextDirection.Ltr))
        assertEquals(0, startsRtl.getOffsetForPosition(Offset(-100f, 1f)))
    }

    @Test
    fun clickingPastTheEndOfALineGoesBeforeItsLastCluster() {
        // Not inside the last grapheme cluster of the line, where the caret has nothing to stand
        // next to: neither between the CR and the LF ending it, nor inside the last cluster of a
        // wrapped line (a Thai letter with its vowel, an emoji).
        fun Paragraph.offsetRightOfFirstLine() = getOffsetForPosition(Offset(10_000f, getLineBottom(0) / 2))
        assertEquals(2, paragraph("ab\r\ncd").offsetRightOfFirstLine())
        val hello = charArrayOf(Char(0x0E2A), Char(0x0E27), Char(0x0E31), Char(0x0E2A), Char(0x0E14), Char(0x0E35))
            .concatToString()
        val polite = charArrayOf(Char(0x0E04), Char(0x0E23), Char(0x0E31), Char(0x0E1A)).concatToString()
        val thai = paragraph(hello + polite, width = (paragraph(hello).maxIntrinsicWidth + 4f).toInt())
        assertEquals(hello.length, thai.getLineEnd(0))
        assertEquals(4, thai.offsetRightOfFirstLine())
        val emoji = charArrayOf(Char(0xD83D), Char(0xDE00)).concatToString()
        val emojis = paragraph(emoji.repeat(4), width = ceil(paragraph(emoji.repeat(2)).maxIntrinsicWidth).toInt() + 1)
        assertEquals(4, emojis.getLineEnd(0))
        assertEquals(2, emojis.offsetRightOfFirstLine())
    }

    @Test
    fun rightToLeftTextIsDrawnWithinItsBounds() {
        // Canvas 2D positions right-to-left text by its right end unless told otherwise.
        val text = "\u05E9\u05DC\u05D5\u05DD"
        val width = ceil(paragraph(text).maxIntrinsicWidth).toInt()
        val p = paragraph(text, width = width)
        val image = ImageBitmap(width, ceil(p.height).toInt())
        p.paint(Canvas(image))
        val pixels = image.toPixelMap()
        var inked = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0.5f) inked++
            }
        }
        assertTrue(inked > 10, "inked pixels: $inked")
    }

    @Test
    fun justifiedLinesAreDrawnToTheirEnd() {
        // Three words fit a line, the fourth goes to the last one, which isn't justified.
        val lineWidth = paragraph("mmm mmm mmm").maxIntrinsicWidth
        val width = ceil(lineWidth * 1.3f).toInt()
        val justified = TextStyle(fontSize = 16.sp, textAlign = TextAlign.Justify)
        fun rightmostInked(style: TextStyle): IntArray {
            val p = paragraph("mmm mmm mmm mmm", width = width, style = style)
            assertEquals(2, p.lineCount)
            val image = ImageBitmap(width, ceil(p.height).toInt())
            p.paint(Canvas(image))
            val pixels = image.toPixelMap()
            return IntArray(2) { line ->
                var rightmost = -1
                for (y in p.getLineTop(line).toInt() until p.getLineBottom(line).toInt()) {
                    for (x in 0 until width) if (pixels[x, y].alpha > 0.5f) rightmost = maxOf(rightmost, x)
                }
                rightmost
            }
        }
        val (first, last) = rightmostInked(justified)
        assertTrue(first >= width - 3, "the first line ends at $first of $width")
        assertTrue(last < lineWidth / 2, "the last line ends at $last")
        val (unjustified, _) = rightmostInked(style)
        assertTrue(unjustified < lineWidth + 1, "the first line, not justified, ends at $unjustified")
    }

    @Test
    fun wordBoundary() {
        val p = paragraph("Hello world")
        val range = p.getWordBoundary(7)
        assertEquals(6, range.start)
        assertEquals(11, range.end)
    }
}

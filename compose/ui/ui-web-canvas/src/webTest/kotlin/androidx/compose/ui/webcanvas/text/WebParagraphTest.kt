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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.ResolvedTextDirection
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
    fun wordBoundary() {
        val p = paragraph("Hello world")
        val range = p.getWordBoundary(7)
        assertEquals(6, range.start)
        assertEquals(11, range.end)
    }
}

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

import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Tests the grapheme clusters, which come from the browser's `Intl.Segmenter`, and their
 * approximation for browsers without it, [approximateGraphemeBoundaries].
 */
class GraphemeBoundariesTest {
    private fun cp(vararg codePoints: Int): String = codePointsToString(codePoints)

    @Test
    fun emojiSequences() {
        // Two flags (pairs of regional indicators), and an odd number of regional indicators.
        assertBoundaries(cp(0x1F1FA, 0x1F1F8, 0x1F1EB, 0x1F1F7), 0, 4, 8)
        assertBoundaries(cp(0x1F1FA, 0x1F1F8, 0x1F1EB), 0, 4, 6)
        // Family: MAN ZWJ WOMAN ZWJ GIRL.
        assertBoundaries(cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467), 0, 8)
        // THUMBS UP SIGN with a skin tone modifier.
        assertBoundaries(cp(0x1F44D, 0x1F3FD), 0, 4)
        // Keycap: DIGIT ONE, VARIATION SELECTOR-16, COMBINING ENCLOSING KEYCAP.
        assertBoundaries(cp('1'.code, 0xFE0F, 0x20E3), 0, 3)
        // An emoji followed by a ZWJ and a non-pictographic character breaks after the ZWJ.
        assertBoundaries(cp(0x1F468, 0x200D, 'a'.code), 0, 3, 4)
    }

    @Test
    fun combiningSequencesAndScripts() {
        assertBoundaries(cp('e'.code, 0x301, 0x302, 'x'.code), 0, 3, 4)
        // Latin text only breaks around single characters, except CR LF.
        assertBoundaries("a\r\nb", 0, 1, 3, 4)
        // Cyrillic with a combining acute accent.
        assertBoundaries(cp(0x43C, 0x438, 0x301, 0x440), 0, 1, 3, 4)
        // Hangul jamo L V T form one syllable.
        assertBoundaries(cp(0x1100, 0x1161, 0x11A8), 0, 3)
        // Devanagari conjuncts (GB9c): KA VIRAMA SSA VOWEL SIGN I, and NA MA SA VIRAMA TA E.
        assertBoundaries(cp(0x915, 0x94D, 0x937, 0x93F), 0, 4)
        assertBoundaries(cp(0x928, 0x92E, 0x938, 0x94D, 0x924, 0x947), 0, 1, 2, 6)
        assertBoundaries("", 0)
    }

    @Test
    fun nextAndPreviousBoundaries() {
        val text = "a" + cp(0x1F600) + "b" // a, U+1F600 (2 code units), b
        assertEquals(1, nextGraphemeBoundary(text, 0))
        assertEquals(3, nextGraphemeBoundary(text, 1))
        assertEquals(3, nextGraphemeBoundary(text, 2)) // inside the surrogate pair
        assertEquals(4, nextGraphemeBoundary(text, 3))
        assertEquals(4, nextGraphemeBoundary(text, 4))
        assertEquals(4, nextGraphemeBoundary(text, 10))
        assertEquals(0, nextGraphemeBoundary(text, -3))

        assertEquals(0, previousGraphemeBoundary(text, 0))
        assertEquals(0, previousGraphemeBoundary(text, -1))
        assertEquals(0, previousGraphemeBoundary(text, 1))
        assertEquals(1, previousGraphemeBoundary(text, 2)) // inside the surrogate pair
        assertEquals(1, previousGraphemeBoundary(text, 3))
        assertEquals(3, previousGraphemeBoundary(text, 4))
        assertEquals(4, previousGraphemeBoundary(text, 9))

        // Latin text, whose boundaries aren't computed.
        val latin = "a\r\nb"
        assertEquals(1, nextGraphemeBoundary(latin, 0))
        assertEquals(3, nextGraphemeBoundary(latin, 1))
        assertEquals(3, nextGraphemeBoundary(latin, 2)) // between CR and LF
        assertEquals(1, previousGraphemeBoundary(latin, 3))
        assertEquals(1, previousGraphemeBoundary(latin, 2))
        assertEquals(0, previousGraphemeBoundary(latin, 1))

        assertEquals(0, nextGraphemeBoundary("", 0))
        assertEquals(0, previousGraphemeBoundary("", 0))
    }

    @Test
    fun aTextIsSegmentedOnce() {
        val text = cp(0x43C, 0x438, 0x301, 0x440).repeat(1000)
        val calls = segmentCallsDuring {
            assertEquals(3001, graphemeBoundariesOf(text).size)
            assertEquals(3001, graphemeBoundariesOf(text).size)
            // An equal text is the same text.
            assertEquals(3001, graphemeBoundariesOf(StringBuilder(text).toString()).size)
        }
        assertEquals(1, calls)
    }

    @Test
    fun approximationWithoutIntlSegmenter() {
        // The approximation handles the usual emoji sequences and combining marks.
        val cases = listOf(
            cp(0x1F1FA, 0x1F1F8, 0x1F1EB, 0x1F1F7) to intArrayOf(0, 4, 8),
            cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467) to intArrayOf(0, 8),
            cp(0x1F44D, 0x1F3FD) to intArrayOf(0, 4),
            cp('1'.code, 0xFE0F, 0x20E3) to intArrayOf(0, 3),
            cp(0x1F468, 0x200D, 'a'.code) to intArrayOf(0, 3, 4),
            cp('e'.code, 0x301, 0x302, 'x'.code) to intArrayOf(0, 3, 4),
            cp(0x43C, 0x438, 0x301, 0x440) to intArrayOf(0, 1, 3, 4),
            "a\r\nb" to intArrayOf(0, 1, 3, 4),
            "" to intArrayOf(0),
        )
        for ((text, expected) in cases) {
            assertContentEquals(expected, approximateGraphemeBoundaries(text), "text: $text")
        }
    }

    private fun assertBoundaries(text: String, vararg expected: Int) {
        assertContentEquals(expected, graphemeBoundariesOf(text), "boundaries of $text")
    }
}

/** The grapheme cluster boundaries of [text], as [nextGraphemeBoundary] steps through them. */
internal fun graphemeBoundariesOf(text: String): IntArray {
    val boundaries = mutableListOf(0)
    while (boundaries.last() < text.length) boundaries.add(nextGraphemeBoundary(text, boundaries.last()))
    return boundaries.toIntArray()
}

/** Counts the calls of `Intl.Segmenter.prototype.segment` while [block] runs. */
private fun segmentCallsDuring(block: () -> Unit): Int {
    val spy = spyOnSegment()
    try {
        block()
    } finally {
        stopSpying(spy)
    }
    return callCount(spy)
}

// language=js
private fun spyOnSegment(): JsAny = js(
    """{
        const prototype = Intl.Segmenter.prototype, original = prototype.segment;
        const spy = { count: 0, original: original };
        prototype.segment = function () {
            spy.count++;
            return original.apply(this, arguments);
        };
        return spy;
    }"""
)

// language=js
private fun stopSpying(spy: JsAny): Unit = js("{ Intl.Segmenter.prototype.segment = spy.original; }")

// language=js
private fun callCount(spy: JsAny): Int = js("spy.count")

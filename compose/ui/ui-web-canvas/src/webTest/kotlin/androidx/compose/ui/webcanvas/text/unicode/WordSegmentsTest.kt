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
import kotlin.test.assertTrue

/**
 * Tests [wordSegments] (`Intl.Segmenter` in the browser) and its approximation for browsers without
 * it, [approximateWordSegments]. The expectations hold for both, except for the dictionary-based
 * segmentation of CJK text.
 */
class WordSegmentsTest {
    private fun cp(vararg codePoints: Int): String = codePointsToString(codePoints)

    private val sample = "abc def-ghi. jkl"
    private val sampleBoundaries = intArrayOf(0, 3, 4, 7, 8, 11, 12, 13, 16)
    private val sampleWordLike = listOf(true, false, true, false, true, false, false, true)

    @Test
    fun boundariesAndWordLikeSegments() {
        for (segments in listOf(wordSegments(sample), approximateWordSegments(sample))) {
            assertContentEquals(sampleBoundaries, segments.boundaries)
            assertEquals(sampleBoundaries.size - 1, segments.segmentCount)
            assertEquals(sampleWordLike, List(segments.segmentCount) { segments.isWordLike(it) })
        }
        assertContentEquals(sampleBoundaries, wordBoundaries(sample))
    }

    @Test
    fun wordRangeAtMatchesSkParagraphGetWordBoundary() {
        // Same expectations as SkikoParagraphTest.getWordBoundary for the raw ICU word ranges.
        assertRange(0, 3, wordRangeAt(sample, sample.indexOf('a')))
        assertRange(0, 3, wordRangeAt(sample, sample.indexOf('c')))
        assertRange(3, 4, wordRangeAt(sample, sample.indexOf(' ')))
        assertRange(4, 7, wordRangeAt(sample, sample.indexOf('d')))
        assertRange(8, 11, wordRangeAt(sample, sample.indexOf('i')))
        assertRange(13, 16, wordRangeAt(sample, sample.indexOf('k')))
        // At or beyond the end: an empty range at the end; before the start: (0, 0).
        assertRange(16, 16, wordRangeAt(sample, sample.length))
        assertRange(16, 16, wordRangeAt(sample, sample.length + 5))
        assertRange(0, 0, wordRangeAt(sample, -1))
        assertRange(0, 0, wordRangeAt("", 0))
        val segments = wordSegments(sample)
        assertEquals(4, segments.segmentAt(sample.indexOf('i')))
        assertEquals(0, segments.segmentAt(-1))
        assertEquals(7, segments.segmentAt(100))
    }

    @Test
    fun wordsWithPunctuationAndNumbers() {
        assertWords("can't stop", 0, 5, 6, 10)
        assertWords("3.14 1,000", 0, 4, 5, 10)
        assertWords("hello_world", 0, 11)
        // The default rules keep "e.g" together; the ICU rules of current browsers split it, so
        // this is only checked for the approximation.
        assertContentEquals(intArrayOf(0, 3, 4, 5, 9), approximateWordSegments("e.g. this").boundaries)
    }

    @Test
    fun emojiStayTogether() {
        // THUMBS UP SIGN with a skin tone modifier, a space and a word.
        assertWords(cp(0x1F44D, 0x1F3FD) + " ok", 0, 4, 5, 7)
        // A ZWJ sequence.
        assertWords(cp(0x1F468, 0x200D, 0x1F469) + "!", 0, 5, 6)
    }

    @Test
    fun longText() {
        val text = "word ".repeat(5000)
        assertEquals(10001, wordBoundaries(text).size)
        assertEquals(10001, approximateWordSegments(text).boundaries.size)
        assertContentEquals(intArrayOf(0, 2, 3, 5), approximateWordSegments("ab cd").boundaries)
    }

    @Test
    fun emptyText() {
        assertContentEquals(intArrayOf(0), wordBoundaries(""))
        assertEquals(0, wordSegments("").segmentCount)
        assertEquals(-1, wordSegments("").segmentAt(0))
        assertContentEquals(intArrayOf(0), approximateWordSegments("").boundaries)
    }

    @Test
    fun locales() {
        assertContentEquals(intArrayOf(0, 5, 6, 11), wordBoundaries("hello world", "en-US"))
        assertContentEquals(intArrayOf(0, 5, 6, 11), wordBoundaries("hello world", "de"))
        // An invalid locale falls back to the default one.
        assertContentEquals(intArrayOf(0, 5, 6, 11), wordBoundaries("hello world", "not a locale!"))
    }

    @Test
    fun cjkText() {
        // "Kyou wa yoi tenki desu." in Japanese.
        val text = cp(0x4ECA, 0x65E5, 0x306F, 0x826F, 0x3044, 0x5929, 0x6C17, 0x3067, 0x3059, 0x3002)
        for (segments in listOf(wordSegments(text, "ja"), approximateWordSegments(text))) {
            val boundaries = segments.boundaries
            assertEquals(0, boundaries.first())
            assertEquals(text.length, boundaries.last())
            for (i in 1 until boundaries.size) assertTrue(boundaries[i - 1] < boundaries[i])
            assertTrue(segments.isWordLike(0))
        }
        // The browser's dictionary finds the words; without it, the sentence is one word.
        assertTrue(wordSegments(text, "ja").segmentCount > 2)
        assertEquals(2, approximateWordSegments(text).segmentCount)
    }

    @Test
    fun boundariesAreGraphemeBoundaries() {
        val text = "Ke" + cp(0x301) + "ln, " + cp(0x1F1E9, 0x1F1EA) + " " +
            cp(0x5E9, 0x5DC, 0x5D5, 0x5DD) + " 42.5%"
        val graphemes = graphemeBoundaries(text)
        for (segments in listOf(wordSegments(text), approximateWordSegments(text))) {
            for (boundary in segments.boundaries) {
                assertTrue(boundary in graphemes, "boundary $boundary")
            }
        }
    }

    @Test
    fun packedRanges() {
        val packed = packRange(3, 70000)
        assertEquals(3, packedRangeStart(packed))
        assertEquals(70000, packedRangeEnd(packed))
    }

    private fun assertWords(text: String, vararg expected: Int) {
        assertContentEquals(expected, wordBoundaries(text), "wordBoundaries of $text")
        assertContentEquals(expected, approximateWordSegments(text).boundaries, "approximation of $text")
    }

    private fun assertRange(start: Int, end: Int, packed: Long) {
        assertEquals(start to end, packedRangeStart(packed) to packedRangeEnd(packed))
    }
}

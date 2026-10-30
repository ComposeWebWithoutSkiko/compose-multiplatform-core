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
import kotlin.js.JsString
import kotlin.js.js
import kotlin.js.toJsString

/**
 * Segments [text] into words using the browser's `Intl.Segmenter` with granularity "word" for the
 * BCP 47 [locale] (null for the browser's default locale). Browsers implement it with ICU's word
 * break iterator, including the dictionaries for CJK and South East Asian scripts, so the result
 * matches ICU's `BreakIterator.getWordInstance()` used by SkParagraph.
 *
 * Without `Intl.Segmenter` (see [IntlSegmenters]), words are approximated: runs of letters, marks
 * and digits, possibly joined by apostrophes, periods and colons.
 *
 * The text is segmented with a single JS interop call (the segments are returned to Kotlin encoded
 * in a string).
 */
internal fun wordSegments(text: String, locale: String? = null): WordSegments {
    if (text.isEmpty()) return WordSegments(intArrayOf(0), BooleanArray(0))
    val segmenter = IntlSegmenters.word(locale) ?: return approximateWordSegments(text)
    return decodeWordSegments(segmentWords(segmenter, text.toJsString(), 0, text.length), text.length)
}

/**
 * The word boundaries inside `text[start, end)`, as indexes into [text] (a JS string), found by
 * segmenting that range alone like [wordSegments] does with the browser's default locale; null
 * without `Intl.Segmenter`.
 */
internal fun wordBoundariesInside(text: JsString, start: Int, end: Int): IntArray? {
    val segmenter = IntlSegmenters.word(null) ?: return null
    val boundaries = decodeWordSegments(segmentWords(segmenter, text, start, end), end - start).boundaries
    // The first and the last boundaries are the ends of the range.
    return IntArray(boundaries.size - 2) { start + boundaries[it + 1] }
}

/**
 * The word segmentation of [text] (like [wordSegments]) for browsers without `Intl.Segmenter`:
 * words are runs of letters, marks, digits and connectors, possibly joined by an apostrophe, a
 * period or a colon (as in "don't" or "3.14"), or by a comma or a semicolon between digits (as in
 * "1,000"). Runs of spaces, CR LF, flags and every other character with the characters extending
 * it are segments of their own. Texts without spaces between words (CJK, Thai) aren't segmented
 * into words.
 */
internal fun approximateWordSegments(text: String): WordSegments =
    if (text.isEmpty()) {
        WordSegments(intArrayOf(0), BooleanArray(0))
    } else {
        decodeWordSegments(approximateWords(text), text.length)
    }

/**
 * Returns the word range of [text] at [offset] with the semantics of SkParagraph's
 * `getWordBoundary`: see [WordSegments.wordRangeAt]. The range is packed as `(start.toLong()
 * shl 32) or end`, see [packedRangeStart] and [packedRangeEnd].
 *
 * To look up several offsets of the same text, call [wordSegments] once and use
 * [WordSegments.wordRangeAt].
 */
internal fun wordRangeAt(text: String, offset: Int, locale: String? = null): Long =
    wordSegments(text, locale).wordRangeAt(offset)

/**
 * The word segmentation of a text: its boundaries and whether each segment is "word-like" (contains
 * letters, digits, ideographs, ...) as opposed to whitespace, punctuation or symbols. See
 * [wordSegments].
 */
internal class WordSegments(
    /**
     * All word boundaries in increasing order, including 0 and the text length (just `[0]` for an
     * empty text). Segment `i` is `[boundaries[i], boundaries[i + 1])`. Do not modify.
     */
    val boundaries: IntArray,
    private val wordLike: BooleanArray,
) {
    /** Number of segments. */
    val segmentCount: Int
        get() = boundaries.size - 1

    /** Whether segment [segment] is word-like (like `Intl.Segmenter`'s `isWordLike`). */
    fun isWordLike(segment: Int): Boolean = wordLike[segment]

    /**
     * The word range at [offset] with the semantics of SkParagraph's `getWordBoundary`, which uses
     * ICU's word `BreakIterator`: `start` is the last boundary `<= offset` and `end` the first
     * boundary `> offset`, or `end == start` if there is none (offsets at or beyond the end of the
     * text give `(length, length)`, negative offsets `(0, 0)`). Note that SkParagraph's callers add
     * their own handling of whitespace on top of this. Returned packed as `(start.toLong() shl 32)
     * or end`, see [packedRangeStart] and [packedRangeEnd].
     */
    fun wordRangeAt(offset: Int): Long {
        val b = boundaries
        // Last boundary <= offset (or the first boundary if offset is before it).
        var low = 0
        var high = b.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (b[mid] <= offset) low = mid else high = mid - 1
        }
        val start = b[low]
        val end = if (b[low] <= offset && low + 1 < b.size) b[low + 1] else start
        return packRange(start, end)
    }
}

/** Packs a range like `TextRange`: `(start.toLong() shl 32) or end`. */
internal fun packRange(start: Int, end: Int): Long =
    (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

/** The start of a range packed by [packRange]. */
internal fun packedRangeStart(packed: Long): Int = (packed ushr 32).toInt()

/** The end of a range packed by [packRange]. */
internal fun packedRangeEnd(packed: Long): Int = (packed and 0xFFFFFFFFL).toInt()

/**
 * Decodes the result of [segmentWords]: two characters per segment, holding bits 0-14 and 15-28 of
 * the start index of the segment and, in bit 14 of the second character, `isWordLike`.
 */
private fun decodeWordSegments(encoded: String, textLength: Int): WordSegments {
    val count = encoded.length / 2
    val boundaries = IntArray(count + 1)
    val wordLike = BooleanArray(count)
    for (i in 0 until count) {
        val low = encoded[2 * i].code
        val high = encoded[2 * i + 1].code
        boundaries[i] = low or ((high and 0x3FFF) shl 15)
        wordLike[i] = high and 0x4000 != 0
    }
    boundaries[count] = textLength
    return WordSegments(boundaries, wordLike)
}

/**
 * Segments `text[start, end)` and returns all its segments encoded in a string, with indexes into
 * the range, see [decodeWordSegments]. The character codes stay below 0x8000, so the result
 * contains no surrogates.
 */
// language=js
private fun segmentWords(segmenter: JsAny, text: JsString, start: Int, end: Int): String =
    js(
        """(function() {
    var codes = [];
    var result = '';
    var iterator = segmenter.segment(text.substring(start, end))[Symbol.iterator]();
    for (var step = iterator.next(); !step.done; step = iterator.next()) {
        var index = step.value.index;
        codes.push(index & 0x7FFF, ((index >>> 15) & 0x3FFF) | (step.value.isWordLike ? 0x4000 : 0));
        if (codes.length >= 8192) {
            result += String.fromCharCode.apply(null, codes);
            codes.length = 0;
        }
    }
    return result + String.fromCharCode.apply(null, codes);
})()"""
    )

/** Like [segmentWords], for browsers without `Intl.Segmenter`: see [approximateWordSegments]. */
// language=js
private fun approximateWords(text: String): String =
    js(
        """(function() {
    var pattern = /(?:[\p{L}\p{M}\p{N}\p{Pc}]*\p{N}(?:[,;]\p{N}+)+|[\p{L}\p{M}\p{N}\p{Pc}]+)(?:['\u2019.:](?:[\p{L}\p{M}\p{N}\p{Pc}]*\p{N}(?:[,;]\p{N}+)+|[\p{L}\p{M}\p{N}\p{Pc}]+))*|\r\n|[^\S\r\n]+|\p{RI}\p{RI}|[\s\S](?:[\p{Grapheme_Extend}\p{Emoji_Modifier}]|\u200D\p{Extended_Pictographic}?)*/gu;
    var wordCharacter = /[\p{L}\p{N}]/u;
    var codes = [];
    var result = '';
    var match;
    while ((match = pattern.exec(text)) !== null) {
        var index = match.index;
        var wordLike = wordCharacter.test(match[0]);
        codes.push(index & 0x7FFF, ((index >>> 15) & 0x3FFF) | (wordLike ? 0x4000 : 0));
        if (codes.length >= 8192) {
            result += String.fromCharCode.apply(null, codes);
            codes.length = 0;
        }
    }
    return result + String.fromCharCode.apply(null, codes);
})()"""
    )

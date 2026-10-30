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

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.webcanvas.text.unicode.BidiClass
import androidx.compose.ui.webcanvas.text.unicode.LineBreakClass
import androidx.compose.ui.webcanvas.text.unicode.STRONG_DIRECTION_LTR
import androidx.compose.ui.webcanvas.text.unicode.STRONG_DIRECTION_RTL
import androidx.compose.ui.webcanvas.text.unicode.bidiClass
import androidx.compose.ui.webcanvas.text.unicode.codePointAt
import androidx.compose.ui.webcanvas.text.unicode.encodeLineBreak
import androidx.compose.ui.webcanvas.text.unicode.firstStrongDirection as unicodeFirstStrongDirection
import androidx.compose.ui.webcanvas.text.unicode.isEmojiPresentation
import androidx.compose.ui.webcanvas.text.unicode.isExtendedPictographic
import androidx.compose.ui.webcanvas.text.unicode.lineBreakClass
import androidx.compose.ui.webcanvas.text.unicode.lineBreakPosition
import androidx.compose.ui.webcanvas.text.unicode.lineBreaks as unicodeLineBreaks
import androidx.compose.ui.webcanvas.text.unicode.nextGraphemeBoundary as unicodeNextGraphemeBoundary
import androidx.compose.ui.webcanvas.text.unicode.packedRangeEnd
import androidx.compose.ui.webcanvas.text.unicode.packedRangeStart
import androidx.compose.ui.webcanvas.text.unicode.previousGraphemeBoundary as unicodePreviousGraphemeBoundary
import androidx.compose.ui.webcanvas.text.unicode.wordBoundariesInside
import androidx.compose.ui.webcanvas.text.unicode.wordRangeAt as unicodeWordRangeAt
import kotlin.js.JsString

/** The Unicode algorithms the text layout uses, in the shape the layout needs them. */
internal object TextUnicode {
    const val DirectionLtr = STRONG_DIRECTION_LTR
    const val DirectionRtl = STRONG_DIRECTION_RTL

    /**
     * Whether [text] may contain right-to-left characters or bidi controls: when it doesn't, a
     * left-to-right paragraph has level 0 everywhere and the bidi algorithm can be skipped.
     */
    fun mayNeedBidi(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.code < 0x0590) {
                i++
                continue
            }
            val codePoint = codePointAt(text, i)
            when (bidiClass(codePoint)) {
                BidiClass.R, BidiClass.AL, BidiClass.AN, BidiClass.LRE, BidiClass.LRO,
                BidiClass.RLE, BidiClass.RLO, BidiClass.PDF, BidiClass.LRI, BidiClass.RLI,
                BidiClass.FSI, BidiClass.PDI -> return true
            }
            i += if (codePoint >= 0x10000) 2 else 1
        }
        return false
    }

    /**
     * The line break opportunities of [layoutText] (see [layoutTextOf]), encoded with
     * [encodeLineBreak]. The last one is always a mandatory break at the end of the text. [jsText]
     * is the text as a JS string, which the words of the scripts written without spaces are found
     * in.
     */
    fun lineBreaks(layoutText: CharSequence, jsText: JsString): IntArray {
        val breaks = unicodeLineBreaks(layoutText)
        return if (hasComplexContext(layoutText)) withDictionaryBreaks(layoutText, jsText, breaks) else breaks
    }

    /**
     * Whether [text] has characters of the scripts written without spaces between words (Thai,
     * Lao, Khmer, Myanmar...: line break class SA), which UAX #14 doesn't break by itself.
     */
    private fun hasComplexContext(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            if (text[i].code < FirstComplexContextCharacter) {
                i++
                continue
            }
            val codePoint = codePointAt(text, i)
            if (lineBreakClass(codePoint) == LineBreakClass.SA) return true
            i += if (codePoint >= 0x10000) 2 else 1
        }
        return false
    }

    /**
     * Adds the word boundaries inside runs of complex context characters of [layoutText] to
     * [breaks]: UAX #14 leaves them to dictionary-based segmentation, which the browser's
     * `Intl.Segmenter` does on each run alone, like ICU's dictionaries do (without it, words of these
     * scripts can only be broken where they don't fit).
     */
    private fun withDictionaryBreaks(layoutText: CharSequence, jsText: JsString, breaks: IntArray): IntArray {
        val extra = ArrayList<Int>()
        var position = 0
        while (position < layoutText.length) {
            val runEnd = complexContextRunEnd(layoutText, position)
            if (runEnd == position) {
                position++
                continue
            }
            val boundaries = wordBoundariesInside(jsText, position, runEnd) ?: return breaks
            for (boundary in boundaries) extra.add(encodeLineBreak(boundary, isMandatory = false))
            position = runEnd
        }
        if (extra.isEmpty()) return breaks
        // Merge the two increasing lists, keeping the existing break at a common position.
        val result = IntArray(breaks.size + extra.size)
        var i = 0
        var j = 0
        var count = 0
        while (i < breaks.size || j < extra.size) {
            if (j >= extra.size ||
                (i < breaks.size && lineBreakPosition(breaks[i]) <= lineBreakPosition(extra[j]))
            ) {
                val encoded = breaks[i++]
                if (j < extra.size && lineBreakPosition(extra[j]) == lineBreakPosition(encoded)) j++
                result[count++] = encoded
            } else {
                result[count++] = extra[j++]
            }
        }
        return result.copyOf(count)
    }

    /** The end of the run of complex context characters (class SA) of [text] from [start]. */
    private fun complexContextRunEnd(text: CharSequence, start: Int): Int {
        var end = start
        while (end < text.length) {
            val codePoint = codePointAt(text, end)
            if (lineBreakClass(codePoint) != LineBreakClass.SA) break
            end += if (codePoint >= 0x10000) 2 else 1
        }
        return end
    }

    /** The first character of class SA (U+0E01, Thai). */
    private const val FirstComplexContextCharacter = 0x0E01

    /** The first grapheme cluster boundary after [index]. */
    fun nextGraphemeBoundary(text: CharSequence, index: Int): Int =
        unicodeNextGraphemeBoundary(text, index)

    /** The last grapheme cluster boundary before [index]. */
    fun previousGraphemeBoundary(text: CharSequence, index: Int): Int =
        unicodePreviousGraphemeBoundary(text, index)

    /** The word containing the character at [offset] (see SkParagraph's `getWordBoundary`). */
    fun wordBoundary(text: String, offset: Int): TextRange {
        val packed = unicodeWordRangeAt(text, offset)
        return TextRange(packedRangeStart(packed), packedRangeEnd(packed))
    }

    /** The direction of the first strong character outside of isolates. */
    fun firstStrongDirection(text: CharSequence): Int = unicodeFirstStrongDirection(text)

    fun isEmojiPresentationOrExtendedPictographic(codePoint: Int): Boolean =
        isEmojiPresentation(codePoint) || isExtendedPictographic(codePoint)
}

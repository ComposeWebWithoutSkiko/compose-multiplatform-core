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

import kotlin.math.max

/**
 * The width-independent analysis of a paragraph: its style runs, bidi levels, line break
 * opportunities and the widths of the text between them. Paragraphs of any width are laid out
 * from it (see [LineLayout]).
 */
internal class ParagraphAnalysis(
    val text: String,
    val styled: StyledText,
    /** 0 for a left-to-right paragraph, 1 for a right-to-left one. */
    val paragraphLevel: Int,
) {
    val isRtl: Boolean get() = paragraphLevel == 1

    /** The resolved bidi embedding level of every UTF-16 unit. */
    val levels: ByteArray = computeLevels()

    /**
     * Items: maximal ranges of text drawn as one piece, i.e. with one style, one bidi level and no
     * placeholder or hard line break inside. Stored as start offsets; item `i` spans
     * `itemStarts[i] until itemStarts[i + 1]`.
     */
    val itemStarts: IntArray

    /** The placeholder of each item, if the item is a placeholder. */
    val itemPlaceholders: Array<PlaceholderRun?>

    /** The style of each item. */
    val itemStyles: Array<RunStyle>

    /**
     * Line break opportunities, as positions where a line may start. A position is encoded as
     * `position shl 1`, plus 1 if the break is mandatory (after a line feed).
     */
    val breaks: IntArray

    init {
        val starts = ArrayList<Int>()
        val placeholders = ArrayList<PlaceholderRun?>()
        val styles = ArrayList<RunStyle>()
        var placeholderIndex = 0
        val placeholderRuns = styled.placeholders
        var i = 0
        val length = text.length
        while (i < length) {
            val placeholder = placeholderRuns.getOrNull(placeholderIndex)
            if (placeholder != null && placeholder.start == i) {
                starts.add(i)
                placeholders.add(placeholder)
                styles.add(placeholder.style)
                i = placeholder.end
                placeholderIndex++
                continue
            }
            val run = styled.runAt(i)
            val level = levels[i]
            var end = i + 1
            val limit = minOf(run.end, placeholder?.start ?: length, length)
            if (text[i] == '\n' || text[i] == '\r') {
                // Line terminators are items of their own: they end lines and draw nothing.
                if (text[i] == '\r' && end < length && text[end] == '\n') end++
            } else {
                while (end < limit && levels[end] == level && text[end] != '\n' && text[end] != '\r') {
                    end++
                }
            }
            starts.add(i)
            placeholders.add(null)
            styles.add(run.style)
            i = end
        }
        starts.add(length)
        itemStarts = starts.toIntArray()
        itemPlaceholders = placeholders.toTypedArray()
        itemStyles = styles.toTypedArray()
        breaks = computeBreaks()
    }

    val itemCount: Int get() = itemStarts.size - 1

    fun itemStart(item: Int): Int = itemStarts[item]

    fun itemEnd(item: Int): Int = itemStarts[item + 1]

    fun isLineTerminatorItem(item: Int): Boolean {
        val start = itemStarts[item]
        return itemPlaceholders[item] == null && start < text.length &&
            (text[start] == '\n' || text[start] == '\r')
    }

    /** The index of the item containing [offset] (the last item for the text end). */
    fun itemAt(offset: Int): Int {
        var low = 0
        var high = itemCount - 1
        if (high < 0) return 0
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (itemStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    private fun computeLevels(): ByteArray {
        val length = text.length
        val levels = ByteArray(length) { paragraphLevel.toByte() }
        if (length == 0 || (!TextUnicode.mayNeedBidi(text) && paragraphLevel == 0)) {
            return levels
        }
        // UAX #9 works paragraph by paragraph: line feeds separate paragraphs, which all share
        // the direction of the Compose paragraph.
        var start = 0
        while (start <= length) {
            var end = start
            while (end < length && text[end] != '\n' && text[end] != '\u2029') end++
            if (end > start) {
                val paragraphLevels = TextUnicode.bidiLevels(text, start, end, paragraphLevel)
                paragraphLevels.copyInto(levels, start)
            }
            start = end + 1
        }
        return levels
    }

    private fun computeBreaks(): IntArray {
        val raw = TextUnicode.lineBreaks(text)
        if (styled.placeholders.isEmpty()) return raw
        // No breaks inside placeholders; breaks are allowed around them.
        val result = ArrayList<Int>(raw.size + styled.placeholders.size * 2)
        var p = 0
        val placeholders = styled.placeholders
        for (encoded in raw) {
            val position = encoded ushr 1
            while (p < placeholders.size && placeholders[p].end < position) p++
            val placeholder = placeholders.getOrNull(p)
            if (placeholder != null && position > placeholder.start && position < placeholder.end) {
                continue
            }
            result.add(encoded)
        }
        for (placeholder in placeholders) {
            result.add(placeholder.start shl 1)
            result.add(placeholder.end shl 1)
        }
        result.sort()
        // Merge duplicates, keeping the mandatory flag.
        val merged = ArrayList<Int>(result.size)
        for (encoded in result) {
            val last = merged.lastOrNull()
            if (last != null && (last ushr 1) == (encoded ushr 1)) {
                merged[merged.lastIndex] = last or encoded
            } else if ((encoded ushr 1) > 0) {
                merged.add(encoded)
            }
        }
        return merged.toIntArray()
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement

    /** The width of the text between [start] and [end] (any range), summing the item pieces. */
    fun width(start: Int, end: Int): Float {
        if (start >= end) return 0f
        var width = 0f
        var item = itemAt(start)
        var position = start
        while (position < end && item < itemCount) {
            val itemEnd = itemEnd(item)
            val pieceEnd = minOf(itemEnd, end)
            width += pieceWidth(item, position, pieceEnd)
            position = pieceEnd
            item++
        }
        return width
    }

    /** The width of the part [start]..[end] of [item]. */
    fun pieceWidth(item: Int, start: Int, end: Int): Float {
        val placeholder = itemPlaceholders[item]
        if (placeholder != null) {
            // A placeholder is atomic.
            return if (start <= placeholder.start && end >= placeholder.end) placeholder.width else 0f
        }
        if (isLineTerminatorItem(item)) return 0f
        val style = itemStyles[item]
        return TextMeasurement.width(style.font, text, start, end, levels[start].toInt() and 1 == 1)
    }

    /** Whether the character at [index] is whitespace that hangs at the end of a line. */
    fun isHangingWhitespace(index: Int): Boolean {
        val c = text[index]
        return c == ' ' || c == '\t' || c == '\u3000' || c == '\n' || c == '\r' ||
            (c in '\u2000'..'\u200A' && c != '\u2007') || c == '\u205F' || c == '\u1680'
    }

    /** [end] moved back over hanging whitespace, but not before [start]. */
    fun trimTrailingWhitespace(start: Int, end: Int): Int {
        var e = end
        while (e > start && isHangingWhitespace(e - 1)) e--
        return e
    }

    /** The widest unbreakable piece of text: the minimum width the paragraph can be laid out in. */
    fun minIntrinsicWidth(): Float {
        var result = 0f
        var previous = 0
        for (encoded in breaks) {
            val position = encoded ushr 1
            val visibleEnd = trimTrailingWhitespace(previous, position)
            result = max(result, width(previous, visibleEnd))
            previous = position
        }
        if (previous < text.length) {
            result = max(result, width(previous, trimTrailingWhitespace(previous, text.length)))
        }
        return result
    }

    /** The width of the widest hard line: the width the paragraph needs to avoid wrapping. */
    fun maxIntrinsicWidth(): Float {
        var result = 0f
        var lineStart = 0
        for (encoded in breaks) {
            if (encoded and 1 == 1) {
                val position = encoded ushr 1
                result = max(result, width(lineStart, position))
                lineStart = position
            }
        }
        if (lineStart < text.length) {
            result = max(result, width(lineStart, text.length))
        }
        return result
    }
}

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

import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph
import androidx.compose.ui.webcanvas.text.unicode.encodeLineBreak
import androidx.compose.ui.webcanvas.text.unicode.isLineBreakWhitespace
import androidx.compose.ui.webcanvas.text.unicode.isLineTerminator
import androidx.compose.ui.webcanvas.text.unicode.isMandatoryLineBreak
import androidx.compose.ui.webcanvas.text.unicode.lineBreakPosition
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.max

/**
 * The width-independent analysis of a paragraph: its style runs, bidi levels, line break
 * opportunities and the widths of the text between them. Paragraphs of any width are laid out
 * from it (see [LineLayout]).
 */
internal class ParagraphAnalysis(
    val text: String,
    /** [text] as the paragraph lays it out, which every analysis of its characters reads. */
    val layoutText: CharSequence,
    val styled: StyledText,
    /** 0 for a left-to-right paragraph, 1 for a right-to-left one. */
    val paragraphLevel: Int,
) {
    val isRtl: Boolean get() = paragraphLevel == 1

    /** [text] as a JS string, which the runs measured and drawn are sliced from (see [jsRunOf]). */
    val jsText: JsString by lazy { text.toJsString() }

    /**
     * The bidi embedding level of every UTF-16 unit (see [BidiParagraph.levels]): 0 everywhere in a
     * left-to-right paragraph without right-to-left characters or bidi controls.
     */
    val levels: ByteArray =
        // The text under the placeholders can only make the raw text "need" bidi more often.
        if (paragraphLevel == 0 && !TextUnicode.mayNeedBidi(text)) {
            ByteArray(text.length)
        } else {
            BidiParagraph(layoutText, paragraphLevel).levels
        }

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
     * Line break opportunities, as positions where a line may start, encoded with
     * [encodeLineBreak]: mandatory after a line terminator (see [isLineTerminator]).
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
            if (isLineTerminator(layoutText[i])) {
                // Line terminators are items of their own: they end lines and draw nothing.
                if (layoutText[i] == '\r' && end < length && layoutText[end] == '\n') end++
            } else {
                while (end < limit && levels[end] == level && !isLineTerminator(layoutText[end])) end++
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

    fun itemEnd(item: Int): Int = itemStarts[item + 1]

    fun isLineTerminatorItem(item: Int): Boolean {
        val start = itemStarts[item]
        return itemPlaceholders[item] == null && start < text.length && isLineTerminator(layoutText[start])
    }

    /**
     * [end] moved back over the line terminator ending `text[..end)`, if any (a CR LF being one):
     * the end of the content of a line ending at [end].
     */
    fun contentEnd(end: Int): Int {
        if (end == 0 || !isLineTerminator(layoutText[end - 1])) return end
        return if (layoutText[end - 1] == '\n' && end >= 2 && layoutText[end - 2] == '\r') end - 2 else end - 1
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

    /**
     * The first boundary after [offset] of a cluster of the paragraph: a grapheme cluster of the
     * text, or a placeholder, which is a single cluster whatever text it replaces. The paragraph
     * splits neither. `text.length` at or after the end.
     */
    fun nextClusterBoundary(offset: Int): Int {
        if (offset >= text.length) return text.length
        val item = itemAt(offset)
        itemPlaceholders[item]?.let { return it.end }
        val boundary = TextUnicode.nextGraphemeBoundary(text, offset)
        // A cluster ends where a placeholder starts, whatever its text starts with.
        var next = item + 1
        while (next < itemCount && itemStarts[next] < boundary) {
            if (itemPlaceholders[next] != null) return itemStarts[next]
            next++
        }
        return boundary
    }

    /** The last boundary before [offset] of a cluster (see [nextClusterBoundary]); 0 at the start. */
    fun previousClusterBoundary(offset: Int): Int {
        if (offset <= 0) return 0
        val item = itemAt(offset - 1)
        itemPlaceholders[item]?.let { return it.start }
        val boundary = TextUnicode.previousGraphemeBoundary(text, offset)
        // A cluster starts where a placeholder ends, whatever its text ends with.
        var previous = item - 1
        while (previous >= 0 && itemEnd(previous) > boundary) {
            if (itemPlaceholders[previous] != null) return itemEnd(previous)
            previous--
        }
        return boundary
    }

    private fun computeBreaks(): IntArray {
        val raw = TextUnicode.lineBreaks(layoutText, jsText)
        if (styled.placeholders.isEmpty()) return raw
        // No breaks inside placeholders; breaks are allowed around them.
        val result = ArrayList<Int>(raw.size + styled.placeholders.size * 2)
        var p = 0
        val placeholders = styled.placeholders
        for (encoded in raw) {
            val position = lineBreakPosition(encoded)
            while (p < placeholders.size && placeholders[p].end < position) p++
            val placeholder = placeholders.getOrNull(p)
            if (placeholder != null && position > placeholder.start && position < placeholder.end) {
                continue
            }
            result.add(encoded)
        }
        for (placeholder in placeholders) {
            result.add(encodeLineBreak(placeholder.start, isMandatory = false))
            result.add(encodeLineBreak(placeholder.end, isMandatory = false))
        }
        result.sort()
        // Merge duplicates, keeping the mandatory flag.
        val merged = ArrayList<Int>(result.size)
        for (encoded in result) {
            val last = merged.lastOrNull()
            if (last != null && lineBreakPosition(last) == lineBreakPosition(encoded)) {
                merged[merged.lastIndex] = last or encoded
            } else if (lineBreakPosition(encoded) > 0) {
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

    // The widths of the segments ending at each break, visible or whole (see segmentWidth).
    private val segmentVisibleWidths = FloatArray(breaks.size) { Float.NaN }
    private val segmentWidths = FloatArray(breaks.size) { Float.NaN }

    /**
     * The width of the segment from [start] to the break [breakIndex] (see [breaks]): its visible
     * part if [visible] (see [visibleEnd]). The line
     * breaker asks for the segment of a break from the same start whatever the width it lays the
     * paragraph out in, so the widths are measured once.
     */
    fun segmentWidth(breakIndex: Int, start: Int, visible: Boolean): Float {
        val widths = if (visible) segmentVisibleWidths else segmentWidths
        val cached = widths[breakIndex]
        if (!cached.isNaN()) return cached
        val end = lineBreakPosition(breaks[breakIndex])
        return width(start, if (visible) visibleEnd(start, end) else end)
            .also { widths[breakIndex] = it }
    }

    /** The width of the part [start]..[end] of [item]. */
    fun pieceWidth(item: Int, start: Int, end: Int): Float {
        val placeholder = itemPlaceholders[item]
        if (placeholder != null) {
            // A placeholder is atomic.
            return if (start <= placeholder.start && end >= placeholder.end) placeholder.width else 0f
        }
        if (isLineTerminatorItem(item)) return 0f
        return runWidth(itemStyles[item].font, start, end, levels[start].toInt() and 1 == 1)
    }

    /** The width of `text[start, end)` drawn with [font] in the given direction, as [jsRun] has it. */
    fun runWidth(font: RunFont, start: Int, end: Int, isRtl: Boolean): Float =
        TextMeasurement.width(
            font, text, jsText, start, end, isRtl, joinsBefore(layoutText, start), joinsAfter(layoutText, end)
        )

    /**
     * The run of `text[start, end)` drawn in the given direction, sliced from [jsText] with the
     * context that keeps its letters joined (see [jsRunOf]).
     */
    fun jsRun(start: Int, end: Int, isRtl: Boolean): JsString =
        jsRunOf(jsText, start, end, isRtl, joinsBefore(layoutText, start), joinsAfter(layoutText, end))

    /** Whether `text[start, end)` is only whitespace, which draws nothing. */
    fun isBlank(start: Int, end: Int): Boolean {
        for (i in start until end) if (!layoutText[i].isWhitespace()) return false
        return true
    }

    /** Whether the character at [index] is a space that justification stretches. */
    fun isJustifiableSpace(index: Int): Boolean =
        when (layoutText[index].code) {
            0x0020, // SPACE
            0x00A0, // NO-BREAK SPACE
            0x3000 -> true // IDEOGRAPHIC SPACE
            else -> false
        }

    /** The spaces that justification stretches in `text[start, end)` (see [isJustifiableSpace]). */
    fun countJustifiableSpaces(start: Int, end: Int): Int {
        var count = 0
        for (i in start until end) if (isJustifiableSpace(i)) count++
        return count
    }

    /**
     * The visible end of `text[start, end)` ending a line: [end] moved back over the line
     * terminator (see [contentEnd]) and the whitespace that hangs at the end of a line (see
     * [isLineBreakWhitespace]), but not before [start].
     */
    fun visibleEnd(start: Int, end: Int): Int {
        var e = contentEnd(end).coerceAtLeast(start)
        // Every hanging whitespace character is in the BMP: a code unit is enough.
        while (e > start && isLineBreakWhitespace(layoutText[e - 1].code)) e--
        return e
    }

    /** The widest unbreakable piece of text: the minimum width the paragraph can be laid out in. */
    fun minIntrinsicWidth(): Float {
        var result = 0f
        var previous = 0
        for (encoded in breaks) {
            val position = lineBreakPosition(encoded)
            result = max(result, width(previous, visibleEnd(previous, position)))
            previous = position
        }
        if (previous < text.length) {
            result = max(result, width(previous, visibleEnd(previous, text.length)))
        }
        return result
    }

    /** The width of the widest hard line: the width the paragraph needs to avoid wrapping. */
    fun maxIntrinsicWidth(): Float {
        var result = 0f
        var lineStart = 0
        for (encoded in breaks) {
            if (isMandatoryLineBreak(encoded)) {
                val position = lineBreakPosition(encoded)
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

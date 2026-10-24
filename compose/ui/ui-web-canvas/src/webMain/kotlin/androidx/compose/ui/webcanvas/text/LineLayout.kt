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

@file:OptIn(ExperimentalWasmJsInterop::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.max
import kotlin.math.min

/**
 * A piece of a line drawn at once: text of one item (so one style and one direction), a
 * placeholder, or the ellipsis. Pieces of a line are stored in visual order, left to right.
 */
internal class Piece(
    /** The logical range of the text the piece shows. */
    val start: Int,
    val end: Int,
    val item: Int,
    val style: RunStyle,
    val isRtl: Boolean,
    val width: Float,
    /** The text drawn; it's not `text[start, end)` for the ellipsis. */
    val drawnText: String?,
    val placeholder: PlaceholderRun? = null,
    val isEllipsis: Boolean = false,
    /** [drawnText] with the context that keeps its letters joined (see [contextualSubstring]). */
    private val contextualText: String? = drawnText,
) {
    /** The left edge, in paragraph coordinates. */
    var x: Float = 0f

    /**
     * The space added after every justifiable space in the piece when the line is justified; the
     * piece is then drawn word by word.
     */
    var justifySpacing: Float = 0f

    /** The extra width the justification adds to the piece. */
    var justifyWidth: Float = 0f

    /** The width the piece takes on the line, including the justification. */
    val totalWidth: Float get() = width + justifyWidth

    val right: Float get() = x + width + justifyWidth

    private var jsTextCache: JsString? = null

    /** The drawn text as a run of the piece's direction (see [directionalRun]). */
    val jsText: JsString
        get() = jsTextCache
            ?: directionalRun(contextualText ?: "", isRtl).toJsString().also { jsTextCache = it }

    /** Cached caret positions: `positions[i]` is the advance before the logical offset `start + i`. */
    var caretAdvances: FloatArray? = null
}

/** A line of a laid out paragraph. Vertical metrics are in pixels, ascent and descent positive. */
internal class Line(
    val index: Int,
    val start: Int,
    /** The end of the line content, excluding a trailing line terminator. */
    val end: Int,
    /** The end excluding trailing whitespace. */
    val visibleEnd: Int,
    /** The end including a trailing line terminator: the start of the next line. */
    val endIncludingNewline: Int,
    val isHardBreak: Boolean,
    val pieces: List<Piece>,
    val isEllipsized: Boolean,
) {
    var top = 0f
    var ascent = 0f
    var descent = 0f
    val baseline: Float get() = top + ascent
    val bottom: Float get() = top + ascent + descent
    val height: Float get() = ascent + descent

    /** The left edge of the visible content. */
    var left = 0f

    /** The width of the visible content (without trailing whitespace). */
    var width = 0f

    val right: Float get() = left + width
}

/**
 * Breaks a paragraph into lines of at most [maxWidth] and positions them: the width-dependent
 * part of the layout.
 */
internal class LineLayout(
    private val analysis: ParagraphAnalysis,
    private val maxWidth: Float,
    private val maxLines: Int,
    /** Whether to end the last line with an ellipsis when the text doesn't fit [maxLines]. */
    private val ellipsize: Boolean,
    private val textAlign: TextAlign,
    private val textIndent: TextIndent?,
    private val indentFirstLine: Float,
    private val indentRestLines: Float,
    private val lineHeightStyle: LineHeightStyle?,
    /** Whether a line height was set on the paragraph. */
    private val hasLineHeight: Boolean,
) {
    private val text = analysis.text
    val lines: List<Line>
    var didExceedMaxLines = false
        private set

    val height: Float
        get() = lines.lastOrNull()?.bottom ?: 0f

    fun firstIndent(): Float = indentFirstLine

    fun restIndent(): Float = indentRestLines

    /** The width of the paragraph used for alignment. */
    private val alignWidth: Float = if (maxWidth.isFinite() && maxWidth < LargeWidth) maxWidth else -1f

    init {
        lines = breakLines()
        computeVerticalMetrics()
    }

    private class LineRange(
        val start: Int,
        val end: Int,
        val endIncludingNewline: Int,
        val isHardBreak: Boolean,
    )

    private fun availableWidth(lineIndex: Int): Float {
        val indent = if (lineIndex == 0) indentFirstLine else indentRestLines
        return max(0f, maxWidth - indent)
    }

    private fun breakLines(): List<Line> {
        val ranges = ArrayList<LineRange>()
        val breaks = analysis.breaks
        val length = text.length
        var lineStart = 0
        var lineWidth = 0f
        var segmentStart = 0
        var breakIndex = 0
        var exceeded = false

        fun emit(end: Int, endIncludingNewline: Int, isHardBreak: Boolean): Boolean {
            if (ranges.size >= maxLines) {
                exceeded = true
                return false
            }
            ranges.add(LineRange(lineStart, end, endIncludingNewline, isHardBreak))
            lineStart = endIncludingNewline
            lineWidth = 0f
            return true
        }

        loop@ while (breakIndex < breaks.size) {
            val encoded = breaks[breakIndex]
            val position = encoded ushr 1
            val mandatory = encoded and 1 == 1
            if (position <= segmentStart && position != length) {
                breakIndex++
                continue
            }
            val segmentEnd = position
            val visibleEnd = analysis.trimTrailingWhitespace(segmentStart, segmentEnd)
            val segmentVisibleWidth = analysis.width(segmentStart, visibleEnd)
            val available = availableWidth(ranges.size)
            if (lineWidth + segmentVisibleWidth > available + Epsilon && segmentStart > lineStart) {
                // The segment doesn't fit: end the line at the previous opportunity.
                val end = segmentStart
                if (!emit(contentEnd(end), end, isHardBreak = false)) break@loop
                lineWidth = 0f
            }
            if (segmentVisibleWidth > availableWidth(ranges.size) + Epsilon &&
                segmentStart == lineStart
            ) {
                // A single segment wider than the line: break it between grapheme clusters.
                var start = segmentStart
                while (true) {
                    val fit = fitGraphemes(start, visibleEnd, availableWidth(ranges.size))
                    if (fit >= visibleEnd) break
                    if (!emit(fit, fit, isHardBreak = false)) break@loop
                    start = fit
                }
                lineWidth = analysis.width(start, segmentEnd)
            } else {
                lineWidth += analysis.width(segmentStart, segmentEnd)
            }
            segmentStart = segmentEnd
            if (mandatory) {
                val end = contentEnd(position)
                if (!emit(end, position, isHardBreak = true)) break@loop
            }
            breakIndex++
        }
        if (!exceeded) {
            if (lineStart < length || ranges.isEmpty() || (length > 0 && isLineTerminator(length - 1))) {
                if (ranges.size < maxLines) {
                    ranges.add(LineRange(lineStart, length, length, isHardBreak = false))
                } else {
                    exceeded = lineStart < length
                }
            }
        }
        didExceedMaxLines = exceeded
        val result = ArrayList<Line>(ranges.size)
        for (i in ranges.indices) {
            val range = ranges[i]
            val isLast = i == ranges.lastIndex
            val ellipsizeLine = isLast && exceeded && ellipsize
            result.add(buildLine(i, range, ellipsizeLine, isLastInParagraph = range.isHardBreak || isLast))
        }
        return result
    }

    private fun isLineTerminator(index: Int): Boolean = text[index] == '\n' || text[index] == '\r'

    /** [end] moved back over a line terminator ending the line. */
    private fun contentEnd(end: Int): Int {
        var e = end
        if (e > 0 && text[e - 1] == '\n') e--
        if (e > 0 && text[e - 1] == '\r') e--
        return e
    }

    /**
     * The largest grapheme boundary in [start]..[end] such that the text from [start] to it fits in
     * [available], but at least the first cluster: every line shows something.
     */
    private fun fitGraphemes(start: Int, end: Int, available: Float): Int {
        val boundaries = graphemeBoundariesBetween(start, end)
        // The width of the text before a boundary grows with the boundary: binary search.
        var low = 0 // fits (always place the first cluster)
        var high = boundaries.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (analysis.width(start, boundaries[mid]) <= available + Epsilon) low = mid else high = mid - 1
        }
        return boundaries[low]
    }

    /** The grapheme boundaries after [start], up to and including [end]. */
    private fun graphemeBoundariesBetween(start: Int, end: Int): IntArray {
        val result = ArrayList<Int>()
        var position = start
        while (position < end) {
            position = TextUnicode.nextGraphemeBoundary(text, position).coerceAtMost(end)
            result.add(position)
        }
        if (result.isEmpty()) result.add(end)
        return result.toIntArray()
    }

    private fun buildLine(index: Int, range: LineRange, ellipsizeLine: Boolean, isLastInParagraph: Boolean): Line {
        var end = range.end
        var lineEnd = range.end
        var lineEndIncludingNewline = range.endIncludingNewline
        val logicalPieces = ArrayList<Piece>()
        var ellipsisPiece: Piece? = null
        if (ellipsizeLine) {
            val ellipsisStyle = analysis.styled.runAt(max(range.start, end - 1).coerceAtMost(max(0, text.length - 1))).style
            val ellipsisWidth = TextMeasurement.width(ellipsisStyle.font, Ellipsis)
            val available = availableWidth(index) - ellipsisWidth
            // Like Android, the last line shows as much of the remaining text (up to the next hard
            // break) as fits together with the ellipsis.
            var remainingEnd = range.start
            while (remainingEnd < text.length && !isLineTerminator(remainingEnd)) remainingEnd++
            // The rest of the text is hidden in this line.
            lineEnd = remainingEnd
            lineEndIncludingNewline = text.length
            end = analysis.trimTrailingWhitespace(range.start, remainingEnd)
            if (analysis.width(range.start, end) > available + Epsilon) {
                val boundaries = graphemeBoundariesBetween(range.start, end)
                var low = -1 // -1: nothing fits
                var high = boundaries.size - 1
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (analysis.width(range.start, boundaries[mid]) <= available + Epsilon) {
                        low = mid
                    } else {
                        high = mid - 1
                    }
                }
                end = if (low >= 0) boundaries[low] else range.start
            }
            end = analysis.trimTrailingWhitespace(range.start, end)
            ellipsisPiece = Piece(
                start = end,
                end = end,
                item = analysis.itemAt(max(range.start, end - 1)),
                style = ellipsisStyle,
                isRtl = analysis.isRtl,
                width = ellipsisWidth,
                drawnText = Ellipsis,
                isEllipsis = true,
            )
        }

        // Logical pieces: items clipped to the line.
        if (end > range.start) {
            var item = analysis.itemAt(range.start)
            var position = range.start
            while (position < end && item < analysis.itemCount) {
                val itemEnd = min(analysis.itemEnd(item), end)
                if (!analysis.isLineTerminatorItem(item) && itemEnd > position) {
                    val placeholder = analysis.itemPlaceholders[item]
                    val style = analysis.itemStyles[item]
                    val isRtl = analysis.levels[position].toInt() and 1 == 1
                    logicalPieces.add(
                        Piece(
                            start = position,
                            end = itemEnd,
                            item = item,
                            style = style,
                            isRtl = isRtl,
                            width = analysis.pieceWidth(item, position, itemEnd),
                            drawnText = if (placeholder == null) text.substring(position, itemEnd) else null,
                            placeholder = placeholder,
                            contextualText = if (placeholder == null) {
                                contextualSubstring(text, position, itemEnd)
                            } else {
                                null
                            },
                        )
                    )
                }
                position = itemEnd
                item++
            }
        }

        val visibleEnd = analysis.trimTrailingWhitespace(range.start, end)
        val pieces = reorder(logicalPieces, visibleEnd, ellipsisPiece)

        val line = Line(
            index = index,
            start = range.start,
            end = lineEnd,
            visibleEnd = if (ellipsizeLine) end else visibleEnd,
            endIncludingNewline = lineEndIncludingNewline,
            isHardBreak = range.isHardBreak,
            pieces = pieces,
            isEllipsized = ellipsizeLine,
        )
        positionPieces(line, isLastInParagraph)
        return line
    }

    /**
     * Orders the pieces of a line visually (UAX #9 rules L1 and L2). Trailing whitespace is moved
     * to the paragraph level, so it hangs at the end of the line in the paragraph direction.
     */
    private fun reorder(logical: List<Piece>, visibleEnd: Int, ellipsis: Piece?): List<Piece> {
        val paragraphLevel = analysis.paragraphLevel
        // Split pieces containing the start of the trailing whitespace, so that it can be moved.
        val pieces = ArrayList<Piece>(logical.size + 2)
        val levels = ArrayList<Int>(logical.size + 2)
        for (piece in logical) {
            if (piece.placeholder == null && piece.start < visibleEnd && piece.end > visibleEnd) {
                pieces.add(piece.slice(piece.start, visibleEnd))
                levels.add(analysis.levels[piece.start].toInt())
                pieces.add(piece.slice(visibleEnd, piece.end, isRtl = paragraphLevel == 1))
                levels.add(paragraphLevel)
            } else if (piece.start >= visibleEnd && piece.placeholder == null) {
                pieces.add(if (piece.isRtl == (paragraphLevel == 1)) piece else piece.withDirection(paragraphLevel == 1))
                levels.add(paragraphLevel)
            } else {
                pieces.add(piece)
                levels.add(analysis.levels[piece.start].toInt())
            }
        }
        if (ellipsis != null) {
            pieces.add(ellipsis)
            levels.add(paragraphLevel)
        }
        if (pieces.size <= 1) return pieces
        var highest = 0
        var lowestOdd = Int.MAX_VALUE
        for (level in levels) {
            highest = max(highest, level)
            if (level and 1 == 1) lowestOdd = min(lowestOdd, level)
        }
        if (highest == 0) return pieces
        // L2: from the highest level down to the lowest odd level, reverse every maximal sequence
        // of pieces at that level or higher.
        val order = IntArray(pieces.size) { it }
        var level = highest
        while (level >= lowestOdd && level > 0) {
            var i = 0
            while (i < order.size) {
                if (levels[order[i]] >= level) {
                    var j = i
                    while (j + 1 < order.size && levels[order[j + 1]] >= level) j++
                    order.reverse(i, j + 1)
                    i = j + 1
                } else {
                    i++
                }
            }
            level--
        }
        return List(pieces.size) { pieces[order[it]] }
    }

    private fun Piece.slice(from: Int, to: Int, isRtl: Boolean = this.isRtl): Piece = Piece(
        start = from,
        end = to,
        item = item,
        style = style,
        isRtl = isRtl,
        width = analysis.pieceWidth(item, from, to),
        drawnText = text.substring(from, to),
        contextualText = contextualSubstring(text, from, to),
    )

    private fun Piece.withDirection(isRtl: Boolean): Piece = Piece(
        start = start,
        end = end,
        item = item,
        style = style,
        isRtl = isRtl,
        width = width,
        drawnText = drawnText,
        placeholder = placeholder,
        isEllipsis = isEllipsis,
        contextualText = if (drawnText != null && placeholder == null && !isEllipsis) {
            contextualSubstring(text, start, end)
        } else {
            drawnText
        },
    )

    /** Computes the x positions of the pieces of [line], aligning and justifying the line. */
    private fun positionPieces(line: Line, isLastInParagraph: Boolean) {
        val pieces = line.pieces
        var contentWidth = 0f
        var trailingWidth = 0f
        for (piece in pieces) {
            if (piece.placeholder == null && !piece.isEllipsis && piece.start >= line.visibleEnd) {
                trailingWidth += piece.width
            } else {
                contentWidth += piece.width
            }
        }
        val isRtl = analysis.isRtl
        val indent = if (line.index == 0) indentFirstLine else indentRestLines
        val available = if (alignWidth >= 0f) max(0f, alignWidth - indent) else contentWidth
        val free = available - contentWidth
        val alignment = when (textAlign) {
            TextAlign.Left -> 0f
            TextAlign.Right -> free
            TextAlign.Center -> free / 2
            TextAlign.End -> if (isRtl) 0f else free
            else -> if (isRtl) free else 0f // Start, Justify and Unspecified
        }
        // The indent is on the start side of the line.
        var left = (if (isRtl) 0f else indent) + alignment
        line.left = left
        line.width = contentWidth

        val justify = textAlign == TextAlign.Justify && !isLastInParagraph && free > 0f && alignWidth >= 0f
        val spaceExtra = if (justify) {
            val spaces = countJustifiableSpaces(line)
            if (spaces > 0) free / spaces else 0f
        } else {
            0f
        }
        if (justify && spaceExtra > 0f) {
            line.left = if (isRtl) 0f else indent
            line.width = available
            left = line.left
        }

        // Trailing whitespace hangs: on the right of LTR lines, on the left of RTL ones.
        var x = if (isRtl) left - trailingWidth else left
        for (piece in pieces) {
            piece.x = x
            if (spaceExtra > 0f && piece.placeholder == null && !piece.isEllipsis &&
                piece.start < line.visibleEnd
            ) {
                piece.justifySpacing = spaceExtra
                piece.justifyWidth =
                    spaceExtra * countSpaces(piece.start, min(piece.end, line.visibleEnd))
            }
            x += piece.totalWidth
        }
    }

    private fun countJustifiableSpaces(line: Line): Int = countSpaces(line.start, line.visibleEnd)

    private fun countSpaces(start: Int, end: Int): Int {
        var count = 0
        for (i in start until end) {
            if (text[i] == ' ' || text[i] == '\u00A0' || text[i] == '\u3000') count++
        }
        return count
    }

    /** Computes the ascent and descent of every line, then stacks the lines. */
    private fun computeVerticalMetrics() {
        val defaultStyle = analysis.styled.defaultStyle
        val defaultMetrics = TextMeasurement.metrics(defaultStyle.font)
        // Like SkParagraph: a line height only shrinks or grows the first ascent and the last
        // descent when the style doesn't trim them.
        val trim = lineHeightStyle?.trim ?: LineHeightStyle.Trim.Both
        val trimFirst: Boolean
        val trimLast: Boolean
        if (hasLineHeight && (defaultStyle.lineHeight ?: 0f) > defaultStyle.fontSize) {
            trimFirst = trim.trimsFirstLineTop
            trimLast = trim.trimsLastLineBottom
        } else {
            trimFirst = hasLineHeight
            trimLast = hasLineHeight
        }

        var top = 0f
        for (line in lines) {
            var ascent = 0f
            var descent = 0f
            var hasText = false
            // Text first: placeholders align relative to the text.
            for (piece in line.pieces) {
                if (piece.placeholder != null) continue
                val (a, d) = styleAscentDescent(piece.style)
                ascent = max(ascent, a)
                descent = max(descent, d)
                hasText = true
            }
            if (!hasText) {
                val style = if (line.start < text.length) analysis.styled.runAt(line.start).style
                else analysis.styled.runAt(max(0, text.length - 1)).style
                val (a, d) = styleAscentDescent(style)
                ascent = a
                descent = d
            }
            val textAscent = ascent
            val textDescent = descent
            for (piece in line.pieces) {
                val placeholder = piece.placeholder ?: continue
                val (a, d) = placeholderAscentDescent(placeholder, textAscent, textDescent)
                ascent = max(ascent, a)
                descent = max(descent, d)
            }
            if (line.index == 0 && trimFirst && hasLineHeight) {
                ascent = -defaultMetrics.ascent
            }
            if (line.index == lines.lastIndex && trimLast && hasLineHeight) {
                descent = defaultMetrics.descent
            }
            line.top = top
            line.ascent = ascent
            line.descent = descent
            top += ascent + descent
        }
    }

    /** The ascent and descent (positive) of text in [style], with its line height applied. */
    internal fun styleAscentDescent(style: RunStyle): Pair<Float, Float> {
        val metrics = TextMeasurement.metrics(style.font)
        var ascent = metrics.ascent // negative
        var descent = metrics.descent
        val lineHeight = style.lineHeight
        if (lineHeight != null) {
            val intrinsic = descent - ascent
            if (intrinsic > 0f) {
                if (style.topRatio in 0f..1f) {
                    val extra = lineHeight - intrinsic
                    ascent -= extra * style.topRatio
                    descent += extra * (1f - style.topRatio)
                } else {
                    val multiplier = lineHeight / intrinsic
                    ascent *= multiplier
                    descent *= multiplier
                }
            }
        }
        val shift = baselineShiftOf(style)
        ascent += shift
        descent += shift
        return Pair(-ascent, descent)
    }

    private fun placeholderAscentDescent(
        placeholder: PlaceholderRun,
        textAscent: Float,
        textDescent: Float,
    ): Pair<Float, Float> {
        val height = placeholder.height
        return when (placeholder.verticalAlign) {
            PlaceholderVerticalAlign.AboveBaseline -> Pair(height, 0f)
            PlaceholderVerticalAlign.TextTop, PlaceholderVerticalAlign.Top ->
                Pair(textAscent, height - textAscent)
            PlaceholderVerticalAlign.TextBottom, PlaceholderVerticalAlign.Bottom ->
                Pair(height - textDescent, textDescent)
            else -> {
                // Centered on the text.
                val center = (textDescent - textAscent) / 2
                Pair(height / 2 - center, height / 2 + center)
            }
        }
    }

    /** The top of a placeholder relative to the baseline of [line]. */
    fun placeholderTop(placeholder: PlaceholderRun, line: Line): Float {
        val textMetrics = lineTextMetrics(line)
        val (ascent, _) = placeholderAscentDescent(placeholder, textMetrics.first, textMetrics.second)
        return line.baseline - ascent
    }

    private fun lineTextMetrics(line: Line): Pair<Float, Float> {
        var ascent = 0f
        var descent = 0f
        var hasText = false
        for (piece in line.pieces) {
            if (piece.placeholder != null) continue
            val (a, d) = styleAscentDescent(piece.style)
            ascent = max(ascent, a)
            descent = max(descent, d)
            hasText = true
        }
        if (!hasText) {
            val (a, d) = styleAscentDescent(analysis.styled.defaultStyle)
            return Pair(a, d)
        }
        return Pair(ascent, descent)
    }

    companion object {
        const val Ellipsis = "\u2026"
        private const val Epsilon = 0.01f
        private const val LargeWidth = 1e7f
    }
}

/** The vertical shift of the baseline of [style]: negative moves the text up. */
internal fun baselineShiftOf(style: RunStyle): Float {
    val shift = style.baselineShift ?: return 0f
    if (shift.multiplier == 0f) return 0f
    return shift.multiplier * TextMeasurement.metrics(style.font).ascent
}

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

import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.webcanvas.text.unicode.BidiParagraph
import androidx.compose.ui.webcanvas.text.unicode.isLineTerminator
import androidx.compose.ui.webcanvas.text.unicode.isMandatoryLineBreak
import androidx.compose.ui.webcanvas.text.unicode.lineBreakPosition
import androidx.compose.ui.webcanvas.text.unicode.trailingWhitespaceStart
import kotlin.js.JsString
import kotlin.math.max
import kotlin.math.min

/**
 * A piece of a line drawn at once: text of one item (so one style and one direction), a
 * placeholder, or the ellipsis. Pieces of a line are stored in visual order, left to right.
 */
internal class Piece(
    /** The logical range of the text the piece shows: none for the ellipsis. */
    val start: Int,
    val end: Int,
    val item: Int,
    val style: RunStyle,
    val isRtl: Boolean,
    val width: Float,
    /** The paragraph whose text the piece shows. */
    private val analysis: ParagraphAnalysis,
    val placeholder: PlaceholderRun? = null,
    val isEllipsis: Boolean = false,
) {
    /** Whether the piece shows text: the ellipsis, or a part of the paragraph's text. */
    val hasText: Boolean get() = placeholder == null && (isEllipsis || end > start)

    /** Whether the piece draws text: it [hasText] that isn't only whitespace. */
    val drawsText: Boolean get() = hasText && (isEllipsis || !analysis.isBlank(start, end))

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

    /** The text drawn, as a run of the piece's direction (see [jsRunOf]). */
    val jsText: JsString
        get() = jsTextCache ?: (
            if (isEllipsis) directionalRun(LineLayout.Ellipsis, isRtl) else analysis.jsRun(start, end, isRtl)
            ).also { jsTextCache = it }

    /** The words of the piece drawn one by one when the line is justified, once drawn. */
    var justifiedWords: List<JustifiedWord>? = null

    /**
     * The caret positions measured in the piece: `caretAdvances[i]` is the advance before the
     * logical offset `start + i`, NaN until measured (see `WebParagraph.advanceInPiece`).
     */
    var caretAdvances: FloatArray? = null
}

/**
 * A word of a justified piece, drawn on its own (see [Piece.justifySpacing]): [advance] from the
 * start of the piece, of [width], drawn as [jsText].
 */
internal class JustifiedWord(val advance: Float, val width: Float, val jsText: JsString)

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
    private val layoutText = analysis.layoutText
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
            val position = lineBreakPosition(encoded)
            val mandatory = isMandatoryLineBreak(encoded)
            if (position <= segmentStart && position != length) {
                breakIndex++
                continue
            }
            val segmentEnd = position
            val segmentVisibleWidth = analysis.segmentWidth(breakIndex, segmentStart, visible = true)
            val available = availableWidth(ranges.size)
            if (lineWidth + segmentVisibleWidth > available + Epsilon && segmentStart > lineStart) {
                // The segment doesn't fit: end the line at the previous opportunity.
                val end = segmentStart
                if (!emit(analysis.contentEnd(end), end, isHardBreak = false)) break@loop
                lineWidth = 0f
            }
            if (segmentVisibleWidth > availableWidth(ranges.size) + Epsilon &&
                segmentStart == lineStart
            ) {
                // A single segment wider than the line: break it between clusters.
                val visibleEnd = analysis.visibleEnd(segmentStart, segmentEnd)
                var start = segmentStart
                while (true) {
                    val fit = fitClusters(start, visibleEnd, availableWidth(ranges.size))
                    if (fit >= visibleEnd) break
                    if (!emit(fit, fit, isHardBreak = false)) break@loop
                    start = fit
                }
                lineWidth = analysis.width(start, segmentEnd)
            } else {
                lineWidth += analysis.segmentWidth(breakIndex, segmentStart, visible = false)
            }
            segmentStart = segmentEnd
            if (mandatory) {
                if (!emit(analysis.contentEnd(position), position, isHardBreak = true)) break@loop
            }
            breakIndex++
        }
        if (!exceeded) {
            if (lineStart < length || ranges.isEmpty() || (length > 0 && isLineTerminator(layoutText[length - 1]))) {
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

    /**
     * The largest cluster boundary (see [ParagraphAnalysis.nextClusterBoundary]) in [start]..[end]
     * such that the text from [start] to it fits in [available], but at least the first cluster:
     * every line shows something.
     */
    private fun fitClusters(start: Int, end: Int, available: Float): Int {
        val boundaries = clusterBoundariesBetween(start, end)
        // The width of the text before a boundary grows with the boundary: binary search.
        var low = 0 // fits (always place the first cluster)
        var high = boundaries.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (analysis.width(start, boundaries[mid]) <= available + Epsilon) low = mid else high = mid - 1
        }
        return boundaries[low]
    }

    /** The cluster boundaries after [start], up to and including [end]. */
    private fun clusterBoundariesBetween(start: Int, end: Int): IntArray {
        val result = ArrayList<Int>()
        var position = start
        while (position < end) {
            position = analysis.nextClusterBoundary(position).coerceAtMost(end)
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
            while (remainingEnd < text.length && !isLineTerminator(layoutText[remainingEnd])) remainingEnd++
            // The rest of the text is hidden in this line.
            lineEnd = remainingEnd
            lineEndIncludingNewline = text.length
            end = analysis.visibleEnd(range.start, remainingEnd)
            if (analysis.width(range.start, end) > available + Epsilon) {
                val boundaries = clusterBoundariesBetween(range.start, end)
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
            end = analysis.visibleEnd(range.start, end)
            ellipsisPiece = Piece(
                start = end,
                end = end,
                item = analysis.itemAt(max(range.start, end - 1)),
                style = ellipsisStyle,
                isRtl = analysis.isRtl,
                width = ellipsisWidth,
                analysis = analysis,
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
                            analysis = analysis,
                            placeholder = placeholder,
                        )
                    )
                }
                position = itemEnd
                item++
            }
        }

        val visibleEnd = analysis.visibleEnd(range.start, end)
        val trailingStart = trailingWhitespaceStart(layoutText, range.start, end)
        val pieces = reorder(logicalPieces, trailingStart, visibleEnd, ellipsisPiece)

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
     * Orders the pieces of a line visually (UAX #9 rule L2). The separators and whitespace ending
     * the line, from [trailingStart], take the paragraph level (rule L1), so that they hang at the
     * end of the line in the paragraph direction. Pieces are cut there, and at [visibleEnd] where
     * the hanging whitespace starts (see [positionPieces]); [trailingStart] is never after it.
     */
    private fun reorder(
        logical: List<Piece>,
        trailingStart: Int,
        visibleEnd: Int,
        ellipsis: Piece?,
    ): List<Piece> {
        val paragraphLevel = analysis.paragraphLevel
        fun levelAt(start: Int): Int =
            if (start >= trailingStart) paragraphLevel else analysis.levels[start].toInt()
        // The part [from, to) of [piece], in the direction of its level.
        fun part(piece: Piece, from: Int, to: Int): Piece {
            val isRtl = levelAt(from) and 1 == 1
            return when {
                from != piece.start || to != piece.end -> piece.slice(from, to, isRtl)
                piece.isRtl != isRtl -> piece.withDirection(isRtl)
                else -> piece
            }
        }
        val cuts = intArrayOf(trailingStart, visibleEnd)
        val pieces = ArrayList<Piece>(logical.size + 3)
        for (piece in logical) {
            if (piece.placeholder != null) {
                pieces.add(piece)
                continue
            }
            var from = piece.start
            for (cut in cuts) {
                if (cut > from && cut < piece.end) {
                    pieces.add(part(piece, from, cut))
                    from = cut
                }
            }
            pieces.add(part(piece, from, piece.end))
        }
        if (ellipsis != null) pieces.add(ellipsis)
        val levels = ByteArray(pieces.size) {
            val piece = pieces[it]
            (if (piece.isEllipsis) paragraphLevel else levelAt(piece.start)).toByte()
        }
        val order = BidiParagraph.reorderVisually(levels) ?: return pieces
        return List(pieces.size) { pieces[order[it]] }
    }

    private fun Piece.slice(from: Int, to: Int, isRtl: Boolean = this.isRtl): Piece = Piece(
        start = from,
        end = to,
        item = item,
        style = style,
        isRtl = isRtl,
        width = analysis.pieceWidth(item, from, to),
        analysis = analysis,
    )

    private fun Piece.withDirection(isRtl: Boolean): Piece = Piece(
        start = start,
        end = end,
        item = item,
        style = style,
        isRtl = isRtl,
        width = width,
        analysis = analysis,
        placeholder = placeholder,
        isEllipsis = isEllipsis,
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
            val spaces = analysis.countJustifiableSpaces(line.start, line.visibleEnd)
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
                    spaceExtra * analysis.countJustifiableSpaces(piece.start, min(piece.end, line.visibleEnd))
            }
            x += piece.totalWidth
        }
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

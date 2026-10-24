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

@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformParagraph
import androidx.compose.ui.text.TextDecorationLineStyle
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.webcanvas.graphics.RecordingCanvas
import androidx.compose.ui.webcanvas.graphics.TextRunDrawing
import androidx.compose.ui.webcanvas.graphics.TextShadow
import androidx.compose.ui.webcanvas.graphics.WebCanvas
import androidx.compose.ui.webcanvas.graphics.WebPaint
import androidx.compose.ui.webcanvas.path.WebPath
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The [PlatformParagraph] of the web backend: lines are broken and positioned by [LineLayout],
 * and the text is drawn piece by piece with the Canvas 2D `fillText`.
 */
internal class WebParagraph(
    private val intrinsics: WebParagraphIntrinsics,
    private val maxLines: Int,
    private val overflow: TextOverflow,
    private val constraints: Constraints,
) : PlatformParagraph {
    private val analysis = intrinsics.analysis
    private val text = analysis.text
    private val textStyle = intrinsics.style

    private val layout: LineLayout

    private val bulletPainter = intrinsics.bullets.takeIf { it.isNotEmpty() }?.let {
        BulletPainter(it, intrinsics.density, intrinsics.fontSize)
    }

    init {
        require(constraints.minHeight == 0 && constraints.minWidth == 0) {
            "Setting Constraints.minWidth and Constraints.minHeight is not supported, " +
                "these should be the default zero values instead."
        }
        require(maxLines >= 1) { "maxLines should be greater than 0" }
        var layout = newLayout(maxLines)
        // Ellipsize if there's not enough vertical space to fit all lines, like the other targets.
        if (overflow.isEllipsis() && layout.height > constraints.maxHeight && maxLines > 1) {
            val fitting = layout.lines.indexOfFirst { floor(it.bottom) > constraints.maxHeight }
                .let { if (it < 0) layout.lines.size else it }
            if (fitting != maxLines) {
                layout = newLayout(fitting.coerceAtLeast(1))
            }
        }
        this.layout = layout
    }

    private fun newLayout(maxLines: Int): LineLayout {
        val indent = textStyle.textIndent
        return LineLayout(
            analysis = analysis,
            maxWidth = constraints.maxWidth.toFloat(),
            maxLines = maxLines,
            ellipsize = overflow.isEllipsis(),
            textAlign = textStyle.textAlign,
            textIndent = indent,
            indentFirstLine = intrinsics.firstLineIndent,
            indentRestLines = intrinsics.restLinesIndent,
            lineHeightStyle = textStyle.lineHeightStyle,
            hasLineHeight = analysis.styled.defaultStyle.lineHeight != null,
        )
    }

    private val lines: List<Line> get() = layout.lines

    override val width: Float get() = constraints.maxWidth.toFloat()

    override val height: Float get() = layout.height

    override val minIntrinsicWidth: Float get() = intrinsics.minIntrinsicWidth

    override val maxIntrinsicWidth: Float get() = intrinsics.maxIntrinsicWidth

    override val firstBaseline: Float get() = lines.firstOrNull()?.baseline ?: 0f

    override val lastBaseline: Float get() = lines.lastOrNull()?.baseline ?: 0f

    override val didExceedMaxLines: Boolean get() = layout.didExceedMaxLines

    override val lineCount: Int get() = max(1, lines.size)

    override val placeholderRects: List<Rect?>
        get() = analysis.styled.placeholders.map { placeholder ->
            var result: Rect? = null
            for (line in lines) {
                val piece = line.pieces.firstOrNull { it.placeholder === placeholder } ?: continue
                val top = layout.placeholderTop(placeholder, line)
                result = Rect(piece.x, top, piece.x + placeholder.width, top + placeholder.height)
                break
            }
            result
        }

    // ---------------------------------------------------------------------------------------------
    // Lines

    private fun line(index: Int): Line? = lines.getOrNull(index)

    override fun getLineLeft(lineIndex: Int): Float = line(lineIndex)?.left ?: 0f

    override fun getLineRight(lineIndex: Int): Float = line(lineIndex)?.right ?: 0f

    override fun getLineTop(lineIndex: Int): Float = line(lineIndex)?.let { floor(it.top) } ?: 0f

    override fun getLineBottom(lineIndex: Int): Float = line(lineIndex)?.let { floor(it.bottom) } ?: 0f

    override fun getLineBaseline(lineIndex: Int): Float = line(lineIndex)?.baseline ?: 0f

    override fun getLineHeight(lineIndex: Int): Float = line(lineIndex)?.height ?: 0f

    override fun getLineWidth(lineIndex: Int): Float = line(lineIndex)?.width ?: 0f

    override fun getLineAscent(lineIndex: Int): Float = -(line(lineIndex)?.ascent ?: 0f)

    override fun getLineDescent(lineIndex: Int): Float = line(lineIndex)?.descent ?: 0f

    override fun getLineStart(lineIndex: Int): Int = line(lineIndex)?.start ?: 0

    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean): Int {
        val line = line(lineIndex) ?: return 0
        // Like Skia (whose behavior the common code of Compose Multiplatform expects), the logical
        // end of a line includes its trailing spaces but not the line feed ending it.
        return if (visibleEnd) line.visibleEnd else line.end
    }

    override fun isLineEllipsized(lineIndex: Int): Boolean = line(lineIndex)?.isEllipsized ?: false

    override fun getLineForOffset(offset: Int): Int {
        if (lines.isEmpty()) return 0
        if (offset <= 0) return 0
        if (offset >= text.length) return lines.lastIndex
        // The first line that starts after the offset is the next one.
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (lines[mid].start <= offset) low = mid else high = mid - 1
        }
        return low
    }

    override fun getLineForVerticalPosition(vertical: Float): Int {
        for (line in lines) {
            if (vertical < line.bottom) return line.index
        }
        return max(0, lines.lastIndex)
    }

    override fun getParagraphDirection(offset: Int): ResolvedTextDirection =
        if (analysis.isRtl) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection {
        if (text.isEmpty()) return getParagraphDirection(offset)
        val index = offset.coerceIn(0, text.length - 1)
        return if (analysis.levels[index].toInt() and 1 == 1) {
            ResolvedTextDirection.Rtl
        } else {
            ResolvedTextDirection.Ltr
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Positions

    /** The piece of [line] showing the character at [offset], or `null`. */
    private fun pieceContaining(line: Line, offset: Int): Piece? =
        line.pieces.firstOrNull { !it.isEllipsis && offset >= it.start && offset < it.end }

    /** The advance from the logical start of [piece] to [offset], in the piece direction. */
    private fun advanceInPiece(piece: Piece, offset: Int): Float {
        if (offset <= piece.start) return 0f
        if (offset >= piece.end) return piece.totalWidth
        if (piece.placeholder != null) return 0f
        var advance = TextMeasurement.width(piece.style.font, text, piece.start, offset, piece.isRtl)
        if (piece.justifySpacing > 0f) {
            advance += piece.justifySpacing * countJustifiableSpaces(piece.start, offset)
        }
        return min(advance, piece.totalWidth)
    }

    private fun countJustifiableSpaces(start: Int, end: Int): Int {
        var count = 0
        for (i in start until end) {
            if (text[i] == ' ' || text[i] == '\u00A0' || text[i] == '\u3000') count++
        }
        return count
    }

    /** The x of the caret before [offset] in [piece] (its leading edge). */
    private fun caretX(piece: Piece, offset: Int): Float {
        val advance = advanceInPiece(piece, offset)
        return if (piece.isRtl) piece.right - advance else piece.x + advance
    }

    private class CharBox(val left: Float, val right: Float, val isRtl: Boolean) {
        /** The edge where the character starts in its direction. */
        val leading: Float get() = if (isRtl) right else left
        /** The edge where the character ends in its direction. */
        val trailing: Float get() = if (isRtl) left else right
    }

    /** The box of the grapheme cluster starting before [offset] or at it, on [line]. */
    private fun charBox(line: Line, offset: Int): CharBox? {
        if (offset < line.start || offset >= line.end) return null
        val piece = pieceContaining(line, offset) ?: return null
        val start = if (offset == piece.start) offset else
            TextUnicode.previousGraphemeBoundary(text, offset + 1).coerceIn(piece.start, offset)
        val end = TextUnicode.nextGraphemeBoundary(text, start).coerceIn(start + 1, piece.end)
        val x0 = caretX(piece, start)
        val x1 = caretX(piece, end)
        return CharBox(min(x0, x1), max(x0, x1), piece.isRtl)
    }

    override fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float {
        if (lines.isEmpty()) return 0f
        val lineIndex = getLineForOffset(offset.coerceIn(0, text.length))
        val line = lines[lineIndex]
        val prev = if (offset - 1 >= line.start) charBox(line, offset - 1) else null
        val next = charBox(line, offset)
        val isRtl = analysis.isRtl
        return when {
            prev == null && next == null -> lineStartPosition(line)
            prev == null -> next!!.leading
            next == null -> {
                // At the end of the line: after the last character, or past the ellipsis.
                if (line.isEllipsized && offset >= line.visibleEnd) {
                    line.pieces.firstOrNull { it.isEllipsis }?.let { if (it.isRtl) it.x else it.right }
                        ?: prev.trailing
                } else {
                    prev.trailing
                }
            }
            next.isRtl == prev.isRtl -> next.leading
            !isRtl && !prev.isRtl -> next.leading
            isRtl && prev.isRtl -> next.leading
            usePrimaryDirection -> prev.trailing
            else -> next.leading
        }
    }

    /** Where the caret of an empty line goes. */
    private fun lineStartPosition(line: Line): Float {
        val isRtl = analysis.isRtl
        val width = width
        if (!width.isFinite() || width > 1e7f) return if (isRtl) line.right else line.left
        val indent = if (line.index == 0) layout.firstIndent() else layout.restIndent()
        return when (textStyle.textAlign) {
            TextAlign.Left -> indent
            TextAlign.Right -> width
            TextAlign.Center -> (width + indent) / 2
            TextAlign.End -> if (isRtl) 0f else width
            else -> if (isRtl) width - indent else indent
        }
    }

    override fun getCursorRect(offset: Int): Rect {
        checkOffsetIsValid(offset)
        val horizontal = getHorizontalPosition(offset, true)
        val line = lines.getOrNull(getLineForOffset(offset)) ?: return Rect(horizontal, 0f, horizontal, 0f)
        return Rect(horizontal, line.top, horizontal, line.bottom)
    }

    override fun getOffsetForPosition(position: Offset): Int {
        if (lines.isEmpty()) return 0
        val line = lines[getLineForVerticalPosition(position.y)]
        val maxOffset = if (line.index == lines.lastIndex) {
            line.endIncludingNewline
        } else {
            // Like Android: the offset after the last character would be on the next line.
            max(line.start, line.endIncludingNewline - 1)
        }
        val pieces = line.pieces.filter { !it.isEllipsis }
        if (pieces.isEmpty()) return line.start
        val x = position.x
        // Past the edges of the line, the caret goes to the start or the end of the line in the
        // paragraph direction (like on Android and with Skia), whatever the direction of the
        // runs at the edges: e.g. clicking right of a left-to-right line ending with Arabic text
        // places the caret after the last character, not before the first Arabic one.
        val isRtl = analysis.isRtl
        if (x < pieces.first().x) return if (isRtl) maxOffset else line.start
        if (x >= pieces.last().right) return if (isRtl) line.start else maxOffset
        val piece = pieces.firstOrNull { x >= it.x && x < it.right } ?: pieces.last()
        val offset = offsetInPiece(piece, x)
        return offset.coerceIn(line.start, maxOffset)
    }

    /** The grapheme boundary of [piece] closest to [x]. */
    private fun offsetInPiece(piece: Piece, x: Float): Int {
        if (piece.placeholder != null) {
            val middle = piece.x + piece.width / 2
            val beforeMiddle = x < middle
            return if (beforeMiddle != piece.isRtl) piece.start else piece.end
        }
        // The advance from the logical start of the piece.
        val advance = if (piece.isRtl) piece.right - x else x - piece.x
        if (advance <= 0f) return piece.start
        if (advance >= piece.totalWidth) return piece.end
        // Grapheme boundaries are few in a piece; walk them, measuring each prefix once.
        var previousOffset = piece.start
        var previousAdvance = 0f
        var offset = TextUnicode.nextGraphemeBoundary(text, piece.start).coerceAtMost(piece.end)
        while (true) {
            val currentAdvance = advanceInPiece(piece, offset)
            if (currentAdvance >= advance) {
                return if (advance - previousAdvance < currentAdvance - advance) previousOffset else offset
            }
            if (offset >= piece.end) return piece.end
            previousOffset = offset
            previousAdvance = currentAdvance
            offset = TextUnicode.nextGraphemeBoundary(text, offset).coerceAtMost(piece.end)
        }
    }

    override fun getRangeForRect(
        rect: Rect,
        granularity: TextGranularity,
        inclusionStrategy: TextInclusionStrategy,
    ): TextRange {
        var start = -1
        var end = -1
        for (line in lines) {
            if (line.bottom <= rect.top || line.top >= rect.bottom) continue
            var offset = line.start
            while (offset < line.end) {
                val next = TextUnicode.nextGraphemeBoundary(text, offset).coerceAtMost(line.end)
                val box = charBox(line, offset)
                if (box != null) {
                    val charRect = Rect(box.left, line.top, box.right, line.bottom)
                    if (inclusionStrategy.isIncluded(charRect, rect)) {
                        if (start < 0) start = offset
                        end = next
                    }
                }
                offset = next
            }
        }
        if (start < 0) return TextRange.Zero
        if (granularity == TextGranularity.Word) {
            start = getWordBoundary(start).start
            end = getWordBoundary(max(start, end - 1)).end
        }
        return TextRange(start, end)
    }

    override fun getBoundingBox(offset: Int): Rect {
        require(offset in text.indices) { "offset($offset) is out of bounds [0,${text.length})" }
        val line = lines[getLineForOffset(offset)]
        val box = charBox(line, offset)
        if (box != null) return Rect(box.left, line.top, box.right, line.bottom)
        // A line terminator or a character cut by the ellipsis: an empty box at the line end.
        val x = if (analysis.isRtl) line.left else line.right
        return Rect(x, line.top, x, line.bottom)
    }

    override fun fillBoundingBoxes(range: TextRange, array: FloatArray, arrayStart: Int) {
        var index = arrayStart
        for (offset in range.min until range.max) {
            val box = getBoundingBox(offset)
            array[index] = box.left
            array[index + 1] = box.top
            array[index + 2] = box.right
            array[index + 3] = box.bottom
            index += 4
        }
    }

    override fun getPathForRange(start: Int, end: Int): Path {
        require(start in 0..end && end <= text.length) {
            "start($start) or end($end) is out of range [0..${text.length}], or start > end!"
        }
        val path = WebPath()
        if (start == end) return path
        for (line in lines) {
            if (line.endIncludingNewline <= start || line.start >= end) continue
            for (piece in line.pieces) {
                if (piece.isEllipsis || piece.end <= start || piece.start >= end) continue
                val x0 = caretX(piece, max(start, piece.start))
                val x1 = caretX(piece, min(end, piece.end))
                path.addRect(Rect(min(x0, x1), line.top, max(x0, x1), line.bottom))
            }
        }
        return path
    }

    override fun getWordBoundary(offset: Int): TextRange {
        checkOffsetIsValid(offset)
        // Like Android: an empty range between whitespaces, the previous word right after it.
        if (offset < text.length && text[offset].isWhitespace() || offset == text.length) {
            return if (offset > 0 && !text[offset - 1].isWhitespace()) {
                TextUnicode.wordBoundary(text, offset - 1)
            } else {
                TextRange(offset, offset)
            }
        }
        return TextUnicode.wordBoundary(text, offset)
    }

    private fun checkOffsetIsValid(offset: Int) {
        require(offset in 0..text.length) { "offset($offset) is out of bounds [0,${text.length}]" }
    }

    // ---------------------------------------------------------------------------------------------
    // Painting

    private var paintKey: PaintKey? = null
    private var paintRuns: StyledText = analysis.styled

    /** The paint-time parameters overriding the paragraph style. */
    private data class PaintKey(
        val color: Color,
        val brush: Brush?,
        val alpha: Float,
        val shadow: Shadow?,
        val decoration: TextDecoration?,
        val drawStyle: DrawStyle?,
    )

    private fun stylesFor(key: PaintKey): StyledText {
        if (key == paintKey) return paintRuns
        val style = textStyle.copy(
            color = if (key.brush == null && key.color.isSpecified) key.color else textStyle.color,
            shadow = key.shadow,
            textDecoration = key.decoration,
        ).let {
            if (key.brush != null) it.copy(brush = key.brush, alpha = key.alpha) else it
        }
        paintRuns = intrinsics.resolveStyles(style, key.drawStyle)
        paintKey = key
        return paintRuns
    }

    override fun paint(canvas: Canvas, color: Color, shadow: Shadow?, textDecoration: TextDecoration?) {
        paintText(canvas, PaintKey(color, null, Float.NaN, shadow, textDecoration, null), DrawScope.DefaultBlendMode)
    }

    override fun paint(
        canvas: Canvas,
        color: Color,
        shadow: Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) {
        paintText(canvas, PaintKey(color, null, Float.NaN, shadow, textDecoration, drawStyle), blendMode)
    }

    override fun paint(
        canvas: Canvas,
        brush: Brush,
        alpha: Float,
        shadow: Shadow?,
        textDecoration: TextDecoration?,
        drawStyle: DrawStyle?,
        blendMode: BlendMode,
    ) {
        paintText(canvas, PaintKey(Color.Unspecified, brush, alpha, shadow, textDecoration, drawStyle), blendMode)
    }

    private fun paintText(canvas: Canvas, key: PaintKey, blendMode: BlendMode) {
        val drawer: TextDrawer = when (canvas) {
            is WebCanvas -> TextDrawer.Direct(canvas)
            is RecordingCanvas -> TextDrawer.Recording(canvas)
            else -> return // A foreign canvas can't draw web text.
        }
        val styles = stylesFor(key)
        val brushSize = Size(width, height)
        val paints = HashMap<RunStyle, WebPaint>()
        fun paintFor(style: RunStyle): WebPaint = paints.getOrPut(style) {
            createTextPaint(style, brushSize, blendMode)
        }
        for (line in lines) {
            for (piece in line.pieces) {
                if (piece.placeholder != null) continue
                val styleOffset = if (piece.isEllipsis) max(0, piece.start - 1) else piece.start
                val style = styles.runAt(styleOffset.coerceAtMost(max(0, text.length - 1))).style
                val paint = paintFor(style)
                val background = style.background
                if (background.isSpecified && background.alpha > 0f) {
                    val backgroundPaint = WebPaint().also {
                        it.color = background
                        it.blendMode = blendMode
                    }
                    canvas.drawRect(piece.x, line.top, piece.right, line.bottom, backgroundPaint)
                }
                val baseline = line.baseline + baselineShiftOf(style)
                drawPieceText(drawer, piece, style, paint, baseline)
                drawDecorations(canvas, piece, style, paint, baseline)
            }
        }
        bulletPainter?.paint(
            canvas = canvas,
            isRtl = intrinsics.textDirection == ResolvedTextDirection.Rtl,
            lineAt = ::laidOutLineAt,
            textColor = key.color.takeOrElse { textStyle.color }.takeOrElse { Color.Black },
            textBrush = key.brush,
            textAlpha = if (key.brush != null) key.alpha else 1f,
            blendMode = blendMode,
        )
    }

    /** The line holding [offset], unless the lines end before it (e.g. cut by [maxLines]). */
    private fun laidOutLineAt(offset: Int): Line? {
        val line = line(getLineForOffset(offset)) ?: return null
        val isLaidOut = offset >= line.start &&
            (offset < line.endIncludingNewline || offset == text.length)
        return if (isLaidOut) line else null
    }

    private fun createTextPaint(style: RunStyle, brushSize: Size, blendMode: BlendMode): WebPaint {
        val paint = WebPaint()
        val foreground = style.foreground
        val brush = foreground.brush
        if (brush is ShaderBrush) {
            brush.applyTo(brushSize, paint, if (foreground.alpha.isNaN()) 1f else foreground.alpha)
        } else {
            paint.color = if (foreground.color.isSpecified) foreground.color else Color.Black
        }
        when (val drawStyle = style.drawStyle) {
            is Stroke -> {
                paint.style = PaintingStyle.Stroke
                paint.strokeWidth = drawStyle.width
                paint.strokeMiterLimit = drawStyle.miter
                paint.strokeJoin = drawStyle.join
                paint.strokeCap = drawStyle.cap
                paint.pathEffect = drawStyle.pathEffect
            }
            Fill, null -> paint.style = PaintingStyle.Fill
        }
        paint.blendMode = blendMode
        return paint
    }

    private fun textShadowOf(style: RunStyle): TextShadow? {
        val shadow = style.shadow ?: return null
        if (shadow == Shadow.None || shadow.color.alpha == 0f) return null
        // Like Android: a blur radius r is a Gaussian of deviation 0.57735 r + 0.5, and the canvas
        // shadowBlur is twice the deviation.
        val blur = if (shadow.blurRadius > 0f) 2 * (0.57735f * shadow.blurRadius + 0.5f) else 0f
        return TextShadow(shadow.color.toArgb(), shadow.offset.x, shadow.offset.y, blur)
    }

    private fun drawPieceText(drawer: TextDrawer, piece: Piece, style: RunStyle, paint: WebPaint, baseline: Float) {
        val drawnText = piece.drawnText ?: return
        if (drawnText.isBlank()) return
        val font = style.font
        val shadow = textShadowOf(style)
        val transformed = font.scaleX != 1f || style.skewX != 0f
        if (piece.justifySpacing > 0f) {
            // Justified: draw word by word, adding the spacing after every space.
            var wordStart = 0
            while (wordStart < drawnText.length) {
                var wordEnd = wordStart
                while (wordEnd < drawnText.length && !isJustifiableSpace(drawnText[wordEnd])) wordEnd++
                if (wordEnd > wordStart) {
                    val advance = advanceInPiece(piece, piece.start + wordStart)
                    val wordWidth = TextMeasurement.width(font, drawnText, wordStart, wordEnd, piece.isRtl)
                    val x = if (piece.isRtl) piece.right - advance - wordWidth else piece.x + advance
                    val run = TextRunDrawing(
                        drawnText.substring(wordStart, wordEnd), font.css, paint, piece.isRtl,
                        font.letterSpacingCss, shadow,
                    )
                    drawer.draw(run, x, baseline, font.scaleX, style.skewX, transformed)
                }
                wordStart = wordEnd + 1
            }
            return
        }
        val run = TextRunDrawing(drawnText, font.css, paint, piece.isRtl, font.letterSpacingCss, shadow, piece.jsText)
        drawer.draw(run, piece.x, baseline, font.scaleX, style.skewX, transformed)
    }

    private fun isJustifiableSpace(c: Char) = c == ' ' || c == '\u00A0' || c == '\u3000'

    private fun drawDecorations(canvas: Canvas, piece: Piece, style: RunStyle, paint: WebPaint, baseline: Float) {
        val decoration = style.decoration ?: return
        if (decoration == TextDecoration.None || piece.drawnText.isNullOrEmpty()) return
        val fontSize = style.fontSize
        val thickness = max(1f, fontSize / 14f)
        val metrics = TextMeasurement.metrics(style.font)
        val linePaint = WebPaint().also {
            it.copyFrom(paint)
            it.style = PaintingStyle.Fill
        }
        val left = piece.x
        val right = piece.right
        if (decoration.contains(TextDecoration.Underline)) {
            val y = baseline + max(thickness * 1.5f, fontSize * 0.1f)
            drawDecorationLine(canvas, left, right, y, thickness, style.decorationLineStyle, linePaint)
        }
        if (decoration.contains(TextDecoration.LineThrough)) {
            val y = baseline - metrics.strikeoutOffset
            drawDecorationLine(canvas, left, right, y, thickness, style.decorationLineStyle, linePaint)
        }
    }

    private fun drawDecorationLine(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float,
        thickness: Float,
        lineStyle: TextDecorationLineStyle?,
        paint: WebPaint,
    ) {
        val half = thickness / 2
        when (lineStyle) {
            TextDecorationLineStyle.Double -> {
                canvas.drawRect(left, centerY - half - thickness, right, centerY + half - thickness, paint)
                canvas.drawRect(left, centerY - half + thickness, right, centerY + half + thickness, paint)
            }
            TextDecorationLineStyle.Dotted, TextDecorationLineStyle.Dashed -> {
                val dash = if (lineStyle == TextDecorationLineStyle.Dotted) thickness else thickness * 4
                var x = left
                while (x < right) {
                    canvas.drawRect(x, centerY - half, min(right, x + dash), centerY + half, paint)
                    x += dash * 2
                }
            }
            TextDecorationLineStyle.Wavy -> {
                val path = WebPath()
                val wavelength = thickness * 6
                val amplitude = thickness * 1.5f
                path.moveTo(left, centerY)
                var x = left
                var up = true
                while (x < right) {
                    val next = min(right, x + wavelength / 2)
                    path.quadraticTo((x + next) / 2, centerY + if (up) -amplitude else amplitude, next, centerY)
                    x = next
                    up = !up
                }
                val strokePaint = WebPaint().also {
                    it.copyFrom(paint)
                    it.style = PaintingStyle.Stroke
                    it.strokeWidth = thickness
                }
                canvas.drawPath(path, strokePaint)
            }
            else -> canvas.drawRect(left, centerY - half, right, centerY + half, paint)
        }
    }

    /** Draws text runs into one of the canvases of the backend. */
    private sealed class TextDrawer {
        abstract val canvas: Canvas

        abstract fun drawRun(run: TextRunDrawing, x: Float, y: Float)

        fun draw(run: TextRunDrawing, x: Float, y: Float, scaleX: Float, skewX: Float, transformed: Boolean) {
            if (!transformed) {
                drawRun(run, x, y)
                return
            }
            canvas.save()
            canvas.translate(x, y)
            if (scaleX != 1f) canvas.scale(scaleX, 1f)
            // A positive skew moves the top of the glyphs to the right; y points down on canvas.
            if (skewX != 0f) canvas.skew(-skewX, 0f)
            drawRun(run, 0f, 0f)
            canvas.restore()
        }

        class Direct(override val canvas: WebCanvas) : TextDrawer() {
            override fun drawRun(run: TextRunDrawing, x: Float, y: Float) = canvas.drawText(run, x, y)
        }

        class Recording(override val canvas: RecordingCanvas) : TextDrawer() {
            override fun drawRun(run: TextRunDrawing, x: Float, y: Float) = canvas.drawText(run, x, y)
        }
    }
}

private fun TextOverflow.isEllipsis(): Boolean =
    this == TextOverflow.Ellipsis || this == TextOverflow.StartEllipsis ||
        this == TextOverflow.MiddleEllipsis

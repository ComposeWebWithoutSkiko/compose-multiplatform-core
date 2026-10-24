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

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Bullet
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.webcanvas.graphics.WebPaint

/**
 * Draws the [Bullet] markers of a paragraph into its leading margin, like the other targets: a
 * bullet is centered on the line its range starts on, [Bullet.padding] before the start of the
 * text of that line (the indentation of a bullet list leaves room for it).
 */
internal class BulletPainter(
    private val bullets: List<AnnotatedString.Range<Bullet>>,
    private val density: Density,
    /** The default font size of the paragraph in pixels, which em sizes are relative to. */
    private val fontSize: Float,
) {
    private val paint = WebPaint()

    /** Built on first use: nothing an outline depends on changes while the painter is alive. */
    private val outlines = arrayOfNulls<Outline>(bullets.size)

    /**
     * @param lineAt the laid out line holding the given text offset, or `null` if it isn't laid
     *   out (e.g. cut by the maximum number of lines)
     * @param textColor the color of the text, for bullets that have neither a brush of their own
     *   nor [textBrush]
     * @param textBrush the brush the text is drawn with, if any
     * @param textAlpha the opacity the text is drawn with, for bullets whose own alpha is NaN
     */
    fun paint(
        canvas: Canvas,
        isRtl: Boolean,
        lineAt: (Int) -> Line?,
        textColor: Color,
        textBrush: Brush?,
        textAlpha: Float,
        blendMode: BlendMode,
    ) {
        val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        bullets.fastForEachIndexed { index, range ->
            val bullet = range.item
            val width = bullet.width.toPx()
            val height = bullet.height.toPx()
            val gap = bullet.padding.toPx()
            if (width.isNaN() || height.isNaN() || gap.isNaN()) return@fastForEachIndexed
            val line = lineAt(range.start) ?: return@fastForEachIndexed

            val x = if (isRtl) line.right + gap else (line.left - (width + gap)).coerceAtLeast(0f)
            val centerY = (line.top + line.bottom) / 2f
            val size = Size(width, height)
            preparePaint(bullet, size, textColor, textBrush, textAlpha, blendMode)
            val outline = outlines[index]
                ?: bullet.shape.createOutline(size, layoutDirection, density).also {
                    outlines[index] = it
                }
            canvas.save()
            canvas.translate(x, centerY - height / 2f)
            canvas.drawOutline(outline, paint)
            canvas.restore()
        }
    }

    /** Sets the paint up for [bullet], clearing what the previous bullet left behind. */
    private fun preparePaint(
        bullet: Bullet,
        size: Size,
        textColor: Color,
        textBrush: Brush?,
        textAlpha: Float,
        blendMode: BlendMode,
    ) {
        val drawStyle = bullet.drawStyle
        if (drawStyle is Stroke) {
            paint.style = PaintingStyle.Stroke
            paint.strokeWidth = drawStyle.width
            paint.strokeMiterLimit = drawStyle.miter
            paint.strokeCap = drawStyle.cap
            paint.strokeJoin = drawStyle.join
            paint.pathEffect = drawStyle.pathEffect
        } else {
            paint.style = PaintingStyle.Fill
            paint.pathEffect = null
        }
        val alpha = when {
            !bullet.alpha.isNaN() -> bullet.alpha
            !textAlpha.isNaN() -> textAlpha
            else -> 1f
        }
        val brush = bullet.brush ?: textBrush
        if (brush != null) {
            brush.applyTo(size, paint, alpha)
        } else {
            paint.shader = null
            paint.color = textColor.copy(alpha = textColor.alpha * alpha)
        }
        paint.blendMode = blendMode
    }

    /**
     * A size or padding of a bullet in pixels, like on Android: an unspecified value is the font
     * size, and an unknown unit is NaN, which skips the bullet.
     */
    private fun TextUnit.toPx(): Float = when {
        this == TextUnit.Unspecified -> fontSize
        isSp -> with(density) { toPx() }
        isEm -> fontSize * value
        else -> Float.NaN
    }
}

/** The [Bullet] annotations among [annotations]. */
internal fun bulletsOf(
    annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
): List<AnnotatedString.Range<Bullet>> {
    if (!annotations.fastAny { it.item is Bullet }) return emptyList()
    val bullets = ArrayList<AnnotatedString.Range<Bullet>>()
    annotations.fastForEach { annotation ->
        val item = annotation.item
        if (item is Bullet) bullets.add(AnnotatedString.Range(item, annotation.start, annotation.end))
    }
    return bullets
}

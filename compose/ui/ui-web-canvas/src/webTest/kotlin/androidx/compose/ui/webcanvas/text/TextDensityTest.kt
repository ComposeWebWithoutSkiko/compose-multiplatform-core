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

@file:OptIn(InternalComposeUiApi::class)

package androidx.compose.ui.webcanvas.text

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.webcanvas.dom.Context2D
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.createElementCanvas
import androidx.compose.ui.webcanvas.dom.requireContext2D
import androidx.compose.ui.webcanvas.dom.setLetterSpacing
import androidx.compose.ui.webcanvas.dom.setLineDash
import androidx.compose.ui.webcanvas.dom.toJsNumberArray
import androidx.compose.ui.webcanvas.graphics.CanvasImageBitmap
import androidx.compose.ui.webcanvas.graphics.FrameRenderer
import androidx.compose.ui.webcanvas.graphics.maxPremultipliedDifference
import kotlin.js.JsString
import kotlin.js.toJsString
import kotlin.math.round
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Text at a density above 1: the font of its size in CSS pixels, scaled up, as the browser draws DOM
 * text. A font twice as large isn't the same: the system font of macOS changes with its size.
 */
class TextDensityTest {
    private val style = TextStyle(fontSize = 16.sp)

    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun paragraph(
        density: Float,
        style: TextStyle = this.style,
        text: String = Sample,
        width: Int = 10_000,
        maxLines: Int = Int.MAX_VALUE,
        overflow: TextOverflow = TextOverflow.Clip,
    ): Paragraph = Paragraph(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = width),
        density = Density(density),
        fontFamilyResolver = createFontFamilyResolver(),
        defaultLocaleList = LocaleList("en-US"),
        maxLines = maxLines,
        overflow = overflow,
    )

    private fun image() = ImageBitmap(Width, Height) as CanvasImageBitmap

    private fun assertSamePixels(expected: Context2D, actual: Context2D, tolerance: Int = 0) {
        val difference = maxPremultipliedDifference(
            expected.getImageData(0, 0, Width, Height),
            actual.getImageData(0, 0, Width, Height),
        )
        assertTrue(difference <= tolerance, "The largest difference is $difference")
    }

    @Test
    fun textIsLaidOutAsItsFontInCssPixelsScaledUp() {
        val css = paragraph(1f)
        for (density in listOf(2f, 1.5f, 1.25f)) {
            assertEquals(density * css.maxIntrinsicWidth, paragraph(density).maxIntrinsicWidth, 1.5f, "at $density")
        }
        assertEquals(2 * css.firstBaseline, paragraph(2f).firstBaseline, 0.01f)
    }

    @Test
    fun linesStayInWholePixelsAtAFractionalDensity() {
        val paragraph = paragraph(1.25f, text = "First\nSecond\nThird")
        for (line in 0 until 3) {
            val height = paragraph.getLineHeight(line)
            val baseline = paragraph.getLineBaseline(line)
            assertEquals(round(height), height, "The height of line $line")
            assertEquals(round(baseline), baseline, "The baseline of line $line")
        }
    }

    @Test
    fun textIsDrawnAsItsFontInCssPixelsScaledUp() {
        assertDrawnAsScaled(
            draw = { paint(it, Color.Black) },
            reference = { text, y ->
                fillStyle = CssColors.of(Color.Black.toArgb())
                fillText(text, 0.0, y)
            },
        )
    }

    @Test
    fun textIsDrawnAsItsFontInCssPixelsAtAFractionalDensity() {
        assertDrawnAsScaled(
            density = 1.5f,
            draw = { paint(it, Color.Black) },
            reference = { text, y ->
                fillStyle = CssColors.of(Color.Black.toArgb())
                fillText(text, 0.0, y)
            },
        )
    }

    @Test
    fun theStrokeOfTextKeepsItsWidth() {
        assertDrawnAsScaled(
            draw = { paint(it, Color.Black, drawStyle = Stroke(width = 3f, miter = 4f)) },
            reference = { text, y ->
                strokeStyle = CssColors.of(Color.Black.toArgb())
                lineWidth = 1.5
                miterLimit = 4.0
                strokeText(text, 0.0, y)
            },
        )
    }

    @Test
    fun aHairlineStrokeOfTextIsOneDevicePixelWide() {
        assertDrawnAsScaled(
            draw = { paint(it, Color.Black, drawStyle = Stroke(width = 0f, miter = 4f)) },
            reference = { text, y ->
                strokeStyle = CssColors.of(Color.Black.toArgb())
                lineWidth = 0.5
                miterLimit = 4.0
                strokeText(text, 0.0, y)
            },
        )
    }

    @Test
    fun theDashesOfTheStrokeOfTextKeepTheirLength() {
        val dashes = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
        assertDrawnAsScaled(
            draw = { paint(it, Color.Black, drawStyle = Stroke(width = 3f, miter = 4f, pathEffect = dashes)) },
            reference = { text, y ->
                strokeStyle = CssColors.of(Color.Black.toArgb())
                lineWidth = 1.5
                miterLimit = 4.0
                setLineDash(this, floatArrayOf(3f, 2f).toJsNumberArray())
                strokeText(text, 0.0, y)
            },
        )
    }

    @Test
    fun theShadowOfTextKeepsItsOffsetAndBlur() {
        val shadow = Shadow(Color.Black, Offset(4f, 6f), blurRadius = 4f)
        assertDrawnAsScaled(
            draw = { paint(it, Color.Black, shadow = shadow) },
            reference = { text, y ->
                fillStyle = CssColors.of(Color.Black.toArgb())
                // As WebParagraph makes the blur of a shadow, in device pixels like the offset.
                shadowColor = CssColors.of(Color.Black.toArgb())
                shadowOffsetX = 4.0
                shadowOffsetY = 6.0
                shadowBlur = (2 * (0.57735f * 4f + 0.5f)).toDouble()
                fillText(text, 0.0, y)
            },
        )
    }

    @Test
    fun letterSpacingIsInTheUnitsOfTheFont() {
        assertDrawnAsScaled(
            style = style.copy(letterSpacing = 1.sp),
            draw = { paint(it, Color.Black) },
            reference = { text, y ->
                fillStyle = CssColors.of(Color.Black.toArgb())
                setLetterSpacing(this, "1px".toJsString())
                fillText(text, 0.0, y)
            },
        )
    }

    @Test
    fun aLinearBrushStaysWhereItIsOverTheText() {
        assertBrushStaysOverTheText(Brush.linearGradient(Colors, Offset(0f, 0f), Offset(400f, 0f)))
    }

    @Test
    fun aRadialBrushStaysWhereItIsOverTheText() {
        assertBrushStaysOverTheText(Brush.radialGradient(Colors, Offset(300f, 20f), radius = 400f))
    }

    @Test
    fun aSweepBrushStaysWhereItIsOverTheText() {
        assertBrushStaysOverTheText(Brush.sweepGradient(Colors, Offset(300f, 20f)))
    }

    @Test
    fun anImageBrushStaysWhereItIsOverTheText() {
        assertBrushStaysOverTheText(ShaderBrush(tilesShader()))
    }

    @Test
    fun aMovedImageBrushStaysWhereItIsOverTheText() {
        val brush = ShaderBrush(tilesShader())
        brush.transform = Matrix().apply { translate(100f, 3f) }
        assertBrushStaysOverTheText(brush)
    }

    /** A shader of tiles of four colors, repeated. */
    private fun tilesShader() = ImageShader(
        ImageBitmap(40, 40).also { tiles ->
            val canvas = Canvas(tiles)
            canvas.drawRect(Rect(0f, 0f, 20f, 20f), Paint().apply { color = Color.Red })
            canvas.drawRect(Rect(20f, 0f, 40f, 20f), Paint().apply { color = Color.Green })
            canvas.drawRect(Rect(0f, 20f, 20f, 40f), Paint().apply { color = Color.Blue })
            canvas.drawRect(Rect(20f, 20f, 40f, 40f), Paint().apply { color = Color.Yellow })
        },
        TileMode.Repeated,
        TileMode.Repeated,
    )

    /**
     * Asserts that the text drawn with [brush] at a density of 2 is its black text with the pixels
     * of [brush] in it: the brush stays in device pixels, whatever the units of the font.
     */
    private fun assertBrushStaysOverTheText(brush: Brush) {
        val paragraph = paragraph(2f)
        val actual = image()
        paragraph.paint(Canvas(actual), brush)
        val expected = image()
        val canvas = Canvas(expected)
        paragraph.paint(canvas, Color.Black)
        val paint = Paint().apply { blendMode = BlendMode.SrcIn }
        brush.applyTo(Size(paragraph.width, paragraph.height), paint, 1f)
        canvas.drawRect(Rect(0f, 0f, Width.toFloat(), Height.toFloat()), paint)
        assertSamePixels(expected.context, actual.context, tolerance = 2)
    }

    @Test
    fun rightToLeftTextIsDrawnAsScaled() {
        assertLaidOutAsScaled {
            paragraph(it, style.copy(textDirection = TextDirection.Rtl), RightToLeftSample, width(it, 400))
        }
    }

    @Test
    fun justifiedTextIsDrawnAsScaled() {
        assertLaidOutAsScaled {
            paragraph(it, style.copy(textAlign = TextAlign.Justify), Sample, width(it, 200))
        }
    }

    @Test
    fun anEllipsisIsDrawnAsScaled() {
        assertLaidOutAsScaled {
            paragraph(it, text = Sample, width = width(it, 200), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Test
    fun scaledAndSkewedTextIsDrawnAsScaled() {
        val transform = TextGeometricTransform(scaleX = 1.5f, skewX = 0.25f)
        assertLaidOutAsScaled { paragraph(it, style.copy(textGeometricTransform = transform)) }
    }

    @Test
    fun anUnderlineWithABrushIsDrawnAsScaled() {
        assertLaidOutAsScaled(
            draw = { canvas, density ->
                val brush = Brush.linearGradient(Colors, Offset.Zero, Offset(200f * density, 0f))
                paint(canvas, brush, textDecoration = TextDecoration.Underline)
            },
        ) { paragraph(it) }
    }

    @Test
    fun recordedTextIsDrawnAsTextDrawnDirectly() {
        val paragraph = paragraph(2f)
        val stroke = Stroke(width = 2f)
        val direct = image()
        paragraph.paint(Canvas(direct), Color.Black, textDecoration = TextDecoration.Underline, drawStyle = stroke)
        val recorded = image()
        FrameRenderer(recorded.drawTarget).render {
            paragraph.paint(it, Color.Black, textDecoration = TextDecoration.Underline, drawStyle = stroke)
        }
        assertSamePixels(direct.context, recorded.context)
    }

    private fun width(density: Float, width: Int): Int = (density * width).toInt()

    /**
     * Asserts that the paragraph made for a density of 2 is the one made for a density of 1 on a
     * canvas scaled twice, in its layout and in the way it's drawn.
     */
    private fun assertLaidOutAsScaled(
        draw: Paragraph.(Canvas, density: Float) -> Unit = { canvas, _ -> paint(canvas, Color.Black) },
        make: (density: Float) -> Paragraph,
    ) {
        val expected = image()
        val scaled = Canvas(expected)
        scaled.scale(2f, 2f)
        make(1f).draw(scaled, 1f)
        val actual = image()
        make(2f).draw(Canvas(actual), 2f)
        assertSamePixels(expected.context, actual.context)
    }

    /**
     * Asserts that a paragraph of [style] at [density], drawn by [draw], has the pixels of the font
     * in CSS pixels drawn by [reference] with the context scaled by [density].
     */
    private fun assertDrawnAsScaled(
        style: TextStyle = this.style,
        density: Float = 2f,
        draw: Paragraph.(Canvas) -> Unit,
        reference: Context2D.(text: JsString, baseline: Double) -> Unit,
    ) {
        val paragraph = paragraph(density, style)
        val actual = image()
        paragraph.draw(Canvas(actual))
        val expected = requireContext2D(createElementCanvas(Width, Height))
        val scale = density.toDouble()
        expected.setTransform(scale, 0.0, 0.0, scale, 0.0, 0.0)
        expected.font = "400 16px ${GenericFamilies.SansSerif}".toJsString()
        expected.reference(directionalRun(Sample, isRtl = false), paragraph.firstBaseline / scale)
        assertSamePixels(expected, actual.context)
    }

    private companion object {
        const val Sample = "The quick brown fox jumps over the lazy dog 0123456789"

        /** Hebrew: alef, bet, gimel, twice. */
        val RightToLeftSample = charArrayOf(
            Char(0x05D0), Char(0x05D1), Char(0x05D2), ' ', Char(0x05D0), Char(0x05D1), Char(0x05D2),
        ).concatToString()
        val Colors = listOf(Color.Red, Color.Blue)
        const val Width = 1400
        const val Height = 200
    }
}

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

package androidx.compose.ui.webcanvas.graphics

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.blendRenderEffect
import androidx.compose.ui.webcanvas.colorFilterRenderEffect
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.CssColors
import androidx.compose.ui.webcanvas.dom.ImageData
import androidx.compose.ui.webcanvas.shaderRenderEffect
import androidx.compose.ui.webcanvas.text.directionalRun
import kotlin.js.toJsString
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Soft-light where it reads the canvas back ([CanvasFeatures.softLightReadsBack], Firefox) is drawn
 * as hard-light, in two passes (see [HardLightPasses]); elsewhere, as it is.
 */
class SoftLightTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private val source = Color(red = 230, green = 77, blue = 153, alpha = 153)

    /** The opaque stripes the source is drawn over. */
    private fun Canvas.drawBackdrop() {
        Backdrop.forEachIndexed { i, color ->
            drawRect(i * Stripe.toFloat(), 0f, (i + 1) * Stripe.toFloat(), Height.toFloat(), Paint().also { it.color = color })
        }
    }

    private fun drawn(scene: Canvas.() -> Unit): ImageData {
        val image = ImageBitmap(Width, Height)
        Canvas(image).scene()
        return image.imageData()
    }

    /** The pixels of [color] (non-premultiplied, 8 bits) drawn over the backdrop in soft-light by the context itself. */
    private fun nativeSoftLight(color: Color): ImageData {
        val image = ImageBitmap(Width, Height)
        Canvas(image).drawBackdrop()
        val ctx = image.asCanvasImage().context
        ctx.globalCompositeOperation = "soft-light".toJsString()
        ctx.fillStyle = CssColors.of(color.toArgb())
        ctx.fillRect(0.0, 0.0, Width.toDouble(), Height.toDouble())
        return image.imageData()
    }

    /** [color] with each channel `c` made `min(2c, 1)`, of 8 bits. */
    private fun darkHalfOf(color: Color): Color = halfOf(color) { min(2 * it, 255) }

    /** [color] with each channel `c` made `max(2c − 1, 0)`, of 8 bits. */
    private fun lightHalfOf(color: Color): Color = halfOf(color) { max(2 * it - 255, 0) }

    private inline fun halfOf(color: Color, half: (Int) -> Int): Color {
        val argb = color.toArgb()
        return Color(
            red = half((argb ushr 16) and 0xFF),
            green = half((argb ushr 8) and 0xFF),
            blue = half(argb and 0xFF),
            alpha = argb ushr 24,
        )
    }

    private fun paint(color: Color, blendMode: BlendMode) = Paint().also {
        it.color = color
        it.blendMode = blendMode
    }

    @Test
    fun aColorInSoftLightIsHardLightWhereSoftLightReadsBack() {
        val pixels = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(source, BlendMode.Softlight))
        }
        val expected = if (CanvasFeatures.softLightReadsBack) {
            drawn {
                drawBackdrop()
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(darkHalfOf(source), BlendMode.Multiply))
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(lightHalfOf(source), BlendMode.Screen))
            }
        } else {
            nativeSoftLight(source)
        }
        assertEquals(0, maxPremultipliedDifference(pixels, expected))
    }

    @Test
    fun aColorFilterAppliesBeforeTheHalves() {
        // A translation: the halves of the filtered color, in Compose's units, not of the color.
        val brighter = ColorFilter.colorMatrix(
            ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 40f, 0f, 1f, 0f, 0f, 40f, 0f, 0f, 1f, 0f, 40f, 0f, 0f, 0f, 1f, 0f))
        )
        val filtered = Color(red = 255, green = 117, blue = 193, alpha = 153)
        val pixels = drawn {
            drawBackdrop()
            val paint = paint(source, BlendMode.Softlight).also { it.colorFilter = brighter }
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint)
        }
        val expected = if (CanvasFeatures.softLightReadsBack) {
            drawn {
                drawBackdrop()
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(darkHalfOf(filtered), BlendMode.Multiply))
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(lightHalfOf(filtered), BlendMode.Screen))
            }
        } else {
            nativeSoftLight(filtered)
        }
        assertEquals(0, maxPremultipliedDifference(pixels, expected))
    }

    @Test
    fun overATransparentBackdropTheHalvesLeaveTheirAlphaTwice() {
        val pixels = drawn {
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(source, BlendMode.Softlight))
        }
        val a = source.alpha
        // Hard-light passes: a·(2 − a). Soft-light composites like source-over: a.
        val alpha = if (CanvasFeatures.softLightReadsBack) a * (2 - a) else a
        val drawnAlpha = argbAt(pixels, 0, 0) ushr 24
        assertTrue(abs(drawnAlpha - (alpha * 255).roundToInt()) <= 1, "alpha $drawnAlpha, expected ${alpha * 255}")
    }

    /** A 4 x 4 image of four colors, tiled by the shaders of the tests. */
    private fun tile(): ImageBitmap = ImageBitmap(4, 4).also { image ->
        val canvas = Canvas(image)
        TileColors.forEachIndexed { i, color ->
            canvas.drawRect((i % 2) * 2f, (i / 2) * 2f, (i % 2) * 2f + 2f, (i / 2) * 2f + 2f, Paint().also { it.color = color })
        }
    }

    private fun shaderPaint(image: ImageBitmap, blendMode: BlendMode, filter: ColorFilter? = null) = Paint().also {
        it.shader = ImageShader(image, TileMode.Repeated, TileMode.Repeated)
        it.alpha = 0.7f
        it.blendMode = blendMode
        it.colorFilter = filter
    }

    @Test
    fun anImageInSoftLightIsDrawnThroughTheHalvesOfItsPixels() {
        val image = tile()
        val pixels = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), shaderPaint(image, BlendMode.Softlight))
        }
        if (!CanvasFeatures.softLightReadsBack) return
        // The two halves of the image, made once.
        assertEquals(2, image.asCanvasImage().filteredCopyEntries)
        val halves = HardLightHalves.plain
        val expected = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), shaderPaint(tile(), BlendMode.Multiply, halves.dark))
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), shaderPaint(tile(), BlendMode.Screen, halves.light))
        }
        assertEquals(0, maxPremultipliedDifference(pixels, expected))
    }

    @Test
    fun anImageTiledThroughAColorFilterIsTiledFilteredLikeItsDrawnThroughIt() {
        val inverted = ColorFilter.colorMatrix(
            ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        )
        // A tint whose copy is opaque, the draw multiplying its alpha by the tint's.
        val translucentTint = ColorFilter.tint(Color.Red.copy(alpha = 0.5f), BlendMode.SrcIn)
        for (filter in listOf(inverted, translucentTint)) {
            val image = tile()
            val tiled = drawn {
                drawRect(0f, 0f, 4f, 4f, shaderPaint(image, BlendMode.SrcOver, filter))
            }
            val drawnImage = drawn {
                drawImage(image, Offset.Zero, Paint().also {
                    it.alpha = 0.7f
                    it.colorFilter = filter
                })
            }
            assertEquals(0, maxPremultipliedDifference(tiled, drawnImage), "$filter")
        }
    }

    @Test
    fun textInSoftLightIsHardLightWhereSoftLightReadsBack() {
        fun run(paint: Paint) = TextRunDrawing(
            directionalRun("Text", isRtl = false),
            "12px sans-serif".toJsString(),
            "en".toJsString(),
            paint.asWebPaint(),
            isRtl = false,
            letterSpacing = null,
            shadow = null,
            pixelRatio = 1f,
        )
        val pixels = drawn {
            drawBackdrop()
            (this as WebCanvas).drawText(run(paint(source, BlendMode.Softlight)), 1f, 10f)
        }
        if (!CanvasFeatures.softLightReadsBack) return
        val expected = drawn {
            drawBackdrop()
            val canvas = this as WebCanvas
            canvas.drawText(run(paint(darkHalfOf(source), BlendMode.Multiply)), 1f, 10f)
            canvas.drawText(run(paint(lightHalfOf(source), BlendMode.Screen)), 1f, 10f)
        }
        assertEquals(0, maxPremultipliedDifference(pixels, expected))
    }

    @Test
    fun aBlendOfEffectsInSoftLightIsHardLightWhereSoftLightReadsBack() {
        // Haze's noise: an image filling the layer, its alpha scaled by a tint, blended over it.
        val tileImage = ImageBitmap(4, 4).also { image ->
            Canvas(image).drawRect(0f, 0f, 4f, 4f, Paint().also { it.color = TileColors[2] })
        }
        val foreground = colorFilterRenderEffect(
            ColorFilter.tint(Color.Black.copy(alpha = 0.6f), BlendMode.DstIn),
            shaderRenderEffect(ImageShader(tileImage, TileMode.Repeated, TileMode.Repeated)),
        )
        val graphicsContext = WebCanvasGraphicsContext()
        val layer = graphicsContext.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, IntSize(Width, Height)) { drawIntoCanvas { it.drawBackdrop() } }
        layer.renderEffect = blendRenderEffect(BlendMode.Softlight, null, foreground)
        val pixels = drawn {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, this, Size(Width.toFloat(), Height.toFloat())) {
                drawLayer(layer)
            }
        }
        graphicsContext.releaseGraphicsLayer(layer)
        val source = TileColors[2].copy(alpha = 0.6f)
        // Elsewhere, soft-light as the browser draws it: Safari's differs from the W3C formula.
        val native = if (CanvasFeatures.softLightReadsBack) null else nativeSoftLight(source)
        Backdrop.forEachIndexed { i, stripe ->
            val x = i * Stripe + Stripe / 2
            val expected = if (native == null) {
                blendColors(source.toArgb(), stripe.toArgb(), BlendMode.Hardlight)
            } else {
                argbAt(native, x, Height / 2)
            }
            val actual = argbAt(pixels, x, Height / 2)
            assertTrue(
                channelsWithin(expected, actual, tolerance = 3),
                "stripe $i: ${actual.toUInt().toString(16)}, expected ${expected.toUInt().toString(16)}",
            )
        }
        if (CanvasFeatures.softLightReadsBack) {
            // The two halves of the image, made once.
            assertEquals(2, tileImage.asCanvasImage().filteredCopyEntries)
        }
    }

    /** Whether each channel of the ARGB colors [a] and [b] differs by at most [tolerance]. */
    private fun channelsWithin(a: Int, b: Int, tolerance: Int): Boolean =
        (0 until 32 step 8).all { shift -> abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)) <= tolerance }

    private companion object {
        const val Stripe = 8
        const val Width = 32
        const val Height = 12

        val Backdrop = listOf(Color(0xFF204060), Color(0xFFB0C0D0), Color(0xFF808080), Color(0xFFF0A020))
        val TileColors = listOf(Color(0xFF102030), Color(0xFFE0F0C0), Color(0xFF7F3FBF), Color(0xFF40C080))
    }
}

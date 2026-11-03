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
import androidx.compose.ui.graphics.CompositeShader
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import androidx.compose.ui.webcanvas.blendRenderEffect
import androidx.compose.ui.webcanvas.colorFilterRenderEffect
import androidx.compose.ui.webcanvas.dom.CanvasFeatures
import androidx.compose.ui.webcanvas.dom.Context2D
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Soft-light where it reads the canvas back ([CanvasFeatures.softLightReadsBack], Firefox) is drawn
 * as hard-light, in two passes (see [HardLightPasses]); elsewhere, as it is. The canvases are as
 * large as Firefox accelerates (`gfx.canvas.accelerated.min-size`), so that where the browser of the
 * tests accelerates canvases, its accelerated drawing is what's tested.
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

    /** The pixels of what [draw] draws over the backdrop into the context itself, in soft-light. */
    private fun nativeSoftLight(draw: Context2D.() -> Unit): ImageData {
        val image = ImageBitmap(Width, Height)
        Canvas(image).drawBackdrop()
        val ctx = image.asCanvasImage().context
        ctx.globalCompositeOperation = "soft-light".toJsString()
        ctx.draw()
        return image.imageData()
    }

    /** The pixels of [color] (non-premultiplied, 8 bits) drawn over the backdrop in soft-light by the context itself. */
    private fun nativeSoftLight(color: Color): ImageData = nativeSoftLight {
        fillStyle = CssColors.of(color.toArgb())
        fillRect(0.0, 0.0, Width.toDouble(), Height.toDouble())
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

    /** The alpha a paint holds for [alpha], of 8 bits, as the context takes it. */
    private fun paintAlpha(alpha: Float): Double = Paint().also { it.alpha = alpha }.alpha.toDouble()

    /** Calls [sample] with a few points of each stripe of the backdrop, and the stripe. */
    private inline fun forEachSample(sample: (x: Int, y: Int, stripe: Int) -> Unit) {
        for (stripe in Backdrop.indices) {
            for (dx in 0..2 step 2) {
                for (y in SampleRows) sample(stripe * Stripe + Stripe / 4 + dx, y, stripe)
            }
        }
    }

    /**
     * Asserts that at each sample point (see [forEachSample]) [pixels] are the color [sourceAt]
     * gives there over the stripe in [blendMode], within [SampleTolerance].
     */
    private fun assertBlended(pixels: ImageData, blendMode: BlendMode, sourceAt: (x: Int, y: Int) -> Color) {
        forEachSample { x, y, stripe ->
            val expected = blendColors(sourceAt(x, y).toArgb(), Backdrop[stripe].toArgb(), blendMode)
            assertColor(Color(expected), Color(argbAt(pixels, x, y)), tolerance = SampleTolerance / 255f, message = "($x, $y)")
        }
    }

    /**
     * Asserts that at each sample point (see [forEachSample]) [pixels] are the color [expectedAt] gives
     * there, within [SampleTolerance].
     */
    private inline fun assertSamplesLike(pixels: ImageData, expectedAt: (x: Int, y: Int) -> Int) {
        forEachSample { x, y, _ ->
            assertColor(Color(expectedAt(x, y)), Color(argbAt(pixels, x, y)), tolerance = SampleTolerance / 255f, message = "($x, $y)")
        }
    }

    /**
     * Asserts that at each sample point (see [forEachSample]) [pixels] are the color [sourceAt] gives
     * there drawn over the backdrop in soft-light by the context itself, within [SampleTolerance].
     */
    private fun assertNativeSoftLight(pixels: ImageData, sourceAt: (x: Int, y: Int) -> Color) {
        val natives = HashMap<Color, ImageData>()
        assertSamplesLike(pixels) { x, y ->
            val source = sourceAt(x, y)
            argbAt(natives.getOrPut(source) { nativeSoftLight(source) }, x, y)
        }
    }

    @Test
    fun aColorInSoftLightIsHardLightWhereSoftLightReadsBack() {
        val pixels = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(source, BlendMode.Softlight))
        }
        if (CanvasFeatures.softLightReadsBack) {
            val expected = drawn {
                drawBackdrop()
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(darkHalfOf(source), BlendMode.Multiply))
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(lightHalfOf(source), BlendMode.Screen))
            }
            assertSamePixels(expected, pixels)
        } else {
            assertSamePixels(nativeSoftLight(source), pixels)
        }
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
        if (CanvasFeatures.softLightReadsBack) {
            val expected = drawn {
                drawBackdrop()
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(darkHalfOf(filtered), BlendMode.Multiply))
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(lightHalfOf(filtered), BlendMode.Screen))
            }
            assertSamePixels(expected, pixels)
        } else {
            assertSamePixels(nativeSoftLight(filtered), pixels)
        }
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

    private fun shaderPaint(image: ImageBitmap, blendMode: BlendMode, filter: ColorFilter? = null) = Paint().also {
        it.shader = ImageShader(image, TileMode.Repeated, TileMode.Repeated)
        it.alpha = ImageAlpha
        it.blendMode = blendMode
        it.colorFilter = filter
    }

    @Test
    fun anImageTiledInSoftLightIsDrawnThroughTheHalvesOfItsPixels() {
        val image = tile()
        val pixels = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), shaderPaint(image, BlendMode.Softlight))
        }
        val copies = image.asCanvasImage().filteredCopyEntries
        if (CanvasFeatures.softLightReadsBack) {
            // The two halves of the image, made once.
            assertEquals(2, copies)
            assertBlended(pixels, BlendMode.Hardlight) { x, y -> tileColorAt(x % 4, y % 4).copy(alpha = ImageAlpha) }
        } else {
            assertEquals(0, copies)
            val expected = nativeSoftLight {
                fillStyle = checkNotNull(createPattern(image.asCanvasImage().canvas, "repeat".toJsString()))
                globalAlpha = paintAlpha(ImageAlpha)
                fillRect(0.0, 0.0, Width.toDouble(), Height.toDouble())
            }
            assertSamePixels(expected, pixels)
        }
    }

    @Test
    fun anImageDrawnInSoftLightIsDrawnThroughTheHalvesOfItsPixels() {
        val image = tile()
        val paint = paint(Color.Black.copy(alpha = ImageAlpha), BlendMode.Softlight).also {
            it.filterQuality = FilterQuality.None
        }
        val pixels = drawn {
            drawBackdrop()
            drawImageRect(image, IntOffset.Zero, IntSize(4, 4), IntOffset.Zero, IntSize(Width, Height), paint)
        }
        val copies = image.asCanvasImage().filteredCopyEntries
        if (CanvasFeatures.softLightReadsBack) {
            assertEquals(2, copies)
            assertBlended(pixels, BlendMode.Hardlight) { x, y ->
                tileColorAt(4 * x / Width, 4 * y / Height).copy(alpha = ImageAlpha)
            }
        } else {
            assertEquals(0, copies)
            val expected = nativeSoftLight {
                imageSmoothingEnabled = false
                globalAlpha = paintAlpha(ImageAlpha)
                drawImage(image.asCanvasImage().canvas, 0.0, 0.0, 4.0, 4.0, 0.0, 0.0, Width.toDouble(), Height.toDouble())
            }
            assertSamePixels(expected, pixels)
        }
    }

    @Test
    fun anImageTiledThroughAColorFilterIsTiledFilteredLikeItsDrawnThroughIt() {
        val inverted = ColorFilter.colorMatrix(ColorMatrix(InvertingMatrix))
        // A tint whose copy is opaque, the draw multiplying its alpha by the tint's.
        val translucentTint = ColorFilter.tint(Color.Red.copy(alpha = 0.5f), BlendMode.SrcIn)
        for (filter in listOf(inverted, translucentTint)) {
            val image = tile()
            val tiled = drawn {
                drawRect(0f, 0f, 4f, 4f, shaderPaint(image, BlendMode.SrcOver, filter))
            }
            val drawnImage = drawn {
                drawImage(image, Offset.Zero, Paint().also {
                    it.alpha = ImageAlpha
                    it.colorFilter = filter
                })
            }
            assertSamePixels(drawnImage, tiled, what = "$filter: ")
        }
    }

    /** The color of [linearGradient] at the center of the row [y] of pixels. */
    private fun gradientColorAt(y: Int): Color {
        val t = (y + 0.5f) / Height
        fun channel(shift: Int): Int {
            val from = (GradientTop.toArgb() ushr shift) and 0xFF
            val to = (GradientBottom.toArgb() ushr shift) and 0xFF
            return (from + (to - from) * t).roundToInt()
        }
        return Color(red = channel(16), green = channel(8), blue = channel(0))
    }

    /** A gradient over the height of the canvas, whose channels cross 0.5, where the halves bend. */
    private fun linearGradient() =
        LinearGradientShader(Offset.Zero, Offset(0f, Height.toFloat()), listOf(GradientTop, GradientBottom))

    @Test
    fun aGradientInSoftLightIsHardLightThroughItsStops() {
        val shader = linearGradient()
        val pixels = drawn {
            drawBackdrop()
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), Paint().also {
                it.shader = shader
                it.alpha = GradientAlpha
                it.blendMode = BlendMode.Softlight
            })
        }
        // Without an image of the gradient (see WebShader.filtered).
        assertNull(shader.webShader.rasters)
        if (CanvasFeatures.softLightReadsBack) {
            assertBlended(pixels, BlendMode.Hardlight) { _, y -> gradientColorAt(y).copy(alpha = GradientAlpha) }
        } else {
            val expected = nativeSoftLight {
                fillStyle = createLinearGradient(0.0, 0.0, 0.0, Height.toDouble()).also {
                    it.addColorStop(0.0, CssColors.of(GradientTop.toArgb()))
                    it.addColorStop(1.0, CssColors.of(GradientBottom.toArgb()))
                }
                globalAlpha = paintAlpha(GradientAlpha)
                fillRect(0.0, 0.0, Width.toDouble(), Height.toDouble())
            }
            assertSamplesLike(pixels) { x, y -> argbAt(expected, x, y) }
        }
    }

    @Test
    fun aShaderDrawnAsAnImageInSoftLightKeepsAnImageThroughEachHalf() {
        // A blend of two gradients, which no fill style draws: an image of it, through each half.
        val across = LinearGradientShader(Offset.Zero, Offset(Width.toFloat(), 0f), listOf(Color.Red, Color.Blue))
        val shader = CompositeShader(linearGradient(), across, BlendMode.Multiply)
        val paint = Paint().also {
            it.shader = shader
            it.blendMode = BlendMode.Softlight
        }
        val image = ImageBitmap(Width, Height)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint)
        val rasters = checkNotNull(shader.webShader.rasters)
        val last = checkNotNull(rasters.last)
        val previous = rasters.previous
        if (CanvasFeatures.softLightReadsBack) {
            assertTrue(previous != null && previous !== last, "an image through each half")
        } else {
            assertNull(previous, "one image, of the shader as it is")
        }
        canvas.drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint)
        // The images of the first draw drew the second.
        assertSame(last, rasters.last)
        assertSame(previous, rasters.previous)
    }

    /** The shapes that the entry points of the canvas drawing with the color of a paint draw. */
    private val shapes: List<Pair<String, Canvas.(Paint) -> Unit>> = listOf(
        "line" to { drawLine(Offset(8f, 16f), Offset(120f, 112f), it.also { paint -> paint.strokeWidth = 16f }) },
        "round rect" to { drawRoundRect(8f, 8f, 120f, 120f, 24f, 24f, it) },
        "oval" to { drawOval(8f, 24f, 120f, 104f, it) },
        "circle" to { drawCircle(Offset(64f, 64f), 56f, it) },
        "arc" to { drawArc(8f, 8f, 120f, 120f, 30f, 240f, true, it) },
        "path" to {
            drawPath(Path().apply { moveTo(8f, 8f); lineTo(120f, 40f); lineTo(40f, 120f); close() }, it)
        },
        "points" to {
            val points = listOf(Offset(32f, 32f), Offset(96f, 96f))
            drawPoints(PointMode.Points, points, it.also { paint -> paint.strokeWidth = 24f; paint.strokeCap = StrokeCap.Round })
        },
    )

    @Test
    fun everyShapeInSoftLightIsHardLightWhereSoftLightReadsBack() {
        for ((name, shape) in shapes) {
            val pixels = drawn {
                drawBackdrop()
                shape(paint(source, BlendMode.Softlight))
            }
            if (CanvasFeatures.softLightReadsBack) {
                val expected = drawn {
                    drawBackdrop()
                    shape(paint(darkHalfOf(source), BlendMode.Multiply))
                    shape(paint(lightHalfOf(source), BlendMode.Screen))
                }
                assertSamePixels(expected, pixels, what = "$name: ")
            } else {
                // Inside the shape, soft-light as the context draws it over the whole canvas; outside
                // it, the backdrop as it is.
                val inside = drawn { shape(paint(Color.Black, BlendMode.SrcOver)) }
                assertSamePixels(nativeSoftLight(source), pixels, NativeShapeTolerance, "$name inside: ", where = inside)
                val outside = drawn {
                    drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint(Color.Black, BlendMode.SrcOver))
                    shape(paint(Color.Black, BlendMode.Clear))
                }
                assertSamePixels(drawn { drawBackdrop() }, pixels, what = "$name outside: ", where = outside)
            }
        }
    }

    /** The coordinate in [tile] that a mirroring pattern shows at [coordinate]. */
    private fun mirrored(coordinate: Int): Int = (coordinate % 8).let { if (it < 4) it else 7 - it }

    @Test
    fun clampedAndMirroredImagesInSoftLightAreHardLightWhereSoftLightReadsBack() {
        val modes = listOf<Pair<TileMode, (Int) -> Int>>(
            // An image that clamps, drawn as an image of the shader (the area is past the image).
            TileMode.Clamp to { min(it, 3) },
            TileMode.Mirror to ::mirrored,
        )
        for ((mode, coordinate) in modes) {
            val image = tile()
            val paint = shaderPaint(image, BlendMode.Softlight).also { it.shader = ImageShader(image, mode, mode) }
            val pixels = drawn {
                drawBackdrop()
                drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint)
            }
            val sourceAt = { x: Int, y: Int -> tileColorAt(coordinate(x), coordinate(y)).copy(alpha = ImageAlpha) }
            if (CanvasFeatures.softLightReadsBack) {
                assertBlended(pixels, BlendMode.Hardlight, sourceAt)
            } else {
                assertNativeSoftLight(pixels, sourceAt)
            }
        }
    }

    @Test
    fun aTintAppliedAsACompositingOperationKeepsSoftLight() {
        // The halves can't follow it (see HardLightPasses): soft-light as the context draws it, everywhere.
        val tint = Color(0xFF3060C0)
        val pixels = drawn {
            drawBackdrop()
            val paint = paint(source, BlendMode.Softlight).also { it.colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn) }
            drawRect(0f, 0f, Width.toFloat(), Height.toFloat(), paint)
        }
        assertSamePixels(nativeSoftLight(tint.copy(alpha = source.alpha)), pixels)
    }

    @Test
    fun coloredVerticesInSoftLightAreHardLightWhereSoftLightReadsBack() {
        // One triangle over the whole canvas: no edge between triangles in it.
        val positions = listOf(Offset.Zero, Offset(2f * Width, 0f), Offset(0f, 2f * Height))
        val vertices = Vertices(VertexMode.Triangles, positions, positions, List(3) { source }, emptyList())
        val pixels = drawn {
            drawBackdrop()
            drawVertices(vertices, BlendMode.Dst, paint(Color.Black, BlendMode.Softlight))
        }
        if (CanvasFeatures.softLightReadsBack) {
            assertBlended(pixels, BlendMode.Hardlight) { _, _ -> source }
        } else {
            assertNativeSoftLight(pixels) { _, _ -> source }
        }
    }

    @Test
    fun textInSoftLightIsHardLightWhereSoftLightReadsBack() {
        val text = directionalRun("Text", isRtl = false)
        fun run(paint: Paint) = TextRunDrawing(
            text,
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
        if (CanvasFeatures.softLightReadsBack) {
            val expected = drawn {
                drawBackdrop()
                val canvas = this as WebCanvas
                canvas.drawText(run(paint(darkHalfOf(source), BlendMode.Multiply)), 1f, 10f)
                canvas.drawText(run(paint(lightHalfOf(source), BlendMode.Screen)), 1f, 10f)
            }
            assertSamePixels(expected, pixels)
        } else {
            // The same run drawn by the context itself, which rasterizes the same glyphs.
            val expected = nativeSoftLight {
                font = "12px sans-serif".toJsString()
                direction = "ltr".toJsString()
                fillStyle = CssColors.of(source.toArgb())
                fillText(text, 1.0, 10.0)
            }
            assertSamePixels(expected, pixels, tolerance = NativeTextTolerance)
        }
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
        if (CanvasFeatures.softLightReadsBack) {
            assertBlended(pixels, BlendMode.Hardlight) { _, _ -> source }
            // The two halves of the image, made once.
            assertEquals(2, tileImage.asCanvasImage().filteredCopyEntries)
        } else {
            // Soft-light as the browser draws it: Safari's differs from the W3C formula.
            assertNativeSoftLight(pixels) { _, _ -> source }
        }
    }

    private companion object {
        const val Stripe = 32
        const val Width = 128
        const val Height = 128

        /** The rows of the sample points of each stripe: two pixels of each row of the tile, in either half of the canvas. */
        val SampleRows = listOf(40, 42, 104, 106)

        val Backdrop = listOf(Color(0xFF204060), Color(0xFFB0C0D0), Color(0xFF808080), Color(0xFFF0A020))

        /** The alpha the images are drawn with. */
        const val ImageAlpha = 0.7f

        /**
         * The levels a sample point can be off from its color blended here ([blendColors]), over an
         * opaque backdrop: the context keeps a source premultiplied in 8 bits, off by up to 0.8 of a
         * level at the alphas of these tests (0.6 and more), which a blend at most doubles and the
         * alpha of the draw scales back, and both round the result to 8 bits, half a level each: 2.6,
         * so 3 between the two 8-bit values.
         */
        const val SampleTolerance = 3

        /**
         * The levels a run of text drawn in soft-light can be off from the same run drawn by the
         * context itself, where both are the context's soft-light: Safari 27.2 drew them off by a
         * level at a glyph's edge in 3 of 15 runs, while 30 canvases drawn the same way by the context
         * itself matched in 3 runs. Drawn as hard-light instead, they would be off by tens of levels.
         */
        const val NativeTextTolerance = 1

        /**
         * The levels a shape drawn in soft-light can be off, inside it, from soft-light as the context
         * draws it over the whole canvas: none in Chrome; in Safari 27.2 a level inside the round rect,
         * the oval, the circle and the arc, none inside the line, the path and the points. Next to its
         * edge, Safari covers a pixel of the line in soft-light in part where it covers it whole in
         * source-over (3 levels off at (16, 13), 0.57 px inside the edge), so the pixels next to the
         * edges aren't compared. Drawn as hard-light instead, they would be off by tens of levels.
         */
        const val NativeShapeTolerance = 1

        val GradientTop = Color(0xFF1040E0)
        val GradientBottom = Color(0xFFE0C020)
        const val GradientAlpha = 0.8f
    }
}

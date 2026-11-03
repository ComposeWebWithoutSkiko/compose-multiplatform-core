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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.webcanvas.dom.ImageData
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** A shader takes the color filter of its draw without an image of it, where it can (see [WebShader.filtered]). */
class ShaderColorFilterTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun drawn(width: Int, height: Int, shader: Shader, filter: ColorFilter?): ImageData {
        val image = ImageBitmap(width, height)
        Canvas(image).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().also {
            it.shader = shader
            it.colorFilter = filter
        })
        return image.imageData()
    }

    @Test
    fun aGradientThroughAColorMatrixIsDrawnThroughItsStops() {
        val cases = listOf(
            // The red doubled less 40, which reaches both 0 and 255 within the gradient, the green
            // made 1.5 times, the alpha kept. The alpha changes over the second half, where a canvas
            // interpolates the colors as they are.
            floatArrayOf(2f, 0f, 0f, 0f, -40f, 0f, 1.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f) to
                listOf(Color(0xFF102080), Color(0xFFF0E040), Color(0x6640A0F0)),
            // The alpha doubled, which reaches 255 between two stops of one color, with no stop in
            // between (see canvasStops).
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 2f, 0f) to
                listOf(Color(0xFFF0E040), Color(0x33F0E040)),
        )
        for ((values, colors) in cases) {
            val shader = LinearGradientShader(Offset.Zero, Offset(GradientWidth.toFloat(), 0f), colors)
            val filtered = drawn(GradientWidth, 1, shader, ColorFilter.colorMatrix(ColorMatrix(values)))
            val plain = drawn(GradientWidth, 1, shader, null)
            for (x in 0 until GradientWidth step 4) {
                val expected = Color(throughMatrix(values, argbAt(plain, x, 0)))
                // Read back from premultiplied pixels of 8 bits, the colors are off by up to 0.5 / a
                // levels where the alpha is a (1.25 at 0.4), in both images, and the matrix doubles
                // the error of the expected ones; the stops of 8 bits add half a level, doubled.
                assertColor(expected, Color(argbAt(filtered, x, 0)), tolerance = 5 / 255f, message = "x $x")
            }
            assertNull(shader.webShader.rasters, "an image of the gradient was made")
        }
    }

    @Test
    fun gradientsOfEveryKindAndTileModeTakeAColorMatrixThroughTheirStops() {
        val values = floatArrayOf(2f, 0f, 0f, 0f, -40f, 0f, 1.5f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        val colors = listOf(Color(0xFF102080), Color(0xFFF0E040), Color(0xFF40A0F0))
        val center = Offset(KindsWidth / 2f, KindsHeight / 2f)
        val shaders = listOf(
            "repeated" to LinearGradientShader(Offset.Zero, Offset(32f, 0f), colors, tileMode = TileMode.Repeated),
            "mirrored" to LinearGradientShader(Offset.Zero, Offset(32f, 0f), colors, tileMode = TileMode.Mirror),
            "decal" to LinearGradientShader(Offset(32f, 0f), Offset(96f, 0f), colors, tileMode = TileMode.Decal),
            "radial" to RadialGradientShader(center, 40f, colors),
            "sweep" to SweepGradientShader(center, colors),
        )
        for ((name, shader) in shaders) {
            val filtered = drawn(KindsWidth, KindsHeight, shader, ColorFilter.colorMatrix(ColorMatrix(values)))
            val plain = drawn(KindsWidth, KindsHeight, shader, null)
            for (y in 0 until KindsHeight step 8) {
                for (x in 0 until KindsWidth step 8) {
                    val expected = Color(throughMatrix(values, argbAt(plain, x, y)))
                    // Opaque, so read back as drawn: each image interpolates in 8 bits (half a level),
                    // which the matrix doubles for the expected colors, and the filtered stops round to
                    // 8 bits (half a level): 2 levels, and half of one rounding the expected colors.
                    assertColor(expected, Color(argbAt(filtered, x, y)), tolerance = 3 / 255f, message = "$name at ($x, $y)")
                }
            }
            assertNull(shader.webShader.rasters, "$name: an image of the gradient was made")
        }
    }

    @Test
    fun aGradientOfNoSizeIsItsColorThroughAFilter() {
        // Clamped, of no length: Skia draws its last color (see degenerateGradientShader).
        val last = Color(0xFFF0E040)
        val shader = LinearGradientShader(Offset(8f, 0f), Offset(8f, 0f), listOf(Color(0xFF102080), last))
        val translucentTint = Color.Red.copy(alpha = 0.5f)
        val cases = listOf(
            ColorFilter.colorMatrix(ColorMatrix(InvertingMatrix)) to Color(throughMatrix(InvertingMatrix, last.toArgb())),
            // Applied opaque, the draw taking the alpha of the tint (see WebColorFilter.Tint.drawAlpha).
            ColorFilter.tint(translucentTint, BlendMode.SrcIn) to translucentTint,
        )
        for ((filter, expected) in cases) {
            val pixels = drawn(4, 4, shader, filter)
            // An 8-bit color read back from an 8-bit premultiplied pixel: within a level.
            assertColor(expected, Color(argbAt(pixels, 1, 1)), tolerance = 1 / 255f, message = "$filter")
        }
        assertNull(shader.webShader.rasters, "an image of the shader was made")
    }

    @Test
    fun aGradientThroughAMatrixThatAddsToTheAlphaIsDrawnAsAnImage() {
        // The gradient ends at the middle, clamped transparent past it, where the filter adds half
        // an alpha to transparent black, as Skia applies it; through its stops, the color that the
        // transparent last stop takes from the stop before it (see canvasStops) would show instead.
        val adding = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 128f)
        val shader = LinearGradientShader(Offset.Zero, Offset(GradientWidth / 2f, 0f), listOf(Color(0xFF2040C0), Color.Transparent))
        val pixels = drawn(GradientWidth, 1, shader, ColorFilter.colorMatrix(ColorMatrix(adding)))
        // An alpha of 128 / 255, read back from an 8-bit premultiplied pixel: within a level.
        assertColor(Color(0f, 0f, 0f, 128 / 255f), Color(argbAt(pixels, 3 * GradientWidth / 4, 0)), tolerance = 1 / 255f)
        assertNotNull(shader.webShader.rasters, "no image of the gradient was made")
    }

    @Test
    fun aGradientWithATransformOfItsOwnThroughAFilterItsStopsDontTakeIsDrawnInPlace() {
        // A red radial gradient scaled on one axis, whose fill style the canvas draws through that
        // scale, through filters that need an image of it and keep the alpha of each of its pixels:
        // a tint, and a matrix that adds the blue, none here, to the alpha.
        val brush = ShaderBrush(RadialGradientShader(Offset(16f, 32f), 16f, listOf(Color.Red, Color.Transparent))).apply {
            transform = Matrix().apply { scale(2f, 1f) }
        }
        val addingBlue = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 0f)
        val filters = listOf(ColorFilter.tint(Color.Blue, BlendMode.SrcIn), ColorFilter.colorMatrix(ColorMatrix(addingBlue)))
        val width = KindsWidth.toFloat()
        val height = KindsHeight.toFloat()
        // Filled as a rectangle and as a path of another shape, which canvases draw apart.
        val shapes = listOf<Pair<String, Canvas.(Paint) -> Unit>>(
            "rect" to { drawRect(0f, 0f, width, height, it) },
            "oval path" to { drawPath(Path().apply { addOval(Rect(0f, 0f, width, height)) }, it) },
        )
        fun drawn(shape: Canvas.(Paint) -> Unit, filter: ColorFilter?): ImageData {
            val image = ImageBitmap(KindsWidth, KindsHeight)
            val paint = Paint()
            brush.applyTo(Size(width, height), paint, 1f)
            paint.colorFilter = filter
            Canvas(image).shape(paint)
            return image.imageData()
        }
        for ((name, shape) in shapes) {
            val plain = alphaBalance(drawn(shape, null))
            for (filter in filters) {
                val filtered = alphaBalance(drawn(shape, filter))
                // The placement, by the sum of the alphas and their center rather than pixel by pixel:
                // drawn through an image of the gradient, pixels round apart from where it's drawn
                // plain for reasons that have nothing to do with placement (Firefox 159 a level at the
                // oval's antialiased edge; Safari 27.2, applying the matrix through the pixels, 2 or 3
                // levels at many). Within ten times the most these moved them, 0.11% of the sum
                // (Safari) and 0.0014 px (Firefox); scaled twice, the gradient doubled the sum.
                assertEquals(plain.mass, filtered.mass, plain.mass * 0.011, "$name, $filter: the sum of the alphas")
                assertEquals(plain.x, filtered.x, 0.014, "$name, $filter: x")
                assertEquals(plain.y, filtered.y, 0.014, "$name, $filter: y")
            }
        }
    }

    @Test
    fun aShaderKeepsItsShadersThroughTheLastTwoFilters() {
        val shader = LinearGradientShader(Offset.Zero, Offset(16f, 0f), listOf(Color.Red, Color.Blue)).webShader
        val halves = HardLightHalves.plain()
        val dark = halves.dark.webColorFilter
        val light = halves.light.webColorFilter
        val throughDark = shader.filtered(dark)
        val throughLight = shader.filtered(light)
        // The halves of a paint drawn as hard-light alternate: neither evicts the other.
        assertSame(throughDark, shader.filtered(dark))
        assertSame(throughLight, shader.filtered(light))
    }

    /** [argb] through the color matrix [values], in Compose's layout (its translation from 0 to 255). */
    private fun throughMatrix(values: FloatArray, argb: Int): Int {
        val channels = intArrayOf((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, argb ushr 24)
        fun row(i: Int): Int {
            val value = (0 until 4).sumOf { values[5 * i + it].toDouble() * channels[it] } + values[5 * i + 4]
            return value.roundToInt().coerceIn(0, 255)
        }
        return argbOf(row(3), row(0), row(1), row(2))
    }

    @Test
    fun aMirroredImageThroughAFilterIsTiledFromOneCopy() {
        val image = tile()
        val shader = ImageShader(image, TileMode.Mirror, TileMode.Mirror)
        val filtered = drawn(16, 16, shader, ColorFilter.colorMatrix(ColorMatrix(InvertingMatrix)))
        val plain = drawn(16, 16, shader, null)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                assertEquals(throughMatrix(InvertingMatrix, argbAt(plain, x, y)), argbAt(filtered, x, y), "($x, $y)")
            }
        }
        // No copy of the image itself: the tile of its reflections, filtered as it's drawn, is the only one.
        assertEquals(0, image.asCanvasImage().filteredCopyEntries)
    }

    private companion object {
        const val GradientWidth = 256

        /** The size of the canvases of the gradients of every kind. */
        const val KindsWidth = 128
        const val KindsHeight = 64
    }
}

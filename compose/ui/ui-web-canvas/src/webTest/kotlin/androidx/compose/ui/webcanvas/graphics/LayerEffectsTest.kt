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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.OffsetEffect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.registerWebCanvasComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.webcanvas.WebCanvasGraphicsContext
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Color filters and render effects of layers, which are applied when the layer is composited. */
class LayerEffectsTest {
    @BeforeTest
    fun register() {
        registerWebCanvasComposeImplementation()
    }

    private fun image(width: Int = 20, height: Int = 20, draw: Canvas.() -> Unit): ImageBitmap {
        val image = ImageBitmap(width, height)
        Canvas(image).draw()
        return image
    }

    private fun assertColor(expected: Color, actual: Color, tolerance: Float = 0.02f) {
        assertTrue(
            abs(expected.red - actual.red) <= tolerance &&
                abs(expected.green - actual.green) <= tolerance &&
                abs(expected.blue - actual.blue) <= tolerance &&
                abs(expected.alpha - actual.alpha) <= tolerance,
            "Expected $expected but was $actual",
        )
    }

    private fun ImageBitmap.pixel(x: Int, y: Int): Color = toPixelMap()[x, y]

    /** Colors to filter: primaries, a mid tone and a translucent one. */
    private val colors = listOf(
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF336699),
        Color(0x80C04020),
    )

    /** Draws [colors] side by side in a layer with [filter] and checks them against the CPU. */
    private fun assertLayerFilterMatchesTheCpu(filter: ColorFilter) {
        val image = image(width = 40, height = 10) {
            saveLayer(Rect(0f, 0f, 40f, 10f), Paint().apply { colorFilter = filter })
            colors.forEachIndexed { i, color ->
                drawRect(Rect(i * 10f, 0f, i * 10f + 10f, 10f), Paint().apply { this.color = color })
            }
            restore()
        }
        colors.forEachIndexed { i, color ->
            val expected = Color(filter.webColorFilter.filter(color.toArgb()))
            assertColor(expected, image.pixel(i * 10 + 5, 5))
        }
    }

    @Test
    fun colorFiltersApplyOnTheGpu() {
        // The tests run in browsers that apply SVG filters; without them, filters read pixels back.
        assertTrue(SvgColorFilters.isSupported, "SVG color filters aren't applied")
    }

    @Test
    fun colorMatrixOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        0.3f, 0.6f, 0.1f, 0f, 20f,
                        0.2f, 0.5f, 0.3f, 0f, -10f,
                        0.1f, 0.1f, 0.8f, 0f, 5f,
                        0f, 0f, 0f, 0.9f, 0f,
                    )
                )
            )
        )
    }

    @Test
    fun saturationOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.2f) })
        )
    }

    @Test
    fun lightingOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(
            ColorFilter.lighting(multiply = Color(0xFF80C040), add = Color(0xFF102030))
        )
    }

    @Test
    fun modulateTintOnALayerMatchesTheCpu() {
        assertLayerFilterMatchesTheCpu(ColorFilter.tint(Color(0xC040A0FF), BlendMode.Modulate))
    }

    @Test
    fun colorMatrixOnAnImageMatchesTheCpu() {
        val filter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        val source = image(width = 40, height = 10) {
            colors.forEachIndexed { i, color ->
                drawRect(Rect(i * 10f, 0f, i * 10f + 10f, 10f), Paint().apply { this.color = color })
            }
        }
        val image = image(width = 40, height = 10) {
            drawImage(source, androidx.compose.ui.geometry.Offset.Zero, Paint().apply { colorFilter = filter })
        }
        colors.forEachIndexed { i, color ->
            val expected = Color(filter.webColorFilter.filter(color.toArgb()))
            assertColor(expected, image.pixel(i * 10 + 5, 5))
        }
    }

    @Test
    fun colorMatrixMakingTransparentPixelsOpaqueStaysInsideTheLayer() {
        // Every pixel, transparent ones included, becomes opaque green.
        val filter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 255f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 255f,
                )
            )
        )
        val image = image {
            saveLayer(Rect(5f, 5f, 15f, 15f), Paint().apply { colorFilter = filter })
            restore()
        }
        assertColor(Color.Green, image.pixel(10, 10))
        assertColor(Color.Transparent, image.pixel(2, 10))
        assertColor(Color.Transparent, image.pixel(17, 17))
    }

    @Test
    fun animatedColorMatricesAllApply() {
        // More matrices than the filters kept in the page, so that filters get reused.
        for (step in 0 until 40) {
            val level = step / 39f
            val filter = ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        level, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
            val image = image(width = 4, height = 4) {
                saveLayer(Rect(0f, 0f, 4f, 4f), Paint().apply { colorFilter = filter })
                drawRect(Rect(0f, 0f, 4f, 4f), Paint().apply { color = Color.White })
                restore()
            }
            assertColor(Color(level, 0f, 0f), image.pixel(2, 2), tolerance = 0.01f)
        }
    }

    private fun drawLayerImage(
        width: Int,
        height: Int,
        layerSize: IntSize,
        topLeft: IntOffset,
        effect: RenderEffect,
        content: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
    ): ImageBitmap {
        val context = WebCanvasGraphicsContext()
        val layer = context.createGraphicsLayer()
        layer.record(Density(1f), LayoutDirection.Ltr, layerSize, content)
        layer.topLeft = topLeft
        layer.renderEffect = effect
        val image = image(width, height) {
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                this,
                Size(width.toFloat(), height.toFloat()),
            ) {
                drawLayer(layer)
            }
        }
        context.releaseGraphicsLayer(layer)
        return image
    }

    @Test
    fun clampedBlurKeepsTheEdgeColorsAndStaysInsideTheLayer() {
        val image = drawLayerImage(
            width = 80,
            height = 80,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(20, 20),
            effect = BlurEffect(8f, 8f, TileMode.Clamp),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        // The edges sample the edge color, so the layer stays red up to its edges.
        assertColor(Color.Red, image.pixel(20, 20))
        assertColor(Color.Red, image.pixel(40, 40))
        assertColor(Color.Red, image.pixel(59, 59))
        assertColor(Color.Transparent, image.pixel(18, 40))
        assertColor(Color.Transparent, image.pixel(61, 40))
    }

    @Test
    fun decalBlurSpreadsTheContentOut() {
        val image = drawLayerImage(
            width = 80,
            height = 80,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(20, 20),
            effect = BlurEffect(6f, 6f, TileMode.Decal),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        val center = image.pixel(40, 40)
        val edge = image.pixel(20, 40)
        val outside = image.pixel(16, 40)
        assertColor(Color.Red, center)
        assertTrue(edge.alpha in 0.3f..0.7f, "alpha at the edge: ${edge.alpha}")
        assertTrue(outside.alpha in 0.01f..0.4f, "alpha outside: ${outside.alpha}")
    }

    @Test
    fun offsetEffectMovesTheContent() {
        val image = drawLayerImage(
            width = 60,
            height = 20,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset(0, 0),
            effect = OffsetEffect(30f, 0f),
        ) {
            drawRect(Color.Blue, size = Size(20f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(10, 10))
        assertColor(Color.Blue, image.pixel(40, 10))
    }

    @Test
    fun blurOfAnOffsetLayerIsMovedToo() {
        val image = drawLayerImage(
            width = 80,
            height = 40,
            layerSize = IntSize(20, 20),
            topLeft = IntOffset(0, 10),
            effect = BlurEffect(OffsetEffect(40f, 0f), 2f, 2f, TileMode.Decal),
        ) {
            drawRect(Color.Blue, size = Size(20f, 20f))
        }
        assertColor(Color.Transparent, image.pixel(10, 20))
        assertColor(Color.Blue, image.pixel(50, 20), tolerance = 0.05f)
    }

    @Test
    fun blurDoesNotPickUpWhatAReusedCanvasHeldBefore() {
        // A large layer leaves its canvas, full of blue, to the pool...
        image(width = 120, height = 120) {
            saveLayer(Rect(0f, 0f, 120f, 120f), Paint().apply { alpha = 0.5f })
            drawRect(Rect(0f, 0f, 120f, 120f), Paint().apply { color = Color.Blue })
            restore()
        }
        // ...which a smaller blurred layer reuses: the blue must not bleed into its edges.
        val image = drawLayerImage(
            width = 40,
            height = 40,
            layerSize = IntSize(40, 40),
            topLeft = IntOffset(0, 0),
            effect = BlurEffect(4f, 4f, TileMode.Decal),
        ) {
            drawRect(Color.Red, size = Size(40f, 40f))
        }
        for (y in listOf(5, 20, 38)) {
            val pixel = image.pixel(38, y)
            assertTrue(pixel.blue < 0.02f, "blue at (38, $y): $pixel")
        }
    }
}
